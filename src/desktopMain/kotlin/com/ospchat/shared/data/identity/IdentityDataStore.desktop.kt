package com.ospchat.shared.data.identity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.ospchat.shared.platform.dataDir
import java.io.File

/**
 * Construct a desktop `DataStore<Preferences>` for [IdentityRepository], rooted
 * at the per-OS user-data directory (XDG on Linux, Application Support on
 * macOS, %APPDATA% on Windows). Wire this into the desktop app's DI container.
 */
fun createIdentityDataStore(filename: String = "identity.preferences_pb"): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        produceFile = {
            File(dataDir(), filename).apply { parentFile?.mkdirs() }
        },
    )
