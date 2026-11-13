package com.pusios.pots.media

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.pusios.pots.PotsAppContext
import com.pusios.pots.speech.piper.PiperTts
import com.pusios.pots.ui.tts.DEFAULT_PIPER_VOICE_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android is the only target with file synthesis (research §4.2): the system
 * engine via `synthesizeToFile` and the bundled Piper voice via the shared
 * `PiperTts` instance (the same engine Settings → Озвучивание uses, so no new
 * dependency). Piper depends on the app-wide sherpa-onnx + piper-phonemize
 * artifacts already present for the offline voice engine.
 */
actual fun localTtsCatalog(): List<MediaModel> = listOf(
    localTtsModel(LOCAL_TTS_PIPER_ID, "Офлайн-нейроголос «Ирина» · только русский"),
    localTtsModel(LOCAL_TTS_SYSTEM_ID, "Системный движок устройства"),
)

actual fun createLocalTtsSynthesizer(modelId: String): LocalTtsSynthesizer? {
    val context = PotsAppContext.application ?: return null
    return when (modelId) {
        LOCAL_TTS_PIPER_ID -> PiperLocalTtsSynthesizer(context)
        LOCAL_TTS_SYSTEM_ID -> SystemLocalTtsSynthesizer(context)
        else -> null
    }
}

/** Piper: PCM floats → 16-bit WAV via the common encoder. */
private class PiperLocalTtsSynthesizer(private val context: Context) : LocalTtsSynthesizer {
    override suspend fun synthesizeToWav(text: String, speed: Float): ByteArray? = withContext(Dispatchers.IO) {
        val engine = PiperTts.shared(context, DEFAULT_PIPER_VOICE_ID) ?: return@withContext null
        val pcm = runCatching { engine.synthesizeBlocking(text, speed) }.getOrNull() ?: return@withContext null
        val (samples, sampleRate) = pcm
        pcm16ToWav(floatSamplesToPcm16(samples), sampleRate)
    }
}

/**
 * System engine: `synthesizeToFile` produces a WAV directly. The latch +
 * flush-poll pattern is the proven one from `CoPhoneTts`; the engine is
 * created per synthesis and shut down after, so the studio never holds a
 * system TTS connection while idle.
 */
private class SystemLocalTtsSynthesizer(private val context: Context) : LocalTtsSynthesizer {
    override suspend fun synthesizeToWav(text: String, speed: Float): ByteArray? = withContext(Dispatchers.IO) {
        var tts: TextToSpeech? = null
        try {
            val initLatch = CountDownLatch(1)
            var initOk = false
            tts = TextToSpeech(context.applicationContext) { status ->
                initOk = status == TextToSpeech.SUCCESS
                initLatch.countDown()
            }
            if (!initLatch.await(10, TimeUnit.SECONDS) || !initOk) return@withContext null
            tts.setSpeechRate(speed.coerceIn(0.5f, 2f))
            val file = File.createTempFile("pots-local-tts", ".wav", context.cacheDir)
            val doneLatch = CountDownLatch(1)
            val done = booleanArrayOf(false)
            val utteranceId = "local-tts-" + System.nanoTime()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) {
                    done[0] = true
                    doneLatch.countDown()
                }

                @Deprecated("deprecated in API level 21")
                override fun onError(id: String?) {
                    doneLatch.countDown()
                }

                override fun onError(id: String?, errorCode: Int) {
                    doneLatch.countDown()
                }
            })
            if (tts.synthesizeToFile(text, null, file, utteranceId) != TextToSpeech.SUCCESS) {
                file.delete()
                return@withContext null
            }
            if (!doneLatch.await(60, TimeUnit.SECONDS) || !done[0]) {
                file.delete()
                return@withContext null
            }
            // onDone can fire before the engine flushes; poll until the WAV parses.
            var bytes: ByteArray? = null
            val deadline = System.currentTimeMillis() + 1200
            while (bytes == null && System.currentTimeMillis() < deadline) {
                val read = runCatching { file.readBytes() }.getOrNull()
                if (read != null && wavDurationSeconds(read) != null) bytes = read else Thread.sleep(50)
            }
            file.delete()
            bytes
        } catch (t: Throwable) {
            Log.w("LocalTts", "system synthesis failed", t)
            null
        } finally {
            runCatching { tts?.shutdown() }
        }
    }
}
