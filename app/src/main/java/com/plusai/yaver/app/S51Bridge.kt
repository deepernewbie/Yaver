package com.plusai.yaver.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * yaver 5.1 — bridge methods for the screen: steer / queue (Turns), schedules (S51Sched), token use (S51Usage),
 * memory approvals (MemoryPlus) and learned skills (L51).
 */
object S51Bridge {
    private val METHODS = setOf("chat.withdraw", "chat.unqueue", "chat.queue", "chat.queue.start", "chat.queue.clear", "usage.stats", "usage.clear",
        "memory.pending", "memory.approve", "memory.reject", "skills.drafts", "skills.draftText", "skills.confirm", "skills.discard", "learn.skills")

    fun handles(m: String) = m in METHODS || S51Sched.handles(m)

    fun dispatch(m: String, a: JSONObject): Any? = when (m) {
        "chat.withdraw" -> {
            val t = Turns.withdraw(a.str("convoId"), a.str("steerId"))
            JSONObject().put("text", t ?: JSONObject.NULL).put("ok", t != null)
        }
        "chat.unqueue" -> JSONObject().put("ok", Turns.unqueue(a.str("convoId"), a.str("id")))
        "chat.queue" -> JSONObject().put("convoId", a.str("convoId")).put("items", Turns.queued(a.str("convoId"))).put("running", Turns.isRunning(a.str("convoId")))
        // the queue strip: start the queued messages now (one after the other, by the harness) / drop them all
        "chat.queue.start" -> JSONObject().put("started", Turns.startQueued(a.str("convoId")))
        "chat.queue.clear" -> {
            val id = a.str("convoId"); val l = Turns.queued(id); var n = 0
            for (i in 0 until l.length()) if (Turns.unqueue(id, l.getJSONObject(i).optString("id"))) n++
            JSONObject().put("removed", n)
        }
        "usage.stats" -> S51Usage.stats(a.int("days", 30).coerceIn(1, 120))
        "usage.clear" -> { S51Usage.clear(); true }
        "memory.pending" -> MemoryPlus.pending()
        "memory.approve" -> done(MemoryPlus.approve(a.str("id")), "memory.changed")
        "memory.reject" -> done(MemoryPlus.reject(a.str("id")), "memory.changed")
        "skills.drafts" -> L51.drafts()
        "skills.draftText" -> JSONObject().put("id", a.str("id")).put("text", L51.draftText(a.str("id")) ?: JSONObject.NULL)
        "skills.confirm" -> done(L51.confirm(a.str("id")), "skills")
        "skills.discard" -> done(L51.discard(a.str("id")), "skills")
        "learn.skills" -> {
            if (a.has("on")) L51.enabled = a.optBoolean("on")
            JSONObject().put("on", L51.enabled).put("available", true)
        }
        else -> S51Sched.dispatch(m, a)
    }

    /** An approve / confirm result: false or {ok:false,error} becomes an error for the screen; otherwise {ok:true}. */
    private fun done(r: Any?, event: String): JSONObject {
        if (r == false) throw IllegalStateException(Y.t("Not found — it may have been handled already", "Bulunamadı — zaten işlenmiş olabilir"))
        if (r is JSONObject && r.has("ok") && !r.optBoolean("ok", true)) throw IllegalStateException(r.optString("error").ifEmpty { Y.t("Not done", "Yapılmadı") })
        Bridge.emit(event, JSONObject())
        return JSONObject().put("ok", true)
    }

    /** Bridge.emit saw a run end: schedules record their window, usage records the final message. */
    fun onRunDone(convoId: String, error: String?) {
        if (convoId.isEmpty()) return
        Y.bg {
            try { S51Sched.onRunDone(convoId, error) } catch (e: Throwable) { Diag.e("s51", "run done", e) }
            try { S51Usage.onRunDone(convoId) } catch (e: Throwable) { Diag.e("usage", "run done", e) }
        }
    }
}
