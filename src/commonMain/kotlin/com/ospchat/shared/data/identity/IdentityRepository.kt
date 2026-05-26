package com.ospchat.shared.data.identity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ospchat.shared.crypto.SigningCrypto
import com.ospchat.shared.crypto.SigningKeyPair
import com.ospchat.shared.util.Base64Util
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

    /**
     * Phase 4 multi-network bridging — `true` when the local user has
     * opted in to relaying signed DTOs for other peers. The flag is
     * surfaced in `GET /v1/info.relayEnabled` and gates the relay-forward
     * branch in `MessageRoutes`. Default `false`.
     *
     * Read at startup and passed to `MessageServer.start(relayEnabled=...)`
     * — runtime toggles take effect on next restart in phase 4 MVP.
     */
    val relayEnabledFlow: Flow<Boolean> = store.data.map { it[RELAY_ENABLED_KEY] ?: false }

    suspend fun currentRelayEnabled(): Boolean = store.data.first()[RELAY_ENABLED_KEY] ?: false

    suspend fun setRelayEnabled(enabled: Boolean) {
        store.edit { it[RELAY_ENABLED_KEY] = enabled }
    }

    /**
     * In-memory cache of the loaded signing keypair. Populated on the
     * first [ensureSigningKeyPair] call; exposed synchronously via
     * [signingKeyPairOrNull] for non-suspend call sites (notably
     * [MessageClient]'s `sign()` overloads, which need the key on every
     * outbound DTO and can't be made suspend without cascading into the
     * inline-reified `postJson` plumbing).
     */
    @Volatile private var cachedSigningKey: SigningKeyPair? = null

    /**
     * Returns this install's stable Ed25519 signing keypair, generating + persisting
     * it on first use. The private seed is stored base64 in DataStore; the
     * public half is derived on every load. Phase 2a multi-network bridging
     * uses the public half as a cryptographic identity advertised over mDNS
     * TXT (`pk=`) and `GET /v1/info`; phase 2b uses the keypair to sign
     * outbound DTOs.
     *
     * Idempotent under concurrent first-time callers: the persisted seed
     * wins under a re-check inside the DataStore edit, mirroring [ensureUuid].
     * The result is cached for [signingKeyPairOrNull].
     */
    suspend fun ensureSigningKeyPair(): SigningKeyPair {
        cachedSigningKey?.let { return it }
        store.data.first()[SIGNING_SEED_KEY]?.let { encoded ->
            runCatching { Base64Util.decode(encoded) }.getOrNull()?.let { seed ->
                if (seed.size == SigningCrypto.privateSeedSize) {
                    val restored = SigningCrypto.fromSeed(seed)
                    cachedSigningKey = restored
                    return restored
                }
            }
            // Corrupt / wrong-size seed: fall through and regenerate.
        }
        val generated = SigningCrypto.generate()
        val seedB64 = Base64Util.encode(generated.privateSeedBytes())
        var winnerSeed = seedB64
        store.edit { prefs ->
            val existing = prefs[SIGNING_SEED_KEY]
            if (existing != null) {
                runCatching { Base64Util.decode(existing) }.getOrNull()?.let { seed ->
                    if (seed.size == SigningCrypto.privateSeedSize) {
                        winnerSeed = existing
                        return@edit
                    }
                }
                // existing was corrupt; overwrite
            }
            prefs[SIGNING_SEED_KEY] = seedB64
        }
        val result =
            if (winnerSeed == seedB64) {
                generated
            } else {
                SigningCrypto.fromSeed(Base64Util.decode(winnerSeed))
            }
        cachedSigningKey = result
        return result
    }

    /**
     * Phase 2b multi-network bridging — non-suspend accessor for the
     * cached signing keypair. Returns null until [ensureSigningKeyPair]
     * has run at least once. Consumers pass `{ identityRepository.signingKeyPairOrNull() }`
     * into [MessageClient]'s `signingKeyProvider` so every outbound DTO
     * carries a fresh signature once the keypair is loaded.
     */
    fun signingKeyPairOrNull(): SigningKeyPair? = cachedSigningKey

    private companion object {
        val NICKNAME_KEY = stringPreferencesKey("nickname")
        val UUID_KEY = stringPreferencesKey("uuid")
        val AVATAR_HASH_KEY = stringPreferencesKey("avatar_hash")
        val SERVER_PORT_KEY = intPreferencesKey("server_port")

        /**
         * Base64-encoded Ed25519 private seed (32 raw bytes → 44 b64 chars).
         * Stored as a string preference because DataStore-Preferences has no
         * `ByteArrayPreferencesKey` primitive in the multiplatform variant.
         */
        val SIGNING_SEED_KEY = stringPreferencesKey("signing_seed_b64")

        /** Phase 4 — opt-in flag for relaying signed DTOs on behalf of other peers. */
        val RELAY_ENABLED_KEY = booleanPreferencesKey("relay_enabled")
    }
}
