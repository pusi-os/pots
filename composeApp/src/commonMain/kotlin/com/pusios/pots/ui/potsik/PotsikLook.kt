package com.pusios.pots.ui.potsik

import androidx.compose.runtime.Immutable

/**
 * Everything the user can change about how Потсик looks.
 *
 * Persisted as individual keys (see `AppSettings`), never as one blob, so a
 * new field can be added later without invalidating the ones already stored.
 *
 * The colours here are the firmware's own: the eight-tone cloth palette, the
 * four lens tints of the shades and the twenty costumes, each with the name
 * the firmware gives it in both languages.
 */
@Immutable
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

    /** A multiplier on the drawn size, 0.6 – 1.6. */
    val sizeScale: Float = 1f,

    /**
     * The size, as a percentage of the size the chat would give him.
     *
     * The firmware's SIZE row: 50 % – 150 %, and it is applied on top of the
     * size the layout asks for, so a big Потсик is big everywhere.
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
) {
    /**
     * The behaviour settings, as the engine wants them.
     *
     * The look is the one bundle that is persisted, so the behaviour is
     * carried inside it rather than in a second store that could fall out of
     * step with the first.
     */
    fun behaviour(): PotsikSettings = PotsikSettings(
        sizePercent = sizePercent,
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
