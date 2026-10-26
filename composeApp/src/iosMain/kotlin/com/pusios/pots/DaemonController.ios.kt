package com.pusios.pots

actual fun createDaemonController(): DaemonController = NoOpDaemonController()
