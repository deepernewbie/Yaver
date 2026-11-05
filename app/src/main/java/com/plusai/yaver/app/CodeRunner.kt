package com.plusai.yaver.app

/**
 * The old run_code engine (a second hidden web view with Pyodide 0.26) is gone: run_code, the planning checks and
 * the heartbeat gates now run on CodeEngine — the same Python (Pyodide 0.28) and JavaScript as execute_code and the
 * terminal, with the whole virtual disk (/workspace, /.yaver) visible. This keeps the old call shape.
 */
object CodeRunner {
    /** Runs [code]; [files] are made available by name (the code starts in their folder). Returns the output, throws on an error. */
    fun run(language: String, code: String, files: List<String>, timeoutSec: Int, cancel: Cancel): String {
        val r = CodeEngine.runCode(language, code, files, timeoutSec, null, null, cancel)
        if (!r.first) throw RuntimeException(r.second)
        return r.second
    }
}
