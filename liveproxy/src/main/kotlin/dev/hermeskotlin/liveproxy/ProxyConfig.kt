package dev.hermeskotlin.liveproxy

import java.net.URLEncoder

/**
 * The proxy's configuration, all from the environment. [apiKey] never leaves this process.
 *
 * International Model Studio (the console at alibabacloud.com) uses the Singapore region.
 * Without a workspace id the existing international domain is used; with one, the workspace
 * domain from the current docs. The console name "Qwen-Audio-3.1-Realtime" is not an API id.
 * The documented 3.1 ids are [DEFAULT_MODEL] and `qwen-audio-3.1-realtime-flash`.
 */
data class ProxyConfig(
    val apiKey: String,
    val region: String,
    val workspaceId: String?,
    val model: String,
    val voice: String,
    val bindHost: String,
    val port: Int,
    val token: String?,
    val upstreamUrl: String,
) {
    companion object {
        const val DEFAULT_MODEL = "qwen-audio-3.1-realtime-plus"
        const val DEFAULT_REGION = "ap-southeast-1"
        const val DEFAULT_VOICE = "longanqian_v3.1"
        const val DEFAULT_PORT = 8787

        fun fromEnv(env: Map<String, String>, upstreamUrl: String? = null): ProxyConfig {
            val apiKey = env["DASHSCOPE_API_KEY"]?.trim().orEmpty()
            require(apiKey.isNotEmpty()) { "DASHSCOPE_API_KEY is required. It stays on this process and is not sent to the phone." }
            val region = normalizeRegion(env["DASHSCOPE_REGION"]?.trim().orEmpty().ifEmpty { DEFAULT_REGION })
            val workspaceId = env["DASHSCOPE_WORKSPACE_ID"]?.trim()?.ifEmpty { null }
            val model = env["DASHSCOPE_REALTIME_MODEL"]?.trim().orEmpty().ifEmpty { DEFAULT_MODEL }
            val voice = env["DASHSCOPE_REALTIME_VOICE"]?.trim().orEmpty().ifEmpty { DEFAULT_VOICE }
            val bindHost = env["HERALD_LIVE_BIND"]?.trim().orEmpty().ifEmpty { "0.0.0.0" }
            val port = env["HERALD_LIVE_PORT"]?.trim()?.toIntOrNull() ?: DEFAULT_PORT
            require(port in 1..65535) { "HERALD_LIVE_PORT must be between 1 and 65535." }
            val token = env["HERALD_LIVE_PROXY_TOKEN"]?.trim()?.ifEmpty { null }
            val loopback = bindHost == "127.0.0.1" || bindHost == "localhost" || bindHost == "::1"
            require(loopback || token != null) {
                "HERALD_LIVE_PROXY_TOKEN is required when the proxy listens beyond localhost ($bindHost). " +
                    "The phone sends this token. The DashScope key stays here."
            }
            return ProxyConfig(
                apiKey = apiKey,
                region = region,
                workspaceId = workspaceId,
                model = model,
                voice = voice,
                bindHost = bindHost,
                port = port,
                token = token,
                upstreamUrl = upstreamUrl ?: dashScopeRealtimeUrl(region, workspaceId, model),
            )
        }
    }
}

/**
 * WebSocket URL for the Qwen-Audio realtime API.
 *
 * Singapore (international), no workspace: `wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime`.
 * With a workspace id: `wss://{WorkspaceId}.{region}.maas.aliyuncs.com/api-ws/v1/realtime`.
 * The model is the `model` query parameter. The key is a header, never part of the URL.
 */
fun dashScopeRealtimeUrl(region: String, workspaceId: String?, model: String): String {
    val regionId = normalizeRegion(region.ifBlank { ProxyConfig.DEFAULT_REGION })
    val workspace = workspaceId?.trim()?.ifEmpty { null }
    val host = if (workspace != null) {
        "$workspace.$regionId.maas.aliyuncs.com"
    } else when (regionId) {
        "ap-southeast-1" -> "dashscope-intl.aliyuncs.com"
        "cn-beijing" -> "dashscope.aliyuncs.com"
        "cn-hongkong" -> "cn-hongkong.dashscope.aliyuncs.com"
        else -> throw IllegalArgumentException(
            "Region '$regionId' has no legacy DashScope domain. Set DASHSCOPE_WORKSPACE_ID to use the workspace endpoint.",
        )
    }
    val encoded = URLEncoder.encode(model, Charsets.UTF_8)
    return "wss://$host/api-ws/v1/realtime?model=$encoded"
}

internal fun normalizeRegion(raw: String): String = when (raw.trim().lowercase()) {
    "", "intl", "international", "singapore" -> ProxyConfig.DEFAULT_REGION
    "beijing", "cn" -> "cn-beijing"
    else -> raw.trim()
}
