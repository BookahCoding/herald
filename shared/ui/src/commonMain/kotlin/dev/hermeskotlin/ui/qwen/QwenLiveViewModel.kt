package dev.hermeskotlin.ui.qwen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.qwen.QwenLiveEngine
import dev.hermeskotlin.core.qwen.QwenLiveState
import dev.hermeskotlin.core.qwen.QwenPhase
import dev.hermeskotlin.core.qwen.QwenSignal
import dev.hermeskotlin.core.qwen.qwenLiveWebSocketUrl
import dev.hermeskotlin.core.qwen.reduceQwenLive
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The live chat: start and stop the mic session, interrupt a reply, and keep the transcript. */
class QwenLiveViewModel(
    private val engine: QwenLiveEngine,
    private val settings: SettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(QwenLiveState())
    val state: StateFlow<QwenLiveState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            engine.signals.collect { signal ->
                when (signal) {
                    is QwenSignal.Level -> _state.update { it.copy(level = signal.level) }
                    is QwenSignal.Failed -> _state.update { it.copy(phase = QwenPhase.Stopped, level = 0f, error = signal.message) }
                    QwenSignal.Stopped -> _state.update {
                        it.copy(phase = if (it.phase == QwenPhase.Idle) it.phase else QwenPhase.Stopped, level = 0f)
                    }
                    is QwenSignal.Event -> _state.update { reduceQwenLive(it, signal.event) }
                }
            }
        }
    }

    fun start() {
        val saved = settings.settings.value
        val url = qwenLiveWebSocketUrl(saved?.qwenProxyUrl.orEmpty())
        if (url == null) {
            _state.update {
                it.copy(
                    phase = QwenPhase.Idle,
                    error = "Add the live proxy address under Settings → Voice. The DashScope key stays on that proxy.",
                )
            }
            return
        }
        _state.update { it.copy(phase = QwenPhase.Connecting, error = null, lines = emptyList(), level = 0f) }
        engine.start(url, saved?.qwenProxyToken?.trim().orEmpty())
    }

    fun stop() {
        engine.stop()
        _state.update { it.copy(phase = QwenPhase.Stopped, level = 0f) }
    }

    /** Cuts off the reply that is playing and tells the model to cancel it. */
    fun interrupt() {
        engine.interrupt()
        _state.update { if (it.phase == QwenPhase.Speaking) it.copy(phase = QwenPhase.Listening) else it }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun showError(message: String) {
        _state.update { it.copy(error = message) }
    }

    override fun onCleared() {
        engine.stop()
    }
}
