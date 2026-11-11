package com.pusios.pots.media

actual fun platformMediaLog(priority: Int, tag: String, message: String) {
    println("$tag: $message")
}
