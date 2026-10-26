package com.pusios.pots.tools

import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.network.tools.ToolSchema
import com.pusios.pots.ui.potsik.PotsikAction
import com.pusios.pots.ui.potsik.PotsikStage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.tool_potsik_description
import pots.composeapp.generated.resources.tool_potsik_name

/**
 * The agent's handle on Потсик, the character on the chat screen.
 *
 * One tool with an `action` switch rather than three tools, because the three
 * things it does are the same thing done to the same character: list what it
 * can do, make it do one of them, put it somewhere.
 *
 * The tool is off by default. The user turns it on in Settings → Tools, and
 * can hand it to the on-device model as well in Settings → Local Model.
 */
object PotsikTool {

    const val ID = "potsik"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Потсик",
        description = "Control Потсик, the character on the chat screen",
        nameRes = Res.string.tool_potsik_name,
        descriptionRes = Res.string.tool_potsik_description,
        // Off until the user asks for it: the character is the app's own, and
        // letting the model move it around uninvited is not its business.
        isEnabled = false,
    )

    /** What the tool can be asked to do. */
    enum class Action(val id: String) {
        /** Report every animation the character has. */
        LIST_ANIMATIONS("list_animations"),

        /** Make the character play one of them. */
        PLAY_ANIMATION("play_animation"),

        /** Put the character somewhere on the screen. */
        MOVE("move"),

        /** Change how big the character is. */
        SET_SCALE("set_scale"),
    }

    fun create(stage: PotsikStage): Tool = object : Tool {

        override val schema = ToolSchema(
            name = ID,
            description = "Control Потсик, the character standing on the chat screen. " +
                "Use action=list_animations to see every animation he has, " +
                "action=play_animation to make him play one (pass its id in 'animation', e.g. 'idle_wave' or 'work_search'), " +
                "action=move to put him somewhere on the screen (pass 'x' and 'y' as fractions of the screen from 0 to 1, 0 being the left or top edge and 1 the right or bottom one), " +
                "and action=set_scale to change his size (pass 'scale', where 1 is his normal size and 2 is twice as big). " +
                "He cannot be moved off the screen: a position that would take him out of view is pulled back into it.",
            parameters = mapOf(
                "action" to ParameterSchema(
                    type = "string",
                    description = "What to do: list_animations, play_animation, move or set_scale",
                    required = true,
                    rawSchema = buildJsonObject {
                        put("type", "string")
                        put("description", "What to do: list_animations, play_animation, move or set_scale")
                        putJsonArray("enum") {
                            Action.entries.forEach { add(JsonPrimitive(it.id)) }
                        }
                    },
                ),
                "animation" to ParameterSchema(
                    type = "string",
                    description = "The animation to play, for action=play_animation. The id from action=list_animations, e.g. 'idle_wave'",
                    required = false,
                ),
                "x" to ParameterSchema(
                    type = "number",
                    description = "Horizontal position as a fraction of the screen, for action=move. 0 is the left edge, 1 the right one",
                    required = false,
                ),
                "y" to ParameterSchema(
                    type = "number",
                    description = "Vertical position as a fraction of the screen, for action=move. 0 is the top edge, 1 the bottom one",
                    required = false,
                ),
                "scale" to ParameterSchema(
                    type = "number",
                    description = "How big the character is, for action=set_scale. 1 is his normal size, 2 is twice as big",
                    required = false,
                ),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val actionId = args["action"]?.toString()?.trim()?.lowercase()
                ?: return mapOf("success" to false, "error" to "Missing action")
            val action = Action.entries.firstOrNull { it.id == actionId }
                ?: return mapOf(
                    "success" to false,
                    "error" to "Unknown action '$actionId'. Use one of: " +
                        Action.entries.joinToString(", ") { it.id },
                )

            return when (action) {
                Action.LIST_ANIMATIONS -> listAnimations()
                Action.PLAY_ANIMATION -> playAnimation(args)
                Action.MOVE -> move(args)
                Action.SET_SCALE -> setScale(args)
            }
        }

        private fun listAnimations(): Any = mapOf(
            "success" to true,
            "count" to PotsikAction.entries.size,
            "animations" to PotsikAction.entries.map {
                mapOf(
                    "id" to it.id,
                    "group" to it.group,
                    "description" to it.description,
                )
            },
        )

        private fun playAnimation(args: Map<String, Any>): Any {
            val requested = args["animation"]?.toString()
                ?: return mapOf("success" to false, "error" to "Missing animation. Call the tool with action=list_animations to see them all.")
            val animation = PotsikAction.fromId(requested)
                ?: return mapOf(
                    "success" to false,
                    "error" to "Unknown animation '$requested'. Call the tool with action=list_animations to see them all.",
                )
            stage.playAnimation(animation)
            return mapOf(
                "success" to true,
                "animation" to animation.id,
                "description" to animation.description,
                "duration_ms" to PotsikStage.DEFAULT_ANIMATION_DURATION_MS,
            )
        }

        private fun move(args: Map<String, Any>): Any {
            // The system mode says the chat decides where he stands, so a move
            // is not a move at all. Saying so is better than reporting a
            // success that did not happen.
            if (!stage.canMove) {
                return mapOf(
                    "success" to false,
                    "error" to "The character cannot be moved: the placement mode is System, " +
                        "so the chat decides where he stands. The user can change the mode " +
                        "in Settings, in the Потсик section.",
                )
            }
            val current = stage.placement.value
            val x = numberArg(args, "x") ?: current.x
            val y = numberArg(args, "y") ?: current.y
            stage.moveTo(x, y)
            val placement = stage.placement.value
            val clamped = kotlin.math.abs(placement.x - x) > 0.001f ||
                kotlin.math.abs(placement.y - y) > 0.001f
            return mapOf(
                "success" to true,
                "x" to placement.x,
                "y" to placement.y,
                "scale" to stage.scaleRelativeToLayout,
                "note" to if (clamped) {
                    "The position was clamped so the character stays fully on the screen."
                } else {
                    "The character was moved."
                },
            )
        }

        private fun setScale(args: Map<String, Any>): Any {
            val scale = numberArg(args, "scale")
                ?: return mapOf("success" to false, "error" to "Missing scale")
            stage.setScale(scale)
            val placement = stage.placement.value
            return mapOf(
                "success" to true,
                "scale" to stage.scaleRelativeToLayout,
                "x" to placement.x,
                "y" to placement.y,
            )
        }

        /**
         * A number argument, however the model chose to spell it.
         *
         * Models send JSON numbers as numbers and, when they send them as
         * strings, as strings — and a model that sends "0.5" is not wrong
         * about the value. A number that is not a number at all is ignored,
         * which leaves the caller to report the missing argument.
         */
        private fun numberArg(args: Map<String, Any>, name: String): Float? = when (val value = args[name]) {
            null -> null
            is Number -> value.toFloat()
            is JsonPrimitive -> value.content.toFloatOrNull()
            else -> value.toString().trim().toFloatOrNull()
        }
    }
}
