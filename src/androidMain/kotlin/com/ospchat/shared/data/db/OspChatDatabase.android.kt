package com.ospchat.shared.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** Open the persistent OSPChat database on Android. Wire from the Hilt module. */
fun ospChatDatabase(
    context: Context,
    name: String = "ospchat.db",
): OspChatDatabase =
    Room
        .databaseBuilder<OspChatDatabase>(
            context = context.applicationContext,
            name = context.getDatabasePath(name).absolutePath,
        ).addMigrations(*OSPCHAT_MIGRATIONS)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
