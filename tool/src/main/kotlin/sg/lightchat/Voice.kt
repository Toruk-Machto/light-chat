package sg.lightchat

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

private const val MIC_RATE = 16_000
private const val REPLY_RATE = 24_000
private const val LIVE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
private const val DICTATION_MODEL = "gemini-3.1-flash-lite"
private const val LIVE_MODEL = "gemini-3.8-live"
private const val SPEECH_URL = "https://openrouter.ai/api/v1/audio/speech"
private const val FREE_VOICE_MODEL = "fish-audio/s2.1-pro-free:free"

internal enum class VoiceMode { Idle, Recording, Processing, Playing, Paused, Connecting, Live }

internal data class VoiceState(val mode: VoiceMode = VoiceMode.Idle, val note: String = "", val liveText: String = "")

internal class VoiceController(
    private val api: ChatApi,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(VoiceState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var input: PcmInput? = null
    private var captured: ByteArrayOutputStream? = null
    private var player: MediaPlayer? = null
    private var live: LiveSession? = null
    var textToRead: String? = null
        private set

    private fun publish(mode: VoiceMode, message: String = "", liveText: String = "") {
        mutable.value = VoiceState(mode, message, liveText)
    }

    fun prepareRead(text: String) {
        stop()
        textToRead = text
        publish(VoiceMode.Idle, "Choose a provider to read this answer aloud.")
    }

    fun startDictating(): Boolean {
        stop()
        val buffer = ByteArrayOutputStream()
        return try {
            input = PcmInput(false) { bytes ->
                synchronized(buffer) {
                    if (buffer.size() < MIC_RATE * 2 * 40) buffer.write(bytes)
                }
            }
            captured = buffer
            publish(VoiceMode.Recording, "Listening…")
            true
        } catch (_: Exception) {
            publish(VoiceMode.Idle, "Could not start the microphone.")
            false
        }
    }

    fun finishDictating(demo: Boolean, getKey: suspend () -> String?, onText: (String) -> Unit) {
        val buffer = captured ?: return
        captured = null
        input?.close()
        input = null
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        if (pcm.size < MIC_RATE / 2) {
            publish(VoiceMode.Idle, "Record a little longer before finishing.")
            return
        }
        publish(VoiceMode.Processing, "Transcribing with Gemini…")
        job = scope.launch {
            attempt(onError = { publish(VoiceMode.Idle, it) }) {
                if (demo) {
                    delay(220)
                    publish(VoiceMode.Idle)
                    onText("Simulated dictation from the emulator.")
                    return@attempt
                }
                val key = getKey() ?: throw ApiProblem("Add a Gemini API key in Settings.")
                val file = Attachment("dictation.wav", "audio/wav", wav(pcm, MIC_RATE))
                val prompt = buildJsonObject {
                    put("contents", buildJsonArray {
                        add(geminiPart("user", "Transcribe the speech verbatim. Return only the transcript. If there is no intelligible speech, return [no speech].", file))
                    })
                }
                val result = api.request(geminiEndpoint(DICTATION_MODEL), Provider.Gemini, key, prompt)
                val text = result.objects("candidates").firstOrNull()?.obj("content")?.objects("parts")
                    ?.filterNot { it.flag("thought") }?.joinToString(" ") { it.text("text") }.orEmpty().trim()
                if (text.isBlank() || text.equals("[no speech]", true)) throw ApiProblem("No speech was heard. Try again.")
                publish(VoiceMode.Idle)
                onText(text)
            }
        }
    }

    fun read(provider: Provider, getKey: suspend () -> String?, demo: Boolean) {
        val text = textToRead ?: return
        stop()
        publish(VoiceMode.Processing, "Preparing audio…")
        job = scope.launch {
            attempt(onError = { publish(VoiceMode.Idle, it) }) {
                val bytes = if (demo) {
                    wav(demoPcm(REPLY_RATE), REPLY_RATE)
                } else {
                    val key = getKey() ?: throw ApiProblem("Add a ${provider.label} API key in Settings.")
                    if (provider == Provider.Router) speakRouter(key, text) else speakGemini(key, text)
                }
                play(bytes)
            }
        }
    }

    private suspend fun speakRouter(key: String, text: String): ByteArray {
        val model = api.request("https://openrouter.ai/api/v1/models?output_modalities=speech", Provider.Router, null)
            .objects("data").firstOrNull { it.text("id") == FREE_VOICE_MODEL }
            ?: throw ApiProblem("The free OpenRouter voice is currently unavailable.")
        val price = model.obj("pricing")
        if (price.decimal("prompt") != 0.0 || price.decimal("completion") != 0.0 ||
            price.any { (_, amount) -> (amount as? JsonPrimitive)?.doubleOrNull?.let { it != 0.0 } == true }
        ) throw ApiProblem("The OpenRouter voice is outside free-only pricing.")
        return api.bytes(SPEECH_URL, Provider.Router, key, buildJsonObject {
            put("model", FREE_VOICE_MODEL)
            put("input", text.take(4_000))
            model.strings("supported_voices").firstOrNull()?.let { put("voice", it) }
            put("response_format", "mp3")
        })
    }

    private suspend fun speakGemini(key: String, text: String): ByteArray {
        val result = api.request("$GEMINI/interactions", Provider.Gemini, key, buildJsonObject {
            put("model", "gemini-3.8-flash-lite-tts")
            put("store", false)
            put("input", text.take(4_000))
            put("response_format", buildJsonObject { put("type", "audio") })
            put("generation_config", buildJsonObject {
                put("speech_config", buildJsonArray { add(buildJsonObject { put("voice", "Kore") }) })
            })
        })
        val audio = result.objects("steps").filter { it.text("type") == "model_output" }
            .flatMap { it.objects("content") }.lastOrNull { it.text("type") == "audio" }?.text("data")
        if (audio.isNullOrBlank()) throw ApiProblem("Gemini did not return playable speech.")
        return Base64.getDecoder().decode(audio)
    }

    private fun play(bytes: ByteArray) {
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attributes(AudioAttributes.USAGE_MEDIA))
            mp.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val actual = minOf(size, bytes.size - position.toInt())
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + actual)
                    return actual
                }

                override fun getSize() = bytes.size.toLong()
                override fun close() {}
            })
            mp.setOnPreparedListener {
                if (player == it) {
                    it.start()
                    publish(VoiceMode.Playing, "Reading aloud")
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                publish(VoiceMode.Idle, "The audio could not be played.")
                true
            }
            mp.setOnCompletionListener {
                stop()
                publish(VoiceMode.Idle, "Playback finished.")
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            stop()
            throw ApiProblem("The audio could not be played.")
        }
    }

    fun pauseOrResume() {
        val mp = player ?: return
        if (mutable.value.mode == VoiceMode.Playing) {
            mp.pause()
            publish(VoiceMode.Paused, "Paused")
        } else if (mutable.value.mode == VoiceMode.Paused) {
            mp.start()
            publish(VoiceMode.Playing, "Reading aloud")
        }
    }

    fun startLive(getKey: suspend () -> String?, demo: Boolean) {
        stop()
        publish(VoiceMode.Connecting, "Connecting to Gemini Live…")
        job = scope.launch {
            attempt(onError = { message -> stop(); publish(VoiceMode.Idle, message) }) {
                if (demo) {
                    delay(250)
                    publish(VoiceMode.Live, "Demo live session", "Model: This is a simulated live voice session.")
                } else {
                    val key = getKey() ?: throw ApiProblem("Add a Gemini API key in Settings.")
                    live = LiveSession(api, key, scope) { event ->
                        when (event) {
                            is LiveEvent.End -> {
                                stop()
                                publish(VoiceMode.Idle, event.reason)
                            }
                            is LiveEvent.Status -> publish(VoiceMode.Live, event.status, mutable.value.liveText)
                            is LiveEvent.Transcript -> {
                                val previous = mutable.value.liveText
                                publish(VoiceMode.Live, mutable.value.note, (previous + event.text).takeLast(1400))
                            }
                        }
                    }
                }
            }
        }
    }

    fun interrupt() {
        live?.interrupt()
        if (mutable.value.mode == VoiceMode.Live) publish(
            VoiceMode.Live, "Speak to interrupt", mutable.value.liveText,
        )
    }

    fun stop() {
        job?.cancel()
        job = null
        captured = null
        input?.close()
        input = null
        player?.release()
        player = null
        live?.close()
        live = null
        publish(VoiceMode.Idle)
    }

    fun close() {
        stop()
        textToRead = null
    }
}

private sealed interface LiveEvent {
    data class End(val reason: String) : LiveEvent
    data class Status(val status: String) : LiveEvent
    data class Transcript(val text: String) : LiveEvent
}

private class LiveSession(
    api: ChatApi,
    key: String,
    private val scope: CoroutineScope,
    private val emit: (LiveEvent) -> Unit,
) {
    private val events = Channel<JsonObject>(64)
    private val speaker = StreamingSpeaker(scope)
    private var mic: PcmInput? = null
    private var closed = false
    private var outputStarted = false
    private var inputStarted = false
    private var muting = false
    private val socket = api.client.newBuilder().pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()
        .newWebSocket(Request.Builder().url(
            LIVE_URL.replaceFirst("wss:", "https:").toHttpUrl().newBuilder().addQueryParameter("key", key).build(),
        ).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(liveSetup().toString())) fail("Gemini Live could not start.")
            }

            override fun onMessage(webSocket: WebSocket, text: String) = receive(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(bytes.utf8())

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                fail(if (code == null) "Gemini Live lost its network connection." else errorText(Provider.Gemini, code, null))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                fail(when (code) {
                    1008 -> "Gemini Live denied this session. Check key permissions, project quota and model availability."
                    1011, 1013 -> "Gemini Live is temporarily unavailable. Start a new session."
                    else -> "Gemini Live ended the session."
                })
            }

            private fun receive(text: String) {
                val json = runCatching { parseObject(text) }.getOrNull() ?: return
                if (events.trySend(json).isFailure) fail("The live audio connection fell behind.")
            }
        })
    private val receiver = scope.launch {
        try {
            val first = withTimeoutOrNull(15_000L) { events.receive() }
                ?: throw ApiProblem("Gemini Live setup timed out.")
            handle(first)
            for (event in events) handle(event)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(if (error is ApiProblem) error.message.orEmpty() else "Live audio could not start.")
        }
    }

    private fun handle(event: JsonObject) {
        if ("error" in event) throw ApiProblem("Gemini Live rejected the session. Check your project's access.")
        if ("setupComplete" in event && mic == null) {
            mic = PcmInput(true) { pcm ->
                if (socket.queueSize() > 512_000L || !socket.send(liveAudio(pcm).toString())) {
                    fail("The live audio connection fell behind.")
                }
            }
            emit(LiveEvent.Status("Listening · speak to interrupt"))
        }
        if ("goAway" in event) {
            fail("Gemini Live is ending this session. Start a new session to continue.")
            return
        }
        val content = event.obj("serverContent")
        if (content.flag("interrupted")) {
            speaker.interrupt()
            muting = false
            outputStarted = false
            emit(LiveEvent.Status("Interrupted · listening"))
        }
        val heard = content.obj("inputTranscription").text("text")
        if (heard.isNotBlank()) {
            emit(LiveEvent.Transcript((if (inputStarted) "" else "\nYou: ") + heard))
            inputStarted = true
        }
        val spoken = content.obj("outputTranscription").text("text")
        if (spoken.isNotBlank() && !muting) {
            emit(LiveEvent.Transcript((if (outputStarted) "" else "\nModel: ") + spoken))
            outputStarted = true
        }
        if (!muting) for (part in content.obj("modelTurn").objects("parts")) {
            val inline = part.obj("inlineData")
            val audio = inline.text("data")
            if (audio.isNotBlank() && inline.text("mimeType").startsWith("audio/pcm")) {
                speaker.enqueue(Base64.getDecoder().decode(audio))
                emit(LiveEvent.Status("Speaking · you can interrupt"))
            }
        }
        if (content.flag("turnComplete")) {
            inputStarted = false
            outputStarted = false
            muting = false
            emit(LiveEvent.Status("Listening · speak to interrupt"))
        }
    }

    fun interrupt() {
        muting = true
        speaker.interrupt()
    }

    private fun fail(reason: String) {
        scope.launch {
            if (!closed) emit(LiveEvent.End(reason))
        }
    }

    fun close() {
        if (closed) return
        closed = true
        receiver.cancel()
        events.cancel()
        mic?.close()
        speaker.close()
        socket.close(1000, null)
    }
}

internal fun liveSetup() = buildJsonObject {
    put("setup", buildJsonObject {
        put("model", "models/$LIVE_MODEL")
        put("generationConfig", buildJsonObject {
            put("responseModalities", buildJsonArray { add("AUDIO") })
        })
        put("systemInstruction", buildJsonObject {
            put("parts", buildJsonArray {
                add(buildJsonObject { put("text", "Speak concisely. Use a conversational tone.") })
            })
        })
        put("inputAudioTranscription", buildJsonObject {})
        put("outputAudioTranscription", buildJsonObject {})
        put("realtimeInputConfig", buildJsonObject {
            put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
        })
    })
}

internal fun liveAudio(pcm: ByteArray) = buildJsonObject {
    put("realtimeInput", buildJsonObject {
        put("audio", buildJsonObject {
            put("mimeType", "audio/pcm;rate=$MIC_RATE")
            put("data", pcm.base64())
        })
    })
}

@SuppressLint("MissingPermission")
private class PcmInput(live: Boolean, private val onAudio: (ByteArray) -> Unit) : AutoCloseable {
    private val record: AudioRecord
    private val echoCancel: AcousticEchoCanceler?
    private val worker: Thread
    @Volatile private var active = true

    init {
        val minimum = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw ApiProblem("This device cannot capture microphone audio.")
        record = AudioRecord(
            if (live) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC, MIC_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 3200),
        )
        echoCancel = if (live && AcousticEchoCanceler.isAvailable()) runCatching {
            AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }.getOrNull() else null
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) throw ApiProblem("The microphone is unavailable.")
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw ApiProblem("The microphone did not start.")
        } catch (error: Exception) {
            echoCancel?.release()
            record.release()
            throw error
        }
        worker = Thread({
            val buffer = ByteArray(1280)
            while (active) {
                val size = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (size < 0) break
                if (size > 0 && active) onAudio(buffer.copyOf(size))
            }
        }, "Light Chat microphone").apply { start() }
    }

    override fun close() {
        active = false
        runCatching { record.stop() }
        runCatching { worker.join(180) }
        echoCancel?.release()
        record.release()
    }
}

private class StreamingSpeaker(scope: CoroutineScope) : AutoCloseable {
    private val queue = Channel<Pair<Int, ByteArray>>(100)
    private val minimum = AudioTrack.getMinBufferSize(REPLY_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val track = AudioTrack.Builder().setAudioAttributes(attributes(AudioAttributes.USAGE_VOICE_COMMUNICATION))
        .setAudioFormat(AudioFormat.Builder().setSampleRate(REPLY_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, 4800)).build()
    private var generation = 0
    private val worker = scope.launch(Dispatchers.IO) {
        for ((version, chunk) in queue) {
            var offset = 0
            while (offset < chunk.size && isActive) {
                val written = synchronized(track) {
                    if (version != generation) -1 else {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    }
                }
                if (written < 0) break
                if (written == 0) delay(10) else offset += written
            }
        }
    }

    fun enqueue(bytes: ByteArray) {
        val version = synchronized(track) { generation }
        if (queue.trySend(version to bytes).isFailure) interrupt()
    }

    fun interrupt() {
        synchronized(track) {
            generation += 1
            while (queue.tryReceive().isSuccess) Unit
            track.pause()
            track.flush()
        }
    }

    override fun close() {
        worker.cancel()
        queue.cancel()
        synchronized(track) {
            generation += 1
            track.release()
        }
    }
}

private fun attributes(usage: Int): AudioAttributes =
    AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

internal fun wav(pcm: ByteArray, rate: Int): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
    .put("RIFF".encodeToByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".encodeToByteArray())
    .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    .put("data".encodeToByteArray()).putInt(pcm.size).put(pcm).array()

private fun demoPcm(rate: Int): ByteArray {
    val buffer = ByteBuffer.allocate(rate * 8).order(ByteOrder.LITTLE_ENDIAN)
    repeat(buffer.capacity() / 2) { index ->
        val note = if (index / (rate / 4) % 2 == 0) 440.0 else 523.25
        val value = kotlin.math.sin(2 * Math.PI * note * index / rate) * 1700
        buffer.putShort(value.toInt().toShort())
    }
    return buffer.array()
}
