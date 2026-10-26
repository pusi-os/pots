package com.pusios.pots.ui.potsik

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * What Потсик is doing — the firmware's `Mood`, in the same order and with the
 * same meaning.
 *
 * The firmware's moods are not "animations" in the sense of a clip of frames:
 * each one is a rule that the renderer follows while the mood lasts, and the
 * rules are what make him move. `BOUNCE` is a hop with the squash at the
 * bottom of it, `WALK` is a leg cycle with the arms swinging against it,
 * `SHOCKED` is a double-take that recoils and settles. Porting the moods
 * rather than the clips is what keeps him the same character.
 */
enum class PotsikMood {
    /** Standing still, breathing, with the weight shifting. */
    IDLE,

    /** One hand up, waving. */
    WAVE,

    /** A double-take: he sees something, flinches, then settles. */
    SHOCKED,

    /** A hop, with the squash at the bottom of it. */
    BOUNCE,

    /** Asleep: the eyes closed, the breathing slow. */
    SLEEPY,

    /** Walking about, sniffing the floor and scratching behind his ear. */
    WALK,

    /** Both arms pumping, the body bouncing. */
    DANCE,

    /** One eye shut, the other open. */
    WINK,

    /** Waking up: both arms overhead, a yawn and a stretch. */
    STRETCH,

    /** Blowing a bubble gum bubble. */
    GUM,

    /** Juggling the packets he has caught. */
    JUGGLE,

    /** One arm across, reaching for a high five. */
    HIGHFIVE,

    /** A fist pumping up and down. */
    PUMP,

    /** An emote's pose — see [PotsikVisitPose]. */
    ACT,

    /** Leaning over to listen to something. */
    LEAN,

    /** A stumble over nothing, and then a look around. */
    TRIP,
}

/**
 * The emote poses — the firmware's `VisitPose`.
 *
 * These are the poses an emote script can put him in: the salute, the bow,
 * the crouch, the howl. The port keeps the whole set, because the emote
 * scripts in the firmware walk through them and a half-empty set would show
 * up as missing poses in the middle of a conversation.
 */
enum class PotsikVisitPose {
    NONE,
    HIGH_FIVE,
    LOW_FIVE,
    FIST,
    STARTLED,
    DANCE,
    PUMP,
    SLEEPY,
    STRETCH,
    LAUGH,
    SALUTE,
    BOW,
    HUG,
    SAD,
    GRR,
    CROUCH,
    PULL,
    WIGGLE,
    CHEER,
    SELFIE,
    HOWL,
    POINT,
    STRAIN,
    COVER,
    LOOK_AROUND,
    HANDS_UP,
}

/**
 * The reactions to being startled — the firmware's `ReactPose`.
 *
 * The firmware maps a detection onto one of these; in the app the same set is
 * what a sudden event maps onto, so a tool that goes wrong gets the same
 * startled flinch a radio tag would have got.
 */
enum class PotsikReactPose {
    STARTLED,
    HANDS_UP,
    COVER_FACE,
    POINT_SHADES,
    DISGUST,
    LOOK_UP,
    LOOK_AROUND,
}

/**
 * The costumes — the firmware's own `OutfitId`, the eighteen the product keeps.
 *
 * The firmware has twenty; the wolf pelt and the shark suit are not among the
 * ones the app offers, so they are not here and neither is their drawing
 * code. What is left is drawn the way the firmware draws it, shape for shape.
 *
 * [unlockThreshold] is the firmware's "how many catches it takes to earn
 * this" — the app has no catch counter, so every costume is available from
 * the start and the number is kept only to say where the firmware would have
 * drawn the line.
 *
 * [bodyColor] and [bellyColor] are the two fur tones the costume repaints him
 * in, the firmware's `outfitFurPalette`. A costume with null tones leaves his
 * own yellow alone and only adds things on top.
 */
enum class PotsikOutfit(
    val nameEn: String,
    val nameRu: String,
    val unlockThreshold: Int,
    val bodyColor: Color? = null,
    val bellyColor: Color? = null,
) {
    /** No costume at all: the tee, the shorts and the sneakers. */
    NONE("None", "Нет", 0),

    /** The tanooki suit, with the ringed tail and the pointed ears. */
    TANOOKI("Tanooki", "Тануки", 0, rgb565(0xDB60), rgb565(0xFC80)),

    /** The unicorn: baby blue over baby pink, with a striped horn. */
    UNICORN("Unicorn", "Единорог", 0, blend(PotsikPalette.WHITE, PotsikPalette.VAPOR_BLUE, 130), blend(PotsikPalette.WHITE, PotsikPalette.VAPOR_PINK, 110)),

    /** The crumpled foil hat, with the little propeller on top. */
    TINFOIL("Cap", "Шапочка", 5),

    /** The ninja: navy, the lower face masked, the red headband trailing. */
    SHADOW("Ninja", "Ниндзя", 15, rgb565(0x212A), rgb565(0x4A55)),

    /** The plumber: red cap, moustache, white gloves, blue overalls. */
    PLUMBER("Mario", "Марио", 25),

    /** The tall brother: the same, in green and taller. */
    TALLBRO("Luigi", "Луиджи", 40),

    /** The astronaut: the bubble helmet, the suit, the boots. */
    SPACE("Astronaut", "Астронавт", 60),

    /** The blue blur: the blue fur, the white gloves, the red sneakers. */
    BLUEBLUR("Hurricane", "Ураган", 100, blend(PotsikPalette.VAPOR_BLUE, PotsikPalette.BLACK, 20), blend(PotsikPalette.VAPOR_BLUE, PotsikPalette.WHITE, 70)),

    /** The captain: the tricorn with the gold braid and the skull. */
    CAPTAIN("Captain", "Капитан", 150),

    /** The chrome wings, flapping behind him. */
    CHROMEWING("Insectoid", "Инсектоид", Int.MAX_VALUE),

    /** The void eye: his head becomes a sphere of stars. */
    VOIDEYE("Void Eye", "Глаз пустоты", Int.MAX_VALUE, rgb565(0x316C), rgb565(0x62D6)),

    /** The snow parka: the orange coat with the hood and the mittens. */
    PARKA("Kenny", "Кенни", Int.MAX_VALUE, rgb565(0xFC43), rgb565(0xFC43)),

    /** YZZERD: the robe, the hat, the beard and the orbiting rune. */
    YZZERD("Wizard", "Волшебник", Int.MAX_VALUE, rgb565(0x6815), rgb565(0x913F)),

    /** Over 9000: the gi, the fringe, the scouter. */
    OVER9000("Hermes", "Гермес", Int.MAX_VALUE, rgb565(0xFB60), null),

    /** The shambler: green-grey, the scar, the bandage. */
    SHAMBLER("Franky", "Фрэнки", Int.MAX_VALUE, rgb565(0x6C8A), rgb565(0x95AA)),

    /** TH3 0N3: the coat, the three red stripes. */
    TH3_0N3("Chosen One", "Избранный", Int.MAX_VALUE, rgb565(0x200A), rgb565(0x200A)),

    /** The prison stripes and the cap, with the iron cuff. */
    STRIPES("Striped", "Полосатый", Int.MAX_VALUE),
    ;

    /**
     * Whether the tee, the shorts and the sneakers stay on under the costume.
     *
     * The firmware's `outfitWearsBaseClothes`: a costume that dresses or
     * recolours the body keeps the base clothes off, so the two never fight.
     */
    val wearsBaseClothes: Boolean
        get() = this == NONE || this == TINFOIL || this == CHROMEWING

    /**
     * How far above his head the costume reaches, in character units.
     *
     * The firmware's `outfitReach`: the wizard's hat and the Over 9000 hair
     * stand well above the skull, and the layout has to know that much so the
     * anchor he stands in is tall enough for them.
     */
    val reachUnits: Int
        get() = when (this) {
            OVER9000 -> 31
            YZZERD -> 26
            UNICORN -> 24
            TINFOIL -> 18
            CAPTAIN -> 16
            PARKA -> 16
            SHAMBLER -> 13
            TALLBRO -> 10
            TANOOKI -> 10
            SPACE -> 9
            else -> 8
        }

    /** The name in the given language. */
    fun name(language: String): String = if (language.startsWith("ru")) nameRu else nameEn
}

/**
 * The four lens tints of the removable shades — the firmware's `SHADE_NAMES`.
 */
enum class PotsikShadeTint(val nameEn: String, val nameRu: String, val color: Color) {
    CYAN("CYAN", "ГОЛУБОЙ", rgb565(0x00E5FF)),
    PINK("PINK", "РОЗОВЫЙ", rgb565(0xFF8FC8)),
    GREEN("GREEN", "ЗЕЛЁНЫЙ", rgb565(0x7CFF3B)),
    PURPLE("PURPLE", "ФИОЛЕТОВЫЙ", rgb565(0xC08BFF)),
}

/**
 * The base clothes' palette — the firmware's `CLOTH_COLORS`, in its order.
 */
enum class PotsikClothColor(val nameEn: String, val nameRu: String, val index: Int) {
    INDIGO("INDIGO", "ИНДИГО", 0),
    BLUE("BLUE", "СИНИЙ", 1),
    MAGENTA("MAGENTA", "МАГЕНТА", 2),
    TEAL("TEAL", "БИрюЗОВЫЙ", 3),
    CRIMSON("CRIMSON", "МАЛИНОВЫЙ", 4),
    PURPLE("PURPLE", "ФИОЛЕТОВЫЙ", 5),
    ORANGE("ORANGE", "ОРАНЖЕВЫЙ", 6),
    WHITE("WHITE", "БЕЛЫЙ", 7),
    ;

    /** The colour, from the firmware's `CLOTH_COLORS`. */
    val color: Color get() = PotsikPalette.CLOTH_COLORS[index]
}

/**
 * How the character is allowed to be moved about — the three persistence
 * modes the settings offer.
 *
 * The firmware keeps him on the screen he is on. The app has three screens
 * and a chat that scrolls, so "where he stands" is a question the settings
 * have to answer: never (the chat puts him where it wants), for this session
 * only, or for good.
 */
enum class PotsikPlacementMode(val nameEn: String, val nameRu: String) {
    /** The chat layout decides where he stands; a move is forgotten. */
    SYSTEM("SYSTEM", "СИСТЕМНЫЙ"),

    /** He stays where he was put until the app is closed. */
    SESSION("SESSION", "СЕССИОННЫЙ"),

    /** He stays where he was put, next time included. */
    PERSISTENT("PERSISTENT", "ПОСТОЯННЫЙ"),
}

/** The settings that decide how the character behaves, in one immutable bundle. */
@Immutable
data class PotsikSettings(
    /** The size, as a percentage of the size the chat layout would give him. */
    val sizePercent: Int = 100,

    /** The tempo, as a percentage of every duration in the firmware. */
    val tempoPercent: Int = 70,

    /** Whether he chats with himself when nothing is going on. */
    val banterEnabled: Boolean = true,

    /** Whether he may be picked up and carried. */
    val draggable: Boolean = true,

    /**
     * Where he is allowed to be.
     *
     * The session mode is the default: he stays where the user put him
     * until the app is closed, which is what a chat wants. The system mode
     * says the chat decides and the character cannot be dragged at all.
     */
    val placementMode: PotsikPlacementMode = PotsikPlacementMode.SESSION,
)
