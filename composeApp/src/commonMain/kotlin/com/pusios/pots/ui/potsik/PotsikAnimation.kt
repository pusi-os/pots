package com.pusios.pots.ui.potsik

/**
 * What Потсик is doing right now, from the app's point of view.
 *
 * The chat screen owns this: the request running, the tool currently executing
 * and the TTS playing all map onto one of these, and the avatar picks the
 * animation for the phase it is in.
 */
enum class PotsikPhase {
    /** Nothing to do: the request is not running and nothing is being spoken. */
    IDLE,

    /** A request is running and a tool is executing. */
    WORKING,

    /** The reply is being read out loud. */
    SPEAKING,

    /** The last request failed. */
    ERROR,
}

/**
 * The animation catalogue — every distinct thing Потсик can be seen doing.
 *
 * The list is the firmware's own: the moods it can be in and the emote poses
 * it can strike. The agent's tool names them, so the ids are stable and
 * lowercase — what `list_animations` reports and what `play_animation`
 * accepts.
 */
enum class PotsikAction {
    // ---- Idle: the resting loop -----------------------------------------
    /** The plain resting loop: a slow breath and a weight shift. */
    IDLE_BREATHE,

    /** The head pans left and right, looking for something to do. */
    IDLE_LOOK_AROUND,

    /** A raised-hand greeting. */
    IDLE_WAVE,

    /** Arms overhead, a yawn and a stretch — the wake-up. */
    IDLE_STRETCH,

    /** Eyes closed, a drifting "Z" — the nap. */
    IDLE_SLEEPY,

    /** Both arms pumping, the body bouncing — the dance. */
    IDLE_DANCE,

    /** He shuffles from one foot to the other. */
    IDLE_SHUFFLE,

    /** Both hands thrown up in surprise. */
    IDLE_HANDS_UP,

    /** A wink, with the sparkle in the corner of the eye. */
    IDLE_WINK,

    /** Blowing a bubble gum bubble. */
    IDLE_GUM,

    /** Juggling the packets he has caught. */
    IDLE_JUGGLE,

    // ---- Working: a tool is executing ------------------------------------

    /** A hand on the chin, the eyes looking up and away. */
    WORK_THINKING,

    /** Binoculars up, panning the horizon — searching the network. */
    WORK_SEARCH,

    /** Juggling packets — juggling several results at once. */
    WORK_JUGGLE,

    /** Head down over a keyboard — writing a document or a message. */
    WORK_TYPE,

    /** A page of a calendar in his hands, pages turning. */
    WORK_CALENDAR,

    /** A notebook open, the pen moving. */
    WORK_NOTES,

    /** A clock in his hands, the hands spinning. */
    WORK_CLOCK,

    /** A map spread out, the finger tracing a route. */
    WORK_MAP,

    /** A phone in his hand, thumb tapping. */
    WORK_PHONE,

    /** Shoulders shrugged — "hmm". */
    WORK_SHRUG,

    /** Both hands on a rope, heaving. */
    WORK_PULL,

    // ---- Speaking: the reply is being read out ----------------------------

    /** The plain talking loop: the mouth flaps, one hand gestures. */
    SPEAK_TALK,

    /** Talking with the hand out, explaining a point. */
    SPEAK_EXPLAIN,

    /** Talking with the eyes closed — enjoying what he is saying. */
    SPEAK_ENJOY,

    // ---- Error ------------------------------------------------------------

    /** The head shaking, a grimace. */
    ERROR_SHAKE,

    /** Both hands out, apologetic. */
    ERROR_SHRUG,

    // ---- The emote poses ---------------------------------------------------

    /** A hand to the brow — the salute. */
    EMOTE_SALUTE,

    /** Hands folded at the belly — the bow. */
    EMOTE_BOW,

    /** Both arms round somebody — the hug. */
    EMOTE_HUG,

    /** The arms hanging limp, and a tear. */
    EMOTE_SAD,

    /** The fists clenched at his sides, shaking. */
    EMOTE_GRR,

    /** Hands on the knees — the crouch. */
    EMOTE_CROUCH,

    /** Both hands on the rope, heaving. */
    EMOTE_PULL,

    /** Tickling fingers, reaching over. */
    EMOTE_WIGGLE,

    /** Both arms up, pumping. */
    EMOTE_CHEER,

    /** A phone held up and out. */
    EMOTE_SELFIE,

    /** Hands cupped either side of the mouth — the howl. */
    EMOTE_HOWL,

    /** Up and away: "look!" */
    EMOTE_POINT,

    /** An arm locked out, trembling. */
    EMOTE_STRAIN,
    ;

    /**
     * The name the agent knows this animation by.
     *
     * Lowercase, underscored and stable: it is what the tool's
     * `list_animations` action reports and what `play_animation` accepts, so
     * it must not change when the enum is reordered.
     */
    val id: String get() = name.lowercase()

    /** What the animation looks like, in a sentence the agent can act on. */
    val description: String get() = when (this) {
        IDLE_BREATHE -> "stands still, breathing slowly and shifting his weight"
        IDLE_LOOK_AROUND -> "turns his head left and right, looking for something to do"
        IDLE_WAVE -> "raises one hand and waves"
        IDLE_STRETCH -> "yawns and stretches both arms overhead"
        IDLE_SLEEPY -> "dozes off, eyes closed, a Z drifting up"
        IDLE_DANCE -> "dances, both arms pumping and the body bouncing"
        IDLE_SHUFFLE -> "shuffles from one foot to the other"
        IDLE_HANDS_UP -> "throws both hands up in surprise"
        IDLE_WINK -> "winks, one eye shut, a sparkle in the corner"
        IDLE_GUM -> "blows a bubble gum bubble and pops it"
        IDLE_JUGGLE -> "juckles the packets he has caught"
        WORK_THINKING -> "holds a hand to his chin, thinking, eyes looking up"
        WORK_SEARCH -> "holds binoculars to his eyes and scans the horizon"
        WORK_JUGGLE -> "juggles packets, one hand up as each one is thrown"
        WORK_TYPE -> "holds a phone up and takes a selfie"
        WORK_CALENDAR -> "points up and away, as if at a calendar"
        WORK_NOTES -> "reaches over and wiggles his fingers, as if writing"
        WORK_CLOCK -> "holds a clock, the hands spinning"
        WORK_MAP -> "spreads out a map and traces a route with a finger"
        WORK_PHONE -> "holds a phone and taps it with his thumb"
        WORK_SHRUG -> "shrugs his shoulders"
        WORK_PULL -> "pulls on a rope with both hands"
        SPEAK_TALK -> "talks, his mouth flapping and one hand gesturing"
        SPEAK_EXPLAIN -> "talks with his hand out, explaining a point"
        SPEAK_ENJOY -> "talks with his eyes closed, enjoying what he is saying"
        ERROR_SHAKE -> "shakes his head and grimaces"
        ERROR_SHRUG -> "holds both hands out, apologetic"
        EMOTE_SALUTE -> "salutes, a hand to the brow"
        EMOTE_BOW -> "bows, his hands folded at the belly"
        EMOTE_HUG -> "hugs somebody, both arms round them"
        EMOTE_SAD -> "hangs his arms and lets a tear fall"
        EMOTE_GRR -> "clenches his fists at his sides and shakes"
        EMOTE_CROUCH -> "crouches down with his hands on his knees"
        EMOTE_PULL -> "heaves on a rope with both hands"
        EMOTE_WIGGLE -> "reaches over and wiggles his fingers"
        EMOTE_CHEER -> "throws both arms up and pumps them"
        EMOTE_SELFIE -> "holds a phone up and takes a selfie"
        EMOTE_HOWL -> "cups his hands and howls"
        EMOTE_POINT -> "points up and away"
        EMOTE_STRAIN -> "locks one arm out and strains against it"
    }

    /** Which of the four situations the animation belongs to. */
    val group: String get() = when (this) {
        IDLE_BREATHE, IDLE_LOOK_AROUND, IDLE_WAVE, IDLE_STRETCH, IDLE_SLEEPY, IDLE_DANCE,
        IDLE_SHUFFLE, IDLE_HANDS_UP, IDLE_WINK, IDLE_GUM, IDLE_JUGGLE,
        -> "idle"

        SPEAK_TALK, SPEAK_EXPLAIN, SPEAK_ENJOY -> "speaking"

        ERROR_SHAKE, ERROR_SHRUG -> "error"

        else -> "working"
    }

    /** The mood this animation puts him in. */
    val mood: PotsikMood get() = when (this) {
        IDLE_BREATHE -> PotsikMood.IDLE
        IDLE_LOOK_AROUND -> PotsikMood.IDLE
        IDLE_WAVE -> PotsikMood.WAVE
        IDLE_STRETCH -> PotsikMood.STRETCH
        IDLE_SLEEPY -> PotsikMood.SLEEPY
        IDLE_DANCE -> PotsikMood.DANCE
        IDLE_SHUFFLE -> PotsikMood.WALK
        IDLE_HANDS_UP -> PotsikMood.SHOCKED
        IDLE_WINK -> PotsikMood.WINK
        IDLE_GUM -> PotsikMood.GUM
        IDLE_JUGGLE -> PotsikMood.JUGGLE
        WORK_THINKING, WORK_SHRUG -> PotsikMood.IDLE
        WORK_SEARCH -> PotsikMood.IDLE
        WORK_JUGGLE -> PotsikMood.JUGGLE
        WORK_TYPE -> PotsikMood.ACT
        WORK_CALENDAR -> PotsikMood.ACT
        WORK_NOTES -> PotsikMood.ACT
        WORK_CLOCK -> PotsikMood.IDLE
        WORK_MAP -> PotsikMood.ACT
        WORK_PHONE -> PotsikMood.ACT
        WORK_PULL -> PotsikMood.ACT
        SPEAK_TALK, SPEAK_EXPLAIN, SPEAK_ENJOY -> PotsikMood.IDLE
        ERROR_SHAKE -> PotsikMood.SHOCKED
        ERROR_SHRUG -> PotsikMood.ACT
        else -> PotsikMood.ACT
    }

    /** The emote's pose, when the animation is one of the emote poses. */
    val visitPose: PotsikVisitPose get() = when (this) {
        EMOTE_SALUTE -> PotsikVisitPose.SALUTE
        EMOTE_BOW -> PotsikVisitPose.BOW
        EMOTE_HUG -> PotsikVisitPose.HUG
        EMOTE_SAD -> PotsikVisitPose.SAD
        EMOTE_GRR -> PotsikVisitPose.GRR
        EMOTE_CROUCH -> PotsikVisitPose.CROUCH
        EMOTE_PULL -> PotsikVisitPose.PULL
        EMOTE_WIGGLE -> PotsikVisitPose.WIGGLE
        EMOTE_CHEER -> PotsikVisitPose.CHEER
        EMOTE_SELFIE -> PotsikVisitPose.SELFIE
        EMOTE_HOWL -> PotsikVisitPose.HOWL
        EMOTE_POINT -> PotsikVisitPose.POINT
        EMOTE_STRAIN -> PotsikVisitPose.STRAIN
        WORK_TYPE -> PotsikVisitPose.SELFIE
        WORK_CALENDAR -> PotsikVisitPose.POINT
        WORK_NOTES -> PotsikVisitPose.WIGGLE
        WORK_MAP -> PotsikVisitPose.POINT
        WORK_PHONE -> PotsikVisitPose.SELFIE
        WORK_PULL -> PotsikVisitPose.PULL
        WORK_SHRUG -> PotsikVisitPose.SAD
        ERROR_SHRUG -> PotsikVisitPose.SAD
        else -> PotsikVisitPose.NONE
    }

    companion object {
        /**
         * The animation with this id, or null when there is none.
         *
         * The id is matched case-insensitively and with dashes and spaces
         * folded into underscores, so "IDLE_WAVE", "idle-wave" and "idle
         * wave" all find the same animation.
         */
        fun fromId(id: String): PotsikAction? {
            val normalized = id.trim().lowercase().replace('-', '_').replace(' ', '_')
            return entries.firstOrNull { it.id == normalized }
        }
    }
}

/**
 * Which animations play in which situation.
 *
 * Everything the chat screen needs to pick a pose is here, so the renderer and
 * the chat screen never have to agree on a mapping by accident.
 */
object PotsikAnimationCatalog {

    /** The idle loop, played in a random order so the character never looks like a loop. */
    val idleActions: List<PotsikAction> = listOf(
        PotsikAction.IDLE_BREATHE,
        PotsikAction.IDLE_LOOK_AROUND,
        PotsikAction.IDLE_WAVE,
        PotsikAction.IDLE_SHUFFLE,
        PotsikAction.IDLE_HANDS_UP,
        PotsikAction.IDLE_STRETCH,
        PotsikAction.IDLE_SLEEPY,
        PotsikAction.IDLE_DANCE,
    )

    /** The working animations, played in a random order while a request runs. */
    val workingActions: List<PotsikAction> = listOf(
        PotsikAction.WORK_THINKING,
        PotsikAction.WORK_SEARCH,
        PotsikAction.WORK_JUGGLE,
        PotsikAction.WORK_TYPE,
        PotsikAction.WORK_CALENDAR,
        PotsikAction.WORK_NOTES,
        PotsikAction.WORK_CLOCK,
        PotsikAction.WORK_MAP,
        PotsikAction.WORK_PHONE,
        PotsikAction.WORK_SHRUG,
        PotsikAction.WORK_PULL,
    )

    /** The speaking animations, played in a random order while the reply is spoken. */
    val speakingActions: List<PotsikAction> = listOf(
        PotsikAction.SPEAK_TALK,
        PotsikAction.SPEAK_EXPLAIN,
        PotsikAction.SPEAK_ENJOY,
    )

    /** The error animations. */
    val errorActions: List<PotsikAction> = listOf(
        PotsikAction.ERROR_SHAKE,
        PotsikAction.ERROR_SHRUG,
    )

    /**
     * The animation a given tool calls for, by tool name.
     *
     * The names are the ones the tools register themselves under (see
     * `tools/`), matched case-insensitively so an MCP server's tool names land
     * on the same defaults. A tool that is not in the table falls back to
     * [PotsikAction.WORK_JUGGLE], which is the generic "something is
     * happening, here are the results" pose.
     */
    private val toolActions: Map<String, PotsikAction> = mapOf(
        // Looking something up on the network.
        "web_search" to PotsikAction.WORK_SEARCH,
        "fetch_url" to PotsikAction.WORK_SEARCH,
        "open_url" to PotsikAction.WORK_SEARCH,
        // Mail: reading is a search, writing is typing.
        "search_email" to PotsikAction.WORK_SEARCH,
        "check_email" to PotsikAction.WORK_SEARCH,
        "read_email" to PotsikAction.WORK_NOTES,
        "compose_email" to PotsikAction.WORK_TYPE,
        "reply_email" to PotsikAction.WORK_TYPE,
        "setup_email" to PotsikAction.WORK_TYPE,
        // Messages.
        "send_sms" to PotsikAction.WORK_PHONE,
        "reply_sms" to PotsikAction.WORK_PHONE,
        "check_sms" to PotsikAction.WORK_PHONE,
        "read_sms" to PotsikAction.WORK_NOTES,
        "search_sms" to PotsikAction.WORK_SEARCH,
        // Scheduling: the calendar.
        "schedule_task" to PotsikAction.WORK_CALENDAR,
        "cancel_task" to PotsikAction.WORK_CALENDAR,
        "list_tasks" to PotsikAction.WORK_CALENDAR,
        // Memory: the notebook.
        "memory_store" to PotsikAction.WORK_NOTES,
        "memory_forget" to PotsikAction.WORK_NOTES,
        "memory_learn" to PotsikAction.WORK_NOTES,
        "memory_reinforce" to PotsikAction.WORK_NOTES,
        // Notifications: another kind of search.
        "check_notifications" to PotsikAction.WORK_SEARCH,
        "read_notification" to PotsikAction.WORK_NOTES,
        "search_notifications" to PotsikAction.WORK_SEARCH,
        // Odds and ends.
        "get_local_time" to PotsikAction.WORK_CLOCK,
        "get_location_from_ip" to PotsikAction.WORK_MAP,
        "promote_learning" to PotsikAction.WORK_NOTES,
    )

    /** The animation the given tool calls for, or [PotsikAction.WORK_JUGGLE] for an unknown tool. */
    fun actionForTool(toolName: String): PotsikAction = toolActions[toolName.trim().lowercase()] ?: PotsikAction.WORK_JUGGLE

    /** The animations available for a phase. */
    fun actionsFor(phase: PotsikPhase): List<PotsikAction> = when (phase) {
        PotsikPhase.IDLE -> idleActions
        PotsikPhase.WORKING -> workingActions
        PotsikPhase.SPEAKING -> speakingActions
        PotsikPhase.ERROR -> errorActions
    }
}
