package com.pusios.pots.handsfree

import com.pusios.pots.data.SttEngine

/**
 * Free RAM, in megabytes, the neural engine needs before a session may start.
 *
 * The measured resident set of the bundled model is 335 MB on a Raspberry Pi 5
 * (`docs/features/stt-tts-engines.md`, §5.2); 700 MB leaves room for the ONNX
 * Runtime arena and for whatever else the phone is doing at the same time. The
 * guard exists so a short-of-memory device says «мало памяти» instead of being
 * killed by the system mid-phrase.
 */
const val NEURAL_STT_REQUIRED_MEMORY_MB = 700L

/**
 * What the device offers right now — the input of [routeSttEngine].
 *
 * Kept as plain data so the decision can be tested without a device: the
 * platform fills it in through [sttRuntimeFacts].
 */
data class SttRuntimeFacts(
    /** True when the selected neural model is fully downloaded and verified. */
    val neuralModelInstalled: Boolean,
    /** Free RAM in megabytes, or null when the platform cannot tell. */
    val availableMemoryMb: Long?,
)

/** Why the requested engine is not the one that runs. */
enum class SttFallback {
    /** The neural model was never downloaded, or its files are gone. */
    ModelMissing,

    /** The device has less free memory than [NEURAL_STT_REQUIRED_MEMORY_MB]. */
    LowMemory,
}

/**
 * The engine that actually runs a session, and why it is not the requested one.
 *
 * [fallback] is null when the request is honoured. It is never silent in the
 * UI: the settings card renders it, so «выбрано Нейро, а слышит Vosk» is a
 * sentence the user can read, not a mystery.
 */
data class SttRoute(
    val requested: SttEngine,
    val engine: SttEngine,
    val fallback: SttFallback?,
) {
    val isFallback: Boolean get() = fallback != null
}

/**
 * Decides which recognition engine runs.
 *
 * Every consumer comes through here — the hands-free dialogue, the overlay pill
 * and dictation — so the engine the user picked in the settings is the engine
 * all of them use. A requested neural engine that cannot run falls back to
 * Vosk, the model that ships inside the app, and never to the platform
 * recognizer, which may need the network. An unknown free-memory figure is not
 * a reason to refuse: only a known shortfall is.
 *
 * A missing model wins over a memory shortfall: downloading it is the fix, and
 * that is the more useful sentence to show.
 */
fun routeSttEngine(requested: SttEngine, facts: SttRuntimeFacts): SttRoute = when {
    requested != SttEngine.Neural -> SttRoute(requested, requested, null)
    !facts.neuralModelInstalled -> SttRoute(requested, SttEngine.Vosk, SttFallback.ModelMissing)
    facts.availableMemoryMb != null && facts.availableMemoryMb < NEURAL_STT_REQUIRED_MEMORY_MB ->
        SttRoute(requested, SttEngine.Vosk, SttFallback.LowMemory)

    else -> SttRoute(requested, SttEngine.Neural, null)
}

/** Reads the device's side of the decision: the model on disk, the free memory. */
expect fun sttRuntimeFacts(neuralModelId: String): SttRuntimeFacts
