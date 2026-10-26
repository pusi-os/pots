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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.pusios.pots.ui.components.PotsSlider
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.potsik.PotsikAvatar
import com.pusios.pots.ui.potsik.PotsikClothColor
import com.pusios.pots.ui.potsik.PotsikLook
import com.pusios.pots.ui.potsik.PotsikOutfit
import com.pusios.pots.ui.potsik.PotsikPhase
import com.pusios.pots.ui.potsik.PotsikPlacementMode
import com.pusios.pots.ui.potsik.PotsikShadeTint
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
import pots.composeapp.generated.resources.potsiki_lashes
import pots.composeapp.generated.resources.potsiki_lashes_description
import pots.composeapp.generated.resources.potsiki_outfit
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
import pots.composeapp.generated.resources.potsiki_size
import pots.composeapp.generated.resources.potsiki_size_multiplier
import pots.composeapp.generated.resources.potsiki_tempo
import pots.composeapp.generated.resources.potsiki_tempo_description
import pots.composeapp.generated.resources.potsiki_title
import kotlin.math.roundToInt

/**
 * The "Потсики" settings tab: everything about how the animated character
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
                    PotsikAvatar(
                        look = look,
                        phase = PotsikPhase.IDLE,
                        size = 170.dp,
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

                    ToggleableHeadline(
                        title = stringResource(Res.string.potsiki_glasses),
                        description = stringResource(Res.string.potsiki_glasses_description),
                        checked = look.glassesOn,
                        onCheckedChange = { actions.onChangePotsikLook(look.copy(glassesOn = it)) },
                    )

                    ToggleableHeadline(
                        title = stringResource(Res.string.potsiki_lashes),
                        description = stringResource(Res.string.potsiki_lashes_description),
                        checked = look.lashesOn,
                        onCheckedChange = { actions.onChangePotsikLook(look.copy(lashesOn = it)) },
                    )

                    PotsikSizeRow(
                        sizeScale = look.sizeScale,
                        onChangeSizeScale = { actions.onChangePotsikLook(look.copy(sizeScale = it)) },
                    )

                    PotsikPercentRow(
                        title = stringResource(Res.string.potsiki_size),
                        percent = look.sizePercent,
                        range = 50..150,
                        onChange = { actions.onChangePotsikLook(look.copy(sizePercent = it)) },
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

/** The size slider, snapped to the same 50 % – 200 % range the UI scale uses. */
@Composable
private fun PotsikSizeRow(
    sizeScale: Float,
    onChangeSizeScale: (Float) -> Unit,
) {
    var sliderValue by remember(sizeScale) { mutableStateOf(sizeScale) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.potsiki_size_multiplier),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "${(sliderValue * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        PotsSlider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onChangeSizeScale(sliderValue) },
            valueRange = 0.6f..1.6f,
            steps = 9,
        )
    }
}

/**
 * The costume's name, in the app's own language.
 *
 * The names are the firmware's — `OUTFITS[].name` and `OUTFIT_RU[]` — so the
 * settings screen reads the same in both languages as the firmware's own
 * appearance page does.
 */
private fun outfitLabel(outfit: PotsikOutfit): String = outfit.name(Locale.current.language)

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
