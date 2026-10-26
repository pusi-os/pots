package com.pusios.pots.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.pusios.pots.db.PotsDatabase

actual fun createConversationSqlDriver(): SqlDriver? = NativeSqliteDriver(PotsDatabase.Schema, "conversations.db")
