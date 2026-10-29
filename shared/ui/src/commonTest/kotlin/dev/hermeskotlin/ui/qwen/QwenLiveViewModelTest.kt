package dev.hermeskotlin.ui.qwen

import dev.hermeskotlin.core.qwen.QwenLine
import dev.hermeskotlin.core.qwen.QwenLiveEngine
import dev.hermeskotlin.core.qwen.QwenPhase
import dev.hermeskotlin.core.qwen.QwenServerEvent
import dev.hermeskotlin.core.qwen.QwenSignal
import dev.hermeskotlin.core.qwen.QwenSpeaker
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QwenLiveViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun startNeedsAProxyAndThenShowsTheTranscript() = runTest {
        val engine = FakeQwenEngine()
        val settings = SettingsStore(InMemoryKeyValueStore(), backgroundScope)
        val missing = QwenLiveViewModel(engine, settings)
        missing.start()
        assertEquals(QwenPhase.Idle, missing.state.value.phase)
        assertTrue(missing.state.value.error!!.contains("proxy"))
        assertNull(engine.started)

        settings.update { it.copy(qwenProxyUrl = "http://10.0.0.8:8787", qwenProxyToken = "phone-token") }
        advanceUntilIdle()
        val vm = QwenLiveViewModel(engine, settings)
        vm.start()
        assertEquals("ws://10.0.0.8:8787/v1/qwen/realtime" to "phone-token", engine.started)
        assertEquals(QwenPhase.Connecting, vm.state.value.phase)
        assertNull(vm.state.value.error)

        engine.emit(QwenServerEvent.SessionCreated("qwen-audio-3.1-realtime-plus"))
        engine.emit(QwenServerEvent.SessionUpdated)
        engine.emit(QwenServerEvent.InputTranscript("u", "Hello", partial = false))
        engine.emit(QwenServerEvent.ResponseCreated("r"))
        engine.emit(QwenServerEvent.OutputDelta("a", "Hi"))
        assertEquals(QwenPhase.Speaking, vm.state.value.phase)
        assertEquals("qwen-audio-3.1-realtime-plus", vm.state.value.model)
        assertEquals(
            listOf(
                QwenLine("in-u", QwenSpeaker.You, "Hello", live = false),
                QwenLine("out-a", QwenSpeaker.Qwen, "Hi", live = true),
            ),
            vm.state.value.lines,
        )

        vm.interrupt()
        assertEquals(1, engine.interrupts)
        assertEquals(QwenPhase.Listening, vm.state.value.phase)

        engine.emit(QwenServerEvent.Failure("DashScope rejected the API key (401).", fatal = true))
        assertEquals(QwenPhase.Stopped, vm.state.value.phase)
        assertTrue(vm.state.value.error!!.contains("401"))
        vm.dismissError()
        assertNull(vm.state.value.error)

        vm.stop()
        assertEquals(1, engine.stops)
        assertEquals(QwenPhase.Stopped, vm.state.value.phase)
    }

    @Test
    fun aSocketFailureIsTheErrorAndStopLeavesItUntilDismissed() = runTest {
        val engine = FakeQwenEngine()
        val settings = SettingsStore(InMemoryKeyValueStore(), backgroundScope)
        settings.update { it.copy(qwenProxyUrl = "10.1.1.1:8787") }
        advanceUntilIdle()
        val vm = QwenLiveViewModel(engine, settings)
        vm.start()
        engine.fail("Connection refused")
        assertEquals("Connection refused", vm.state.value.error)
        assertEquals(QwenPhase.Stopped, vm.state.value.phase)
        engine.stopped()
        assertEquals("Connection refused", vm.state.value.error)
    }
}

private class FakeQwenEngine : QwenLiveEngine {
    private val signalsFlow = MutableSharedFlow<QwenSignal>(extraBufferCapacity = 16)
    override val signals = signalsFlow
    var started: Pair<String, String>? = null
    var interrupts = 0
    var stops = 0

    override fun start(webSocketUrl: String, proxyToken: String) {
        started = webSocketUrl to proxyToken
    }

    override fun interrupt() {
        interrupts += 1
    }

    override fun stop() {
        stops += 1
    }

    fun emit(event: QwenServerEvent) {
        signalsFlow.tryEmit(QwenSignal.Event(event))
    }

    fun fail(message: String) {
        signalsFlow.tryEmit(QwenSignal.Failed(message))
    }

    fun stopped() {
        signalsFlow.tryEmit(QwenSignal.Stopped)
    }
}
