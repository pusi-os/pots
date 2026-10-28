@file:OptIn(ExperimentalMaterial3Api::class)

package com.pusios.pots

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.svg.SvgDecoder
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.ThemeMode
import com.pusios.pots.data.miniapp.MiniAppServer
import com.pusios.pots.data.miniapp.MiniAppServerController
import com.pusios.pots.data.miniapp.isMiniAppServerBackgroundEnabled
import com.pusios.pots.data.miniapp.isMiniAppServerEnabled
import com.pusios.pots.tools.AppPermission
import com.pusios.pots.tools.PermissionController
import com.pusios.pots.tools.SetupPermissionHandler
import com.pusios.pots.ui.DarkColorScheme
import com.pusios.pots.ui.LightColorScheme
import com.pusios.pots.ui.Theme
import com.pusios.pots.ui.background.AnimatedBackdrop
import com.pusios.pots.ui.background.screenBackground
import com.pusios.pots.ui.chat.ChatScreen
import com.pusios.pots.ui.chat.ChatViewModel
import com.pusios.pots.ui.components.FullScreenImageHost
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.rememberSandboxAwareUriHandler
import com.pusios.pots.ui.settings.SettingsScreen
import com.pusios.pots.ui.withBlackBackground
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.marc_apps.tts.TextToSpeechInstance
import nl.marc_apps.tts.experimental.ExperimentalVoiceApi
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.dsl.koinConfiguration
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.tab_chat
import pots.composeapp.generated.resources.tab_settings

@Serializable
@SerialName("home")
object Home

@Serializable
@SerialName("settings")
object Settings

val LocalAppLanguageCode = compositionLocalOf { "en" }

@Composable
fun App(
    navController: NavHostController,
    lightColorScheme: ColorScheme = LightColorScheme,
    darkColorScheme: ColorScheme = DarkColorScheme,
    textToSpeech: TextToSpeechInstance? = null,
    isKoinStarted: Boolean = false,
    onAppOpens: ((Int) -> Unit)? = null,
) {
    setSingletonImageLoaderFactory { context: PlatformContext ->
        ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory())
                add(SvgDecoder.Factory())
            }
            .build()
    }

    // Reuse global Koin if already started (Android Application class),
    // otherwise create a new instance (iOS, Desktop, Wasm).
    if (isKoinStarted) {
        AppContent(navController, lightColorScheme, darkColorScheme, textToSpeech, onAppOpens)
    } else {
        KoinApplication(
            configuration = koinConfiguration {
                modules(appModule)
            },
        ) {
            AppContent(navController, lightColorScheme, darkColorScheme, textToSpeech, onAppOpens)
        }
    }
}

@Composable
private fun AppContent(
    navController: NavHostController,
    lightColorScheme: ColorScheme,
    darkColorScheme: ColorScheme,
    textToSpeech: TextToSpeechInstance?,
    onAppOpens: ((Int) -> Unit)?,
) {
    val appSettings = koinInject<AppSettings>()
    val miniAppServer = koinInject<MiniAppServer>()
    val miniAppServerController = koinInject<MiniAppServerController>()

    // The built-in server is a user preference, not a chat feature: start it as
    // soon as the app composition exists, and ask the foreground service to keep
    // it alive when background mode is on.
    LaunchedEffect(Unit) {
        if (appSettings.isMiniAppServerEnabled()) {
            miniAppServer.start()
            if (appSettings.isMiniAppServerBackgroundEnabled()) {
                miniAppServerController.startBackground()
            }
        }
    }

    // Track app opens after Koin is initialized
    onAppOpens?.let { callback ->
        LaunchedEffect(Unit) {
            callback(appSettings.trackAppOpen())
        }
    }

    // Set up permission handlers
    AppPermission.entries.forEach { permission ->
        SetupPermissionHandler(koinInject<PermissionController>(permissionQualifier(permission)))
    }

    // The spoken language follows the app language: the saved system voice wins
    // when it can speak it, otherwise the engine's default for that language is
    // picked explicitly (the library default is the device locale, which is why
    // Russian answers were once read by an English voice). Rate and pitch apply
    // on top, every time any of the inputs changes.
    val currentAppLanguage = LocalAppLanguageCode.current
    val ttsSystemVoice by appSettings.ttsSystemVoiceFlow.collectAsStateWithLifecycle()
    val ttsRate by appSettings.ttsRateFlow.collectAsStateWithLifecycle()
    val ttsPitch by appSettings.ttsPitchFlow.collectAsStateWithLifecycle()
    val ttsPiperVoice by appSettings.ttsPiperVoiceFlow.collectAsStateWithLifecycle()
    @OptIn(ExperimentalVoiceApi::class)
    LaunchedEffect(textToSpeech, currentAppLanguage, ttsSystemVoice, ttsRate, ttsPitch) {
        val tts = textToSpeech ?: return@LaunchedEffect
        tts.rate = ttsRate
        tts.pitch = ttsPitch
        val saved = tts.voices.firstOrNull { it.name == ttsSystemVoice && ttsSystemVoice.isNotBlank() }
        val byLanguage = tts.voices.firstOrNull { it.languageTag.startsWith(currentAppLanguage) }
        val fallback = tts.voices.firstOrNull { it.isDefault }
        val chosen = saved ?: byLanguage ?: fallback ?: return@LaunchedEffect
        val current = runCatching { tts.currentVoice }.getOrNull()
        if (current?.name != chosen.name) {
            tts.currentVoice = chosen
        }
    }

    val uiScale by appSettings.uiScaleFlow.collectAsStateWithLifecycle()
    val defaultDensity = LocalDensity.current
    val scaledDensity = remember(defaultDensity, uiScale) {
        Density(defaultDensity.density * uiScale, defaultDensity.fontScale)
    }

    val themeMode by appSettings.themeModeFlow.collectAsStateWithLifecycle()
    val systemInDark = isSystemInDarkTheme()
    val effectiveColorScheme = when (themeMode) {
        ThemeMode.System -> if (systemInDark) darkColorScheme else lightColorScheme
        ThemeMode.Light -> lightColorScheme
        ThemeMode.Dark -> darkColorScheme
        ThemeMode.OledBlack -> darkColorScheme.withBlackBackground()
    }

    val appLanguage by appSettings.appLanguageFlow.collectAsStateWithLifecycle()
    val resolvedLanguageCode = when (appLanguage) {
        com.pusios.pots.data.AppLanguage.System -> Locale.current.language
        com.pusios.pots.data.AppLanguage.Russian -> "ru"
        com.pusios.pots.data.AppLanguage.English -> "en"
    }
    val isSystemDark = isSystemInDarkTheme()

    LaunchedEffect(resolvedLanguageCode) {
        applyAppLocale(resolvedLanguageCode)
    }

    val sandboxController = koinInject<SandboxController>()
    val sandboxAwareUriHandler = rememberSandboxAwareUriHandler(sandboxController)

    key(appLanguage) {
        CompositionLocalProvider(
            LocalDensity provides scaledDensity,
            LocalUriHandler provides sandboxAwareUriHandler,
            LocalAppLanguageCode provides resolvedLanguageCode,
        ) {
            Theme(colorScheme = effectiveColorScheme) {
                val animatedBackground by appSettings.animatedBackgroundFlow.collectAsStateWithLifecycle()
                val backgroundOn = animatedBackground != com.pusios.pots.ui.background.AnimatedBackground.NONE
                // The animation sits behind every screen; screens that paint an opaque
                // background opt out through LocalAnimatedBackgroundOn.
                Box(Modifier.fillMaxSize()) {
                    AnimatedBackdrop(kind = animatedBackground, modifier = Modifier)
                }
                CompositionLocalProvider(com.pusios.pots.ui.background.LocalAnimatedBackgroundOn provides backgroundOn) {
                    FullScreenImageHost {
                        val chatViewModel: ChatViewModel = koinViewModel()
                        val showTabBar = currentPlatform !is Platform.Mobile
                        val currentBackStackEntry by navController.currentBackStackEntryAsState()
                        val isHome = currentBackStackEntry?.destination?.route == "home"

                        val navigationTabBar: @Composable () -> Unit = {
                            val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                            val count = 2
                            SingleChoiceSegmentedButtonRow {
                                SegmentedButton(
                                    selected = isHome,
                                    onClick = {
                                        navController.navigate(Home) {
                                            popUpTo(Home) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = if (isRtl) count - 1 else 0, count = count),
                                    modifier = Modifier.handCursor(),
                                ) {
                                    Text(stringResource(Res.string.tab_chat))
                                }
                                SegmentedButton(
                                    selected = !isHome,
                                    onClick = {
                                        navController.navigate(Settings) {
                                            popUpTo(Home)
                                            launchSingleTop = true
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = if (isRtl) 0 else count - 1, count = count),
                                    modifier = Modifier.handCursor(),
                                ) {
                                    Text(stringResource(Res.string.tab_settings))
                                }
                            }
                        }

                        NavHost(
                            navController,
                            startDestination = Home,
                            // Keep the animated backdrop visible: when it is on, the host (and
                            // every screen using screenBackground()) stays transparent instead
                            // of painting the theme's opaque surface over it.
                            modifier = Modifier.screenBackground(),
                        ) {
                            composable<Home> {
                                ChatScreen(
                                    viewModel = chatViewModel,
                                    textToSpeech = textToSpeech,
                                    onNavigateToSettings = {
                                        navController.navigate(Settings)
                                    },
                                    isSandboxAvailable = currentPlatform is Platform.Mobile.Android,
                                    isPotsBuildAvailable = currentPlatform is Platform.Mobile.Android,
                                    navigationTabBar = if (showTabBar) navigationTabBar else null,
                                )
                            }
                            composable<Settings> {
                                if (showTabBar) {
                                    DisposableEffect(Unit) {
                                        onDispose {
                                            chatViewModel.refreshSettings()
                                        }
                                    }
                                }
                                SettingsScreen(
                                    onNavigateBack = {
                                        chatViewModel.refreshSettings()
                                        navController.navigateUp()
                                    },
                                    navigationTabBar = if (showTabBar) navigationTabBar else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
