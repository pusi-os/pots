package com.pusios.pots.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.pusios.pots.data.SttEngine
import com.pusios.pots.handsfree.pcm16ToFloats
import com.pusios.pots.handsfree.routeSttEngine
import com.pusios.pots.handsfree.sttRuntimeFacts
import com.pusios.pots.speech.sherpa.SherpaSttModels
import com.pusios.pots.speech.vosk.VoskModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream

/**
 * Records three seconds and decodes them with the engine the dialogue would
 * use — through [routeSttEngine], so the test proves the routed engine, not a
 * hypothetical one.
 *
 * The platform recognizer is not tested here: it cannot decode a finished buffer
 * offline, and the card only offers the button for the offline engines.
 */
actual suspend fun testSttOnMicrophone(
    engine: SttEngine,
    voskModelId: String,
    neuralModelId: String,
): SttMicTestResult = withContext(Dispatchers.IO) {
    val context = sttContextOrNull() ?: return@withContext SttMicTestResult.Unsupported
    if (!hasRecordPermission(context)) {
        return@withContext SttMicTestResult.Failed(SttMicTestError.PermissionDenied)
    }
    val routed = routeSttEngine(engine, sttRuntimeFacts(neuralModelId)).engine
    if (routed == SttEngine.System) return@withContext SttMicTestResult.Unsupported

    val pcm = recordPcm16(TEST_DURATION_MS)
        ?: return@withContext SttMicTestResult.Failed(SttMicTestError.RecordingFailed)

    val text = when (routed) {
        SttEngine.Neural -> {
            val recognizer = SherpaSttModels.recognizer(context, neuralModelId)
                ?: return@withContext SttMicTestResult.Failed(SttMicTestError.ModelMissing)
            SherpaSttModels.transcribe(recognizer, pcm16ToFloats(pcm))
        }

        SttEngine.Vosk -> transcribeVosk(context, voskModelId, pcm)
            ?: return@withContext SttMicTestResult.Failed(SttMicTestError.ModelMissing)

        SttEngine.System -> return@withContext SttMicTestResult.Unsupported
    }.trim()

    if (text.isEmpty()) SttMicTestResult.NothingHeard else SttMicTestResult.Recognized(text)
}

/** One utterance through the bundled Vosk model; null when the model cannot be loaded. */
private fun transcribeVosk(context: Context, modelId: String, pcm: ByteArray): String? {
    val model = VoskModels.ensure(context, modelId) ?: return null
    val recognizer = Recognizer(model, SAMPLE_RATE_HZ.toFloat())
    return try {
        recognizer.acceptWaveForm(pcm, pcm.size)
        val json = JSONObject(recognizer.finalResult)
        json.optString("text").trim()
    } catch (_: Throwable) {
        ""
    } finally {
        runCatching { recognizer.close() }
    }
}

/**
 * Records [durationMs] of 16 kHz mono PCM.
 *
 * Null when the microphone cannot be opened or stops answering — the card then
 * says «не удалось записать», which is a different sentence from «ничего не
 * распознано».
 */
private fun recordPcm16(durationMs: Long): ByteArray? {
    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL, ENCODING)
    val recorder = try {
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            CHANNEL,
            ENCODING,
            maxOf(minBuffer, READ_BUFFER_BYTES),
        )
    } catch (_: Throwable) {
        return null
    }
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release()
        return null
    }
    val out = ByteArrayOutputStream()
    val target = (durationMs * BYTES_PER_MS).toInt()
    try {
        recorder.startRecording()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (out.size() < target) {
            val n = recorder.read(buffer, 0, buffer.size)
            if (n < 0) return null
            if (n > 0) out.write(buffer, 0, n)
        }
    } catch (_: Throwable) {
        return null
    } finally {
        runCatching { recorder.stop() }
        recorder.release()
    }
    return out.toByteArray()
}

private fun hasRecordPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.RECORD_AUDIO,
) == PackageManager.PERMISSION_GRANTED

private fun sttContextOrNull(): Context? = try {
    val context: Context by inject(Context::class.java)
    context
} catch (_: Exception) {
    null
}

private const val SAMPLE_RATE_HZ = 16_000
private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

/** 16-bit mono at 16 kHz: 32 bytes per millisecond of audio. */
private const val BYTES_PER_MS = SAMPLE_RATE_HZ * 2 / 1_000

private const val READ_BUFFER_BYTES = 2048

/** The card's promise: three seconds, one phrase. */
private const val TEST_DURATION_MS = 3_000L
