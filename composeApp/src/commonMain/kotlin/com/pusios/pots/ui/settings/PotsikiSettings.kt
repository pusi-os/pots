package com.pusios.pots.ui.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pusios.pots.LocalAppLanguageCode
import com.pusios.pots.ui.components.PotsSlider
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.potsik.PotsikAction
import com.pusios.pots.ui.potsik.PotsikAvatar
import com.pusios.pots.ui.potsik.PotsikClothColor
import com.pusios.pots.ui.potsik.PotsikLook
import com.pusios.pots.ui.potsik.PotsikOutfit
import com.pusios.pots.ui.potsik.PotsikPhase
import com.pusios.pots.ui.potsik.PotsikPlacementMode
import com.pusios.pots.ui.potsik.PotsikShadeTint
import com.pusios.pots.ui.potsik.canDrawDesktopPetOverlay
import com.pusios.pots.ui.potsik.desktopPetAvailability
import com.pusios.pots.ui.potsik.desktopPetRow
import com.pusios.pots.ui.potsik.openDesktopPetPermissionSettings
import com.pusios.pots.ui.potsik.syncDesktopPetService
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.potsiki_banter
import pots.composeapp.generated.resources.potsiki_banter_description
import pots.composeapp.generated.resources.potsiki_color_blue
import pots.composeapp.generated.resources.potsiki_color_crimson
import pots.composeapp.generated.resources.potsiki_color_indigo
import pots.composeapp.generated.resources.potsiki_color_magenta
import pots.composeapp.generated.resources.potsiki_color_orange
import pots.composeapp.generated.resources.potsiki_color_purple
import pots.composeapp.generated.resources.potsiki_color_teal
import pots.composeapp.generated.resources.potsiki_color_white
import pots.composeapp.generated.resources.potsiki_description
import pots.composeapp.generated.resources.potsiki_drag
import pots.composeapp.generated.resources.potsiki_drag_description
import pots.composeapp.generated.resources.potsiki_glasses
import pots.composeapp.generated.resources.potsiki_glasses_description
import pots.composeapp.generated.resources.potsiki_glasses_hood
import pots.composeapp.generated.resources.potsiki_lashes
import pots.composeapp.generated.resources.potsiki_lashes_description
import pots.composeapp.generated.resources.potsiki_outfit
import pots.composeapp.generated.resources.potsiki_overlay
import pots.composeapp.generated.resources.potsiki_overlay_description
import pots.composeapp.generated.resources.potsiki_overlay_needs_newer
import pots.composeapp.generated.resources.potsiki_overlay_permission_button
import pots.composeapp.generated.resources.potsiki_overlay_permission_needed
import pots.composeapp.generated.resources.potsiki_placement
import pots.composeapp.generated.resources.potsiki_placement_persistent
import pots.composeapp.generated.resources.potsiki_placement_session
import pots.composeapp.generated.resources.potsiki_placement_system
import pots.composeapp.generated.resources.potsiki_reset
import pots.composeapp.generated.resources.potsiki_reset_description
import pots.composeapp.generated.resources.potsiki_shade_cyan
import pots.composeapp.generated.resources.potsiki_shade_green
import pots.composeapp.generated.resources.potsiki_shade_pink
import pots.composeapp.generated.resources.potsiki_shade_purple
import pots.composeapp.generated.resources.potsiki_shades
import pots.composeapp.generated.resources.potsiki_shirt
import pots.composeapp.generated.resources.potsiki_shoes
import pots.composeapp.generated.resources.potsiki_shorts
import pots.composeapp.generated.resources.potsiki_show
import pots.composeapp.generated.resources.potsiki_show_description
import pots.composeapp.generated.resources.potsiki_size_pinch_hint
import pots.composeapp.generated.resources.potsiki_tempo
import pots.composeapp.generated.resources.potsiki_tempo_description
import pots.composeapp.generated.resources.potsiki_title
import kotlin.math.roundToInt

/**
 * The "Потсик" settings tab: everything about how the animated character
 * looks.
 *
 * The character is previewed live at the top, so every change is visible
 * straight away — the same way the PotSquad firmware's APPEARANCE page keeps a
 * live Потсик behind the outfit picker.
 */
@Composable
internal fun PotsikiContent(uiState: SettingsUiState, actions: SettingsActions) {
    val look = uiState.potsikLook

    StaggeredSettingsColumns(
        start = {
            SettingsCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(Res.string.potsiki_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.potsiki_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    // The preview moves. A still frame of the character is
                    // what the owner complained about, so the avatar plays a
                    // catalogue animation and rolls on to the next one: the
                    // costume is seen in motion, not frozen mid-breath.
                    var previewTick by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(PREVIEW_STEP_MS)
                            previewTick++
                        }
                    }
                    PotsikAvatar(
                        look = look,
                        phase = PotsikPhase.IDLE,
                        size = 170.dp,
                        animationOverride = PREVIEW_ACTIONS[previewTick % PREVIEW_ACTIONS.size],
                        animationKey = previewTick,
                    )
                }
            }
        },
        end = {
            SettingsCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ToggleableHeadline(
                        title = stringResource(Res.string.potsiki_show),
                        description = stringResource(Res.string.potsiki_show_description),
                        checked = look.isVisible,
                        onCheckedChange = { actions.onChangePotsikLook(look.copy(isVisible = it)) },
                    )

                    // The desktop pet (Android only): a system overlay window
                    // keeps the character on the desktop while the app is
                    // minimized, and opening the app brings him back inside —
                    // never both at once. Where the platform has no such
                    // window, there is no row either; where the Android is too
                    // old for it, the row is there but dead, with the reason.
                    PotsikOverlayRow(look = look, actions = actions)

                    PotsikOptionRow(
                        title = stringResource(Res.string.potsiki_outfit),
                        valueLabel = outfitLabel(look.outfit),
                        swatch = null,
                        options = PotsikOutfit.entries.map { outfitLabel(it) to it },
                        onSelect = { actions.onChangePotsikLook(look.copy(outfit = it)) },
                    )

                    PotsikOptionRow(
                        title = stringResource(Res.string.potsiki_shirt),
                        valueLabel = clothLabel(look.shirtColor),
                        swatch = look.shirtColor.color,
                        options = PotsikClothColor.entries.map { clothLabel(it) to it },
                        onSelect = { actions.onChangePotsikLook(look.copy(shirtColor = it)) },
                    )

                    PotsikOptionRow(
                        title = stringResource(Res.string.potsiki_shorts),
                        valueLabel = clothLabel(look.shortsColor),
                        swatch = look.shortsColor.color,
                        options = PotsikClothColor.entries.map { clothLabel(it) to it },
                        onSelect = { actions.onChangePotsikLook(look.copy(shortsColor = it)) },
                    )

                    PotsikOptionRow(
                        title = stringResource(Res.string.potsiki_shoes),
                        valueLabel = clothLabel(look.shoesColor),
                        swatch = look.shoesColor.color,
                        options = PotsikClothColor.entries.map { clothLabel(it) to it },
                        onSelect = { actions.onChangePotsikLook(look.copy(shoesColor = it)) },
                    )

                    PotsikOptionRow(
                        title = stringResource(Res.string.potsiki_shades),
                        valueLabel = shadeLabel(look.shadeTint),
                        swatch = look.shadeTint.color,
                        options = PotsikShadeTint.entries.map { shadeLabel(it) to it },
                        onSelect = { actions.onChangePotsikLook(look.copy(shadeTint = it)) },
                    )

                    // The parka's hood hides everything but the eyes, so the
                    // shades cannot be worn with it — the switch stays off and
                    // disabled while Kenny is on.
                    val glassesAvailable = look.outfit != PotsikOutfit.PARKA
                    ToggleableHeadline(
                        title = stringResource(Res.string.potsiki_glasses),
                        description = if (glassesAvailable) {
                            stringResource(Res.string.potsiki_glasses_description)
                        } else {
                            stringResource(Res.string.potsiki_glasses_hood)
                        },
                        checked = glassesAvailable && look.glassesOn,
                        onCheckedChange = { actions.onChangePotsikLook(look.copy(glassesOn = it)) },
                        enabled = glassesAvailable,
                    )

                    ToggleableHeadline(
                        title = stringResource(Res.string.potsiki_lashes),
                        description = stringResource(Res.string.potsiki_lashes_description),
                        checked = look.lashesOn,
                        onCheckedChange = { actions.onChangePotsikLook(look.copy(lashesOn = it)) },
                    )

                    // One size, and no slider for it: it is set with two
                    // fingers on the character itself, in the app and on the
                    // desktop alike. The row is a hint, not a control.
                    Text(
                        text = stringResource(Res.string.potsiki_size_pinch_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    PotsikPercentRow(
                        title = stringResource(Res.string.potsiki_tempo),
                        percent = look.tempoPercent,
                        range = 50..200,
                        onChange = { actions.onChangePotsikLook(look.copy(tempoPercent = it)) },
                    )

                    ToggleableHeadline(
                        title = stringResource(Res.string.potsiki_banter),
                        description = stringResource(Res.string.potsiki_banter_description),
                        checked = look.banterEnabled,
                        onCheckedChange = { actions.onChangePotsikLook(look.copy(banterEnabled = it)) },
                    )

                    ToggleableHeadline(
                        title = stringResource(Res.string.potsiki_drag),
                        description = stringResource(Res.string.potsiki_drag_description),
                        checked = look.draggable,
                        onCheckedChange = { actions.onChangePotsikLook(look.copy(draggable = it)) },
                    )

                    PotsikOptionRow(
                        title = stringResource(Res.string.potsiki_placement),
                        valueLabel = placementLabel(look.placementMode),
                        swatch = null,
                        options = PotsikPlacementMode.entries.map { placementLabel(it) to it },
                        onSelect = { actions.onChangePotsikLook(look.copy(placementMode = it)) },
                    )

                    Text(
                        text = stringResource(Res.string.potsiki_reset),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .handCursor()
                            .clickable { actions.onResetPotsikLook() },
                    )
                    Text(
                        text = stringResource(Res.string.potsiki_reset_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/**
 * A row that shows the current value of a choice and opens a menu with all of
 * them. The swatch is the colour of the option, when the option is a colour.
 */
@Composable
private fun <T> PotsikOptionRow(
    title: String,
    valueLabel: String,
    swatch: Color?,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .handCursor()
                    .clickable { expanded = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (swatch != null) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(swatch)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(16.dp),
        ) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (label == valueLabel) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                    modifier = Modifier
                        .handCursor()
                        .then(
                            if (label == valueLabel) {
                                Modifier
                                    .padding(horizontal = 4.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
    }
}

/**
 * The «Поверх окон» row, in all three states the platform can be in.
 *
 * Hidden where there is no such window at all (iOS, desktop, web), shown but
 * dead on an Android older than the mode needs — the owner's rule: a mode the
 * old system cannot do is not shimmed, the switch is disabled and the text
 * names the version — and live everywhere else.
 *
 * The switch shows the *effective* state, not the saved wish: with the mode
 * saved on but the permission missing it reads off, and the note under it says
 * what to do. A switch that claimed to be on would promise a character
 * standing on the desktop when there is none.
 *
 * [LifecycleResumeEffect] is the whole point of this row's shape: the user
 * leaves for the system's permission screen, grants the permission, comes
 * back — and the character has to appear without touching the switch again.
 * ON_RESUME re-reads the permission and reconciles the service; the same hook
 * catches a permission revoked in the system while the mode was on, which
 * takes the window down and turns the switch off.
 */
@Composable
private fun PotsikOverlayRow(
    look: PotsikLook,
    actions: SettingsActions,
) {
    val availability = remember { desktopPetAvailability() }
    var permissionGranted by remember { mutableStateOf(canDrawDesktopPetOverlay()) }
    LifecycleResumeEffect(Unit) {
        permissionGranted = canDrawDesktopPetOverlay()
        syncDesktopPetService()
        onPauseOrDispose { }
    }

    val row = desktopPetRow(
        availability = availability,
        savedEnabled = look.overlayEnabled,
        permissionGranted = permissionGranted,
    )
    if (!row.visible) return

    ToggleableHeadline(
        title = stringResource(Res.string.potsiki_overlay),
        description = if (row.minimumOsLabel != null) {
            stringResource(Res.string.potsiki_overlay_needs_newer, row.minimumOsLabel)
        } else {
            stringResource(Res.string.potsiki_overlay_description)
        },
        checked = row.checked,
        // The wish is saved even without the permission: that is what lets
        // the return from the system screen apply it on its own. Until the
        // permission is there the switch stays off and the note explains.
        onCheckedChange = { enabled -> actions.onChangePotsikLook(look.copy(overlayEnabled = enabled)) },
        enabled = row.enabled,
    )

    if (row.enabled && look.overlayEnabled && !permissionGranted) {
        Text(
            text = stringResource(Res.string.potsiki_overlay_permission_needed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { openDesktopPetPermissionSettings() }) {
            Text(stringResource(Res.string.potsiki_overlay_permission_button))
        }
    }
}

/** The animations the settings preview cycles through, one after another. */
private val PREVIEW_ACTIONS = listOf(
    PotsikAction.IDLE_WAVE,
    PotsikAction.IDLE_HANDS_UP,
    PotsikAction.IDLE_DANCE,
)

/** How long one of them runs before the next takes over (the avatar plays each for 2.6 s). */
private const val PREVIEW_STEP_MS = 2_800L

/**
 * The costume's name, in the app's own language.
 *
 * The names are the firmware's — `OUTFITS[].name` and `OUTFIT_RU[]` — so the
 * settings screen reads the same in both languages as the firmware's own
 * appearance page does.
 */
@Composable
private fun outfitLabel(outfit: PotsikOutfit): String = outfit.name(LocalAppLanguageCode.current)

/**
 * A percentage row.
 *
 * The firmware's SIZE and TEMPO rows are both a percentage, so they share one
 * row here rather than having two that do the same thing.
 */
@Composable
private fun PotsikPercentRow(
    title: String,
    percent: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    var sliderValue by remember(percent) { mutableStateOf(percent.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "${sliderValue.roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        PotsSlider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onChange(sliderValue.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.count() - 2,
        )
    }
}

/** Where he is allowed to be, in the app's own words. */
@Composable
private fun placementLabel(mode: PotsikPlacementMode): String = stringResource(
    when (mode) {
        PotsikPlacementMode.SYSTEM -> Res.string.potsiki_placement_system
        PotsikPlacementMode.SESSION -> Res.string.potsiki_placement_session
        PotsikPlacementMode.PERSISTENT -> Res.string.potsiki_placement_persistent
    },
)

@Composable
private fun clothLabel(color: PotsikClothColor): String = stringResource(
    when (color) {
        PotsikClothColor.INDIGO -> Res.string.potsiki_color_indigo
        PotsikClothColor.BLUE -> Res.string.potsiki_color_blue
        PotsikClothColor.MAGENTA -> Res.string.potsiki_color_magenta
        PotsikClothColor.TEAL -> Res.string.potsiki_color_teal
        PotsikClothColor.CRIMSON -> Res.string.potsiki_color_crimson
        PotsikClothColor.PURPLE -> Res.string.potsiki_color_purple
        PotsikClothColor.ORANGE -> Res.string.potsiki_color_orange
        PotsikClothColor.WHITE -> Res.string.potsiki_color_white
    },
)

@Composable
private fun shadeLabel(tint: PotsikShadeTint): String = stringResource(
    when (tint) {
        PotsikShadeTint.CYAN -> Res.string.potsiki_shade_cyan
        PotsikShadeTint.PINK -> Res.string.potsiki_shade_pink
        PotsikShadeTint.GREEN -> Res.string.potsiki_shade_green
        PotsikShadeTint.PURPLE -> Res.string.potsiki_shade_purple
    },
)
