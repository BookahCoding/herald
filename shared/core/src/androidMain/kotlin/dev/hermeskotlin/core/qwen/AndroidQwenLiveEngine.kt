package dev.hermeskotlin.core.qwen

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Microphone to the live proxy, and the proxy's PCM back out of the speaker.
 * The DashScope key is not here: [start] only receives the proxy address and its token.
 */
class AndroidQwenLiveEngine : QwenLiveEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _signals = MutableSharedFlow<QwenSignal>(extraBufferCapacity = 64)
    override val signals = _signals

    /** Text frames waiting to go up the socket. Not the socket's own `outgoing`. */
    private val toSend = Channel<String>(64)
    private val gate = QwenPlaybackGate()
    private val lock = Any()
    private var generation = 0
    private var job: Job? = null

    @Volatile private var speaker: QwenPcmSpeaker? = null

    override fun start(webSocketUrl: String, proxyToken: String) {
        val gen = synchronized(lock) { ++generation }
        job?.cancel()
        while (toSend.tryReceive().isSuccess) { }
        gate.reset()
        job = scope.launch {
            val local = QwenPcmSpeaker()
            speaker = local
            try {
                local.start(this)
                runSession(webSocketUrl, proxyToken, local)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _signals.tryEmit(QwenSignal.Failed(friendlyQwenError(e.message)))
            } finally {
                if (speaker === local) speaker = null
                local.release()
                if (generation == gen) _signals.tryEmit(QwenSignal.Stopped)
            }
        }
    }

    override fun interrupt() {
        gate.interrupt()
        speaker?.flush()
        toSend.trySend(qwenResponseCancel())
    }

    override fun stop() {
        synchronized(lock) { generation++ }
        job?.cancel()
        job = null
        _signals.tryEmit(QwenSignal.Stopped)
    }

    private suspend fun runSession(url: String, token: String, speaker: QwenPcmSpeaker) {
        liveClient().use { client ->
            client.webSocket(url, request = {
                if (token.isNotBlank()) header("X-Herald-Live-Token", token)
            }) {
                coroutineScope {
                    val ready = CompletableDeferred<Unit>()
                    val reader = launch {
                        try {
                            for (frame in incoming) {
                                if (frame !is Frame.Text) continue
                                when (val event = parseQwenServerEvent(frame.readText())) {
                                    is QwenServerEvent.Audio -> if (gate.shouldPlay(event.responseId)) speaker.play(event.pcm)
                                    QwenServerEvent.SpeechStarted -> {
                                        // The server is cancelling the reply because the person started talking.
                                        gate.interrupt()
                                        speaker.flush()
                                        _signals.tryEmit(QwenSignal.Event(event))
                                    }
                                    is QwenServerEvent.ResponseCreated -> {
                                        gate.onResponseCreated(event.id)
                                        _signals.tryEmit(QwenSignal.Event(event))
                                    }
                                    QwenServerEvent.SessionUpdated -> {
                                        if (!ready.isCompleted) ready.complete(Unit)
                                        _signals.tryEmit(QwenSignal.Event(event))
                                    }
                                    is QwenServerEvent.Failure -> {
                                        if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(event.message))
                                        _signals.tryEmit(QwenSignal.Event(event))
                                    }
                                    else -> _signals.tryEmit(QwenSignal.Event(event))
                                }
                            }
                        } finally {
                            if (!ready.isCompleted) {
                                ready.completeExceptionally(IllegalStateException("The realtime connection closed before the session was ready."))
                            }
                        }
                    }
                    val sender = launch {
                        for (text in toSend) send(Frame.Text(text))
                    }
                    try {
                        try {
                            withTimeout(8_000) { ready.await() }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            _signals.tryEmit(
                                QwenSignal.Failed("Qwen didn't confirm the session. Check the proxy, the region, and DASHSCOPE_REALTIME_MODEL."),
                            )
                            return@coroutineScope
                        }
                        capture()
                    } finally {
                        reader.cancel()
                        sender.cancel()
                    }
                }
            }
        }
    }

    /** 16 kHz mono PCM, about 100 ms a chunk, which is what the realtime API asks to be sent. */
    @SuppressLint("MissingPermission") // The screen asks for RECORD_AUDIO before start.
    private suspend fun capture() = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            QWEN_INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            QWEN_INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, QWEN_INPUT_CHUNK_SAMPLES * 2 * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("The microphone isn't available.")
        }
        val effects = buildList {
            if (AcousticEchoCanceler.isAvailable()) {
                runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull()?.let(::add)
            }
            if (NoiseSuppressor.isAvailable()) {
                runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull()?.let(::add)
            }
        }
        effects.forEach { runCatching { it.enabled = true } }
        val frame = ShortArray(QWEN_INPUT_CHUNK_SAMPLES)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("The microphone didn't start.")
            while (coroutineContext.isActive) {
                val read = record.read(frame, 0, frame.size)
                if (read <= 0) continue
                val bytes = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                for (i in 0 until read) {
                    bytes.putShort(frame[i])
                    sum += frame[i].toDouble() * frame[i]
                }
                val level = min(1.0, sqrt(sum / read) / 256.0 / 42.0).toFloat()
                _signals.tryEmit(QwenSignal.Level(level))
                toSend.send(qwenAppendAudio(bytes.array()))
            }
        } finally {
            runCatching { record.stop() }
            effects.forEach { runCatching { it.release() } }
            record.release()
            _signals.tryEmit(QwenSignal.Level(0f))
        }
    }

    private fun liveClient() = HttpClient {
        install(WebSockets) { pingIntervalMillis = 20_000 }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }
}

/** Plays 24 kHz PCM as it arrives, and drops what is queued when the person interrupts. */
private class QwenPcmSpeaker {
    private val queue = Channel<ByteArray>(Channel.BUFFERED)
    private val generation = AtomicInteger()
    private val lock = Any()
    private var track: AudioTrack? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        val min = AudioTrack.getMinBufferSize(
            QWEN_OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val built = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(QWEN_OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, QWEN_OUTPUT_SAMPLE_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        built.play()
        track = built
        job = scope.launch(Dispatchers.IO) {
            for (chunk in queue) write(built, chunk)
        }
    }

    fun play(pcm: ByteArray) {
        if (pcm.isNotEmpty()) queue.trySend(pcm)
    }

    fun flush() {
        generation.incrementAndGet()
        while (queue.tryReceive().isSuccess) { }
        synchronized(lock) {
            track?.let {
                runCatching { it.pause() }
                runCatching { it.flush() }
                runCatching { it.play() }
            }
        }
    }

    fun release() {
        generation.incrementAndGet()
        queue.close()
        job?.cancel()
        synchronized(lock) {
            track?.let {
                runCatching { it.pause() }
                runCatching { it.flush() }
                runCatching { it.release() }
            }
            track = null
        }
    }

    private suspend fun write(track: AudioTrack, chunk: ByteArray) {
        val gen = generation.get()
        var offset = 0
        while (offset < chunk.size) {
            val wrote = synchronized(lock) {
                if (gen != generation.get()) return
                track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            if (wrote < 0) return
            if (wrote == 0) delay(10) else offset += wrote
        }
    }
}
