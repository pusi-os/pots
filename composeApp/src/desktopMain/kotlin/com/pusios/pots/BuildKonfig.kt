package com.pusios.pots

// For desktop, a common way is to use a system property.
// This can be set, for example, in the JVM arguments when running in debug mode: -Dpots.debug=true
actual val isDebugBuild: Boolean = System.getProperty("pots.debug", "false").toBoolean()
