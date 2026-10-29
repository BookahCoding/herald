package dev.hermeskotlin.core.qwen

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QwenRealtimeTest {

    @Test
    fun parsesTheEventsTheScreenActsOn() {
        val created = parseQwenServerEvent(
            """{"type":"session.created","session":{"model":"qwen-audio-3.1-realtime-plus"}}""",
        )
        assertEquals(QwenServerEvent.SessionCreated("qwen-audio-3.1-realtime-plus"), created)

        val input = parseQwenServerEvent(
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"u1","text":"Hello","stash":" there"}""",
        )
        assertEquals(QwenServerEvent.InputTranscript("u1", "Hello there", partial = true), input)

        val done = parseQwenServerEvent(
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"u1","transcript":"Hello there"}""",
        )
        assertEquals(QwenServerEvent.InputTranscript("u1", "Hello there", partial = false), done)

        val delta = parseQwenServerEvent(
            """{"type":"response.audio_transcript.delta","item_id":"a1","delta":"Hi"}""",
        )
        assertEquals(QwenServerEvent.OutputDelta("a1", "Hi"), delta)

        val reply = parseQwenServerEvent(
            """{"type":"response.audio_transcript.done","item_id":"a1","transcript":"Hi there"}""",
        )
        assertEquals(QwenServerEvent.OutputDone("a1", "Hi there"), reply)

        val pcm = ByteArray(4) { it.toByte() }
        val audio = parseQwenServerEvent(
            """{"type":"response.audio.delta","response_id":"r1","delta":"${Base64.encode(pcm)}"}""",
        )
        val played = assertIs<QwenServerEvent.Audio>(audio)
        assertEquals("r1", played.responseId)
        assertTrue(pcm.contentEquals(played.pcm))

        val failure = parseQwenServerEvent(
            """{"type":"error","error":{"type":"server_error","message":"upstream down"}}""",
        )
        assertEquals(QwenServerEvent.Failure("upstream down", fatal = true), failure)

        val clientError = parseQwenServerEvent(
            """{"type":"error","error":{"type":"invalid_request_error","message":"busy"}}""",
        )
        assertEquals(QwenServerEvent.Failure("busy", fatal = false), clientError)

        assertEquals(QwenServerEvent.Ignored, parseQwenServerEvent("not json"))
        assertEquals(QwenServerEvent.SpeechStarted, parseQwenServerEvent("""{"type":"input_audio_buffer.speech_started"}"""))
        assertEquals(
            QwenServerEvent.ResponseFinished("cancelled"),
            parseQwenServerEvent("""{"type":"response.done","response":{"status":"cancelled"}}"""),
        )
    }

    @Test
    fun appendsMicAudioAndCancelsAReply() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val append = qwenAppendAudio(pcm)
        assertTrue(append.contains("\"type\":\"input_audio_buffer.append\""))
        val encoded = Base64.encode(pcm)
        assertTrue(encoded in append)
        val decoded = assertIs<QwenServerEvent.Audio>(
            parseQwenServerEvent("""{"type":"response.audio.delta","delta":"$encoded"}"""),
        )
        assertTrue(pcm.contentEquals(decoded.pcm))
        assertEquals("""{"type":"response.cancel"}""", qwenResponseCancel())
        assertTrue("sk-" !in append)
    }

    @Test
    fun proxyAddressBecomesTheLiveSocket() {
        assertEquals("ws://192.168.1.8:8787/v1/qwen/realtime", qwenLiveWebSocketUrl("http://192.168.1.8:8787"))
        assertEquals("wss://voice.example.com/v1/qwen/realtime", qwenLiveWebSocketUrl("https://voice.example.com/"))
        assertEquals("ws://10.0.0.5:8787/v1/qwen/realtime", qwenLiveWebSocketUrl("10.0.0.5:8787"))
        assertEquals(
            "ws://10.0.0.5:8787/custom",
            qwenLiveWebSocketUrl("ws://10.0.0.5:8787/custom"),
        )
        assertNull(qwenLiveWebSocketUrl("   "))
        assertNull(qwenLiveWebSocketUrl("ws://"))
    }

    @Test
    fun transcriptStreamsThenSettlesAndAFailureStaysOnScreen() {
        var state = QwenLiveState(phase = QwenPhase.Connecting)
        state = reduceQwenLive(state, QwenServerEvent.SessionCreated("qwen-audio-3.1-realtime-plus"))
        assertEquals("qwen-audio-3.1-realtime-plus", state.model)
        assertEquals(QwenPhase.Connecting, state.phase)
        state = reduceQwenLive(state, QwenServerEvent.SessionUpdated)
        assertEquals(QwenPhase.Listening, state.phase)

        state = reduceQwenLive(state, QwenServerEvent.InputTranscript("u", "Hel", partial = true))
        state = reduceQwenLive(state, QwenServerEvent.InputTranscript("u", "Hello", partial = false))
        assertEquals(listOf(QwenLine("in-u", QwenSpeaker.You, "Hello", live = false)), state.lines)

        state = reduceQwenLive(state, QwenServerEvent.ResponseCreated("r"))
        state = reduceQwenLive(state, QwenServerEvent.OutputDelta("a", "Hi"))
        state = reduceQwenLive(state, QwenServerEvent.OutputDelta("a", " there"))
        assertEquals(QwenPhase.Speaking, state.phase)
        assertEquals("Hi there", state.lines.last().text)
        assertTrue(state.lines.last().live)

        state = reduceQwenLive(state, QwenServerEvent.OutputDone("a", "Hi there."))
        state = reduceQwenLive(state, QwenServerEvent.ResponseFinished("completed"))
        assertEquals("Hi there.", state.lines.last().text)
        assertFalse(state.lines.last().live)
        assertEquals(QwenPhase.Listening, state.phase)

        state = reduceQwenLive(state, QwenServerEvent.SpeechStarted)
        assertEquals(QwenPhase.Listening, state.phase)

        state = reduceQwenLive(state, QwenServerEvent.Failure("busy", fatal = false))
        assertEquals("busy", state.error)
        assertEquals(QwenPhase.Listening, state.phase)
        state = reduceQwenLive(state, QwenServerEvent.Failure("upstream down", fatal = true))
        assertEquals(QwenPhase.Stopped, state.phase)
        assertEquals("upstream down", state.error)
    }

    @Test
    fun playbackGateDropsTheReplyThatWasInterrupted() {
        val gate = QwenPlaybackGate()
        gate.onResponseCreated("r1")
        assertTrue(gate.shouldPlay("r1"))
        gate.interrupt()
        assertFalse(gate.shouldPlay("r1"))
        assertFalse(gate.shouldPlay("r2"))
        gate.onResponseCreated("r2")
        assertTrue(gate.shouldPlay("r2"))
        assertFalse(gate.shouldPlay("r1"))
    }

    @Test
    fun errorsNameTheThingToFix() {
        assertTrue("token" in friendlyQwenError("WebSocket closed: bad token"))
        assertTrue("401" in friendlyQwenError("Expected HTTP 101, got 401"))
        assertTrue("proxy" in friendlyQwenError("Connection refused"))
        assertTrue("address" in friendlyQwenError("Unable to resolve host \"voice.local\""))
        assertEquals("The live connection failed.", friendlyQwenError(null))
        assertEquals("session closed", friendlyQwenError("session closed"))
    }
}
