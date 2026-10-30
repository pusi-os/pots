package com.pusios.pots.ui.chat.composables

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pusios.pots.companion.CompanionStatus
import com.pusios.pots.handsfree.HandsFreeStatus
import com.pusios.pots.ui.chat.ChatActions
import com.pusios.pots.ui.handCursor
import nl.marc_apps.tts.TextToSpeechInstance
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.agent_profiles_content_description
import pots.composeapp.generated.resources.chat_history_content_description
import pots.composeapp.generated.resources.handsfree_content_description
import pots.composeapp.generated.resources.ic_add
import pots.composeapp.generated.resources.ic_handsfree
import pots.composeapp.generated.resources.ic_handsfree_active
import pots.composeapp.generated.resources.ic_history
import pots.composeapp.generated.resources.ic_miniapp_library
import pots.composeapp.generated.resources.ic_settings
import pots.composeapp.generated.resources.ic_stop
import pots.composeapp.generated.resources.ic_volume_off
import pots.composeapp.generated.resources.ic_volume_up
import pots.composeapp.generated.resources.mini_app_toolbar_content_description
import pots.composeapp.generated.resources.new_chat_content_description
import pots.composeapp.generated.resources.sandbox_content_description
import pots.composeapp.generated.resources.settings_content_description
import pots.composeapp.generated.resources.toggle_speech_output_content_description

@Composable
internal fun TopBar(
    textToSpeech: TextToSpeechInstance? = null,
    isSpeechOutputEnabled: Boolean,
    isSpeaking: Boolean,
    actions: ChatActions,
    isChatHistoryEmpty: Boolean,
    hasSavedConversations: Boolean,
    onNavigateToSettings: () -> Unit,
    isSandboxAvailable: Boolean,
    isSandboxOpen: Boolean,
    isShellExecuting: Boolean,
    onToggleSandbox: () -> Unit,
    onShowHistory: () -> Unit,
    navigationTabBar: (@Composable () -> Unit)? = null,
    handsFreeStatus: HandsFreeStatus? = null,
    onToggleHandsFree: (() -> Unit)? = null,
    companionStatus: CompanionStatus? = null,
    onToggleCompanion: (() -> Unit)? = null,
    miniAppsOpen: Boolean = false,
    onToggleMiniApps: (() -> Unit)? = null,
    profilesOpen: Boolean = false,
    onToggleProfiles: (() -> Unit)? = null,
) {
    if (navigationTabBar != null) {
        Box(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp),
        ) {
            Row(modifier = Modifier.align(Alignment.CenterStart)) {
                LeadingButtons(textToSpeech, isSpeechOutputEnabled, isSpeaking, actions, isChatHistoryEmpty, hasSavedConversations, onShowHistory, isSandboxAvailable, isSandboxOpen, isShellExecuting, onToggleSandbox)
            }
            Box(modifier = Modifier.align(Alignment.Center)) {
                navigationTabBar()
            }
            Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                if (textToSpeech != null) {
                    SpeechToggleButton(textToSpeech, isSpeechOutputEnabled, isSpeaking, actions)
                }
                if (companionStatus != null) {
                    CompanionIndicator(status = companionStatus, onClick = onToggleCompanion)
                }
                if (handsFreeStatus != null && onToggleHandsFree != null) {
                    HandsFreeButton(status = handsFreeStatus, onClick = onToggleHandsFree)
                }
                if (onToggleProfiles != null) {
                    AgentProfilesButton(open = profilesOpen, onClick = onToggleProfiles)
                }
                if (onToggleMiniApps != null) {
                    MiniAppsButton(open = miniAppsOpen, onClick = onToggleMiniApps)
                }
            }
        }
    } else {
        Row {
            LeadingButtons(textToSpeech, isSpeechOutputEnabled, isSpeaking, actions, isChatHistoryEmpty, hasSavedConversations, onShowHistory, isSandboxAvailable, isSandboxOpen, isShellExecuting, onToggleSandbox)
            Spacer(Modifier.weight(1f))
            if (textToSpeech != null) {
                SpeechToggleButton(textToSpeech, isSpeechOutputEnabled, isSpeaking, actions)
            }
            if (companionStatus != null) {
                CompanionIndicator(status = companionStatus, onClick = onToggleCompanion)
            }
            if (handsFreeStatus != null && onToggleHandsFree != null) {
                HandsFreeButton(status = handsFreeStatus, onClick = onToggleHandsFree)
            }
            IconButton(
                modifier = Modifier.handCursor(),
                onClick = onNavigateToSettings,
            ) {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_settings),
                    contentDescription = stringResource(Res.string.settings_content_description),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            if (onToggleProfiles != null) {
                AgentProfilesButton(open = profilesOpen, onClick = onToggleProfiles)
            }
            if (onToggleMiniApps != null) {
                MiniAppsButton(open = miniAppsOpen, onClick = onToggleMiniApps)
            }
        }
    }
}

@Composable
private fun AgentProfilesButton(open: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.handCursor()) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = stringResource(Res.string.agent_profiles_content_description),
            tint = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun MiniAppsButton(open: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.handCursor()) {
        Icon(
            imageVector = vectorResource(Res.drawable.ic_miniapp_library),
            contentDescription = stringResource(Res.string.mini_app_toolbar_content_description),
            tint = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun LeadingButtons(
    textToSpeech: TextToSpeechInstance?,
    isSpeechOutputEnabled: Boolean,
    isSpeaking: Boolean,
    actions: ChatActions,
    isChatHistoryEmpty: Boolean,
    hasSavedConversations: Boolean,
    onShowHistory: () -> Unit,
    isSandboxAvailable: Boolean,
    isSandboxOpen: Boolean,
    isShellExecuting: Boolean,
    onToggleSandbox: () -> Unit,
) {
    if (hasSavedConversations) {
        IconButton(
            modifier = Modifier.handCursor(),
            onClick = onShowHistory,
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_history),
                contentDescription = stringResource(Res.string.chat_history_content_description),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
    if (!isChatHistoryEmpty) {
        IconButton(
            modifier = Modifier.handCursor(),
            onClick = {
                if (isSpeechOutputEnabled && isSpeaking) {
                    actions.setIsSpeaking(false, "")
                    textToSpeech?.stop()
                }
                actions.startNewChat()
            },
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_add),
                contentDescription = stringResource(Res.string.new_chat_content_description),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
    if (isSandboxAvailable) {
        val flashAlpha = remember { Animatable(0f) }
        LaunchedEffect(isShellExecuting) {
            if (isShellExecuting) {
                flashAlpha.snapTo(0.4f)
                flashAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                )
            }
        }
        val primary = MaterialTheme.colorScheme.primary
        val checkedContainer = primary.copy(alpha = 0.2f)
        val flashContainer = primary.copy(alpha = flashAlpha.value)
        IconToggleButton(
            checked = isSandboxOpen,
            onCheckedChange = { onToggleSandbox() },
            modifier = Modifier.handCursor(),
            colors = IconButtonDefaults.iconToggleButtonColors(
                containerColor = flashContainer,
                checkedContainerColor = if (flashAlpha.value > 0f) flashContainer else checkedContainer,
                checkedContentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Dns,
                contentDescription = stringResource(Res.string.sandbox_content_description),
                tint = if (isSandboxOpen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            )
        }
    }
}

/**
 * Toggle for the hands-free voice mode.
 *
 * The icon carries the state: a plain microphone when the mode is off, the active variant
 * while the loop runs, and a stop symbol while the agent is speaking — tapping it then
 * interrupts the agent instead of stopping the mode.
 */
@Composable
private fun HandsFreeButton(
    status: HandsFreeStatus,
    onClick: () -> Unit,
) {
    // A slow pulse while the microphone is open: it is the only signal that the app is
    // listening, and the agent is not speaking at that moment.
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(status) {
        if (status == HandsFreeStatus.Listening) {
            pulse.snapTo(1f)
            pulse.animateTo(
                targetValue = 0.55f,
                animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            )
        } else {
            pulse.snapTo(1f)
        }
    }

    IconButton(
        modifier = Modifier.handCursor(),
        onClick = onClick,
    ) {
        Icon(
            imageVector = vectorResource(
                when (status) {
                    HandsFreeStatus.Speaking -> Res.drawable.ic_stop
                    HandsFreeStatus.Idle -> Res.drawable.ic_handsfree
                    else -> Res.drawable.ic_handsfree_active
                },
            ),
            contentDescription = stringResource(Res.string.handsfree_content_description),
            // x = Res.string.foo_bar
            tint = if (status == HandsFreeStatus.Idle) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = pulse.value)
            },
        )
    }
}

@Composable
private fun SpeechToggleButton(
    textToSpeech: TextToSpeechInstance,
    isSpeechOutputEnabled: Boolean,
    isSpeaking: Boolean,
    actions: ChatActions,
) {
    IconButton(
        modifier = Modifier.handCursor(),
        onClick = {
            if (isSpeechOutputEnabled && isSpeaking) {
                actions.setIsSpeaking(false, "")
                textToSpeech.stop()
            }
            actions.toggleSpeechOutput()
        },
    ) {
        Icon(
            imageVector = if (isSpeechOutputEnabled) {
                vectorResource(Res.drawable.ic_volume_up)
            } else {
                vectorResource(Res.drawable.ic_volume_off)
            },
            contentDescription = stringResource(Res.string.toggle_speech_output_content_description),
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
internal fun CompanionIndicator(
    status: CompanionStatus,
    onClick: (() -> Unit)? = null,
) {
    val (icon, tint) = when (status) {
        CompanionStatus.Available -> Icons.Filled.Bluetooth to androidx.compose.material3.MaterialTheme.colorScheme.primary
        CompanionStatus.Listening -> Icons.Filled.Mic to androidx.compose.material3.MaterialTheme.colorScheme.secondary
        CompanionStatus.Thinking -> Icons.Filled.HourglassEmpty to androidx.compose.material3.MaterialTheme.colorScheme.tertiary
        CompanionStatus.Speaking -> Icons.Filled.VolumeUp to androidx.compose.material3.MaterialTheme.colorScheme.primary
        CompanionStatus.Pairing -> Icons.Filled.Bluetooth to androidx.compose.material3.MaterialTheme.colorScheme.tertiary
        CompanionStatus.Error -> Icons.Filled.Error to androidx.compose.material3.MaterialTheme.colorScheme.error
        else -> Icons.Filled.BluetoothDisabled to androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    }
    if (onClick != null) {
        androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.handCursor()) {
            androidx.compose.material3.Icon(imageVector = icon, contentDescription = "Companion", tint = tint)
        }
    } else {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = "Companion",
            tint = tint,
        )
    }
}
