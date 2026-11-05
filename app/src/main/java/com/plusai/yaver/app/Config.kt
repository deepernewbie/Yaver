package com.plusai.yaver.app

/** All user settings. Secrets go through [Secure]. */
object Cfg {
    private fun s(k: String, d: String = "") = Prefs.get("cfg.$k", d)
    private fun ps(k: String, v: String) = Prefs.put("cfg.$k", v)
    private fun secret(k: String) = Secure.dec(Prefs.get("sec.$k", ""))
    private fun putSecret(k: String, v: String) = Prefs.put("sec.$k", Secure.enc(v.trim()))

    // ---- language model ----
    var llmBase: String get() = s("llmBase", "https://openrouter.ai/api/v1"); set(v) = ps("llmBase", v.trim().trimEnd('/'))
    var llmKey: String get() = secret("llmKey"); set(v) = putSecret("llmKey", v)
    var model: String get() = s("model", ""); set(v) = ps("model", v.trim())
    var fastModel: String get() = s("fastModel", ""); set(v) = ps("fastModel", v.trim())
    var visionModel: String get() = s("visionModel", ""); set(v) = ps("visionModel", v.trim())
    var temperature: Double get() = s("temperature", "0.3").toDoubleOrNull() ?: 0.3; set(v) = ps("temperature", v.toString())
    var maxSteps: Int get() = s("maxSteps", "40").toIntOrNull() ?: 40; set(v) = ps("maxSteps", v.toString())
    /** Most tokens the model may write in one reply (0 = the service's default). */
    var maxOut: Int get() = s("maxOut", "0").toIntOrNull() ?: 0; set(v) = ps("maxOut", v.toString())
    /** Context budget in tokens; 0 = automatic (from the model's window). */
    var contextTokens: Int get() = s("contextTokens", "0").toIntOrNull() ?: 0; set(v) = ps("contextTokens", v.toString())
    var stream: Boolean get() = s("stream", "1") == "1"; set(v) = ps("stream", if (v) "1" else "0")
    var sendImages: Boolean get() = s("sendImages", "1") == "1"; set(v) = ps("sendImages", if (v) "1" else "0")

    // ---- behaviour ----
    var lang: String get() = s("lang", "auto"); set(v) = ps("lang", v)
    var persona: String get() = s("persona", ""); set(v) = ps("persona", v)
    var askRisky: Boolean get() = s("askRisky", "1") == "1"; set(v) = ps("askRisky", if (v) "1" else "0")

    // ---- web ----
    var searxng: String get() = s("searxng", ""); set(v) = ps("searxng", v.trim().trimEnd('/'))
    var braveKey: String get() = secret("brave"); set(v) = putSecret("brave", v)
    var tavilyKey: String get() = secret("tavily"); set(v) = putSecret("tavily", v)

    // ---- company mail ----
    var mailEws: String get() = s("mailEws", ""); set(v) = ps("mailEws", v.trim())
    var mailOwa: String get() = s("mailOwa", ""); set(v) = ps("mailOwa", v.trim())
    var mailUser: String get() = s("mailUser", ""); set(v) = ps("mailUser", v.trim())
    var mailPass: String get() = secret("mailPass"); set(v) = putSecret("mailPass", v)
    var mailTotp: String get() = secret("mailTotp"); set(v) = putSecret("mailTotp", Totp.secretFrom(v))
    var mailAutoSubmit: Boolean get() = s("mailAutoSubmit", "1") == "1"; set(v) = ps("mailAutoSubmit", if (v) "1" else "0")
    var mailMode: String get() = s("mailMode", ""); set(v) = ps("mailMode", v)
    var mailPoll: Int get() = s("mailPoll", "3").toIntOrNull() ?: 3; set(v) = ps("mailPoll", v.toString())
    var mailNotify: Boolean get() = s("mailNotify", "1") == "1"; set(v) = ps("mailNotify", if (v) "1" else "0")

    val mailConfigured: Boolean get() = mailEws.startsWith("http") || mailOwa.startsWith("http")
    val llmReady: Boolean get() = model.isNotEmpty() && (llmKey.isNotEmpty() || !llmBase.contains("openrouter") && !llmBase.contains("openai.com"))
}
