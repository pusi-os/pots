package com.pusios.pots.ui.chat.composables

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pusios.pots.data.TurnMetrics
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.token_stats_incoming
import pots.composeapp.generated.resources.token_stats_outgoing
import pots.composeapp.generated.resources.token_stats_per_request
import pots.composeapp.generated.resources.token_stats_percent
import pots.composeapp.generated.resources.token_stats_request_n
import pots.composeapp.generated.resources.token_stats_requests
import pots.composeapp.generated.resources.token_stats_session_total
import pots.composeapp.generated.resources.token_stats_title
import pots.composeapp.generated.resources.token_stats_total
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val tokenStatsPalette: List<Color> = listOf(
    Color(0xFF7C5CFF),
    Color(0xFF4FC3F7),
    Color(0xFF81C784),
    Color(0xFFFFB74D),
    Color(0xFFF06292),
    Color(0xFF4DB6AC),
    Color(0xFFBA68C8),
    Color(0xFFA1887F),
    Color(0xFF90A4AE),
    Color(0xFFE57373),
)

@Composable
fun TokenStatsDialog(
    turnMetrics: List<TurnMetrics>,
    contextWindowTokens: Int,
    onDismiss: () -> Unit,
) {
    val last = turnMetrics.lastOrNull()
    val totalTokens = last?.sessionTokens ?: 0
    val promptTokens = last?.sessionPromptTokens ?: 0
    val completionTokens = last?.sessionCompletionTokens ?: 0
    val contextTokens = last?.promptTokens ?: 0
    val window = if (contextWindowTokens > 0) contextWindowTokens else (last?.contextWindowTokens ?: 0)
    val percent = if (window > 0) (contextTokens.toLong() * 100 / window).toInt().coerceIn(0, 100) else 0

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.token_stats_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(8.dp))

                ContextFillGauge(
                    usedTokens = contextTokens,
                    contextWindowTokens = window,
                    modifier = Modifier.size(220.dp),
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(Res.string.token_stats_percent, percent),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(Res.string.token_stats_total, formatTokenCount(contextTokens), formatTokenCount(window)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.token_stats_session_total, formatTokenCount(totalTokens)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatCell(stringResource(Res.string.token_stats_incoming), formatTokenCount(promptTokens), Color(0xFF4FC3F7))
                    StatCell(stringResource(Res.string.token_stats_outgoing), formatTokenCount(completionTokens), Color(0xFF81C784))
                    StatCell(stringResource(Res.string.token_stats_requests), turnMetrics.size.toString(), MaterialTheme.colorScheme.primary)
                }

                if (turnMetrics.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(Res.string.token_stats_per_request),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                    )
                    Spacer(Modifier.height(8.dp))
                    turnMetrics.forEachIndexed { index, m ->
                        val color = tokenStatsPalette[index % tokenStatsPalette.size]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(color),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(Res.string.token_stats_request_n, index + 1),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "↓${formatTokenCount(m.promptTokens)}  ↑${formatTokenCount(m.completionTokens)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ContextFillGauge(
    usedTokens: Int,
    contextWindowTokens: Int,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "liquid")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    val sway by transition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sway",
    )

    val fraction = if (contextWindowTokens > 0) {
        (usedTokens.toFloat() / contextWindowTokens).coerceIn(0f, 1f)
    } else {
        0f
    }
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    Canvas(modifier = modifier) {
        val stroke = 22.dp.toPx()
        val radius = (size.minDimension / 2f) - stroke / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)

        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )

        if (fraction > 0f) {
            drawArc(
                color = color,
                startAngle = -90f + sway,
                sweepAngle = (fraction * 360f).coerceAtLeast(2f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        // liquid sway highlight: a translucent wavy ring overlay
        val waveAmplitude = 4f
        val path = Path()
        val steps = 64
        for (i in 0..steps) {
            val angle = (i.toFloat() / steps) * 2f * PI.toFloat()
            val wobble = sin(angle * 3f + phase) * waveAmplitude
            val r = radius - stroke / 2f + wobble
            val x = center.x + r * cos(angle)
            val y = center.y + r * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.08f),
        )
    }
}

private fun formatTokenCount(tokens: Int): String = when {
    tokens < 1_000 -> tokens.toString()
    tokens < 10_000 -> "${(tokens / 100) / 10.0}k"
    else -> "${tokens / 1000}k"
}
