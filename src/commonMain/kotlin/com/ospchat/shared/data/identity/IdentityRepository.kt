package com.ospchat.shared.data.identity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Nickname / UUID / avatar-hash store, backed by a platform-supplied
 * `DataStore<Preferences>`. The platform DI graph constructs the DataStore
 * with the appropriate file location (Android: Context.preferencesDataStore;
 * Desktop: XDG_DATA_HOME/ospchat/identity.preferences_pb).
 */
@OptIn(ExperimentalUuidApi::class)
class IdentityRepository(
    private val store: DataStore<Preferences>,
) {
    val nicknameFlow: Flow<String?> = store.data.map { it[NICKNAME_KEY] }

    val uuidFlow: Flow<String?> = store.data.map { it[UUID_KEY] }

    /**
     * SHA-256 hex of the currently-set custom avatar JPEG. `null` means
     * the user has no custom avatar; the UI falls back to nickname
     * initials. Peers consult this via `GET /v1/info`.
     */
    val avatarHashFlow: Flow<String?> = store.data.map { it[AVATAR_HASH_KEY] }

    suspend fun setNickname(nickname: String) {
        val trimmed = nickname.trim()
        require(trimmed.isNotEmpty()) { "Nickname must not be blank" }
        store.edit { it[NICKNAME_KEY] = trimmed }
    }

    /** Returns the stable per-install UUID, generating it on first use. */
    suspend fun ensureUuid(): String {
        store.data.first()[UUID_KEY]?.let { return it }
        val generated = Uuid.random().toString()
        var winner = generated
        store.edit { prefs ->
            // Re-check under the edit transaction: if a concurrent caller
            // already wrote a UUID, keep theirs and discard ours.
            val existing = prefs[UUID_KEY]
            if (existing != null) {
                winner = existing
            } else {
                prefs[UUID_KEY] = generated
            }
        }
        return winner
    }

    suspend fun currentAvatarHash(): String? = store.data.first()[AVATAR_HASH_KEY]

    suspend fun setAvatarHash(hash: String?) {
        store.edit { prefs ->
            if (hash == null) {
                prefs.remove(AVATAR_HASH_KEY)
            } else {
                prefs[AVATAR_HASH_KEY] = hash
            }
        }
    }

    /**
     * Last TCP port the embedded server bound to. Persisted so a restart can
     * re-bind the same port — peers that cache our mDNS resolution (Android
     * NSD's framework cache, for one) don't fire onServiceFound/Lost for a
     * port-only change, so reusing the port keeps cross-device messaging
     * working through a desktop/Android restart.
     *
     * Returns `null` on first run / if no port has been persisted yet.
     */
    suspend fun lastServerPort(): Int? = store.data.first()[SERVER_PORT_KEY]?.takeIf { it in 1..65535 }

    suspend fun setLastServerPort(port: Int) {
        require(port in 1..65535) { "port must be a valid TCP port, got $port" }
        store.edit { it[SERVER_PORT_KEY] = port }
    }

    private companion object {
        val NICKNAME_KEY = stringPreferencesKey("nickname")
        val UUID_KEY = stringPreferencesKey("uuid")
        val AVATAR_HASH_KEY = stringPreferencesKey("avatar_hash")
        val SERVER_PORT_KEY = intPreferencesKey("server_port")
    }
}
