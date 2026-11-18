package com.pusios.pots.ui.potsik

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pusios.pots.ui.potsAdaptiveCardBorder
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.ic_close
import pots.composeapp.generated.resources.ic_handsfree
import pots.composeapp.generated.resources.ic_screen_ask
import pots.composeapp.generated.resources.ic_send
import pots.composeapp.generated.resources.ic_stop
import pots.composeapp.generated.resources.potsik_cloud_cancel
import pots.composeapp.generated.resources.potsik_cloud_chip_translate
import pots.composeapp.generated.resources.potsik_cloud_chip_what
import pots.composeapp.generated.resources.potsik_cloud_chip_wrong
import pots.composeapp.generated.resources.potsik_cloud_close
import pots.composeapp.generated.resources.potsik_cloud_input_placeholder
import pots.composeapp.generated.resources.potsik_cloud_listening
import pots.composeapp.generated.resources.potsik_cloud_mic_busy
import pots.composeapp.generated.resources.potsik_cloud_no_chat
import pots.composeapp.generated.resources.potsik_cloud_no_match
import pots.composeapp.generated.resources.potsik_cloud_no_network
import pots.composeapp.generated.resources.potsik_cloud_no_recognizer
import pots.composeapp.generated.resources.potsik_cloud_open_in_chat
import pots.composeapp.generated.resources.potsik_cloud_permission_denied
import pots.composeapp.generated.resources.potsik_cloud_retry
import pots.composeapp.generated.resources.potsik_cloud_screen
import pots.composeapp.generated.resources.potsik_cloud_screen_denied
import pots.composeapp.generated.resources.potsik_cloud_screen_failed
import pots.composeapp.generated.resources.potsik_cloud_screen_off
import pots.composeapp.generated.resources.potsik_cloud_screen_placeholder
import pots.composeapp.generated.resources.potsik_cloud_screen_remove
import pots.composeapp.generated.resources.potsik_cloud_screen_revoked
import pots.composeapp.generated.resources.potsik_cloud_screen_waiting
import pots.composeapp.generated.resources.potsik_cloud_screenshot_caption
import pots.composeapp.generated.resources.potsik_cloud_send
import pots.composeapp.generated.resources.potsik_cloud_speak_aloud
import pots.composeapp.generated.resources.potsik_cloud_stop
import pots.composeapp.generated.resources.potsik_cloud_text
import pots.composeapp.generated.resources.potsik_cloud_thinking
import pots.composeapp.generated.resources.potsik_cloud_timeout
import pots.composeapp.generated.resources.potsik_cloud_voice
import pots.composeapp.generated.resources.potsik_cloud_voice_unavailable
import kotlin.math.PI
import kotlin.math.sin

/**
 * The capsule itself: the menu on a double tap, and the pill it turns into
 * while the voice loop runs.
 *
 * Drawn inside a window that is exactly its size ([potsikCloudWindowSize]) —
 * the platform delivers every tap inside a window's rect to its owner,
 * transparent pixels included, so a roomier window would eat taps meant for
 * the app underneath.
 *
 * The menu is the spec's 176×64 capsule: three 48 dp actions — voice, text
 * and the screen. The screen button is last on purpose: it is the only one
 * that raises a system dialog, and a stray tap near the edge of the capsule
 * should not start a screen capture.
 */
@Composable
fun PotsikCloudCapsule(
    state: PotsikCloudState,
    voiceAvailable: Boolean,
    /** The frame the screen mode is holding, if any: the pill draws its thumbnail. */
    shot: PotsikShotUi?,
    onVoice: () -> Unit,
    onText: () -> Unit,
    onScreen: () -> Unit,
    onPillTap: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemoveShot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(POTSIK_CLOUD_RADIUS_DP.dp)
    val container = when (state.phase) {
        PotsikCloudPhase.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f)
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    }
    Column(modifier = modifier.fillMaxSize()) {
        // The dead voice button explains itself here: a grey circle alone tells
        // a sighted user nothing, and the mode cannot be started to reach an
        // error pill. The line goes above the capsule, not under the tail —
        // the window's bottom edge is anchored to his head, so anything below
        // the tail would push the tail off it.
        if (!voiceAvailable && state.phase == PotsikCloudPhase.Menu) {
            Text(
                text = stringResource(Res.string.potsik_cloud_no_recognizer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(POTSIK_CLOUD_NOTICE_HEIGHT_DP.dp)
                    .padding(bottom = 4.dp),
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = shape,
            color = container,
            border = potsAdaptiveCardBorder(),
            tonalElevation = 3.dp,
        ) {
            when (state.phase) {
                PotsikCloudPhase.Menu -> PotsikCloudMenu(
                    voiceAvailable = voiceAvailable,
                    onVoice = onVoice,
                    onText = onText,
                    onScreen = onScreen,
                )

                else -> PotsikCloudPill(
                    state = state,
                    shot = shot,
                    onPillTap = onPillTap,
                    onCancel = onCancel,
                    onRetry = onRetry,
                    onRemoveShot = onRemoveShot,
                )
            }
        }
        PotsikCloudTail(
            color = container,
            modifier = Modifier
                .fillMaxWidth()
                .height(POTSIK_CLOUD_TAIL_HEIGHT_DP.dp),
        )
    }
}

@Composable
private fun PotsikCloudMenu(
    voiceAvailable: Boolean,
    onVoice: () -> Unit,
    onText: () -> Unit,
    onScreen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(POTSIK_CLOUD_GAP_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(POTSIK_CLOUD_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CloudActionButton(
            // A dead voice button says why to TalkBack instead of failing at
            // the tap: the explanation that used to sit in the empty third
            // slot lives in its contentDescription now that «Экран» owns it.
            enabled = voiceAvailable,
            icon = { tint ->
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_handsfree),
                    contentDescription = stringResource(
                        if (voiceAvailable) Res.string.potsik_cloud_voice else Res.string.potsik_cloud_voice_unavailable,
                    ),
                    tint = tint,
                )
            },
            onClick = onVoice,
        )
        CloudActionButton(
            enabled = true,
            icon = { tint ->
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = stringResource(Res.string.potsik_cloud_text),
                    tint = tint,
                )
            },
            onClick = onText,
        )
        CloudActionButton(
            enabled = true,
            outlined = true,
            icon = { tint ->
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_screen_ask),
                    contentDescription = stringResource(Res.string.potsik_cloud_screen),
                    tint = tint,
                )
            },
            onClick = onScreen,
        )
    }
}

/**
 * One 48 dp action: the accessibility minimum, and what the capsule is sized from.
 *
 * [outlined] is the secondary look the spec gives the screen button: it is the
 * only action that raises a system dialog, so it reads as a step out of the
 * capsule rather than a peer of the two filled tonal ones.
 */
@Composable
private fun CloudActionButton(
    enabled: Boolean,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    outlined: Boolean = false,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        outlined -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Box(
        modifier = Modifier
            .size(POTSIK_CLOUD_BUTTON_DP.dp)
            .clip(CircleShape)
            .then(
                when {
                    // A dead button keeps no shape at all: the grey glyph is the message.
                    !enabled -> Modifier

                    outlined -> Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)

                    else -> Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                },
            ),
    ) {
        IconButton(
            onClick = { if (enabled) onClick() },
            enabled = enabled,
            modifier = Modifier.size(POTSIK_CLOUD_BUTTON_DP.dp),
        ) {
            icon(tint)
        }
    }
}

/**
 * The state pill: listening, thinking, speaking, the reason it stopped, the
 * wait for the consent dialog, or the frame the screen mode took.
 *
 * A tap on it is the barge-in while he is talking — the speech stops first and
 * the microphone opens after, which is the order the whole app uses. On the
 * shot pill the same tap opens the question about the frame.
 */
@Composable
private fun PotsikCloudPill(
    state: PotsikCloudState,
    shot: PotsikShotUi?,
    onPillTap: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemoveShot: () -> Unit,
) {
    val content = if (state.phase == PotsikCloudPhase.Error) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    if (state.phase == PotsikCloudPhase.Shot) {
        PotsikCloudShotRow(shot = shot, content = content, onTap = onPillTap, onRemove = onRemoveShot)
        return
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.phase) {
            PotsikCloudPhase.Listening -> {
                PotsikCloudPulse(content)
                PotsikCloudWaveform(content)
            }

            PotsikCloudPhase.Thinking -> PotsikCloudDots(content)

            PotsikCloudPhase.Speaking -> PotsikCloudWaveform(content)

            else -> Unit
        }
        Text(
            text = potsikCloudPillText(state),
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            state.phase == PotsikCloudPhase.Speaking -> PillButton(
                icon = {
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_stop),
                        contentDescription = stringResource(Res.string.potsik_cloud_stop),
                        tint = content,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = onPillTap,
            )

            state.phase == PotsikCloudPhase.Thinking && state.cancelOffered -> TextButton(onClick = onCancel) {
                Text(stringResource(Res.string.potsik_cloud_cancel), color = content, fontSize = 13.sp)
            }

            state.phase == PotsikCloudPhase.Error -> TextButton(onClick = onRetry) {
                Text(stringResource(Res.string.potsik_cloud_retry), color = content, fontSize = 13.sp)
            }

            // Listening: the pill is the barge-in target as well — tapping it
            // ends this attempt and starts a new one, same as the app.
            state.phase == PotsikCloudPhase.Listening -> PillButton(
                icon = {
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_close),
                        contentDescription = stringResource(Res.string.potsik_cloud_cancel),
                        tint = content,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = onCancel,
            )

            // Waiting for the consent dialog: the system dialog is modal and
            // owns the screen, so the pill carries the words and no buttons.
            else -> Unit
        }
    }
}

/**
 * The shot pill: the thumbnail, its size, and [×].
 *
 * A tap anywhere but [×] opens the question about the frame — the field is the
 * next step of the flow, and the pill is the only thing to tap for it.
 */
@Composable
private fun PotsikCloudShotRow(
    shot: PotsikShotUi?,
    content: Color,
    onTap: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onTap)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PotsikCloudThumbnail(shot = shot, side = POTSIK_CLOUD_THUMBNAIL_DP.dp)
        Text(
            text = shot?.let { stringResource(Res.string.potsik_cloud_screenshot_caption, it.label) }.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PillButton(
            icon = {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_close),
                    contentDescription = stringResource(Res.string.potsik_cloud_screen_remove),
                    tint = content,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = onRemove,
        )
    }
}

/** The frame itself, clipped to the Pots corner radius; a grey square if it could not be decoded. */
@Composable
private fun PotsikCloudThumbnail(shot: PotsikShotUi?, side: Dp) {
    val shape = RoundedCornerShape(POTSIK_CLOUD_THUMBNAIL_RADIUS_DP.dp)
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    if (shot?.preview != null) {
        Image(
            bitmap = shot.preview,
            contentDescription = stringResource(Res.string.potsik_cloud_screenshot_caption, shot.label),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(side)
                .clip(shape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(side)
                .clip(shape)
                .background(placeholder),
        )
    }
}

@Composable
private fun PillButton(icon: @Composable () -> Unit, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { icon() }
}

/**
 * The three hints above the field of Flow 3: «Что здесь?» · «Переведи» ·
 * «Что не так?».
 *
 * A chip fills the draft instead of sending at once: the question is the
 * user's, and a chip that sends on its own would take the decision to ask away
 * from the field that exists for it. The row scrolls sideways so three chips
 * never push the field off a narrow screen.
 */
@Composable
private fun PotsikCloudChips(onDraftChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (hint in POTSIK_CLOUD_SCREEN_HINTS) {
            val text = stringResource(hint)
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onDraftChange(text) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The chips' order is the spec's: what, translate, what is wrong. */
private val POTSIK_CLOUD_SCREEN_HINTS = listOf(
    Res.string.potsik_cloud_chip_what,
    Res.string.potsik_cloud_chip_translate,
    Res.string.potsik_cloud_chip_wrong,
)

/** What the pill says, in the language the app is set to. */
@Composable
private fun potsikCloudPillText(state: PotsikCloudState): String = when (state.phase) {
    PotsikCloudPhase.Listening -> state.transcript.ifBlank { stringResource(Res.string.potsik_cloud_listening) }
    PotsikCloudPhase.Thinking -> stringResource(Res.string.potsik_cloud_thinking)
    PotsikCloudPhase.Speaking -> state.reply
    PotsikCloudPhase.Error -> stringResource(potsikCloudTroubleText(state.trouble))
    PotsikCloudPhase.AwaitingConsent -> stringResource(Res.string.potsik_cloud_screen_waiting)
    else -> ""
}

/** The one line of explanation behind an error pill. */
fun potsikCloudTroubleText(trouble: PotsikCloudTrouble?): StringResource = when (trouble) {
    PotsikCloudTrouble.NoRecognizer -> Res.string.potsik_cloud_no_recognizer
    PotsikCloudTrouble.NoMatch -> Res.string.potsik_cloud_no_match
    PotsikCloudTrouble.ListenTimeout -> Res.string.potsik_cloud_timeout
    PotsikCloudTrouble.NoNetwork, null -> Res.string.potsik_cloud_no_network
    PotsikCloudTrouble.PermissionDenied -> Res.string.potsik_cloud_permission_denied
    PotsikCloudTrouble.ScreenOff -> Res.string.potsik_cloud_screen_off
    PotsikCloudTrouble.MicBusy -> Res.string.potsik_cloud_mic_busy
    PotsikCloudTrouble.NoChat -> Res.string.potsik_cloud_no_chat
    PotsikCloudTrouble.ScreenConsentDenied -> Res.string.potsik_cloud_screen_denied
    PotsikCloudTrouble.ScreenConsentRevoked -> Res.string.potsik_cloud_screen_revoked
    PotsikCloudTrouble.ScreenCaptureFailed -> Res.string.potsik_cloud_screen_failed
}

/** The tail under the capsule, pointing at his head. */
@Composable
private fun PotsikCloudTail(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val tailWidth = POTSIK_CLOUD_TAIL_WIDTH_DP.dp.toPx()
        val left = (size.width - tailWidth) / 2f
        val path = Path().apply {
            moveTo(left, 0f)
            lineTo(left + tailWidth, 0f)
            lineTo(left + tailWidth / 2f, size.height)
            close()
        }
        drawPath(path, color)
    }
}

/** The listening ring: the same 1.2 s pulse the hands-free button uses in the app. */
@Composable
private fun PotsikCloudPulse(color: Color) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "cloud-pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 1_200,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        ),
        label = "cloud-pulse-progress",
    )
    Canvas(modifier = Modifier.size(18.dp)) {
        val radius = size.minDimension / 2f
        drawCircle(color = color.copy(alpha = 0.35f * (1f - progress)), radius = radius * (0.4f + 0.6f * progress))
        drawCircle(color = color, radius = radius * 0.35f)
    }
}

/** Three bars that rise and fall while the microphone is open. */
@Composable
private fun PotsikCloudWaveform(color: Color) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "cloud-wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 900, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "cloud-wave-phase",
    )
    Canvas(modifier = Modifier.size(width = 16.dp, height = 18.dp)) {
        val bars = 3
        val barWidth = size.width / (bars * 2 - 1)
        for (index in 0 until bars) {
            val wave = sin((phase + index * 0.33f) * 2f * PI.toFloat())
            val height = size.height * (0.35f + 0.65f * ((wave + 1f) / 2f))
            drawBar(
                x = index * barWidth * 2f,
                width = barWidth,
                height = height,
                color = color.copy(alpha = 0.8f),
            )
        }
    }
}

private fun DrawScope.drawBar(x: Float, width: Float, height: Float, color: Color) {
    drawRoundRect(
        color = color,
        topLeft = Offset(x, (size.height - height) / 2f),
        size = Size(width, height),
        cornerRadius = CornerRadius(width / 2f),
    )
}

/** The three dots of «Думаю…», staggered the way the spec asks. */
@Composable
private fun PotsikCloudDots(color: Color) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "cloud-dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 1_400, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "cloud-dots-phase",
    )
    Canvas(modifier = Modifier.size(width = 20.dp, height = 18.dp)) {
        val dot = size.width / 5f
        for (index in 0 until 3) {
            val local = (phase - index * 0.16f + 1f) % 1f
            val alpha = 0.3f + 0.7f * ((1f - local) * 0.5f + 0.5f)
            drawCircle(
                color = color.copy(alpha = alpha.coerceIn(0.2f, 1f)),
                radius = dot / 2f,
                center = Offset(dot / 2f + index * dot * 2f, size.height / 2f),
            )
        }
    }
}

/**
 * The text field of Flow 2, in the translucent activity the cloud opens for it.
 *
 * A field inside the overlay window was the obvious build and the wrong one:
 * `TYPE_APPLICATION_OVERLAY` is always below the keyboard, so the keyboard
 * would have been drawn over the capsule. As an activity the field sits at the
 * bottom of the window, `adjustResize` lifts it above the keyboard, and back
 * closes it the way back closes everything.
 */
@Composable
fun PotsikCloudInputPanel(
    state: PotsikCloudState,
    draft: String,
    /** The frame the question is about, if the field was opened from the screen mode. */
    shot: PotsikShotUi?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onRemoveShot: () -> Unit,
    onSpeak: (() -> Unit)?,
    onOpenInChat: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The field is the reason this screen exists, so it takes the keyboard by
    // itself instead of asking for one more tap.
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            // A tap outside the panel closes the screen — the cloud's own
            // «тап мимо», kept the same way here.
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                // The panel swallows its own taps, so only the empty area
                // around it closes the screen.
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(POTSIK_CLOUD_RADIUS_DP.dp),
            color = if (state.phase == PotsikCloudPhase.Error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            border = potsAdaptiveCardBorder(),
            tonalElevation = 3.dp,
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                when (state.phase) {
                    PotsikCloudPhase.Thinking -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = POTSIK_CLOUD_PILL_HEIGHT_DP.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PotsikCloudDots(MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = stringResource(Res.string.potsik_cloud_thinking),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = vectorResource(Res.drawable.ic_close),
                                contentDescription = stringResource(Res.string.potsik_cloud_close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    PotsikCloudPhase.Speaking -> Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = state.reply,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    imageVector = vectorResource(Res.drawable.ic_close),
                                    contentDescription = stringResource(Res.string.potsik_cloud_close),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (onSpeak != null) {
                                TextButton(onClick = onSpeak) {
                                    Text(stringResource(Res.string.potsik_cloud_speak_aloud), fontSize = 13.sp)
                                }
                            }
                            TextButton(onClick = onOpenInChat) {
                                Text(stringResource(Res.string.potsik_cloud_open_in_chat), fontSize = 13.sp)
                            }
                        }
                    }

                    PotsikCloudPhase.Error -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = POTSIK_CLOUD_PILL_HEIGHT_DP.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(potsikCloudTroubleText(state.trouble)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetry) {
                            Text(stringResource(Res.string.potsik_cloud_retry), fontSize = 13.sp)
                        }
                    }

                    else -> Column {
                        if (shot != null) {
                            // What will be sent, above the question about it: the
                            // picture is what the chips and the field are for.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PotsikCloudThumbnail(shot = shot, side = 40.dp)
                                Text(
                                    text = stringResource(Res.string.potsik_cloud_screenshot_caption, shot.label),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = onRemoveShot, modifier = Modifier.size(40.dp)) {
                                    Icon(
                                        imageVector = vectorResource(Res.drawable.ic_close),
                                        contentDescription = stringResource(Res.string.potsik_cloud_screen_remove),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            PotsikCloudChips(onDraftChange)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = onDraftChange,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester),
                                placeholder = {
                                    Text(
                                        stringResource(
                                            if (shot != null) {
                                                Res.string.potsik_cloud_screen_placeholder
                                            } else {
                                                Res.string.potsik_cloud_input_placeholder
                                            },
                                        ),
                                    )
                                },
                                maxLines = 4,
                                shape = RoundedCornerShape(16.dp),
                            )
                            IconButton(
                                onClick = { if (potsikCloudSendEnabled(draft)) onSend() },
                                enabled = potsikCloudSendEnabled(draft),
                                modifier = Modifier.size(POTSIK_CLOUD_BUTTON_DP.dp),
                            ) {
                                Icon(
                                    imageVector = vectorResource(Res.drawable.ic_send),
                                    contentDescription = stringResource(Res.string.potsik_cloud_send),
                                    tint = if (potsikCloudSendEnabled(draft)) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
