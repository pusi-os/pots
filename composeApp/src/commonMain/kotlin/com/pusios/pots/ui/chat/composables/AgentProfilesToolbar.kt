package com.pusios.pots.ui.chat.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.AgentProfile
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.potsik.PotsikAction
import com.pusios.pots.ui.potsik.PotsikAnimationCatalog
import com.pusios.pots.ui.potsik.PotsikAvatar
import com.pusios.pots.ui.potsik.PotsikPhase
import com.pusios.pots.ui.potsik.PotsikPlacement
import com.pusios.pots.ui.potsik.PotsikStage
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
internal fun AgentProfilesToolbar(
    visible: Boolean,
    profiles: List<AgentProfile>,
    activeProfileId: String,
    onSelectProfile: (AgentProfile) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it / 2 } + fadeIn(),
        exit = slideOutVertically { -it / 2 } + fadeOut(),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(86.dp).padding(horizontal = 12.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                profiles.forEach { profile ->
                    val selected = profile.id == activeProfileId
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .handCursor()
                            .then(Modifier),
                        onClick = { onSelectProfile(profile) },
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.width(64.dp).padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            ProfilePotsikAvatar(profile = profile)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.widthIn(max = 56.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilePotsikAvatar(profile: AgentProfile) {
    var action by remember(profile.id) { mutableStateOf(PotsikAction.IDLE_BREATHE) }
    var animationKey by remember(profile.id) { mutableIntStateOf(0) }
    LaunchedEffect(profile.id) {
        while (true) {
            delay(Random.nextLong(3_000L, 5_001L))
            action = PotsikAnimationCatalog.idleActions.random()
            animationKey++
        }
    }
    PotsikAvatar(
        look = profile.potsikLook,
        phase = PotsikPhase.IDLE,
        size = 40.dp,
        modifier = Modifier.size(40.dp),
        animationOverride = action,
        animationKey = animationKey,
    )
}

@Composable
internal fun AgentProfileHandoffOverlay(
    stage: PotsikStage,
    outgoing: AgentProfile,
    incoming: AgentProfile,
    onActivateIncoming: () -> Unit,
    onFinished: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val startPlacement = remember(outgoing.id, incoming.id) { stage.placement.value }
        val area = remember(outgoing.id, incoming.id) { stage.area.value }
        val startCharacterPx = stage.baseCharacterSize.value * startPlacement.scale
        val startCxPx = startPlacement.x * area.width.toFloat()
        val startFeetYpx = startPlacement.y * area.height.toFloat()
        val avatarSize = with(density) { startCharacterPx.toDp() }
        val travelPx = with(density) { (area.width.toFloat() + startCharacterPx + 144.dp.toPx()) }
        val horizontalOffset = remember(outgoing.id, incoming.id) { Animatable(0f) }
        var showingIncoming by remember(outgoing.id, incoming.id) { mutableStateOf(false) }
        LaunchedEffect(outgoing.id, incoming.id) {
            delay(650L)
            horizontalOffset.animateTo(-travelPx, animationSpec = tween(420))
            onActivateIncoming()
            showingIncoming = true
            horizontalOffset.snapTo(travelPx)
            horizontalOffset.animateTo(0f, animationSpec = tween(520))
            delay(350L)
            onFinished()
        }
        val profile = if (showingIncoming) incoming else outgoing
        Box(modifier = Modifier.fillMaxSize()) {
            PotsikAvatar(
                look = profile.potsikLook,
                phase = PotsikPhase.IDLE,
                size = avatarSize,
                modifier = Modifier.offset {
                    IntOffset(
                        (startCxPx - startCharacterPx / 2f + horizontalOffset.value).roundToInt(),
                        (startFeetYpx - startCharacterPx).roundToInt(),
                    )
                },
                animationOverride = PotsikAction.IDLE_WAVE,
                animationKey = if (showingIncoming) 2 else 1,
            )
        }
    }
}
