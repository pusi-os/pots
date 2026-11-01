package com.pusios.pots.companion.cophone

import android.util.Log
import com.pusios.pots.data.DataRepository
import com.pusios.pots.data.Service
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cloud fallbacks for the cube voice loop, hitting the OpenAI-compatible
 * endpoints of the app's first configured cloud service:
 * `POST /audio/transcriptions` (Whisper-style STT) and
 * `POST /audio/speech` (TTS with `response_format=opus`).
 *
 * Best-effort by design: every call returns null on any failure so the
 * dialog degrades to the on-device engines (or silence + bubbles) instead
 * of crashing the turn.
 */
internal object CoPhoneCloud {
    private const val TAG = "CoPhoneCloud"

    data class Creds(val base: String, val key: String)

    /** The first configured non-on-device instance with a real API key. */
    fun creds(repo: DataRepository): Creds? {
        return try {
            val inst = repo.getConfiguredServiceInstances().firstOrNull {
                val s = Service.fromId(it.serviceId)
                !s.isOnDevice && repo.getInstanceApiKey(it.instanceId).isNotBlank()
            } ?: return null
            val service = Service.fromId(inst.serviceId)
            val base = repo.getInstanceBaseUrl(inst.instanceId, service).trim().trimEnd('/')
            val key = repo.getInstanceApiKey(inst.instanceId).trim()
            if (base.isBlank() || key.isBlank()) null else Creds(base, key)
        } catch (t: Throwable) {
            Log.w(TAG, "creds unavailable", t)
            null
        }
    }

    /** 16 kHz mono PCM -> WAV -> `/audio/transcriptions`. */
    fun transcribeWav(pcm16: ShortArray, c: Creds, language: String?): String? {
        if (pcm16.isEmpty()) return null
        return transcribeUpload(wavBytes(pcm16, 16000), "voice.wav", "audio/wav", c, language)
    }

    /** Raw cube Opus frames -> Ogg/Opus -> `/audio/transcriptions`. */
    fun transcribeOgg(frames: List<ByteArray>, c: Creds, language: String?): String? {
        if (frames.isEmpty()) return null
        return transcribeUpload(CoPhoneOgg.frame(frames), "voice.ogg", "audio/ogg", c, language)
    }

    private fun transcribeUpload(
        audio: ByteArray,
        name: String,
        mime: String,
        c: Creds,
        language: String?,
    ): String? {
        return try {
            val fields = LinkedHashMap<String, String>()
            fields["model"] = "whisper-1"
            if (!language.isNullOrBlank()) fields["language"] = language
            val body = postMultipart(c.base + "/audio/transcriptions", c.key, fields, name, mime, audio)
                ?: return null
            val text = JSONObject(String(body, Charsets.UTF_8)).optString("text").trim()
            Log.i(TAG, "cloud stt: ${text.take(60)}")
            text.ifBlank { null }
        } catch (t: Throwable) {
            Log.w(TAG, "cloud stt failed", t)
            null
        }
    }

    /** `/audio/speech` -> Ogg/Opus bytes (the caller demuxes), or null. */
    fun ttsOpus(text: String, c: Creds): ByteArray? {
        return try {
            val body = JSONObject()
                .put("model", "tts-1")
                .put("input", text)
                .put("voice", "alloy")
                .put("response_format", "opus")
                .toString()
                .toByteArray(Charsets.UTF_8)
            val conn = open(c.base + "/audio/speech", c.key)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "cloud tts http $code")
                conn.disconnect()
                return null
            }
            val ct = conn.contentType ?: ""
            if (!ct.startsWith("audio/")) {
                Log.w(TAG, "cloud tts not audio: $ct")
                conn.disconnect()
                return null
            }
            val out = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            Log.i(TAG, "cloud tts: ${out.size} bytes")
            if (out.isEmpty()) null else out
        } catch (t: Throwable) {
            Log.w(TAG, "cloud tts failed", t)
            null
        }
    }

    /** A minimal RIFF/WAVE (16-bit mono) container around the PCM. */
    fun wavBytes(pcm: ShortArray, rate: Int): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) data.putShort(s)
        val d = data.array()
        val out = ByteArrayOutputStream(44 + d.size)
        fun le32(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }

        fun le16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        le32(36 + d.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1) // PCM
        le16(1) // mono
        le32(rate)
        le32(rate * 2)
        le16(2)
        le16(16)
        out.write("data".toByteArray())
        le32(d.size)
        out.write(d)
        return out.toByteArray()
    }

    private fun open(url: String, key: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.setRequestProperty("x-api-key", key)
        return conn
    }

    private fun postMultipart(
        url: String,
        key: String,
        fields: Map<String, String>,
        fileName: String,
        mime: String,
        file: ByteArray,
    ): ByteArray? {
        val boundary = "----CoPhone" + System.nanoTime().toString(16)
        val conn = open(url, key)
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.doOutput = true
        conn.outputStream.use { os ->
            for ((k, v) in fields) {
                os.write("--$boundary\r\n".toByteArray())
                os.write("Content-Disposition: form-data; name=\"$k\"\r\n\r\n".toByteArray())
                os.write(v.toByteArray(Charsets.UTF_8))
                os.write("\r\n".toByteArray())
            }
            os.write("--$boundary\r\n".toByteArray())
            os.write(
                "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray(),
            )
            os.write("Content-Type: $mime\r\n\r\n".toByteArray())
            os.write(file)
            os.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            Log.w(TAG, "cloud stt http $code")
            conn.disconnect()
            return null
        }
        val out = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        return out
    }
}
