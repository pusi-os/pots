package com.pusios.pots.data.miniapp

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.pusios.pots.db.PotsDatabase
import org.koin.java.KoinJavaComponent.inject

actual fun createMiniAppSqlDriver(): SqlDriver? {
    val context: Context by inject(Context::class.java)
    return AndroidSqliteDriver(PotsDatabase.Schema, context, "mini-apps.db")
}
