package com.pusios.pots.data.miniapp

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.pusios.pots.db.PotsDatabase

actual fun createMiniAppSqlDriver(): SqlDriver? = NativeSqliteDriver(PotsDatabase.Schema, "mini-apps.db")
