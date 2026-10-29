package dev.hermeskotlin.core.qwen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * Qwen-Audio realtime, speech to speech.
 *
 * The console lists the model as "Qwen-Audio-3.1-Realtime". That display name is not an API id.
 * The documented ids are `qwen-audio-3.1-realtime-plus` and `qwen-audio-3.1-realtime-flash`
 * (https://www.alibabacloud.com/help/en/model-studio/qwen-audio-realtime-user-guides). The proxy
 * picks which one with `DASHSCOPE_REALTIME_MODEL`; plus is the default, the id in the official
 * quick start. The phone never sees the API key: it talks to the proxy, which holds
 * `DASHSCOPE_API_KEY` and opens the DashScope WebSocket.
 *
 * Audio in is PCM 16 kHz 16-bit mono. Audio out is PCM 24 kHz 16-bit mono.
 */

/** PCM the phone sends, one chunk about 100 ms (16 kHz × 16-bit × 0.1 s). */
const val QWEN_INPUT_SAMPLE_RATE = 16_000
const val QWEN_INPUT_CHUNK_SAMPLES = 1_600

/** PCM DashScope streams back. */
const val QWEN_OUTPUT_SAMPLE_RATE = 24_000

/** Voice the official 3.1 quick start sets. The proxy sends it; the phone does not. */
const val QWEN_DEFAULT_VOICE = "longanqian_v3.1"

private val qwenJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** What the phone does with one server event. Audio is played by the engine, not shown. */
sealed interface QwenServerEvent {
    data class SessionCreated(val model: String?) : QwenServerEvent
    data object SessionUpdated : QwenServerEvent
    data object SpeechStarted : QwenServerEvent
    data object SpeechStopped : QwenServerEvent
    data class InputTranscript(val itemId: String, val text: String, val partial: Boolean) : QwenServerEvent
    data class OutputDelta(val itemId: String, val delta: String) : QwenServerEvent
    data class OutputDone(val itemId: String, val transcript: String) : QwenServerEvent
    data class ResponseCreated(val id: String?) : QwenServerEvent
    data class ResponseFinished(val status: String) : QwenServerEvent
    data class Failure(val message: String, val fatal: Boolean) : QwenServerEvent
    data class Audio(val responseId: String?, val pcm: ByteArray) : QwenServerEvent
    data object Ignored : QwenServerEvent
}

/** Reads one realtime server event. Anything we don't act on, or that isn't JSON, is [QwenServerEvent.Ignored]. */
fun parseQwenServerEvent(raw: String): QwenServerEvent {
    val event = runCatching { qwenJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return QwenServerEvent.Ignored
    return when (event.text("type")) {
        "session.created" -> QwenServerEvent.SessionCreated((event["session"] as? JsonObject)?.text("model"))
        "session.updated" -> QwenServerEvent.SessionUpdated
        "input_audio_buffer.speech_started" -> QwenServerEvent.SpeechStarted
        "input_audio_buffer.speech_stopped" -> QwenServerEvent.SpeechStopped
        "conversation.item.input_audio_transcription.delta" -> {
            val text = event.text("text").orEmpty()
            val stash = event.text("stash").orEmpty()
            val shown = text + stash
            if (shown.isBlank()) QwenServerEvent.Ignored
            else QwenServerEvent.InputTranscript(event.text("item_id") ?: "input", shown, partial = true)
        }
        "conversation.item.input_audio_transcription.completed" -> {
            val transcript = event.text("transcript").orEmpty()
            if (transcript.isBlank()) QwenServerEvent.Ignored
            else QwenServerEvent.InputTranscript(event.text("item_id") ?: "input", transcript, partial = false)
        }
        "response.audio_transcript.delta", "response.text.delta" -> {
            val delta = event.text("delta").orEmpty()
            if (delta.isEmpty()) QwenServerEvent.Ignored
            else QwenServerEvent.OutputDelta(event.text("item_id") ?: event.text("response_id") ?: "reply", delta)
        }
        "response.audio_transcript.done", "response.text.done" ->
            QwenServerEvent.OutputDone(
                event.text("item_id") ?: event.text("response_id") ?: "reply",
                event.text("transcript") ?: event.text("text").orEmpty(),
            )
        "response.created" -> QwenServerEvent.ResponseCreated((event["response"] as? JsonObject)?.text("id"))
        "response.done" -> QwenServerEvent.ResponseFinished((event["response"] as? JsonObject)?.text("status") ?: "completed")
        "response.audio.delta" -> decodeAudio(event)
        "error" -> {
            val error = event["error"] as? JsonObject
            QwenServerEvent.Failure(
                error?.text("message")?.takeIf { it.isNotBlank() } ?: "The realtime session failed.",
                fatal = error?.text("type") == "server_error",
            )
        }
        else -> QwenServerEvent.Ignored
    }
}

private fun decodeAudio(event: JsonObject): QwenServerEvent {
    val delta = event.text("delta").orEmpty()
    if (delta.isBlank()) return QwenServerEvent.Ignored
    val pcm = runCatching { Base64.decode(delta) }.getOrNull() ?: return QwenServerEvent.Ignored
    if (pcm.isEmpty()) return QwenServerEvent.Ignored
    return QwenServerEvent.Audio(event.text("response_id"), pcm)
}

/** One mic chunk, base64 PCM, as `input_audio_buffer.append`. */
fun qwenAppendAudio(pcm: ByteArray): String = buildJsonObject {
    put("type", "input_audio_buffer.append")
    put("audio", Base64.encode(pcm))
}.toString()

/** Stops the reply in progress. The server answers with `response.done` status `cancelled`. */
fun qwenResponseCancel(): String = """{"type":"response.cancel"}"""

/**
 * The proxy WebSocket for an address typed in Settings. `http` becomes `ws` and a bare host gets
 * the proxy path. Null when it isn't an address.
 */
fun qwenLiveWebSocketUrl(input: String): String? {
    val raw = input.trim()
    // A trailing slash is an empty path. Don't eat the slashes in "ws://".
    val trimmed = if (raw.endsWith("/") && !raw.endsWith("://")) raw.trimEnd('/') else raw
    if (trimmed.isEmpty()) return null
    val withScheme = when {
        trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
        trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
        trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
        else -> "ws://$trimmed"
    }
    val schemeEnd = withScheme.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = withScheme.substring(0, schemeEnd)
    val rest = withScheme.substring(schemeEnd + 3)
    if (rest.isEmpty() || rest.startsWith("/") || rest.startsWith("?")) return null
    val pathAt = rest.indexOf('/')
    val queryAt = rest.indexOf('?')
    val authorityEnd = listOf(pathAt, queryAt).filter { it >= 0 }.minOrNull() ?: rest.length
    val authority = rest.substring(0, authorityEnd)
    if (authority.isBlank() || authority.startsWith(":")) return null
    val host = if (authority.startsWith("[")) {
        val end = authority.indexOf(']')
        if (end < 0) return null
        authority.substring(0, end + 1)
    } else {
        authority.substringBefore(':')
    }
    if (host.isBlank()) return null
    val port = authority.removePrefix(host).substringBefore('?').let { if (it.startsWith(":")) it else "" }
    val tail = rest.substring(authorityEnd)
    val path = if (tail.isEmpty() || tail.startsWith("?")) "/v1/qwen/realtime$tail" else tail
    return "$scheme://$host$port$path"
}

/** A line in the live transcript. [live] while the words are still streaming. */
data class QwenLine(val id: String, val speaker: QwenSpeaker, val text: String, val live: Boolean)

enum class QwenSpeaker { You, Qwen }

enum class QwenPhase { Idle, Connecting, Listening, Speaking, Stopped }

data class QwenLiveState(
    val phase: QwenPhase = QwenPhase.Idle,
    val lines: List<QwenLine> = emptyList(),
    val error: String? = null,
    val level: Float = 0f,
    val model: String? = null,
)

private const val MAX_LINES = 80

/** Applies one server event to the transcript and the phase. Audio is not part of this state. */
fun reduceQwenLive(state: QwenLiveState, event: QwenServerEvent): QwenLiveState = when (event) {
    is QwenServerEvent.SessionCreated -> state.copy(model = event.model ?: state.model)
    QwenServerEvent.SessionUpdated -> state.copy(
        phase = if (state.phase == QwenPhase.Stopped) state.phase else QwenPhase.Listening,
        error = null,
    )
    QwenServerEvent.SpeechStarted -> state.copy(phase = if (state.phase == QwenPhase.Stopped) state.phase else QwenPhase.Listening)
    QwenServerEvent.SpeechStopped -> state
    is QwenServerEvent.InputTranscript -> state.withLines(upsert(state.lines, "in-${event.itemId}", QwenSpeaker.You, event.text.trim(), event.partial))
    is QwenServerEvent.OutputDelta -> state.copy(phase = speaking(state.phase))
        .withLines(appendDelta(state.lines, "out-${event.itemId}", event.delta))
    is QwenServerEvent.OutputDone -> state.withLines(finishOutput(state.lines, "out-${event.itemId}", event.transcript))
    is QwenServerEvent.ResponseCreated -> state.copy(phase = speaking(state.phase), error = null)
    is QwenServerEvent.ResponseFinished -> {
        val listening = state.copy(
            phase = if (state.phase == QwenPhase.Stopped) state.phase else QwenPhase.Listening,
            lines = state.lines.map { if (it.speaker == QwenSpeaker.Qwen && it.live) it.copy(live = false) else it },
        )
        if (event.status == "failed") listening.copy(error = "Qwen couldn't finish that reply.") else listening
    }
    is QwenServerEvent.Failure -> state.copy(
        error = event.message,
        phase = if (event.fatal) QwenPhase.Stopped else state.phase,
    )
    is QwenServerEvent.Audio, QwenServerEvent.Ignored -> state
}

private fun speaking(phase: QwenPhase) = if (phase == QwenPhase.Stopped) phase else QwenPhase.Speaking

private fun QwenLiveState.withLines(lines: List<QwenLine>) =
    copy(lines = if (lines.size <= MAX_LINES) lines else lines.takeLast(MAX_LINES))

private fun upsert(lines: List<QwenLine>, id: String, speaker: QwenSpeaker, text: String, live: Boolean): List<QwenLine> {
    if (text.isEmpty()) return lines
    val line = QwenLine(id, speaker, text, live)
    val index = lines.indexOfFirst { it.id == id }
    if (index < 0) return lines + line
    return lines.toMutableList().apply { this[index] = line }
}

private fun appendDelta(lines: List<QwenLine>, id: String, delta: String): List<QwenLine> {
    if (delta.isEmpty()) return lines
    val index = lines.indexOfFirst { it.id == id }
    if (index < 0) return lines + QwenLine(id, QwenSpeaker.Qwen, delta, live = true)
    val current = lines[index]
    return lines.toMutableList().apply { this[index] = current.copy(text = current.text + delta, live = true) }
}

private fun finishOutput(lines: List<QwenLine>, id: String, transcript: String): List<QwenLine> {
    val index = lines.indexOfFirst { it.id == id }
    val text = transcript.ifBlank { lines.getOrNull(index)?.text.orEmpty() }
    if (text.isBlank()) return lines
    val line = QwenLine(id, QwenSpeaker.Qwen, text, live = false)
    if (index < 0) return lines + line
    return lines.toMutableList().apply { this[index] = line }
}

/**
 * Drops audio from a reply the person already interrupted, until the next `response.created`.
 * A late delta from the cancelled reply must not play over the next one.
 */
class QwenPlaybackGate {
    private var responseId: String? = null
    private var suppressed = false

    fun onResponseCreated(id: String?) {
        responseId = id
        suppressed = false
    }

    fun interrupt() {
        suppressed = true
        responseId = null
    }

    fun reset() {
        responseId = null
        suppressed = false
    }

    fun shouldPlay(responseId: String?): Boolean {
        if (suppressed) return false
        val current = this.responseId
        if (current != null && responseId != null && responseId != current) return false
        return true
    }
}

/** Turns a socket failure into one sentence. The DashScope key is not on the phone, so it can't be in here. */
fun friendlyQwenError(message: String?): String {
    val text = message?.trim().orEmpty()
    return when {
        text.contains("bad token", ignoreCase = true) || text.contains("1008") ->
            "The proxy token was rejected. Set the same HERALD_LIVE_PROXY_TOKEN under Settings → Voice."
        text.contains("401") -> "The live proxy or DashScope refused the connection (401). Check the proxy token, and DASHSCOPE_API_KEY on the proxy."
        text.contains("403") -> "The connection was refused (403). Check the proxy token and that the DashScope key matches the region."
        text.contains("Unable to resolve host", ignoreCase = true) || text.contains("UnknownHost") ->
            "Can't find the live proxy. Check the address under Settings → Voice."
        text.contains("Connection refused", ignoreCase = true) || text.contains("ECONNREFUSED") ->
            "The live proxy isn't running at that address."
        text.contains("timeout", ignoreCase = true) || text.contains("timed out", ignoreCase = true) ->
            "The live proxy didn't answer in time."
        text.isBlank() -> "The live connection failed."
        else -> text.take(240)
    }
}
