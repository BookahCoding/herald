package dev.hermeskotlin.core.qwen

import kotlinx.coroutines.flow.Flow

/** What the microphone session reports. Audio bytes stay inside the engine and are played there. */
sealed interface QwenSignal {
    data class Event(val event: QwenServerEvent) : QwenSignal
    data class Level(val level: Float) : QwenSignal
    data class Failed(val message: String) : QwenSignal
    data object Stopped : QwenSignal
}

/**
 * One live call: microphone up to the proxy, replies out of the speaker.
 * [start] replaces a call already going. The API key is not a parameter; the proxy adds it.
 */
interface QwenLiveEngine {
    val signals: Flow<QwenSignal>
    fun start(webSocketUrl: String, proxyToken: String)
    fun interrupt()
    fun stop()
}
