package com.pusios.pots.ui.chat.composables

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.potsik.PotsikAnchor
import com.pusios.pots.ui.potsik.PotsikAnchorKind
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.handsfree_content_description
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun EmptyState(modifier: Modifier, onStartHandsFree: (() -> Unit)? = null) {
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        PotsikAnchor(kind = PotsikAnchorKind.EMPTY, size = 132.dp)
        if (onStartHandsFree != null) {
            Spacer(Modifier.height(32.dp))
            VoiceButton(onStartHandsFree)
        }
    }
}

/** One animated canvas keeps the decorative particles out of the composition tree. */
@Composable
private fun VoiceButton(onClick: () -> Unit) {
    val description = stringResource(Res.string.handsfree_content_description)
    val transition = rememberInfiniteTransition()
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart))
    Canvas(
        Modifier.size(104.dp).handCursor().clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        val r = size.minDimension / 2f
        val t = phase * 2f * PI.toFloat()
        val pulse = 0.96f + 0.025f * (sin(t * 3f) + sin(t * 6f).coerceAtLeast(0f))
        drawCircle(brush = Brush.radialGradient(listOf(Color(0xFFEF7FCE), Color(0xFF8059E8), Color(0xFF3753B9)), center = Offset(r * (0.7f + 0.2f * sin(t)), r * (0.65f + 0.2f * cos(t))), radius = r * 1.8f), radius = r * pulse)
        repeat(18) { i ->
            val a = i * 2.4f + t * (if (i % 2 == 0) 1f else -1f)
            val distance = r * (0.58f + 0.19f * sin(t + i * 1.7f))
            val p = center + Offset(cos(a) * distance, sin(a * 1.3f) * distance)
            val color = Color.White.copy(alpha = 0.25f + (i % 3) * 0.12f)
            if (i % 3 == 0) {
                drawLine(color, p - Offset(r * 0.025f, 0f), p + Offset(r * 0.025f, 0f), strokeWidth = r * 0.025f, cap = StrokeCap.Round)
            } else {
                drawCircle(color, radius = r * (0.015f + (i % 2) * 0.012f), center = p)
            }
        }
        // A speech bubble and waveform combine conversation and voice in one vector mark.
        drawCircle(Color.White.copy(alpha = 0.9f), radius = r * 0.39f, style = Stroke(width = r * 0.045f))
        drawLine(Color.White, center + Offset(r * 0.18f, r * 0.33f), center + Offset(r * 0.34f, r * 0.43f), r * 0.045f, cap = StrokeCap.Round)
        listOf(0.16f, 0.31f, 0.22f, 0.12f).forEachIndexed { i, height ->
            val x = center.x + (i - 1.5f) * r * 0.13f
            drawLine(Color.White, Offset(x, center.y - r * height / 2), Offset(x, center.y + r * height / 2), r * 0.055f, cap = StrokeCap.Round)
        }
    }
}
