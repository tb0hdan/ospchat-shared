package com.ospchat.shared.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ospchat.shared.platform.dataDir
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Open the persistent OSPChat database on desktop, rooted in the per-OS user-data dir. */
fun ospChatDatabase(name: String = "ospchat.db"): OspChatDatabase {
    val file = File(dataDir(), name)
    return Room
        .databaseBuilder<OspChatDatabase>(name = file.absolutePath)
        .addMigrations(*OSPCHAT_MIGRATIONS)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
