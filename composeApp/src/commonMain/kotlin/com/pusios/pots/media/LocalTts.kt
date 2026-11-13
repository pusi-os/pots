package com.pusios.pots.media

/**
 * Local TTS engines in the media studio (spec §14, research §4).
 *
 * Honesty contract: only engines that really synthesize into a FILE on the
 * platform may appear here. Today that is Android only — the system
 * `TextToSpeech` (`synthesizeToFile`) and the bundled Piper voice
 * (sherpa-onnx, the same engine Settings → Озвучивание uses). Desktop
 * TextToSpeechKt plays but cannot write audio, browser `speechSynthesis`
 * never exposes audio data, and iOS/macOS file synthesis is not wired — so
 * their actuals return an empty catalog and the sheet shows no group at all
 * instead of a disabled fake.
 *
 * Piper rationale: sherpa-onnx + piper-phonemize are already app
 * dependencies (the offline «Ирина» voice engine); the studio reuses the
 * shared `PiperTts` instance, so no new dependency is introduced.
 *
 * Voice cloning lives exclusively in the cloud path (`/api/media/voices`);
 * the local group has no cloned voices by design.
 */

const val LOCAL_TTS_ID_PREFIX = "local:"

/** Badge and sheet-group title; local synthesis never bills (owner directive). */
const val LOCAL_TTS_BADGE = "Локально · 0 ₽"

/** Journal mark: local results carry no provider (spec §14). */
const val LOCAL_TTS_PROVIDER_MARK = "локально"

const val LOCAL_TTS_PIPER_ID = "local:piper-irina"
const val LOCAL_TTS_SYSTEM_ID = "local:system"

val MediaModel.isLocalTts: Boolean get() = id.startsWith(LOCAL_TTS_ID_PREFIX)

fun String.isLocalTtsId(): Boolean = startsWith(LOCAL_TTS_ID_PREFIX)

/**
 * Synthetic catalog entry for a local engine. `task=tts` lets it ride the
 * existing TTS-flow filters; the empty-options `speed` axis becomes the
 * standard preset chip (both Android engines accept a rate; pitch is not
 * supported by either — research §4.3).
 */
fun localTtsModel(id: String, note: String): MediaModel = MediaModel(
    id = id,
    kind = MediaKind.Audio,
    task = "tts",
    note = note,
    fromRub = 0.0,
    priceStatus = "exact",
    params = listOf(MediaParamDef(key = "speed", label = "Скорость", type = "select")),
)

/** Human-friendly name for sheet rows and the journal meta line. */
fun localTtsDisplayName(modelId: String): String = when (modelId) {
    LOCAL_TTS_PIPER_ID -> "Piper «Ирина» (офлайн)"
    LOCAL_TTS_SYSTEM_ID -> "Системный голос устройства"
    else -> "Локальный синтез"
}

/** The platform's local engines; empty where file synthesis does not exist. */
expect fun localTtsCatalog(): List<MediaModel>

/** True when [modelId] is a local engine this platform actually has. */
fun isKnownLocalTtsId(modelId: String, localCatalog: List<MediaModel> = localTtsCatalog()): Boolean = modelId.isLocalTtsId() && localCatalog.any { it.id == modelId }

/** Writes [text] into WAV bytes; null when the engine or synthesis fails. */
interface LocalTtsSynthesizer {
    suspend fun synthesizeToWav(text: String, speed: Float): ByteArray?
}

/** The synthesizer behind a local catalog id; null on unsupported platforms. */
expect fun createLocalTtsSynthesizer(modelId: String): LocalTtsSynthesizer?
