package com.pusios.pots.data.miniapp

import com.pusios.pots.data.AppSettings

actual fun createMiniAppServer(repository: MiniAppRepository, appSettings: AppSettings): MiniAppServer = NoOpMiniAppServer()
