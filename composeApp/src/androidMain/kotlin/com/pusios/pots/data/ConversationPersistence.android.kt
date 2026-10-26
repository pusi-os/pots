package com.pusios.pots.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.pusios.pots.db.PotsDatabase
import org.koin.java.KoinJavaComponent.inject

actual fun createConversationSqlDriver(): SqlDriver? {
    val context: Context by inject(Context::class.java)
    return AndroidSqliteDriver(PotsDatabase.Schema, context, "conversations.db")
}
