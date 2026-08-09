package dev.yaver.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sub-agents: one question, several investigators.
 *
 * A single loop can only follow one thread of enquiry at a time, and a
 * question like "which of these four watches is worth buying" is four separate
 * investigations. Each sub-agent gets its own short loop, a narrow toolset and
 * a hard round limit, then reports back in prose. The main agent reads the
 * reports rather than the pages.
 *
 * Two at a time, deliberately. Running five in parallel is faster on paper and
 * worse in practice: free tiers throttle per minute, and a burst returns five
 * rate-limit errors instead of five answers.
 */
object SubAgent {

    private const val CONCURRENCY = 2
    private const val MAX_ROUNDS = 5

    /** Only what an investigator needs. No calendar, no memory, no writing. */
    private val ALLOWED = setOf("web_search", "browse_open", "calculate", "recall")

    data class Finding(val task: String, val result: String, val rounds: Int, val failed: Boolean)

    // A sub-agent gets four tools and no drawers to open — its whole job is
    // narrow enough that browsing a menu would be wasted turns.
    private fun toolSchema(): String = Tools.primitives
        .filter { it.name in ALLOWED }
        .joinToString("\n\n") { t ->
            val params = t.parameters.entries.joinToString("\n") { "    ${it.key}: ${it.value}" }
            "- ${t.name}: ${t.description}" + if (params.isNotEmpty()) "\n$params" else ""
        }

    private fun prompt(task: String): String = """
        You are a research sub-agent. You have one task and a few rounds to do it.

        Task: $task

        Call a tool by emitting exactly:
        <tool_call>
        {"name": "tool_name", "arguments": {...}}
        </tool_call>

        Available tools:

        ${toolSchema()}

        How to work:
        - Search, then open the most promising result. Snippets are leads, not findings.
        - Never state a price, date, figure or URL you did not read from a tool result.
        - If a page blocks you, say so and try a different source rather than guessing.
        - You have $MAX_ROUNDS rounds. When they run out, report what you have.

        Finish with a plain-prose report: what you established, where it came from,
        and — separately and explicitly — what you could not verify. No tool calls
        in your final answer.
    """.trimIndent()

    private fun runOne(context: Context, task: String): Finding {
        val convo = mutableListOf("system" to prompt(task), "user" to task)
        var rounds = 0

        for (round in 0 until MAX_ROUNDS) {
            if (Llm.cancelled) break
            rounds++

            val raw = try {
                Llm.complete(convo, temperature = 0.3)
            } catch (e: Exception) {
                return Finding(task, "(failed: ${e.message})", rounds, true)
            }

            val calls = Agent.parseCalls(raw)
            if (calls.isEmpty()) {
                return Finding(task, Agent.stripCalls(raw).ifBlank { "(no conclusion)" }, rounds, false)
            }

            convo.add("assistant" to raw)
            val responses = StringBuilder()
            for (call in calls.take(3)) {
                val result = if (call.name in ALLOWED) {
                    Tools.run(context, call.name, call.arguments)
                } else {
                    JSONObject().put("error", "Sub-agents may only use: ${ALLOWED.joinToString(", ")}")
                }
                val forModel = JSONObject(result.toString()).apply { remove("card") }
                responses.append("<tool_response>").append(forModel.toString().take(8000))
                    .append("</tool_response>\n")
            }
            val left = MAX_ROUNDS - round - 1
            if (left <= 1) responses.append("\n[Last round. Write your report now.]")
            convo.add("user" to responses.toString())
        }

        // Out of rounds without a report: ask once more with tools closed.
        return try {
            convo.add("user" to "Stop researching. Report what you established and what you could not verify. No tool calls.")
            val closing = Llm.complete(convo, temperature = 0.2)
            Finding(task, Agent.stripCalls(closing).ifBlank { "(no conclusion reached)" }, rounds, false)
        } catch (e: Exception) {
            Finding(task, "(failed: ${e.message})", rounds, true)
        }
    }

    fun runMany(context: Context, tasks: List<String>, onProgress: (String) -> Unit): List<Finding> {
        val limited = tasks.filter { it.isNotBlank() }.take(4)
        if (limited.isEmpty()) return emptyList()

        val pool = Executors.newFixedThreadPool(minOf(CONCURRENCY, limited.size))
        return try {
            val jobs = limited.map { task ->
                Callable {
                    onProgress("investigating: ${task.take(50)}")
                    runOne(context, task)
                }
            }
            // A hard ceiling: a stuck sub-agent must not hold the whole answer.
            pool.invokeAll(jobs, 5, TimeUnit.MINUTES).mapIndexed { index, future ->
                try { future.get() } catch (e: Exception) {
                    Finding(limited[index], "(timed out or failed: ${e.message})", 0, true)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    fun findingsJson(findings: List<Finding>): JSONArray {
        val arr = JSONArray()
        findings.forEach { f ->
            arr.put(JSONObject()
                .put("task", f.task)
                .put("rounds", f.rounds)
                .put("failed", f.failed)
                .put("result", f.result.take(6000)))
        }
        return arr
    }

    /** Split a broad question into the separate investigations it contains. */
    fun planQuestions(question: String, breadth: Int): List<String> {
        val instruction = "Break this question into ${breadth.coerceIn(2, 4)} independent " +
            "sub-questions that can be researched separately. Each must stand alone and be " +
            "specific enough to search for. Reply with ONLY a JSON array of strings."
        val reply = try {
            Llm.complete(listOf("system" to instruction, "user" to question), temperature = 0.3)
        } catch (e: Exception) {
            return listOf(question)
        }
        val cleaned = reply.replace("```json", "").replace("```", "").trim()
        return try {
            val arr = JSONArray(cleaned)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                .ifEmpty { listOf(question) }
        } catch (e: Exception) {
            listOf(question)
        }
    }
}
