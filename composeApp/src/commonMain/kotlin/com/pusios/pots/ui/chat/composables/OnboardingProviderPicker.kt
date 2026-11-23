package com.pusios.pots.ui.chat.composables

// The onboarding provider picker (docs/design/onboarding/01..03): a radio list of the
// full-functionality providers, an expandable "how to sign up and get a key" section
// with per-provider steps and a video placeholder, and the API-key field whose
// «Проверить и добавить» runs the same add-service flow as Settings → Services.

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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.onboarding_button_added
import pots.composeapp.generated.resources.onboarding_button_check_and_add
import pots.composeapp.generated.resources.onboarding_button_checking
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
import pots.composeapp.generated.resources.onboarding_error_empty_key
import pots.composeapp.generated.resources.onboarding_error_invalid_key
import pots.composeapp.generated.resources.onboarding_error_network
import pots.composeapp.generated.resources.onboarding_error_no_balance
import pots.composeapp.generated.resources.onboarding_guide_step_key
import pots.composeapp.generated.resources.onboarding_guide_step_register
import pots.composeapp.generated.resources.onboarding_guide_step_topup
import pots.composeapp.generated.resources.onboarding_guide_title
import pots.composeapp.generated.resources.onboarding_key_added
import pots.composeapp.generated.resources.onboarding_key_hint_aitunnel
import pots.composeapp.generated.resources.onboarding_key_hint_generic
import pots.composeapp.generated.resources.onboarding_key_hint_llmrouter
import pots.composeapp.generated.resources.onboarding_key_hint_plusvibe
import pots.composeapp.generated.resources.onboarding_provider_default
import pots.composeapp.generated.resources.onboarding_provider_section
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

/**
 * Screen 1 of the onboarding: PlusVibe preselected first, the other full-functionality
 * providers as radio cards, the guide section below. Selection is local to the screen;
 * the check result (checking / errors / added) comes from [OnboardingState].
 */
@Composable
internal fun OnboardingProviderPicker(
    state: OnboardingState,
    actions: ChatActions,
    modifier: Modifier = Modifier,
) {
    var selectedId by rememberSaveable { mutableStateOf(Service.PlusVibe.id) }
    var guideOpen by rememberSaveable { mutableStateOf(state.isGuideOpen) }
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }

    fun pick(service: Service) {
        // Switching cards closes the guide and clears the draft — the key belongs to its
        // provider and must not travel to another one (03-key-states §3). Reopening the
        // same provider keeps the unsent draft.
        if (service.id != selectedId) {
            selectedId = service.id
            guideOpen = false
            keyDraft = ""
            keyVisible = false
        }
    }

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
                enabled = !state.isChecking,
                onClick = { pick(service) },
            )
        }

        val selected = Service.fromId(selectedId) ?: Service.PlusVibe
        OnboardingGuideSection(
            service = selected,
            state = state,
            actions = actions,
            open = guideOpen,
            onToggle = { guideOpen = !guideOpen },
            keyDraft = keyDraft,
            onKeyDraftChange = { keyDraft = it },
            keyVisible = keyVisible,
            onKeyVisibleChange = { keyVisible = it },
        )
    }
}

@Composable
private fun OnboardingProviderCard(
    service: Service,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
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

@Composable
private fun OnboardingGuideSection(
    service: Service,
    state: OnboardingState,
    actions: ChatActions,
    open: Boolean,
    onToggle: () -> Unit,
    keyDraft: String,
    onKeyDraftChange: (String) -> Unit,
    keyVisible: Boolean,
    onKeyVisibleChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PickerShape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.03f))
            .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f), PickerShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 12.dp)) {
                guideSteps(service).forEachIndexed { index, (titleRes, descRes) ->
                    if (index > 0) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                        )
                    }
                    GuideStepRow(number = index + 1, titleRes = titleRes, descRes = descRes)
                }
                Spacer(Modifier.height(12.dp))
                OnboardingVideoPlaceholder()
                Spacer(Modifier.height(12.dp))
                OnboardingKeyField(
                    service = service,
                    state = state,
                    actions = actions,
                    keyDraft = keyDraft,
                    onKeyDraftChange = onKeyDraftChange,
                    keyVisible = keyVisible,
                    onKeyVisibleChange = onKeyVisibleChange,
                )
            }
        }
    }
}

@Composable
private fun GuideStepRow(number: Int, titleRes: StringResource, descRes: StringResource) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    // never moves the field or the button.
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
    }

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { actions.onboardingAddKey(service.id, keyDraft) },
        enabled = !state.isChecking && !state.isKeyAdded,
        modifier = Modifier.fillMaxWidth().handCursor(),
        shape = PickerShape,
    ) {
        if (state.isChecking) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.onboarding_button_checking))
        } else {
            Text(
                stringResource(
                    if (state.isKeyAdded) Res.string.onboarding_button_added else Res.string.onboarding_button_check_and_add,
                ),
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

private fun providerIcon(service: Service): ImageVector = when (service) {
    Service.PlusVibe -> Icons.Filled.OnboardPulse
    Service.Polza -> Icons.Filled.OnboardLayers
    Service.AiTunnel -> Icons.Filled.OnboardTunnel
    Service.Gptunnel -> Icons.Filled.OnboardChip
    else -> Icons.Filled.OnboardRoute
}

// The picker only renders Service.onboardingServices, so the `else` branches below
// are unreachable in practice.

private fun providerSubtitle(service: Service): StringResource = when (service) {
    Service.PlusVibe -> Res.string.onboarding_sub_plusvibe
    Service.Polza -> Res.string.onboarding_sub_polza
    Service.AiTunnel -> Res.string.onboarding_sub_aitunnel
    Service.Gptunnel -> Res.string.onboarding_sub_gptunnel
    else -> Res.string.onboarding_sub_llmrouter
}

private fun keyHint(service: Service): StringResource = when (service) {
    Service.PlusVibe -> Res.string.onboarding_key_hint_plusvibe
    Service.AiTunnel -> Res.string.onboarding_key_hint_aitunnel
    Service.LlmRouter -> Res.string.onboarding_key_hint_llmrouter
    else -> Res.string.onboarding_key_hint_generic
}

private fun guideSteps(service: Service): List<Pair<StringResource, StringResource>> = when (service) {
    Service.PlusVibe -> listOf(
        Res.string.onboarding_guide_step_register to Res.string.onboarding_desc_plusvibe_1,
        Res.string.onboarding_guide_step_key to Res.string.onboarding_desc_plusvibe_2,
        Res.string.onboarding_guide_step_topup to Res.string.onboarding_desc_plusvibe_3,
    )

    Service.Polza -> listOf(
        Res.string.onboarding_guide_step_register to Res.string.onboarding_desc_polza_1,
        Res.string.onboarding_guide_step_key to Res.string.onboarding_desc_polza_2,
        Res.string.onboarding_guide_step_topup to Res.string.onboarding_desc_polza_3,
    )

    Service.AiTunnel -> listOf(
        Res.string.onboarding_guide_step_register to Res.string.onboarding_desc_aitunnel_1,
        Res.string.onboarding_guide_step_key to Res.string.onboarding_desc_aitunnel_2,
        Res.string.onboarding_guide_step_topup to Res.string.onboarding_desc_aitunnel_3,
    )

    Service.Gptunnel -> listOf(
        Res.string.onboarding_guide_step_register to Res.string.onboarding_desc_gptunnel_1,
        Res.string.onboarding_guide_step_key to Res.string.onboarding_desc_gptunnel_2,
        Res.string.onboarding_guide_step_topup to Res.string.onboarding_desc_gptunnel_3,
    )

    else -> listOf(
        Res.string.onboarding_guide_step_register to Res.string.onboarding_desc_llmrouter_1,
        Res.string.onboarding_guide_step_key to Res.string.onboarding_desc_llmrouter_2,
        Res.string.onboarding_guide_step_topup to Res.string.onboarding_desc_llmrouter_3,
    )
}
