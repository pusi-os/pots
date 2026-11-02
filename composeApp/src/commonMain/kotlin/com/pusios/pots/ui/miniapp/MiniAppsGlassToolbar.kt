package com.pusios.pots.ui.miniapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.miniapp.MiniAppSummary
import com.pusios.pots.ui.handCursor
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.mini_app_toolbar_add
import pots.composeapp.generated.resources.mini_app_toolbar_configure

/**
 * The glass strip under the chat's top bar.
 *
 * It holds the apps the user pinned: one tap opens the app, and the tune button
 * opens the library where the pinned set is chosen. With no apps at all the
 * strip is a single plus that starts the studio, so the first app is one tap
 * away rather than buried in a menu.
 */
@Composable
fun MiniAppsGlassToolbar(
    visible: Boolean,
    apps: List<MiniAppSummary>,
    pinned: List<MiniAppSummary>,
    onOpenApp: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onNewApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(22.dp)
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState())
                .shadow(10.dp, shape)
                .clip(shape)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.38f),
                        ),
                    ),
                )
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)), shape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The plus is the entry into the studio: an icon-only circle, the
            // same size as the app icons, so the strip stays clean vector art.
            GlassIconButton(
                onClick = onNewApp,
                contentDescription = stringResource(Res.string.mini_app_toolbar_add),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            if (apps.isNotEmpty()) {
                pinned.forEach { app ->
                    AppIcon(app = app, onClick = { onOpenApp(app.id) })
                }
                GlassChip(
                    label = stringResource(Res.string.mini_app_toolbar_configure),
                    onClick = onOpenLibrary,
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: MiniAppSummary, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier.size(46.dp).clip(shape).semantics { contentDescription = app.name }.handCursor(),
        shape = shape,
        color = accentColor(app.accent).copy(alpha = 0.22f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(app.icon.ifBlank { "🧩" }, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * An icon-only circle in the strip: no text label, just the vector glyph. Used
 * for the studio "+" so adding an app is one obvious tap, with the description
 * carried by accessibility instead of pixels.
 */
@Composable
private fun GlassIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    icon: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .handCursor(),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            icon()
        }
    }
}

@Composable
private fun GlassChip(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).handCursor(),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.10f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon()
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
