package com.pusios.pots

/** There is no Android-style background mode on this target. */
actual fun createSandboxBackgroundController(): SandboxBackgroundController = NoOpSandboxBackgroundController()
