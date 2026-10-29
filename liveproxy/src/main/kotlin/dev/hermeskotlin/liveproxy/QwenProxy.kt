package dev.hermeskotlin.liveproxy

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

private val events = Json { ignoreUnknownKeys = true }

private const val MAX_FRAME_CHARS = 1_000_000
private const val MAX_BUFFERED_CHUNKS = 50

fun Application.installQwenProxy(config: ProxyConfig, upstream: HttpClient) {
    install(WebSockets) {
        // A spoken reply is a stream of PCM frames. Cap one frame so a bad client can't pin memory.
        maxFrameSize = 1_048_576
    }
    routing {
        get("/health") {
            // The key is never included. The phone can show the model the proxy will call.
            val body = buildJsonObject {
                put("ok", true)
                put("model", config.model)
                put("region", config.region)
                put("voice", config.voice)
                put("workspace", config.workspaceId != null)
            }.toString()
            call.respondText(body, ContentType.Application.Json)
        }
        webSocket("/v1/qwen/realtime") {
            val provided = call.request.headers["X-Herald-Live-Token"].orEmpty()
            if (config.token != null && !constantTimeEquals(provided, config.token)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad token"))
                return@webSocket
            }
            val clientSession = this
            try {
                upstream.webSocket(config.upstreamUrl, request = {
                    header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                    header(HttpHeaders.UserAgent, "hikyaku-live-proxy")
                    config.workspaceId?.let { header("X-DashScope-WorkSpace", it) }
                }) {
                    relay(config, clientSession, this)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = redact(friendlyUpstream(e), config.apiKey)
                runCatching { send(Frame.Text(proxyError(message, fatal = true))) }
                runCatching { close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "upstream")) }
            }
        }
    }
}

/**
 * Relays text frames. The proxy sends `session.update` itself (voice, VAD) and ignores one from
 * the phone, so the phone can't point this key at a different setup. Mic audio that arrives
 * before DashScope confirms the session is held, then forwarded in order.
 */
private suspend fun relay(
    config: ProxyConfig,
    clientSession: io.ktor.websocket.WebSocketSession,
    upstream: io.ktor.websocket.WebSocketSession,
) = coroutineScope {
    val toUpstream = Channel<String>(Channel.UNLIMITED)
    val mutex = Mutex()
    val pending = ArrayDeque<String>()
    var configured = false
    val sender = launch {
        for (text in toUpstream) upstream.send(Frame.Text(text))
    }
    val down = launch {
        for (frame in upstream.incoming) {
            if (frame !is Frame.Text) continue
            val text = redact(frame.readText(), config.apiKey)
            clientSession.send(Frame.Text(text))
            when (eventType(text)) {
                "session.created" -> toUpstream.send(sessionUpdate(config.voice))
                "session.updated", "error" -> mutex.withLock {
                    // trySend on an unlimited channel does not suspend, so this stays ordered with later mic chunks.
                    configured = true
                    pending.forEach { toUpstream.trySend(it) }
                    pending.clear()
                }
            }
        }
    }
    val up = launch {
        for (frame in clientSession.incoming) {
            if (frame !is Frame.Text) continue
            val text = frame.readText()
            if (text.length > MAX_FRAME_CHARS) {
                clientSession.send(Frame.Text(proxyError("That audio frame is too large.", fatal = false)))
                continue
            }
            if (config.apiKey.isNotEmpty() && config.apiKey in text) {
                clientSession.send(Frame.Text(proxyError("The phone must not send the DashScope key.", fatal = false)))
                continue
            }
            // The proxy owns the session. A phone-sent update would replace the voice and the VAD mode.
            if (eventType(text) == "session.update") continue
            mutex.withLock {
                if (configured) toUpstream.trySend(text)
                else {
                    pending.addLast(text)
                    while (pending.size > MAX_BUFFERED_CHUNKS) pending.removeFirst()
                }
            }
        }
    }
    select {
        down.onJoin {}
        up.onJoin {}
    }
    down.cancel()
    up.cancel()
    toUpstream.close()
    sender.join()
}

/** The first `session.update`. Voice and server VAD match the official 3.1 quick start. */
internal fun sessionUpdate(voice: String): String = buildJsonObject {
    put("type", "session.update")
    put("session", buildJsonObject {
        put("modalities", buildJsonArray {
            add(JsonPrimitive("text"))
            add(JsonPrimitive("audio"))
        })
        put("voice", voice)
        put("instructions", "You are a live voice companion. Reply in short spoken sentences.")
        put("turn_detection", buildJsonObject {
            put("type", "server_vad")
            put("threshold", 0.5)
            put("silence_duration_ms", 800)
        })
    })
}.toString()

internal fun eventType(text: String): String? = runCatching {
    (events.parseToJsonElement(text).jsonObject["type"] as? JsonPrimitive)?.contentOrNull
}.getOrNull()

internal fun proxyError(message: String, fatal: Boolean): String = buildJsonObject {
    put("type", "error")
    put("error", buildJsonObject {
        put("type", if (fatal) "server_error" else "invalid_request_error")
        put("message", message)
    })
}.toString()

internal fun redact(text: String, key: String): String =
    if (key.isNotEmpty() && key in text) text.replace(key, "••••") else text

internal fun friendlyUpstream(error: Throwable): String {
    val text = error.message?.trim().orEmpty()
    return when {
        "401" in text -> "DashScope rejected the API key (401). Check DASHSCOPE_API_KEY and that the key belongs to this region."
        "403" in text -> "DashScope refused the connection (403). Check the key, the region, and DASHSCOPE_WORKSPACE_ID."
        text.isBlank() -> "Couldn't open the Qwen realtime session."
        else -> "Couldn't open the Qwen realtime session. ${text.take(180)}"
    }
}

/** Equal-length secrets compare without stopping at the first different byte. */
internal fun constantTimeEquals(given: String, expected: String): Boolean {
    val a = given.toByteArray(Charsets.UTF_8)
    val b = expected.toByteArray(Charsets.UTF_8)
    if (a.size != b.size) return false
    return MessageDigest.isEqual(a, b)
}
