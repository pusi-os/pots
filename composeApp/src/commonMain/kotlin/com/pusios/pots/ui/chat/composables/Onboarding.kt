package com.pusios.pots.ui.chat.composables

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.Service
import com.pusios.pots.ui.PotsClearableTextField
import com.pusios.pots.ui.chat.ChatActions
import com.pusios.pots.ui.chat.OnboardingPhase
import com.pusios.pots.ui.chat.OnboardingState
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.icons.LocalModel
import com.pusios.pots.ui.icons.OnboardKey
import com.pusios.pots.ui.icons.OnboardPaste
import com.pusios.pots.ui.icons.OnboardPerson
import com.pusios.pots.ui.icons.OnboardWallet
import com.pusios.pots.ui.icons.PotsGame
import com.pusios.pots.ui.potsAdaptiveCardBorder
import com.pusios.pots.ui.potsAdaptiveCardColors
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.onboarding_aborted
import pots.composeapp.generated.resources.onboarding_button_check
import pots.composeapp.generated.resources.onboarding_button_open_site
import pots.composeapp.generated.resources.onboarding_button_resume
import pots.composeapp.generated.resources.onboarding_button_save_key
import pots.composeapp.generated.resources.onboarding_button_top_up
import pots.composeapp.generated.resources.onboarding_error_empty_key
import pots.composeapp.generated.resources.onboarding_field_api_key
import pots.composeapp.generated.resources.onboarding_intro_1
import pots.composeapp.generated.resources.onboarding_intro_2
import pots.composeapp.generated.resources.onboarding_local_button
import pots.composeapp.generated.resources.onboarding_local_limits
import pots.composeapp.generated.resources.onboarding_local_offer
import pots.composeapp.generated.resources.onboarding_skip_button
import pots.composeapp.generated.resources.onboarding_skipped
import pots.composeapp.generated.resources.onboarding_step1
import pots.composeapp.generated.resources.onboarding_step2
import pots.composeapp.generated.resources.onboarding_step3
import pots.composeapp.generated.resources.onboarding_step4
import pots.composeapp.generated.resources.onboarding_success
import pots.composeapp.generated.resources.settings_status_checking

private const val TOTAL_STEPS = 4

/**
 * The first-launch setup wizard (§2 spec). Rendered in the empty-chat slot while
 * [OnboardingState.phase] is [OnboardingPhase.Active]; every copy string is @stiv's
 * final text, the provider example is PlusVibeAPI, the result lands through the same
 * add-service path as Settings → Services.
 */
@Composable
internal fun OnboardingWizardCard(
    state: OnboardingState,
    actions: ChatActions,
    modifier: Modifier = Modifier,
    onLocalConfirmed: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    var apiKey by rememberSaveable { mutableStateOf("") }
    val accountUrl = Service.PlusVibe.apiKeyUrl

    Card(
        modifier = modifier.widthIn(max = 480.dp),
        colors = potsAdaptiveCardColors(),
        border = potsAdaptiveCardBorder(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PotsGame,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.onboarding_intro_1),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.onboarding_intro_2),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { state.step.toFloat() / TOTAL_STEPS },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            for (step in 1..TOTAL_STEPS) {
                OnboardingStepRow(
                    step = step,
                    state = state,
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it },
                    onOpenSite = {
                        accountUrl?.let(uriHandler::openUri)
                        actions.onboardingOpenSite()
                    },
                    onSaveKey = { actions.onboardingSaveKey(apiKey) },
                    onTopUp = {
                        accountUrl?.let(uriHandler::openUri)
                        actions.onboardingTopUp()
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(thickness = 0.5.dp)
            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(Res.string.onboarding_local_offer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.showLocalLimits) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.onboarding_local_limits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val confirming = state.showLocalLimits
                    actions.onboardingChooseLocal()
                    if (confirming) onLocalConfirmed()
                },
                modifier = Modifier.handCursor(),
            ) {
                Icon(
                    imageVector = Icons.Filled.LocalModel,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.onboarding_local_button))
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { actions.onboardingSkip() },
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.onboarding_skip_button))
                }
                WizardMainButton(state = state, actions = actions, onOpenSite = {
                    accountUrl?.let(uriHandler::openUri)
                    actions.onboardingOpenSite()
                }, onSaveKey = { actions.onboardingSaveKey(apiKey) })
            }
        }
    }
}

/** The footer's primary action is always the active step's action (§2.4). */
@Composable
private fun WizardMainButton(
    state: OnboardingState,
    actions: ChatActions,
    onOpenSite: () -> Unit,
    onSaveKey: () -> Unit,
) {
    when (state.step) {
        1, 2 -> Button(onClick = onOpenSite, modifier = Modifier.handCursor()) {
            Text(stringResource(Res.string.onboarding_button_open_site))
        }

        3 -> Button(onClick = onSaveKey, modifier = Modifier.handCursor()) {
            Text(stringResource(Res.string.onboarding_button_save_key))
        }

        else -> if (state.isChecking) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.settings_status_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Button(onClick = { actions.onboardingCheck() }, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.onboarding_button_check))
            }
        }
    }
}

@Composable
private fun OnboardingStepRow(
    step: Int,
    state: OnboardingState,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onOpenSite: () -> Unit,
    onSaveKey: () -> Unit,
    onTopUp: () -> Unit,
) {
    val isActive = state.phase == OnboardingPhase.Active && state.step == step
    val isDone = state.step > step
    val dimmed = !isActive && !isDone

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.6f else 1f)
            .padding(vertical = 6.dp),
    ) {
        StepIndicator(step = step, isActive = isActive, isDone = isDone)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(stepText(step)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (isActive) {
                when (step) {
                    1, 2 -> {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenSite, modifier = Modifier.handCursor()) {
                            Text(stringResource(Res.string.onboarding_button_open_site))
                        }
                    }

                    3 -> {
                        Spacer(Modifier.height(8.dp))
                        PotsClearableTextField(
                            value = apiKey,
                            onValueChange = onApiKeyChange,
                            label = { Text(stringResource(Res.string.onboarding_field_api_key)) },
                            singleLine = true,
                        )
                        if (state.error == Res.string.onboarding_error_empty_key) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(Res.string.onboarding_error_empty_key),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    4 -> {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onTopUp, modifier = Modifier.handCursor(), enabled = !state.isChecking) {
                            Text(stringResource(Res.string.onboarding_button_top_up))
                        }
                        val checkError = state.error?.takeIf { it != Res.string.onboarding_error_empty_key }
                        if (checkError != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(checkError),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            } else if (isDone && step == 3) {
                // A completed key step keeps its summary: the key is on device, no text needed.
            }
        }
    }
}

@Composable
private fun StepIndicator(step: Int, isActive: Boolean, isDone: Boolean) {
    if (isDone) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        return
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = stepIcon(step),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun stepText(step: Int): StringResource = when (step) {
    1 -> Res.string.onboarding_step1
    2 -> Res.string.onboarding_step2
    3 -> Res.string.onboarding_step3
    else -> Res.string.onboarding_step4
}

private fun stepIcon(step: Int) = when (step) {
    1 -> Icons.Filled.OnboardPerson
    2 -> Icons.Filled.OnboardKey
    3 -> Icons.Filled.OnboardPaste
    else -> Icons.Filled.OnboardWallet
}

/**
 * Final card for a skipped or interrupted wizard (§5.3/§5.5): the reason text,
 * a resume button, and a close icon that hides the card until the next launch.
 */
@Composable
internal fun OnboardingFinalCard(
    phase: OnboardingPhase,
    actions: ChatActions,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.widthIn(max = 480.dp),
        colors = potsAdaptiveCardColors(),
        border = potsAdaptiveCardBorder(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(
                        if (phase == OnboardingPhase.Skipped) Res.string.onboarding_skipped else Res.string.onboarding_aborted,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { actions.onboardingDismiss() },
                    modifier = Modifier.handCursor(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { actions.onboardingResume() },
                modifier = Modifier.handCursor(),
            ) {
                Text(stringResource(Res.string.onboarding_button_resume))
            }
        }
    }
}

/** Success card (§2.7) — shown after the check passes or the local branch confirms. */
@Composable
internal fun OnboardingSuccessCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.widthIn(max = 480.dp),
        colors = potsAdaptiveCardColors(),
        border = potsAdaptiveCardBorder(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.onboarding_success),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
