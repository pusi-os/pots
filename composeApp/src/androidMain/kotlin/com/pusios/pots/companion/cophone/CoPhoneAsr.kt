package com.pusios.pots.companion.cophone

import android.content.Context
import android.util.Log
import com.pusios.pots.speech.vosk.VoskModels
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * Offline speech recognition for the cube dialog, fed the cube's decoded PCM.
 *
 * Model management (install from the bundled asset or download, load, delete)
 * lives in [VoskModels] and is shared with the hands-free recognizer; this
 * object keeps the cube's short model ids ("ru"/"en") and the batch
 * transcription of one decoded utterance. Everything degrades gracefully —
 * [transcribe] returns null when the model is not ready or the native lib is
 * missing, and the caller falls back to the cloud transcription endpoint.
 */
internal object CoPhoneAsr {
    private const val TAG = "CoPhoneAsr"

    private fun voskId(id: String): String = when (id) {
        "en" -> "vosk-model-small-en-us-0.15"
        else -> VoskModels.DEFAULT_MODEL_ID
    }

    /** Human-readable state for a diagnostic line (mirrors [VoskModels.status]). */
    val status: String
        get() = VoskModels.status

    /** True once the given model is loaded (not merely present on disk). */
    fun ready(id: String): Boolean = VoskModels.ready(voskId(id))

    /** Load (installing the model first if needed). Safe to call repeatedly. */
    fun ensure(ctx: Context, id: String) {
        VoskModels.ensure(ctx, voskId(id))
    }

    /**
     * Transcribe 16 kHz mono PCM with the given model. Returns null when the
     * model is unavailable, the text is empty, or recognition failed.
     */
    fun transcribe(pcm16: ShortArray, id: String): String? {
        val model = VoskModels.loadedModel(voskId(id)) ?: return null
        if (pcm16.isEmpty()) return null
        var rec: Recognizer? = null
        return try {
            rec = Recognizer(model, 16000f)
            val bytes = CoPhoneOpus.pcmToBytes(pcm16)
            rec.acceptWaveForm(bytes, bytes.size)
            val json = JSONObject(rec.finalResult)
            val text = json.optString("text").trim()
            text.ifBlank { null }
        } catch (t: Throwable) {
            Log.w(TAG, "recognize failed", t)
            null
        } finally {
            try {
                rec?.close()
            } catch (_: Throwable) {
            }
        }
    }
}
