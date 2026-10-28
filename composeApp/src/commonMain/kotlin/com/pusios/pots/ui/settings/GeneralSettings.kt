package com.pusios.pots.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import com.pusios.pots.LocalAppLanguageCode
import com.pusios.pots.data.AppLanguage
import com.pusios.pots.data.ThemeMode
import com.pusios.pots.ui.PotsOutlinedTextField
import com.pusios.pots.ui.background.AnimatedBackground
import com.pusios.pots.ui.components.PotsSlider
import com.pusios.pots.ui.handCursor
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.ic_arrow_drop_down
import pots.composeapp.generated.resources.settings_animated_background
import pots.composeapp.generated.resources.settings_animated_background_description
import pots.composeapp.generated.resources.settings_daemon_mode
import pots.composeapp.generated.resources.settings_daemon_mode_description
import pots.composeapp.generated.resources.settings_dynamic_ui
import pots.composeapp.generated.resources.settings_dynamic_ui_description
import pots.composeapp.generated.resources.settings_language
import pots.composeapp.generated.resources.settings_language_en
import pots.composeapp.generated.resources.settings_language_ru
import pots.composeapp.generated.resources.settings_language_system
import pots.composeapp.generated.resources.settings_theme
import pots.composeapp.generated.resources.settings_theme_dark
import pots.composeapp.generated.resources.settings_theme_description
import pots.composeapp.generated.resources.settings_theme_light
import pots.composeapp.generated.resources.settings_theme_oled
import pots.composeapp.generated.resources.settings_theme_system
import pots.composeapp.generated.resources.settings_ui_scale
import kotlin.math.roundToInt

@Composable
internal fun GeneralContent(uiState: SettingsUiState, actions: SettingsActions) {
    StaggeredSettingsColumns(
        start = {
            if (uiState.showDaemonToggle) {
                SettingsCard {
                    DaemonModeToggle(
                        isDaemonEnabled = uiState.isDaemonEnabled,
                        onToggleDaemon = actions.onToggleDaemon,
                    )
                }
            }
            SettingsCard {
                DynamicUiToggle(
                    isDynamicUiEnabled = uiState.isDynamicUiEnabled,
                    onToggleDynamicUi = actions.onToggleDynamicUi,
                )
            }
            SettingsCard {
                AppLanguagePicker(
                    appLanguage = uiState.appLanguage,
                    onChangeAppLanguage = actions.onChangeAppLanguage,
                )
            }
            SettingsCard {
                ThemeModePicker(
                    themeMode = uiState.themeMode,
                    onChangeThemeMode = actions.onChangeThemeMode,
                )
            }
            SettingsCard {
                AnimatedBackgroundSection(
                    kind = uiState.animatedBackground,
                    onChangeKind = actions.onChangeAnimatedBackground,
                )
            }
        },
        end = {
            if (uiState.showUiScale) {
                SettingsCard {
                    UiScaleSection(
                        uiScale = uiState.uiScale,
                        onChangeUiScale = actions.onChangeUiScale,
                    )
                }
            }
            SettingsCard {
                ExportImportSection(
                    onExportSettings = actions.onExportSettings,
                    onPrepareExport = actions.onPrepareExport,
                    onImportSettings = actions.onImportSettings,
                )
            }
        },
    )
}

@Composable
private fun DaemonModeToggle(
    isDaemonEnabled: Boolean,
    onToggleDaemon: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ToggleableHeadline(
            title = stringResource(Res.string.settings_daemon_mode),
            description = stringResource(Res.string.settings_daemon_mode_description),
            checked = isDaemonEnabled,
            onCheckedChange = onToggleDaemon,
        )
    }
}

@Composable
private fun DynamicUiToggle(
    isDynamicUiEnabled: Boolean,
    onToggleDynamicUi: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ToggleableHeadline(
            title = stringResource(Res.string.settings_dynamic_ui),
            description = stringResource(Res.string.settings_dynamic_ui_description),
            checked = isDynamicUiEnabled,
            onCheckedChange = onToggleDynamicUi,
        )
    }
}

@Composable
private fun AppLanguagePicker(
    appLanguage: AppLanguage,
    onChangeAppLanguage: (AppLanguage) -> Unit,
) {
    val options = listOf(
        AppLanguage.System to stringResource(Res.string.settings_language_system),
        AppLanguage.Russian to stringResource(Res.string.settings_language_ru),
        AppLanguage.English to stringResource(Res.string.settings_language_en),
    )
    val selectedLabel = options.first { it.first == appLanguage }.second
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.settings_language),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            PotsOutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    Icon(
                        modifier = Modifier.handCursor(),
                        imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                },
            )
            Box(
                modifier = Modifier.matchParentSize().handCursor().clickable { expanded = true },
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(16.dp),
            ) {
                options.forEach { (lang, label) ->
                    val isSelected = lang == appLanguage
                    DropdownMenuItem(
                        text = { Text(text = label, style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            expanded = false
                            onChangeAppLanguage(lang)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeModePicker(
    themeMode: ThemeMode,
    onChangeThemeMode: (ThemeMode) -> Unit,
) {
    val options = listOf(
        ThemeMode.System to stringResource(Res.string.settings_theme_system),
        ThemeMode.Light to stringResource(Res.string.settings_theme_light),
        ThemeMode.Dark to stringResource(Res.string.settings_theme_dark),
        ThemeMode.OledBlack to stringResource(Res.string.settings_theme_oled),
    )
    val selectedLabel = options.first { it.first == themeMode }.second
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.settings_theme),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(Res.string.settings_theme_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            PotsOutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    Icon(
                        modifier = Modifier.handCursor(),
                        imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                },
            )
            // Transparent overlay to capture clicks reliably on all platforms
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .handCursor()
                    .clickable { expanded = true },
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(16.dp),
            ) {
                options.forEach { (mode, label) ->
                    val isSelected = mode == themeMode
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        onClick = {
                            expanded = false
                            onChangeThemeMode(mode)
                        },
                        modifier = Modifier
                            .handCursor()
                            .then(
                                if (isSelected) {
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
}

@Composable
private fun UiScaleSection(
    uiScale: Float,
    onChangeUiScale: (Float) -> Unit,
) {
    var sliderValue by remember(uiScale) { mutableStateOf(uiScale) }
    val steps = 14 // 16 snap points from 50% to 200% in 10% increments (14 intermediate)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.settings_ui_scale),
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
            onValueChangeFinished = { onChangeUiScale(sliderValue) },
            valueRange = 0.5f..2.0f,
            steps = steps,
        )
    }
}

/**
 * The animated background: a switch for whether there is one, and a picker for
 * which of the firmware's backgrounds it is. Switching it on picks the first
 * one; switching it off remembers nothing, so it comes back on the next time.
 */
@Composable
private fun AnimatedBackgroundSection(
    kind: AnimatedBackground,
    onChangeKind: (AnimatedBackground) -> Unit,
) {
    val enabled = kind != AnimatedBackground.NONE
    val language = LocalAppLanguageCode.current
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        ToggleableHeadline(
            title = stringResource(Res.string.settings_animated_background),
            description = stringResource(Res.string.settings_animated_background_description),
            checked = enabled,
            onCheckedChange = { on ->
                onChangeKind(if (on) AnimatedBackground.DIGITAL_RAIN else AnimatedBackground.NONE)
            },
        )
        if (enabled) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true }
                        .handCursor()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = kind.name(language),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                        contentDescription = null,
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AnimatedBackground.entries.filter { it != AnimatedBackground.NONE }.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.name(language)) },
                            onClick = {
                                onChangeKind(option)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}
