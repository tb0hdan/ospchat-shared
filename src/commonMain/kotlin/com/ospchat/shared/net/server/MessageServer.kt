package com.ospchat.shared.net.server

import com.ospchat.shared.data.attachments.AttachmentStore
import com.ospchat.shared.data.avatar.AvatarStore
import com.ospchat.shared.data.calls.CallRepository
import com.ospchat.shared.data.discovery.DiscoveryRepository
import com.ospchat.shared.data.groups.GroupMessageRepository
import com.ospchat.shared.data.groups.GroupRepository
import com.ospchat.shared.data.groups.GroupSyncer
import com.ospchat.shared.data.identity.IdentityRepository
import com.ospchat.shared.data.messages.MessageDao
import com.ospchat.shared.data.messages.MessageRepository
import com.ospchat.shared.data.peers.PeerAvatarSync
import com.ospchat.shared.data.reactions.ReactionRepository
import com.ospchat.shared.net.dto.ErrorDto
import com.ospchat.shared.util.Log
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing

/**
 * Embedded Ktor HTTP server. Binds to an ephemeral port and exposes the full
 * OSPChat peer API.
 *
 * Group routes and avatar serving are opt-in: pass non-null
 * [groupMessageRepository] / [groupRepository] / [groupSyncer] to enable
 * the `/v1/groups/` routes, and non-null [avatarStore] / [peerAvatarSync] to enable
 * `/v1/avatar` + `/v1/notify-refresh`. Desktop builds that don't yet need
 * those features can leave them null.
 *
 * Start / stop are made safe against concurrent invocation and against the
 * launching coroutine being cancelled mid-bind: [engine] is assigned before
 * we suspend, and a `try`-block ensures we tear the engine down if anything
 * after that throws.
 *
 * [start] is idempotent: a second call returns the previously-bound port
 * instead of double-registering.
 */
class MessageServer(
    private val discoveryRepository: DiscoveryRepository,
    private val messageRepository: MessageRepository,
    private val messageDao: MessageDao,
    private val attachmentStore: AttachmentStore,
    private val identityRepository: IdentityRepository,
    private val reactionRepository: ReactionRepository,
    private val avatarStore: AvatarStore? = null,
    private val peerAvatarSync: PeerAvatarSync? = null,
    private val groupMessageRepository: GroupMessageRepository? = null,
    private val groupRepository: GroupRepository? = null,
    private val groupSyncer: GroupSyncer? = null,
    private val callRepository: CallRepository? = null,
) {
    @Volatile private var engine: ApplicationEngine? = null

    @Volatile private var boundPort: Int = 0
    private val lock = Any()

    /**
     * Starts the embedded server. If [preferredPort] is non-zero and free, the
     * server binds to it; if the bind fails (typically EADDRINUSE), falls back
     * to an ephemeral port (kernel-assigned). Returns the actually-bound port.
     *
     * Reusing the previous boot's port across restarts is what keeps peers
     * reachable through a desktop/Android restart — the mDNS resolution
     * cached by the peer's NSD framework doesn't fire an update for a
     * port-only change, so changing the port on each boot strands the peer.
     */
    suspend fun start(
        uuid: String,
        nickname: String,
        preferredPort: Int = 0,
    ): Int {
        synchronized(lock) {
            if (engine != null) return boundPort
        }
        val identity = ServerIdentity(uuid = uuid, nickname = nickname)

        if (preferredPort in 1..65535) {
            tryBind(identity, preferredPort)?.let { return it }
            Log.w(TAG, "Preferred port $preferredPort unavailable; falling back to ephemeral")
        }
        return tryBind(identity, 0)
            ?: error("Could not bind to any TCP port")
    }

    /**
     * Build and start the embedded engine on [port] (0 = ephemeral). Returns
     * the bound port on success, or `null` if the bind failed (e.g. port in
     * use) so the caller can fall back. Other failures (engine construction
     * issues, configuration bugs) still throw.
     */
    private suspend fun tryBind(
        identity: ServerIdentity,
        port: Int,
    ): Int? {
        val server =
            embeddedServer(CIO, port = port, host = "0.0.0.0") {
                install(ContentNegotiation) { json() }
                install(StatusPages) {
                    exception<BadRequestException> { call, cause ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorDto(error = ErrorCodes.BAD_REQUEST, detail = cause.message),
                        )
                    }
                    exception<Throwable> { call, cause ->
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorDto(error = ErrorCodes.INTERNAL_ERROR, detail = cause.message),
                        )
                    }
                }
                routing {
                    installMessageRoutes(
                        identity = identity,
                        discoveryRepository = discoveryRepository,
                        messageRepository = messageRepository,
                        messageDao = messageDao,
                        attachmentStore = attachmentStore,
                        reactionRepository = reactionRepository,
                        avatarStore = avatarStore,
                        peerAvatarSync = peerAvatarSync,
                        groupMessageRepository = groupMessageRepository,
                        groupRepository = groupRepository,
                        groupSyncer = groupSyncer,
                        callRepository = callRepository,
                        avatarHashSupplier = { identityRepository.currentAvatarHash() },
                    )
                }
            }

        synchronized(lock) {
            if (engine != null) {
                runCatching { server.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS) }
                return boundPort
            }
            engine = server
        }
        try {
            server.start(wait = false)
            val bound = server.resolvedConnectors().first().port
            boundPort = bound
            return bound
        } catch (t: Throwable) {
            synchronized(lock) {
                if (engine === server) {
                    engine = null
                    boundPort = 0
                }
            }
            runCatching { server.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS) }
            if (port != 0 && isBindFailure(t)) {
                return null
            }
            throw t
        }
    }

    /**
     * True for the OS-level "address already in use" / permission-denied
     * variants Ktor surfaces when an explicit port is taken. We test by
     * walking the cause chain because Ktor CIO wraps the underlying NIO
     * exception in its own bind-failure type on some platforms.
     */
    private fun isBindFailure(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val name = cur::class.simpleName ?: ""
            if (name == "BindException" || name == "ChannelBindException") return true
            val msg = cur.message?.lowercase().orEmpty()
            if ("address already in use" in msg ||
                ("bind" in msg && "fail" in msg) ||
                "permission denied" in msg
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    fun stop() {
        val current =
            synchronized(lock) {
                val e = engine
                engine = null
                boundPort = 0
                e
            }
        current?.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS)
    }

    private companion object {
        const val TAG = "MessageServer"
        const val STOP_TIMEOUT_MS = 1_000L
    }
}
