package com.pusios.pots.speech.piper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.pusios.pots.ui.tts.PotsTextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The bundled offline Piper voice (sherpa-onnx runtime), Android only.
 *
 * Text is phonemized with espeak-ng (piper-phonemize), synthesized by the VITS
 * model and streamed to an [AudioTrack] as it is produced, so [stop] can cut
 * the speech mid-sentence. The model is heavy: it loads lazily on the first
 * utterance and is released by [close], after which the next [say] simply
 * re-initializes it.
 *
 * Instances are shared per voice id through [PiperTts] so the chat, the
 * settings "test" button and the cube dialogue never load the model twice.
 */
class PiperTextToSpeech internal constructor(
    private val context: Context,
    private val voiceId: String,
    private val rateProvider: () -> Float = { 1.0f },
) : PotsTextToSpeech {

    /** Serializes utterances; also guards [synthesizeBlocking] (cube path). */
    private val sayMutex = Mutex()
    private val engineLock = Any()

    private var engine: OfflineTts? = null
    private var phonemizerReady = false

    @Volatile
    private var track: AudioTrack? = null

    private var utteranceToken = 0

    override val isAvailable: Boolean
        get() = PiperVoices.isBundled(context, voiceId)

    override suspend fun say(text: String, flush: Boolean, interrupt: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (interrupt) stop()
        sayMutex.withLock {
            val token = nextToken()
            withContext(Dispatchers.IO) {
                runCatching { speakUnderToken(token, trimmed) }
                    .onFailure { Log.w(TAG, "piper synthesis failed", it) }
            }
        }
    }

    override fun stop() {
        nextToken()
        runCatching {
            track?.let {
                it.pause()
                it.flush()
            }
        }
    }

    override fun close() {
        nextToken()
        synchronized(engineLock) {
            engine?.release()
            engine = null
            phonemizerReady = false
        }
        runCatching { track?.release() }
        track = null
    }

    /**
     * Synthesizes [text] to PCM without playing it. Blocking — call from a
     * background thread. Used by the cube dialogue ([com.pusios.pots.companion.cophone.CoPhoneTts])
     * to produce the packets it sends over the air.
     */
    fun synthesizeBlocking(text: String, speed: Float = 1.0f): Pair<FloatArray, Int>? = runBlocking {
        sayMutex.withLock {
            withContext(Dispatchers.IO) {
                val trimmed = text.trim()
                if (trimmed.isEmpty()) return@withContext null
                val tts = ensureEngine() ?: return@withContext null
                val phonemes = phonemize(tts, trimmed) ?: return@withContext null
                runCatching {
                    val audio = tts.generateWithConfig(
                        text = "",
                        config = GenerationConfig().apply {
                            phonemeCodepoints = phonemes
                            this.speed = speed.coerceIn(0.5f, 2.0f)
                        },
                    )
                    if (audio.samples.isEmpty()) null else audio.samples to audio.sampleRate
                }.getOrNull()
            }
        }
    }

    private fun speakUnderToken(token: Int, text: String) {
        val tts = ensureEngine() ?: return
        val phonemes = phonemize(tts, text) ?: return
        if (token != utteranceToken) return
        val audioTrack = ensureTrack(tts.sampleRate()) ?: return
        audioTrack.play()
        tts.generateWithConfigAndCallback(
            text = "",
            config = GenerationConfig().apply {
                phonemeCodepoints = phonemes
                speed = rateProvider().coerceIn(0.5f, 2.0f)
            },
        ) { samples ->
            // Returning 0 aborts generation on the native side.
            if (token != utteranceToken) return@generateWithConfigAndCallback 0
            writeSamples(audioTrack, samples)
            if (token != utteranceToken) 0 else samples.size
        }
    }

    /** Loads the model on first use; null when the voice cannot be installed. */
    private fun ensureEngine(): OfflineTts? {
        engine?.let { return it }
        synchronized(engineLock) {
            engine?.let { return it }
            val voiceDir = PiperVoices.install(context, voiceId)
            if (voiceDir == null) {
                Log.w(TAG, "piper voice not available: $voiceId")
                return null
            }
            return try {
                if (!phonemizerReady) {
                    piperPhonemizeInitialize(context.applicationContext)
                    phonemizerReady = true
                }
                val tts = OfflineTts(
                    assetManager = null,
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = File(voiceDir, "$voiceId.onnx").absolutePath,
                                // Pre-phonemized input: no lexicon needed; the
                                // tokens file still maps phoneme ids.
                                tokens = File(voiceDir, "tokens.txt").absolutePath,
                            ),
                            numThreads = 2,
                        ),
                    ),
                )
                Log.i(TAG, "piper engine ready: $voiceId (${tts.sampleRate()} Hz, lang=${tts.lang()})")
                engine = tts
                tts
            } catch (t: Throwable) {
                Log.w(TAG, "piper engine init failed for $voiceId", t)
                null
            }
        }
    }

    private fun phonemize(tts: OfflineTts, text: String): Array<IntArray>? {
        val lang = tts.lang().ifEmpty { "ru" }
        val result = runCatching { piperPhonemizeText(text, lang) }.getOrNull()
        if (result == null) {
            Log.w(TAG, "phonemize failed for lang=$lang")
            return null
        }
        return try {
            Array(result.numSentences) { i -> result.getPhonemes(i) }
        } finally {
            result.close()
        }
    }

    private fun ensureTrack(sampleRate: Int): AudioTrack? {
        val existing = track
        if (existing != null && existing.sampleRate == sampleRate) return existing
        existing?.release()
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val created = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            minBuffer * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track = created
        return created
    }

    private fun writeSamples(audioTrack: AudioTrack, samples: FloatArray) {
        val shorts = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        var offset = 0
        while (offset < shorts.size) {
            val written = audioTrack.write(shorts, offset, shorts.size - offset)
            if (written <= 0) break
            offset += written
        }
    }

    private fun nextToken(): Int = synchronized(engineLock) {
        utteranceToken += 1
        utteranceToken
    }

    private companion object {
        private const val TAG = "PiperTextToSpeech"
    }
}

/**
 * Shared Piper engines, one per voice id. Creating an instance is cheap; the
 * model itself loads lazily on the first utterance.
 */
internal object PiperTts {
    private val cache = HashMap<String, PiperTextToSpeech>()

    /** The engine for [voiceId], or null when the voice is not bundled. */
    fun shared(context: Context, voiceId: String): PiperTextToSpeech? {
        val appCtx = context.applicationContext
        if (!PiperVoices.isBundled(appCtx, voiceId)) return null
        return synchronized(cache) {
            cache.getOrPut(voiceId) {
                PiperTextToSpeech(appCtx, voiceId, rateProvider = ::settingsRate)
            }
        }
    }

    private fun settingsRate(): Float = try {
        val settings: com.pusios.pots.data.AppSettings by org.koin.java.KoinJavaComponent.inject(
            com.pusios.pots.data.AppSettings::class.java,
        )
        settings.getTtsRate()
    } catch (_: Exception) {
        1.0f
    }
}
