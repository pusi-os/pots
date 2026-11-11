package com.pusios.pots.media

actual fun platformMediaLog(priority: Int, tag: String, message: String) {
    android.util.Log.println(priority, tag, message)
}
