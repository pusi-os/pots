package com.pusios.pots.ui.chat.composables

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.chat.ChatActions
import com.pusios.pots.ui.chat.OnboardingPhase
import com.pusios.pots.ui.chat.OnboardingState
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.icons.LocalModel
import com.pusios.pots.ui.icons.PotsGame
import com.pusios.pots.ui.potsAdaptiveCardBorder
import com.pusios.pots.ui.potsAdaptiveCardColors
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.onboarding_aborted
import pots.composeapp.generated.resources.onboarding_button_resume
import pots.composeapp.generated.resources.onboarding_intro_1
import pots.composeapp.generated.resources.onboarding_intro_2
import pots.composeapp.generated.resources.onboarding_local_button
import pots.composeapp.generated.resources.onboarding_local_limits
import pots.composeapp.generated.resources.onboarding_local_offer
import pots.composeapp.generated.resources.onboarding_skip_button
import pots.composeapp.generated.resources.onboarding_skipped
import pots.composeapp.generated.resources.onboarding_success

/**
 * The first-launch setup wizard (docs/design/onboarding). Rendered in the empty-chat slot
 * while [OnboardingState.phase] is [OnboardingPhase.Active]: the provider picker drives
 * the screen, the key lands through the same add-service path as Settings → Services,
 * and the local-model branch and «Пропустить» are kept from the previous wizard.
 */
@Composable
internal fun OnboardingWizardCard(
    state: OnboardingState,
    actions: ChatActions,
    modifier: Modifier = Modifier,
    onLocalConfirmed: () -> Unit = {},
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

            OnboardingProviderPicker(state = state, actions = actions)

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
            }
        }
    }
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

/** Success card — shown after «Готово» confirms the added service or the local branch confirms. */
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
