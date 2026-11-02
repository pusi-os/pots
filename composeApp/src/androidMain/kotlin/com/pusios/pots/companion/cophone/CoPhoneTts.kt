package com.pusios.pots.companion.cophone

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.TtsEngine
import com.pusios.pots.speech.piper.PiperTts
import com.pusios.pots.ui.tts.DEFAULT_PIPER_VOICE_ID
import org.koin.java.KoinJavaComponent.inject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device TTS for the cube (ported from the PixelTag CoPhone reference): the
 * bundled Piper voice (when it is the app's chosen engine) or the system
 * engine synthesizes audio, which is resampled to 16 kHz and encoded to raw
 * Opus packets for `audio_down`. No cloud round-trip, deterministic latency.
 * Returns null when the engine/language/WAV is unavailable so the caller can
 * fall back.
 */
internal class CoPhoneTts(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private val initLatch = CountDownLatch(1)

    @Volatile
    private var initOk = false

    @Volatile
    private var langOk = false

    private fun ensure(): Boolean {
        if (initOk) return true
        synchronized(this) {
            if (initOk) return true
            if (tts == null) {
                tts = TextToSpeech(ctx.applicationContext) { status ->
                    initOk = status == TextToSpeech.SUCCESS
                    if (initOk) {
                        langOk = try {
                            val r = tts?.setLanguage(Locale("ru", "RU"))
                            r != TextToSpeech.LANG_MISSING_DATA &&
                                r != TextToSpeech.LANG_NOT_SUPPORTED
                        } catch (_: Throwable) {
                            false
                        }
                    }
                    initLatch.countDown()
                }
            }
            try {
                initLatch.await(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
            return initOk
        }
    }

    fun available(): Boolean = ensure() && langOk

    private var lastVoice = ""
    private var lastRate = -1f
    private var lastPitch = -1f

    private fun applyVoice(voiceName: String, rate: Float, pitch: Float) {
        val t = tts ?: return
        val r = rate.coerceIn(0.5f, 2.0f)
        val p = pitch.coerceIn(0.5f, 2.0f)
        // Idempotent: re-setting language/voice before every sentence reset the
        // engine mid-write and produced truncated WAVs. Apply only on change.
        if (voiceName == lastVoice && r == lastRate && p == lastPitch) return
        try {
            t.setSpeechRate(r)
            t.setPitch(p)
            val v = if (voiceName.isNotEmpty()) {
                t.voices?.firstOrNull { it.name == voiceName }
            } else {
                null
            }
            if (v != null) t.voice = v else t.setLanguage(Locale("ru", "RU"))
            lastVoice = voiceName
            lastRate = r
            lastPitch = p
        } catch (_: Throwable) {
        }
    }

    /** Synthesize [text] to raw Opus packets (16 kHz mono), or null. */
    fun synthesize(
        text: String,
        voiceName: String = "",
        rate: Float = 1.0f,
        pitch: Float = 1.0f,
    ): List<ByteArray>? {
        // The bundled Piper voice wins when it is the app's chosen engine;
        // the system engine below is the fallback that keeps the cube
        // speaking no matter what.
        piperPackets(text, rate)?.let { return it }
        if (!ensure() || !langOk) return null
        val t = tts ?: return null
        applyVoice(voiceName, rate, pitch)
        val file = File.createTempFile("cop-tts", ".wav", ctx.cacheDir)
        val latch = CountDownLatch(1)
        val done = booleanArrayOf(false)
        val id = "cop" + System.nanoTime()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                done[0] = true
                latch.countDown()
            }

            @Deprecated("deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                latch.countDown()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                latch.countDown()
            }
        })
        val rc = t.synthesizeToFile(text, null, file, id)
        if (rc != TextToSpeech.SUCCESS) {
            file.delete()
            return null
        }
        try {
            if (!latch.await(25, TimeUnit.SECONDS) || !done[0]) {
                file.delete()
                return null
            }
        } catch (_: InterruptedException) {
            file.delete()
            return null
        }
        // onDone can fire a hair before the engine has flushed the file to
        // disk, so a short sentence reads back as a truncated/invalid WAV.
        // Poll briefly until the file parses.
        var wav: Pair<ShortArray, Int>? = null
        val deadline = System.currentTimeMillis() + 1200
        while (wav == null && System.currentTimeMillis() < deadline) {
            wav = readWav(file)
            if (wav == null) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        if (wav == null) {
            Log.w(TAG, "bad tts wav (${file.length()} bytes)")
            file.delete()
            return null
        }
        file.delete()
        val (pcm, rateOut) = wav
        val mono = if (rateOut == 16000) pcm else CoPhoneOpus.resample(pcm, rateOut, 16000)
        return CoPhoneOpus.encodeAll(mono)
    }

    /** Piper voice → 16 kHz mono PCM → Opus packets, or null to fall back. */
    private fun piperPackets(text: String, rate: Float): List<ByteArray>? {
        val settings = appSettings() ?: return null
        if (settings.getTtsEngine() != TtsEngine.Piper) return null
        val voiceId = settings.getTtsPiperVoice().ifEmpty { DEFAULT_PIPER_VOICE_ID }
        val engine = PiperTts.shared(ctx, voiceId) ?: return null
        val pcm = engine.synthesizeBlocking(text, rate) ?: return null
        val (samples, sampleRate) = pcm
        val shorts = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val mono = if (sampleRate == 16000) shorts else CoPhoneOpus.resample(shorts, sampleRate, 16000)
        return CoPhoneOpus.encodeAll(mono)
    }

    private fun appSettings(): AppSettings? = try {
        val settings: AppSettings by inject(AppSettings::class.java)
        settings
    } catch (_: Exception) {
        null
    }

    private fun readWav(f: File): Pair<ShortArray, Int>? {
        val b = f.readBytes()
        if (b.size < 44 || leStr(b, 0) != "RIFF" || leStr(b, 8) != "WAVE") return null
        var pos = 12
        var channels = 1
        var rate = 24000
        var bits = 16
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = leStr(b, pos)
            val sz = leInt(b, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    channels = leShort(b, body + 2)
                    rate = leInt(b, body + 4)
                    bits = leShort(b, body + 14)
                }

                "data" -> {
                    dataOff = body
                    dataLen = sz
                }
            }
            if (id == "data") break
            pos = body + sz + (sz and 1)
        }
        if (dataOff < 0 || bits != 16) return null
        val avail = minOf(dataLen, b.size - dataOff).coerceAtLeast(0)
        val n = avail / 2
        val all = ShortArray(n)
        ByteBuffer.wrap(b, dataOff, avail).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().get(all)
        if (channels <= 1) return all to rate
        val mono = ShortArray(n / channels)
        for (i in mono.indices) {
            var s = 0
            for (c in 0 until channels) s += all[i * channels + c]
            mono[i] = (s / channels).toShort()
        }
        return mono to rate
    }

    private fun leStr(b: ByteArray, off: Int): String = String(b, off, 4, Charsets.US_ASCII)

    private fun leInt(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun shutdown() {
        try {
            tts?.stop()
        } catch (_: Throwable) {
        }
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        initOk = false
    }

    companion object {
        private const val TAG = "CoPhoneTts"
    }
}
