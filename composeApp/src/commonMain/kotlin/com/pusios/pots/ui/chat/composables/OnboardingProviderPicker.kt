package com.pusios.pots.ui.chat.composables

// The onboarding wizard (docs/design/onboarding; 3.20.0 rework) walks the user forward with
// «Далее»: step 1 — provider cards (each with its referral link), step 2 — «Регистрация» and
// «Личный кабинет · API-ключи» buttons plus the per-provider guide, step 3 — the API-key field
// that saves immediately (no separate check button; validation still runs validateConnection)
// and then shows the model dropdown (DeepSeek V4.1 Flash default) and the balance.
// State resets between providers stay per 03-key-states §3.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.Service
import com.pusios.pots.ui.chat.ChatActions
import com.pusios.pots.ui.chat.OnboardingState
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.icons.OnboardAlert
import com.pusios.pots.ui.icons.OnboardChip
import com.pusios.pots.ui.icons.OnboardKey
import com.pusios.pots.ui.icons.OnboardLayers
import com.pusios.pots.ui.icons.OnboardPlay
import com.pusios.pots.ui.icons.OnboardPulse
import com.pusios.pots.ui.icons.OnboardRoundCheck
import com.pusios.pots.ui.icons.OnboardRoute
import com.pusios.pots.ui.icons.OnboardTunnel
import com.pusios.pots.ui.icons.OnboardWifiOff
import com.pusios.pots.ui.settings.formatProviderBalance
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.onboarding_balance_label
import pots.composeapp.generated.resources.onboarding_button_cabinet
import pots.composeapp.generated.resources.onboarding_button_done
import pots.composeapp.generated.resources.onboarding_button_next
import pots.composeapp.generated.resources.onboarding_button_retry
import pots.composeapp.generated.resources.onboarding_button_signup
import pots.composeapp.generated.resources.onboarding_checking_message
import pots.composeapp.generated.resources.onboarding_desc_aitunnel_1
import pots.composeapp.generated.resources.onboarding_desc_aitunnel_2
import pots.composeapp.generated.resources.onboarding_desc_aitunnel_3
import pots.composeapp.generated.resources.onboarding_desc_gptunnel_1
import pots.composeapp.generated.resources.onboarding_desc_gptunnel_2
import pots.composeapp.generated.resources.onboarding_desc_gptunnel_3
import pots.composeapp.generated.resources.onboarding_desc_llmrouter_1
import pots.composeapp.generated.resources.onboarding_desc_llmrouter_2
import pots.composeapp.generated.resources.onboarding_desc_llmrouter_3
import pots.composeapp.generated.resources.onboarding_desc_plusvibe_1
import pots.composeapp.generated.resources.onboarding_desc_plusvibe_2
import pots.composeapp.generated.resources.onboarding_desc_plusvibe_3
import pots.composeapp.generated.resources.onboarding_desc_polza_1
import pots.composeapp.generated.resources.onboarding_desc_polza_2
import pots.composeapp.generated.resources.onboarding_desc_polza_3
import pots.composeapp.generated.resources.onboarding_error_invalid_key
import pots.composeapp.generated.resources.onboarding_error_network
import pots.composeapp.generated.resources.onboarding_error_no_balance
import pots.composeapp.generated.resources.onboarding_guide_title
import pots.composeapp.generated.resources.onboarding_key_added
import pots.composeapp.generated.resources.onboarding_key_autosave_hint
import pots.composeapp.generated.resources.onboarding_key_hint_aitunnel
import pots.composeapp.generated.resources.onboarding_key_hint_generic
import pots.composeapp.generated.resources.onboarding_key_hint_llmrouter
import pots.composeapp.generated.resources.onboarding_key_hint_plusvibe
import pots.composeapp.generated.resources.onboarding_model_guide_body
import pots.composeapp.generated.resources.onboarding_model_guide_title
import pots.composeapp.generated.resources.onboarding_model_label
import pots.composeapp.generated.resources.onboarding_models_loading
import pots.composeapp.generated.resources.onboarding_provider_default
import pots.composeapp.generated.resources.onboarding_provider_section
import pots.composeapp.generated.resources.onboarding_step_key_title
import pots.composeapp.generated.resources.onboarding_sub_aitunnel
import pots.composeapp.generated.resources.onboarding_sub_gptunnel
import pots.composeapp.generated.resources.onboarding_sub_llmrouter
import pots.composeapp.generated.resources.onboarding_sub_plusvibe
import pots.composeapp.generated.resources.onboarding_sub_polza
import pots.composeapp.generated.resources.onboarding_video_note
import pots.composeapp.generated.resources.onboarding_video_title

/** The mockup's warning token (#F9C74F) — a network failure, not a bad key. */
private val GuideWarnColor = Color(0xFFF9C74F)

/** The mockup's success token (#6EE7A0), same green as the model cards' «Free» badge. */
private val GuideSuccessColor = Color(0xFF6EE7A0)

private val PickerShape = RoundedCornerShape(12.dp)

/** How long the key draft must stay unchanged before the automatic save+check fires. */
private const val KEY_AUTOSAVE_DELAY_MS = 700L

/**
 * Screen dispatcher of the onboarding: step 1 — the provider picker, step 2 — sign-up and the
 * cabinet guide, steps 3/4 — the key field with the model dropdown and the balance. The
 * provider for steps 2/4 comes from [OnboardingState.serviceId]; before it is set (and before
 * the user reaches step 2) the local card selection is used, falling back to PlusVibe.
 */
@Composable
internal fun OnboardingProviderPicker(
    state: OnboardingState,
    actions: ChatActions,
    modifier: Modifier = Modifier,
) {
    var selectedId by rememberSaveable { mutableStateOf(Service.PlusVibe.id) }

    fun pick(service: Service) {
        // Switching cards clears the check verdict (error/added) and everything loaded from
        // the previous key — the key and its catalog belong to their provider and must not
        // travel to another one (03-key-states §3). Reopening the same provider keeps them.
        if (service.id != selectedId) {
            selectedId = service.id
            actions.onboardingClearKeyState()
        }
    }

    when (state.step) {
        2 -> OnboardingSignupStep(
            service = Service.fromId(state.serviceId ?: selectedId) ?: Service.PlusVibe,
            actions = actions,
            modifier = modifier,
        )

        3, 4 -> OnboardingKeyStep(
            service = Service.fromId(state.serviceId ?: selectedId) ?: Service.PlusVibe,
            state = state,
            actions = actions,
            modifier = modifier,
        )

        else -> OnboardingProviderStep(
            selectedId = selectedId,
            isChecking = state.isChecking,
            onPick = ::pick,
            onNext = { actions.onboardingGoToStep(2, selectedId) },
            modifier = modifier,
        )
    }
}

/** Step 1: the provider cards and the «Далее» button below them. */
@Composable
private fun OnboardingProviderStep(
    selectedId: String,
    isChecking: Boolean,
    onPick: (Service) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.OnboardChip,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(Res.string.onboarding_provider_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = Service.onboardingServices.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Service.onboardingServices.forEach { service ->
            OnboardingProviderCard(
                service = service,
                selected = service.id == selectedId,
                enabled = !isChecking,
                onClick = { onPick(service) },
            )
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().handCursor(),
            shape = PickerShape,
        ) {
            Text(stringResource(Res.string.onboarding_button_next))
        }
    }
}

@Composable
private fun OnboardingProviderCard(
    service: Service,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(PickerShape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.03f)
                },
            )
            .border(
                width = 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
                },
                shape = PickerShape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = if (selected) 0.08f else 0.05f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = providerIcon(service),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = service.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (service == Service.PlusVibe) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(GuideSuccessColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.onboarding_provider_default),
                            style = MaterialTheme.typography.labelSmall,
                            color = GuideSuccessColor,
                        )
                    }
                }
            }
            Text(
                text = stringResource(providerSubtitle(service)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        // Referral link — the same one as Settings → Services. Clicking it must not
        // change the selection, so it is its own button, not part of the card click.
        service.apiKeyUrl?.let { url ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = { uriHandler.openUri(url) })
                    .handCursor(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = url,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        val ringColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f)
        Box(
            modifier = Modifier
                .size(20.dp)
                .drawBehind {
                    drawCircle(color = ringColor, style = Stroke(width = 1.5.dp.toPx()))
                    if (selected) {
                        drawCircle(color = ringColor, radius = size.minDimension / 2f - 3.dp.toPx())
                    }
                },
        )
    }
}

/** Step 2: the per-provider guide, the sign-up / cabinet buttons and «Далее» to the key field. */
@Composable
private fun OnboardingSignupStep(
    service: Service,
    actions: ChatActions,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.OnboardKey,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(Res.string.onboarding_guide_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PickerShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.03f))
                .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f), PickerShape)
                .padding(horizontal = 14.dp)
                .padding(top = 4.dp, bottom = 12.dp),
        ) {
            providerGuideSteps(service).forEachIndexed { index, descRes ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                    )
                }
                GuideStepRow(number = index + 1, descRes = descRes)
            }
        }

        OnboardingVideoPlaceholder()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { service.apiKeyUrl?.let { uriHandler.openUri(it) } },
                modifier = Modifier.weight(1f).handCursor(),
                shape = PickerShape,
            ) {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.onboarding_button_signup), maxLines = 1)
            }
            service.cabinetApiKeysUrl?.let { url ->
                OutlinedButton(
                    onClick = { uriHandler.openUri(url) },
                    modifier = Modifier.weight(1f).handCursor(),
                    shape = PickerShape,
                ) {
                    Icon(
                        imageVector = Icons.Filled.OnboardKey,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.onboarding_button_cabinet), maxLines = 1)
                }
            }
        }

        Button(
            onClick = { actions.onboardingGoToStep(3, service.id) },
            modifier = Modifier.fillMaxWidth().handCursor(),
            shape = PickerShape,
        ) {
            Text(stringResource(Res.string.onboarding_button_next))
        }
    }
}

/**
 * Step 3: the API-key field (the key saves automatically once the draft settles) and, after a
 * successful save, the model dropdown with the onboarding default, the balance and «Готово».
 */
@Composable
private fun OnboardingKeyStep(
    service: Service,
    state: OnboardingState,
    actions: ChatActions,
    modifier: Modifier = Modifier,
) {
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }

    // The key saves immediately after the paste (03-key-states rule 4): when the draft stops
    // changing for a moment, the same add-service flow as Settings → Services runs. A stale
    // draft never retries on its own — the next check fires only after the user edits the field.
    LaunchedEffect(keyDraft, service.id) {
        if (keyDraft.isNotBlank() && !state.isKeyAdded) {
            delay(KEY_AUTOSAVE_DELAY_MS)
            actions.onboardingAddKey(service.id, keyDraft)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.onboarding_step_key_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        OnboardingKeyField(
            service = service,
            state = state,
            actions = actions,
            keyDraft = keyDraft,
            onKeyDraftChange = { keyDraft = it },
            keyVisible = keyVisible,
            onKeyVisibleChange = { keyVisible = it },
        )

        if (state.isKeyAdded) {
            OnboardingModelGuide()
            OnboardingModelSection(state = state, actions = actions)
            state.balance?.let { balance ->
                OnboardingBalanceRow(balanceText = formatProviderBalance(balance))
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = actions.onboardingConfirmKey,
                modifier = Modifier.fillMaxWidth().handCursor(),
                shape = PickerShape,
            ) {
                Text(stringResource(Res.string.onboarding_button_done))
            }
        }
    }
}

@Composable
private fun OnboardingKeyField(
    service: Service,
    state: OnboardingState,
    actions: ChatActions,
    keyDraft: String,
    onKeyDraftChange: (String) -> Unit,
    keyVisible: Boolean,
    onKeyVisibleChange: (Boolean) -> Unit,
) {
    // The five field states from 03-key-states: default, checking (primary), invalid
    // (error), network (warn), added (success). The message lives under the field and
    // never moves the field or the buttons below it.
    val accent: Color? = when {
        state.isKeyAdded -> GuideSuccessColor

        state.isChecking -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)

        state.error == Res.string.onboarding_error_invalid_key || state.error == Res.string.onboarding_error_no_balance ->
            MaterialTheme.colorScheme.error

        state.error == Res.string.onboarding_error_network -> GuideWarnColor

        else -> null
    }

    OutlinedTextField(
        value = keyDraft,
        onValueChange = onKeyDraftChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isChecking && !state.isKeyAdded,
        singleLine = true,
        placeholder = {
            Text(
                text = stringResource(keyHint(service)),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.OnboardKey,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            IconButton(
                onClick = { onKeyVisibleChange(!keyVisible) },
                modifier = Modifier.handCursor(),
            ) {
                Icon(
                    imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = null,
                )
            }
        },
        // The key is masked by default so screenshots and screen sharing never leak it.
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        shape = PickerShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent ?: MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = accent ?: MaterialTheme.colorScheme.outline,
            disabledBorderColor = accent ?: MaterialTheme.colorScheme.outline,
        ),
    )

    when {
        state.isChecking -> Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.onboarding_checking_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.isKeyAdded -> GuideMessage(
            icon = Icons.Filled.OnboardRoundCheck,
            color = GuideSuccessColor,
            text = stringResource(Res.string.onboarding_key_added),
        )

        state.error != null -> {
            val isNetwork = state.error == Res.string.onboarding_error_network
            GuideMessage(
                icon = if (isNetwork) Icons.Filled.OnboardWifiOff else Icons.Filled.OnboardAlert,
                color = if (isNetwork) GuideWarnColor else MaterialTheme.colorScheme.error,
                text = stringResource(state.error),
            )
        }

        // No verdict yet: the field saves the key on its own, so the copy just says so.
        else -> Text(
            text = stringResource(Res.string.onboarding_key_autosave_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (state.error != null && !state.isKeyAdded) {
        TextButton(
            onClick = { actions.onboardingAddKey(service.id, keyDraft) },
            enabled = !state.isChecking && keyDraft.isNotBlank(),
            modifier = Modifier.handCursor(),
        ) {
            Text(stringResource(Res.string.onboarding_button_retry))
        }
    }
}

/** Guide D from the researcher report (t_b1dce70b §5): how a newcomer picks a model. */
@Composable
private fun OnboardingModelGuide() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PickerShape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.03f))
            .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f), PickerShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(Res.string.onboarding_model_guide_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.onboarding_model_guide_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The model dropdown fed by the provider's own catalog, loaded right after the key validated. */
@Composable
private fun OnboardingModelSection(
    state: OnboardingState,
    actions: ChatActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selected = state.modelOptions.firstOrNull { it.id == state.selectedModelId }

    Text(
        text = stringResource(Res.string.onboarding_model_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )

    if (state.isLoadingDetails && state.modelOptions.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.onboarding_models_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (state.modelOptions.isEmpty()) return

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected?.displayName ?: state.selectedModelId.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = PickerShape,
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).rotate(if (menuOpen) 180f else 0f),
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
        // The readOnly field swallows clicks, so a transparent overlay above it opens the menu.
        Box(
            modifier = Modifier.matchParentSize().clip(PickerShape).clickable(onClick = { menuOpen = true }).handCursor(),
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            state.modelOptions.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = model.displayName ?: model.id,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (model.id == state.selectedModelId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        actions.onboardingSelectModel(model.id)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun OnboardingBalanceRow(balanceText: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(Res.string.onboarding_balance_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = balanceText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = GuideSuccessColor,
        )
    }
}

@Composable
private fun GuideStepRow(number: Int, descRes: StringResource) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(descRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The video-guide placeholder: dashed outline and a dimmed play circle mark the slot;
 * the copy says the video will appear later instead of promising a player.
 */
@Composable
private fun OnboardingVideoPlaceholder() {
    val lineColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(PickerShape)
            .drawBehind {
                drawRoundRect(
                    color = lineColor,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                )
            }
            .alpha(0.55f)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .drawBehind { drawCircle(color = lineColor, style = Stroke(width = 1.dp.toPx())) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.OnboardPlay,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = LocalContentColor.current,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.onboarding_video_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(Res.string.onboarding_video_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GuideMessage(icon: ImageVector, color: Color, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

// The icon/subtitle/hint/step tables enumerate every member of Service.onboardingServices
// explicitly; a new onboarding service must add its row here (no silent LLM-Router fallback).

private fun providerIcon(service: Service): ImageVector = when (service) {
    Service.PlusVibe -> Icons.Filled.OnboardPulse
    Service.Polza -> Icons.Filled.OnboardLayers
    Service.AiTunnel -> Icons.Filled.OnboardTunnel
    Service.Gptunnel -> Icons.Filled.OnboardChip
    Service.LlmRouter -> Icons.Filled.OnboardRoute
    else -> error("not an onboarding provider: ${service.id}")
}

private fun providerSubtitle(service: Service): StringResource = when (service) {
    Service.PlusVibe -> Res.string.onboarding_sub_plusvibe
    Service.Polza -> Res.string.onboarding_sub_polza
    Service.AiTunnel -> Res.string.onboarding_sub_aitunnel
    Service.Gptunnel -> Res.string.onboarding_sub_gptunnel
    Service.LlmRouter -> Res.string.onboarding_sub_llmrouter
    else -> error("not an onboarding provider: ${service.id}")
}

private fun keyHint(service: Service): StringResource = when (service) {
    Service.PlusVibe -> Res.string.onboarding_key_hint_plusvibe
    Service.Polza, Service.Gptunnel -> Res.string.onboarding_key_hint_generic
    Service.AiTunnel -> Res.string.onboarding_key_hint_aitunnel
    Service.LlmRouter -> Res.string.onboarding_key_hint_llmrouter
    else -> error("not an onboarding provider: ${service.id}")
}

private fun providerGuideSteps(service: Service): List<StringResource> = when (service) {
    Service.PlusVibe -> listOf(
        Res.string.onboarding_desc_plusvibe_1,
        Res.string.onboarding_desc_plusvibe_2,
        Res.string.onboarding_desc_plusvibe_3,
    )

    Service.Polza -> listOf(
        Res.string.onboarding_desc_polza_1,
        Res.string.onboarding_desc_polza_2,
        Res.string.onboarding_desc_polza_3,
    )

    Service.AiTunnel -> listOf(
        Res.string.onboarding_desc_aitunnel_1,
        Res.string.onboarding_desc_aitunnel_2,
        Res.string.onboarding_desc_aitunnel_3,
    )

    Service.Gptunnel -> listOf(
        Res.string.onboarding_desc_gptunnel_1,
        Res.string.onboarding_desc_gptunnel_2,
        Res.string.onboarding_desc_gptunnel_3,
    )

    Service.LlmRouter -> listOf(
        Res.string.onboarding_desc_llmrouter_1,
        Res.string.onboarding_desc_llmrouter_2,
        Res.string.onboarding_desc_llmrouter_3,
    )

    else -> error("not an onboarding provider: ${service.id}")
}
