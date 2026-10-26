package com.pusios.pots.email

actual suspend fun createEmailConnection(host: String, port: Int, tls: Boolean): EmailConnection = throw UnsupportedOperationException("Email is not supported on this platform")
