package com.pusios.pots.overlay

/**
 * The foreground-service types to pass to `startForeground()`.
 *
 * SPECIAL_USE exists from API 34 and the three-argument `startForeground` from
 * API 29; the microphone is a while-in-use permission, so it is a separate
 * argument rather than baked in — the caller retries without it when the
 * system refuses.
 *
 * This lives here, next to the desktop build, because the decision is pure and
 * has to be tested on a plain JVM: `compileAndroidMain` only proves the branch
 * compiles, and a wrong mask there already cost a review round (the fallback
 * re-declared MICROPHONE and threw a second, uncaught `SecurityException`).
 *
 * The constants are the platform's own numbers rather than `ServiceInfo`'s,
 * because this source set is compiled for the desktop too, where the Android
 * SDK is not on the classpath. They are spelled once, with the platform name
 * above each, and never copied into a `when`.
 */
internal fun foregroundTypes(sdkInt: Int, withMicrophone: Boolean): Int {
    var types = 0
    if (sdkInt >= 34) types = types or FOREGROUND_TYPE_SPECIAL_USE
    // While-in-use from API 30, which is where the system started enforcing
    // RECORD_AUDIO on the type; below that the type is not declared at all.
    if (withMicrophone && sdkInt >= 30) types = types or FOREGROUND_TYPE_MICROPHONE
    return types
}

/** `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, added in API 34. */
internal const val FOREGROUND_TYPE_SPECIAL_USE = 0x40000000

/** `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE`, added in API 29. */
internal const val FOREGROUND_TYPE_MICROPHONE = 0x80
