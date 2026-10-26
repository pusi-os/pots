package com.pusios.pots.data.miniapp

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.pusios.pots.db.PotsDatabase
import com.pusios.pots.getAppFilesDirectory
import java.io.File

actual fun createMiniAppSqlDriver(): SqlDriver? {
    val file = File(getAppFilesDirectory(), "mini-apps.db")
    file.parentFile?.mkdirs()
    return JdbcSqliteDriver(url = "jdbc:sqlite:${file.absolutePath}", schema = PotsDatabase.Schema)
}
