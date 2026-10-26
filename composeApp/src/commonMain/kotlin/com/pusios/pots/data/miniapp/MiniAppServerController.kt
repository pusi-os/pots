package com.pusios.pots.data.miniapp

/** Keeps the built-in server alive after the app goes to the background. Android-only. */
interface MiniAppServerController {
    fun startBackground()
    fun stopBackground()
}

class NoOpMiniAppServerController : MiniAppServerController {
    override fun startBackground() {}
    override fun stopBackground() {}
}

expect fun createMiniAppServerController(): MiniAppServerController
