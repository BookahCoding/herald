package dev.hermeskotlin.core.models

/**
 * Human model names from gateway ids, after Hermes Desktop's `formatModelPillLabel`:
 * `openai/gpt-6-sol` → "GPT-6-sol", `claude-opus-5-5` → "Opus 5.5", `glm-5.2` → "GLM 5.2".
 */
fun displayModelName(id: String): String {
    val base = id.trim().substringAfterLast('/')
    if (base.isEmpty()) return id
    return when {
        base.startsWith("claude-", ignoreCase = true) ->
            base.drop(7).replace(Regex("""(\d)-(?=\d)"""), "$1.").replace('-', ' ').titleCase().vendorCasing()
        base.startsWith("gpt-", ignoreCase = true) -> "GPT-" + base.drop(4)
        else -> base.replace('-', ' ').titleCase().vendorCasing()
    }
}

/**
 * Desktop's provider labels where the slug reads badly. A bare `custom` row the gateway names
 * "Custom endpoint" (or nothing) is the Custom category the chat picker used to list local models under.
 */
fun displayProviderName(slug: String, fallback: String? = null): String {
    PROVIDER_NAMES[slug.trim().lowercase()]?.let { return it }
    val name = fallback?.takeIf { it.isNotBlank() } ?: slug
    if (slug.trim().equals("custom", ignoreCase = true) && name.trim().lowercase() in GENERIC_CUSTOM_NAMES) return "Custom"
    return name
}

/** Names a bare `custom` row uses when the user hasn't given the endpoint one. */
private val GENERIC_CUSTOM_NAMES = setOf("custom", "custom endpoint")

private val PROVIDER_NAMES = mapOf(
    "anthropic" to "Anthropic",
    "nous" to "Nous Portal",
    "openai-codex" to "ChatGPT subscription",
    "qwen-oauth" to "Qwen Code",
    "xai" to "xAI",
    "xai-oauth" to "xAI Grok",
)

/** Capitalises each word, but not what follows a colon: OpenRouter's `:free` stays `:free`. */
private fun String.titleCase(): String =
    Regex("""\b[a-z]""").replace(this) { it.value.uppercase() }.trim()
        .let { Regex(""":([A-Z])""").replace(it) { m -> ":" + m.groupValues[1].lowercase() } }

private val VENDOR_CASING = listOf(
    "Deepseek" to "DeepSeek", "Glm" to "GLM", "Minimax" to "MiniMax", "Openai" to "OpenAI",
    "Ernie" to "ERNIE", "Mimo" to "MiMo", "Vl" to "VL", "Ai" to "AI",
)

private fun String.vendorCasing(): String {
    var cased = Regex("""\b([aA]?)(\d+(?:\.\d+)?)[bB]\b""").replace(this) { "${it.groupValues[1].uppercase()}${it.groupValues[2]}B" }
    for ((from, to) in VENDOR_CASING) cased = Regex("""\b""" + from + """\b""").replace(cased, to)
    return cased
}
