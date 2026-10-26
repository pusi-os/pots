package com.pusios.pots.data.miniapp

actual fun createMiniAppHostBridge(): MiniAppHostBridge = object : MiniAppHostBridge {
    override fun listCalendar(fromMs: Long, toMs: Long): List<Map<String, Any?>>? = null
    override fun createCalendar(title: String, startIso: String, endIso: String?, notes: String?): Long? = null
}
