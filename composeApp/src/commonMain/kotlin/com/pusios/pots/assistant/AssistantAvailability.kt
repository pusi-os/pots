package com.pusios.pots.assistant

/**
 * Whether this device can make Pots the default assistant.
 *
 * `ROLE_ASSISTANT` exists from Android 10 (API 29) and is not requestable
 * in code — AOSP marks it `requestable="false"`, so the only way to assign
 * it is the system default-apps screen. Older Android, OEM builds that hide
 * the role, and every non-Android target are honest about that instead of
 * showing a dead request dialog.
 */
sealed class AssistantAvailability {
    /**
     * The role exists. [held] is true when Pots is already the user's
     * assistant; the settings card still offers the default-apps screen so
     * they can change it.
     */
    data class Available(val isHeld: Boolean) : AssistantAvailability()

    /** No assistant role on this platform (desktop / iOS / web, or an OEM that hid it). */
    data object NotOnThisPlatform : AssistantAvailability()

    /** The role exists only from this Android version (label like `"10"`). */
    data class NeedsNewerAndroid(val minLabel: String) : AssistantAvailability()

    val held: Boolean get() = (this as? Available)?.isHeld == true

    /** Opening default-apps settings only helps when the role can be held. */
    val canOpenDefaultApps: Boolean get() = this is Available
}

/** First Android that ships [android.app.role.RoleManager] / `ROLE_ASSISTANT`. */
const val ASSISTANT_MIN_SDK = 29

/** User-facing version label that matches [ASSISTANT_MIN_SDK]. */
const val ASSISTANT_MIN_ANDROID_LABEL = "10"

/**
 * Pure mapping used by the settings card and by the Android actual.
 *
 * [sdkInt] is null off Android. Below API 29 the role does not exist yet;
 * from 29, a missing role is an OEM hole rather than "upgrade Android".
 */
fun assistantAvailability(
    sdkInt: Int?,
    roleAvailable: Boolean,
    roleHeld: Boolean,
): AssistantAvailability {
    if (sdkInt == null) return AssistantAvailability.NotOnThisPlatform
    if (sdkInt < ASSISTANT_MIN_SDK) {
        return AssistantAvailability.NeedsNewerAndroid(ASSISTANT_MIN_ANDROID_LABEL)
    }
    if (!roleAvailable) return AssistantAvailability.NotOnThisPlatform
    return AssistantAvailability.Available(isHeld = roleHeld)
}

/** Live reading of [assistantAvailability] for this process. */
expect fun currentAssistantAvailability(): AssistantAvailability

/**
 * Opens the system default-apps screen (fallback: voice-input settings).
 * A no-op where the role cannot be held.
 */
expect fun openDefaultAppsSettings()
