package com.pusios.pots.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Voice cloning «Мой голос» (spec §16, contract plusvibe.md «Клонирование
 * голоса»). Cloud-only by the owner directive: samples go to the provider's
 * `/api/media/voices`, the local TTS path never sees cloned voices.
 *
 * Levels mirror the catalog note of `voice-clone-tts`. The contract
 * documents a single creation engine (`elevenlabs`); Standard/Premium engine
 * strings are unpublished (research §3.5) and unlock after a live check by
 * the owner — until then those rows are informational, not creatable.
 */
enum class VoiceLevel(
    val titleRu: String,
    val priceNoteRu: String,
    val minDurationSec: Int,
    /** Provider `engine` string for creation; null while undocumented. */
    val engine: String?,
) {
    ElevenLabs(
        "Голос ElevenLabs",
        "Создание бесплатно · хранение 60 ₽/голос в месяц (первый месяц включён) · запись 10–60 с",
        10,
        "elevenlabs",
    ),
    Standard(
        "Обычный",
        "Бесплатно · запись 3–60 с",
        3,
        null,
    ),
    Premium(
        "Premium",
        "190 ₽ разово · максимальная похожесть · запись 10–60 с",
        10,
        null,
    ),
    ;

    val isCreatable: Boolean get() = engine != null
}

const val VOICE_SAMPLE_MAX_BYTES: Long = 10L * 1024 * 1024
const val VOICE_SAMPLE_MAX_SECONDS = 60.0

/**
 * Pre-upload sample gate (spec §8): format, size and duration are validated
 * before any request leaves the device. Returns the Russian user message, or
 * null when the sample may be sent. WAV duration is exact; MP3 is a CBR
 * estimate — the provider does the authoritative check either way.
 */
fun validateVoiceSample(fileName: String, bytes: ByteArray, level: VoiceLevel): String? {
    val lower = fileName.lowercase()
    val isWav = lower.endsWith(".wav")
    val isMp3 = lower.endsWith(".mp3")
    if (!isWav && !isMp3) {
        return "Формат не поддерживается. Поддерживаются wav и mp3."
    }
    if (bytes.size > VOICE_SAMPLE_MAX_BYTES) {
        return sampleErrorRu(level)
    }
    val duration = if (isWav) wavDurationSeconds(bytes) else mp3DurationSecondsEstimate(bytes)
    if (duration == null) {
        return "Не удалось прочитать длительность файла. Выбери другую запись (wav или mp3)."
    }
    if (duration < level.minDurationSec || duration > VOICE_SAMPLE_MAX_SECONDS) {
        return sampleErrorRu(level)
    }
    return null
}

private fun sampleErrorRu(level: VoiceLevel): String = "Сэмпл не подходит. Запись должна быть ${level.minDurationSec}–60 секунд, wav или mp3, не больше 10 МБ."

/** Status of a cloned voice as the journal/voice list shows it. */
enum class VoiceStatus { Ready, Processing, Error, Unknown }

fun parseVoiceStatus(raw: String?): VoiceStatus = when (raw?.lowercase()) {
    "ready", "available", "ok", "active", "completed", "done" -> VoiceStatus.Ready
    "processing", "pending", "creating", "in_progress", "queued" -> VoiceStatus.Processing
    "error", "failed", "failure" -> VoiceStatus.Error
    else -> VoiceStatus.Unknown
}

data class MediaVoice(
    val id: String,
    val name: String,
    val status: VoiceStatus,
    val engine: String? = null,
    val createdAt: String? = null,
)

private val VoicesJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Tolerant parse of `GET /api/media/voices`: the exact envelope is not
 * live-verified (needs a key), so `voices[]`, `data[]` and a bare array are
 * all accepted, with id/voice_id/voiceId field aliases.
 */
fun parseVoicesPayload(body: String): List<MediaVoice> {
    val root: JsonElement = runCatching { VoicesJson.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
    val arr = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["voices"] ?: root["data"] ?: root["items"]) as? JsonArray ?: return emptyList()
        else -> return emptyList()
    }
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] ?: o["voice_id"] ?: o["voiceId"])?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank()) return@mapNotNull null
        MediaVoice(
            id = id,
            name = (o["name"] ?: o["title"])?.jsonPrimitive?.contentOrNull ?: id,
            status = parseVoiceStatus((o["status"] ?: o["state"])?.jsonPrimitive?.contentOrNull),
            engine = o["engine"]?.jsonPrimitive?.contentOrNull,
            createdAt = (o["createdAt"] ?: o["created_at"] ?: o["created"])?.jsonPrimitive?.contentOrNull,
        )
    }
}

/** `POST /api/media/voices` body — strict top-level contract fields only. */
fun buildVoiceCreateBody(name: String, engine: String, audioDataUri: String): JsonObject = JsonObject(
    mapOf(
        "name" to JsonPrimitive(name),
        "consent" to JsonPrimitive(true),
        "engine" to JsonPrimitive(engine),
        "audio_base64" to JsonPrimitive(audioDataUri),
    ),
)

/** (code, message) from a provider error body; either field may be absent. */
fun parseVoiceErrorPayload(body: String): Pair<String?, String?> {
    val root = runCatching { VoicesJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null to null
    val error = root["error"] as? JsonObject
    val code = (error?.get("code") ?: root["code"] ?: root["errorCode"])?.jsonPrimitive?.contentOrNull
    val message = (error?.get("message") ?: root["message"] ?: root["failMsg"])?.jsonPrimitive?.contentOrNull
    return code to message
}

/** Typed voice-API errors; messages follow the §8 cards (see [userMessageRu]). */
sealed class VoiceException(message: String) : Exception(message) {
    class SampleRejected(detail: String?) : VoiceException(detail ?: "voice sample rejected")
    class SamplesLimit(detail: String?) : VoiceException(detail ?: "voice samples limit")
    class DailyLimit(detail: String?) : VoiceException(detail ?: "voice daily limit")
    class VoiceNotFound(detail: String?) : VoiceException(detail ?: "voice not found")
    class EngineRefused(detail: String?) : VoiceException(detail ?: "voice engine refused")
    class KeyProblem(detail: String?) : VoiceException(detail ?: "invalid key")
    class Balance(detail: String?) : VoiceException(detail ?: "insufficient balance")
    class Unknown(detail: String?) : VoiceException(detail ?: "unknown voice error")
}

/** HTTP status + provider error code → typed error (contract error table). */
fun mapVoiceHttpError(status: Int, errorCode: String?, detail: String?): VoiceException = when {
    status == 404 || errorCode == "voice_not_found" -> VoiceException.VoiceNotFound(detail)

    status == 429 || errorCode == "voice_samples_rate_limited" -> VoiceException.DailyLimit(detail)

    errorCode == "samples_limit" || errorCode == "slots_full" -> VoiceException.SamplesLimit(detail)

    errorCode == "voice_samples_unsupported" -> VoiceException.EngineRefused(detail)

    status == 502 || errorCode == "voice_samples_failed" -> VoiceException.EngineRefused(detail)

    status == 422 && errorCode in setOf("voice_sample_too_large", "invalid", "samples_too_large") ->
        VoiceException.SampleRejected(detail)

    // 402 is checked before the 401..403 range: payment-required is a balance
    // problem, not an auth one.
    status == 402 -> VoiceException.Balance(detail)

    status in 401..403 -> VoiceException.KeyProblem(detail)

    status == 422 -> VoiceException.SampleRejected(detail)

    else -> VoiceException.Unknown(detail ?: "HTTP $status")
}

/** Russian §8 wording for the studio snackbar and the clone sheet. */
fun VoiceException.userMessageRu(): String = when (this) {
    is VoiceException.SampleRejected ->
        "Сэмпл не подходит. Запись должна быть 3–60 секунд, wav или mp3, не больше 10 МБ."

    is VoiceException.SamplesLimit ->
        "Записей слишком много. К голосу можно добавить не больше 25 записей суммарно 300 секунд."

    is VoiceException.DailyLimit ->
        "Сегодня уже много добавлений. В сутки можно сделать не больше 20 добавлений голосов. Попробуйте завтра."

    is VoiceException.VoiceNotFound ->
        "Голос не найден. Он удалён или недоступен — обнови список."

    is VoiceException.EngineRefused ->
        "Не удалось загрузить голос. Сервер не смог обработать сэмпл — попробуй ещё раз."

    is VoiceException.KeyProblem -> "API-ключ сервиса не найден или не подходит"

    is VoiceException.Balance -> "Недостаточно баланса для этой операции"

    is VoiceException.Unknown -> message?.let { "Не удалось выполнить операцию: $it" } ?: "Не удалось выполнить операцию"
}
