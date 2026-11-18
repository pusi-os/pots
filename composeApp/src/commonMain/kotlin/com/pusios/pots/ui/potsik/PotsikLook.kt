package com.pusios.pots.ui.potsik

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Everything the user can change about how Потсик looks.
 *
 * Persisted as part of a version-tolerant per-profile JSON object (see
 * `AppSettings`), with legacy individual preference keys migrated on first load.
 *
 * The colours here are the firmware's own: the eight-tone cloth palette, the
 * four lens tints of the shades and the twenty costumes, each with the name
 * the firmware gives it in both languages.
 */
@Immutable
@Serializable
data class PotsikLook(
    /** When false the character is not drawn at all and the chat keeps its plain layout. */
    val isVisible: Boolean = true,

    /** The costume he is wearing. */
    val outfit: PotsikOutfit = PotsikOutfit.NONE,

    /** The colour of the tee. */
    val shirtColor: PotsikClothColor = PotsikClothColor.INDIGO,

    /** The colour of the shorts. */
    val shortsColor: PotsikClothColor = PotsikClothColor.BLUE,

    /** The colour of the sneakers. */
    val shoesColor: PotsikClothColor = PotsikClothColor.WHITE,

    /** The tint of the lens. */
    val shadeTint: PotsikShadeTint = PotsikShadeTint.CYAN,

    /** The removable shades. Off reveals the bare Simpsons-style eyes underneath. */
    val glassesOn: Boolean = true,

    /** The lashes on the bare eyes. Only ever visible with the shades off. */
    val lashesOn: Boolean = true,

    /**
     * The size, as a multiplier on the base size the chat gives him.
     *
     * This is the **only** size there is: the pinch on the character writes
     * it, and everything — the drawing, the desktop window, the bounds —
     * works from `132 dp × sizeScale`, clamped to 72…320 dp.
     */
    val sizeScale: Float = 1f,

    /**
     * The old second size dial — the firmware's SIZE row, 50 % – 150 %.
     *
     * Kept for reading old profiles only: it used to multiply [sizeScale],
     * and [withSizeMigrated] folds it into that one number on load. Nothing
     * in the UI writes it any more, and a look that comes out of the
     * migration always has it at 100.
     */
    val sizePercent: Int = 100,

    /**
     * The tempo, as a percentage of every duration in the character.
     *
     * The firmware's TEMPO row: 50 % – 200 %. It scales the length of every
     * mood, every bubble and every idle beat, so a slow character is slow all
     * the way through rather than slow only in his walk.
     */
    val tempoPercent: Int = 70,

    /** Whether he talks to himself when nothing is happening. */
    val banterEnabled: Boolean = true,

    /** Whether he may be picked up and carried. */
    val draggable: Boolean = true,

    /**
     * Where he is allowed to be, and whether a move is remembered.
     *
     * The session mode is the default: he stays where the user put him until
     * the app is closed, which is what a chat wants. The system mode says
     * the chat puts him where it wants and forgets any move; the persistent
     * mode says he stays where he was put, next time included.
     */
    val placementMode: PotsikPlacementMode = PotsikPlacementMode.SESSION,

    /**
     * Where he was left, as fractions of the screen.
     *
     * Null when he has not been moved — or when the placement mode is
     * [PotsikPlacementMode.SYSTEM], which says the chat puts him where it
     * wants and forgets any move.
     */
    val pinnedX: Float? = null,

    /** Where he was left, vertically. See [pinnedX]. */
    val pinnedY: Float? = null,

    /**
     * «Поверх окон»: whether he stays on the desktop in a system overlay
     * window while the app is in the background, and returns into the app
     * when it is opened again. Android only — the other platforms hide the
     * toggle and ignore the flag.
     *
     * Separate from [isVisible]: whether the character is on the chat screen
     * at all and whether he keeps standing on the desktop are two different
     * questions.
     */
    val overlayEnabled: Boolean = false,
) {
    /**
     * The behaviour settings, as the engine wants them.
     *
     * The look is the one bundle that is persisted, so the behaviour is
     * carried inside it rather than in a second store that could fall out of
     * step with the first.
     */
    fun behaviour(): PotsikSettings = PotsikSettings(
        tempoPercent = tempoPercent,
        banterEnabled = banterEnabled,
        draggable = draggable,
        placementMode = placementMode,
    )

    /** Cycles to the next costume, wrapping around. */
    fun nextOutfit(): PotsikLook = copy(outfit = PotsikOutfit.entries[(outfit.ordinal + 1) % PotsikOutfit.entries.size])

    /** Cycles to the next lens tint, wrapping around. */
    fun nextShadeTint(): PotsikLook = copy(shadeTint = PotsikShadeTint.entries[(shadeTint.ordinal + 1) % PotsikShadeTint.entries.size])

    /** Cycles the shirt through the shared palette. */
    fun nextShirtColor(): PotsikLook = copy(shirtColor = PotsikClothColor.entries[(shirtColor.ordinal + 1) % PotsikClothColor.entries.size])

    /** Cycles the shorts through the shared palette. */
    fun nextShortsColor(): PotsikLook = copy(shortsColor = PotsikClothColor.entries[(shortsColor.ordinal + 1) % PotsikClothColor.entries.size])

    /** Cycles the sneakers through the shared palette. */
    fun nextShoesColor(): PotsikLook = copy(shoesColor = PotsikClothColor.entries[(shoesColor.ordinal + 1) % PotsikClothColor.entries.size])

    companion object {
        /** The look a fresh install starts with: the bare one, in the firmware's default colours. */
        val Default = PotsikLook()
    }
}

/**
 * Folds the old two-dial size into the one number there is now.
 *
 * The look used to be drawn at `132 dp × sizeScale × sizePercent / 100`: two
 * sliders for one thing. A user who had the percent row at 150 and the
 * multiplier at 1.2 was looking at a 237 dp character, and dropping the
 * percent field without this would silently shrink him to 158 dp. The
 * product is what he was drawn at, so the product is what the single size
 * becomes; the legacy field is then parked at 100.
 *
 * Called on every look read from storage — the profile JSON and the legacy
 * individual keys alike.
 */
fun PotsikLook.withSizeMigrated(): PotsikLook = if (sizePercent == 100) {
    this
} else {
    copy(sizeScale = sizeScale * sizePercent / 100f, sizePercent = 100)
}
