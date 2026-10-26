package com.pusios.pots.ui.chat.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.TurnMetrics
import com.pusios.pots.getBackgroundDispatcher
import com.pusios.pots.ui.dynamicui.FrozenSubmission
import com.pusios.pots.ui.dynamicui.toSpeakableText
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.markdown.MarkdownContent
import com.pusios.pots.ui.markdown.parseMarkdown
import com.pusios.pots.ui.potsik.PotsikAnchor
import com.pusios.pots.ui.potsik.PotsikAnchorKind
import com.pusios.pots.ui.rememberCopyToClipboard
import com.pusios.pots.ui.tts.sayChunked
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import nl.marc_apps.tts.TextToSpeechInstance
import nl.marc_apps.tts.errors.TextToSpeechSynthesisInterruptedError
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.bot_message_copy_content_description
import pots.composeapp.generated.resources.bot_message_duration_content_description
import pots.composeapp.generated.resources.bot_message_regenerate_content_description
import pots.composeapp.generated.resources.bot_message_speech_content_description
import pots.composeapp.generated.resources.bot_message_thinking_expand_content_description
import pots.composeapp.generated.resources.bot_message_thinking_label
import pots.composeapp.generated.resources.bot_message_tokens_content_description
import pots.composeapp.generated.resources.chat_cancel_edit_content_description
import pots.composeapp.generated.resources.chat_edit_submission_content_description
import pots.composeapp.generated.resources.ic_copy
import pots.composeapp.generated.resources.ic_refresh
import pots.composeapp.generated.resources.ic_stop
import pots.composeapp.generated.resources.ic_timer
import pots.composeapp.generated.resources.ic_tokens
import pots.composeapp.generated.resources.ic_volume_up
import kotlin.math.roundToInt

@Composable
internal fun BotMessage(
    message: String,
    textToSpeech: TextToSpeechInstance?,
    isSpeaking: Boolean,
    setIsSpeaking: (Boolean) -> Unit,
    onRegenerate: (() -> Unit)? = null,
    isInteractive: Boolean = false,
    onUiCallback: ((event: String, data: Map<String, String>) -> Unit)? = null,
    frozen: FrozenSubmission? = null,
    onResubmit: ((event: String, data: Map<String, String>) -> Unit)? = null,
    reasoningSegments: ImmutableList<String> = persistentListOf(),
    thinkingExpanded: Boolean = false,
    onThinkingExpandedChange: (Boolean) -> Unit = {},
    turnMetrics: TurnMetrics? = null,
    onTokenCounterClick: () -> Unit = {},
) {
    val document = remember(message) { parseMarkdown(message) }
    var isEditing by remember(frozen) { mutableStateOf(false) }
    val effectiveFrozen = if (isEditing && frozen != null) frozen.copy(pressedEvent = null) else frozen
    val effectiveInteractive = if (frozen != null) (onResubmit != null && isEditing) else isInteractive
    val potsUiCallback: (String, Map<String, String>) -> Unit = if (onResubmit != null) {
        { event, data ->
            isEditing = false
            onResubmit(event, data)
        }
    } else {
        onUiCallback ?: { _, _ -> }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val nonBlankSegments = remember(reasoningSegments) {
                reasoningSegments.filter { it.isNotBlank() }.toImmutableList()
            }
            if (nonBlankSegments.isNotEmpty()) {
                ReasoningBlockquote(
                    segments = nonBlankSegments,
                    expanded = thinkingExpanded,
                    onExpandedChange = onThinkingExpandedChange,
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 16.dp, top = 12.dp, end = 16.dp),
                )
            }
            if (message.isNotEmpty()) {
                // When reasoning is shown above, the Thinking row already provides
                // the visual gap to the answer — drop the duplicated top inset.
                val answerTopPadding = if (nonBlankSegments.isNotEmpty()) 6.dp else 16.dp
                SelectionContainer {
                    MarkdownContent(
                        document = document,
                        isInteractive = effectiveInteractive,
                        onUiCallback = potsUiCallback,
                        frozen = effectiveFrozen,
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = 16.dp, top = answerTopPadding, end = 16.dp, bottom = 8.dp),
                    )
                }
            }
        }
        if (frozen != null && onResubmit != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .handCursor()
                    .clickable { isEditing = !isEditing },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isEditing) Icons.Default.Close else Icons.Default.Edit,
                    contentDescription = stringResource(
                        if (isEditing) Res.string.chat_cancel_edit_content_description else Res.string.chat_edit_submission_content_description,
                    ),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (message.isEmpty()) return

    // Потсик's place is in the footer, to the right of the buttons under the
    // answer. The anchor here only says where that is; the character himself
    // is drawn once, over the whole chat, so he can be dragged anywhere and
    // resized with two fingers without ever being cut off by the card.
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (textToSpeech != null) {
                val componentScope = rememberCoroutineScope()
                SmallIconButton(
                    iconResource = if (isSpeaking) Res.drawable.ic_stop else Res.drawable.ic_volume_up,
                    contentDescription = stringResource(Res.string.bot_message_speech_content_description),
                    onClick = {
                        componentScope.launch(getBackgroundDispatcher()) {
                            textToSpeech.stop()
                            if (isSpeaking) {
                                setIsSpeaking(false)
                            } else {
                                setIsSpeaking(true)
                                try {
                                    textToSpeech.sayChunked(text = message.toSpeakableText())
                                } catch (ignore: TextToSpeechSynthesisInterruptedError) {
                                    // Expected interruption - no action needed
                                } catch (e: Exception) {
                                    // Handle TTS errors gracefully (service failure, audio issues, etc.)
                                }
                                setIsSpeaking(false)
                            }
                        }
                    },
                )
            }
            val copyToClipboard = rememberCopyToClipboard()
            SmallIconButton(
                iconResource = Res.drawable.ic_copy,
                contentDescription = stringResource(Res.string.bot_message_copy_content_description),
                onClick = { copyToClipboard(message) },
            )
            if (onRegenerate != null) {
                SmallIconButton(
                    iconResource = Res.drawable.ic_refresh,
                    contentDescription = stringResource(Res.string.bot_message_regenerate_content_description),
                    onClick = onRegenerate,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // The character's place in the footer. He is drawn by the overlay
        // over the chat — dragged anywhere, resized with two fingers — so
        // there is only ever one of him.
        PotsikAnchor(
            kind = PotsikAnchorKind.FOOTER,
            size = 48.dp,
        )
        Spacer(Modifier.weight(1f))
        if (turnMetrics != null) {
            TurnMetricsRow(metrics = turnMetrics, onClick = onTokenCounterClick)
        }
    }
}

/**
 * Right-aligned footer metrics of an assistant reply: the tokens this reply's request used
 * (with the share of the model's context window its prompt occupied) and how long it took.
 * The cumulative session totals live in the dialog opened by tapping this row.
 *
 * The icons carry the meaning, so the row stays readable at the small label size.
 */
@Composable
private fun TurnMetricsRow(metrics: TurnMetrics, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .padding(start = 8.dp, end = 4.dp)
            .clickable(onClick = onClick)
            .handCursor(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_tokens),
            contentDescription = stringResource(Res.string.bot_message_tokens_content_description),
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "↓${formatTokenCount(metrics.promptTokens)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = " ↑${formatTokenCount(metrics.completionTokens)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (metrics.contextWindowTokens > 0) {
            Text(
                text = " \u00B7 ${metrics.contextUsagePercent}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(Res.drawable.ic_timer),
            contentDescription = stringResource(Res.string.bot_message_duration_content_description),
            modifier = Modifier
                .padding(start = 6.dp)
                .size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatDuration(metrics.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Renders a token count compactly: `1.2k` above a thousand, plain digits below. */
private fun formatTokenCount(tokens: Int): String = when {
    tokens < 1_000 -> tokens.toString()
    tokens < 10_000 -> "${(tokens / 100) / 10.0}k"
    else -> "${tokens / 1000}k"
}

/** Renders a duration as `12.3s` below a minute and `1m 05s` above it. */
private fun formatDuration(durationMs: Long): String {
    if (durationMs < 0) return "0.0s"
    val totalSeconds = durationMs / 1000
    if (totalSeconds < 60) return "${durationMs % 1000 / 100.0}s"
    return "${totalSeconds / 60}m ${totalSeconds % 60}s"
}

@Composable
private fun ReasoningBlockquote(
    segments: ImmutableList<String>,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Preview always reflects the MOST RECENT thinking segment so the user gets a
    // visual update each time a new reasoning phase starts, without expanding.
    val preview = remember(segments) {
        segments.lastOrNull()
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .handCursor(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(Res.string.bot_message_thinking_expand_content_description),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(Res.string.bot_message_thinking_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!expanded && preview.isNotEmpty()) {
                Text(
                    text = " · $preview",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (segment in segments) {
                        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                            VerticalDivider(
                                thickness = 2.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.fillMaxHeight(),
                            )
                            SelectionContainer(modifier = Modifier.padding(start = 10.dp)) {
                                Text(
                                    text = segment,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
