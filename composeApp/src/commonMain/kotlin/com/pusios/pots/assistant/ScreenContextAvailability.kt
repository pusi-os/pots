package com.pusios.pots.assistant

/**
 * Where the text of the screen can come from right now.
 *
 * The choice of mechanism is made by *what is available*, never by a stored
 * flag: the assist snapshot only exists while Pots holds the assistant role,
 * and the screenshot flow works everywhere the role does not (and is the one
 * the owner uses from the cloud's «Экран» button).
 */
enum class ScreenContextAvailability {
    /**
     * Pots is the device assistant: the assist gesture brings a snapshot of the
     * screen's text with it, and the cloud can use it as context.
     */
    AssistSnapshot,

    /**
     * The role is not held: the screen can only be *looked at* through the
     * screenshot flow (MediaProjection → a frame → a question about the frame).
     */
    Screenshot,

    /** No screen context at all on this platform (desktop, iOS, web). */
    NotOnThisPlatform,
}

/**
 * Pure mapping, used by the settings card and by the Android actual.
 *
 * The assistant role is the only thing that changes the answer: holding it is
 * what makes the snapshot possible, and losing it is what makes the screenshot
 * the only way back.
 */
fun screenContextFor(assistant: AssistantAvailability): ScreenContextAvailability = when (assistant) {
    is AssistantAvailability.Available ->
        if (assistant.isHeld) ScreenContextAvailability.AssistSnapshot else ScreenContextAvailability.Screenshot

    is AssistantAvailability.NeedsNewerAndroid -> ScreenContextAvailability.Screenshot

    AssistantAvailability.NotOnThisPlatform -> ScreenContextAvailability.NotOnThisPlatform
}

/** Live reading of [screenContextFor] for this process. */
expect fun currentScreenContextAvailability(): ScreenContextAvailability
