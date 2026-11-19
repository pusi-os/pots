package com.pusios.pots.assistant

actual fun currentAssistantAvailability(): AssistantAvailability = AssistantAvailability.NotOnThisPlatform

actual fun currentScreenContextAvailability(): ScreenContextAvailability = ScreenContextAvailability.NotOnThisPlatform

actual fun openDefaultAppsSettings() = Unit
