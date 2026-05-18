package com.ospchat.shared.net.server

import com.ospchat.shared.data.attachments.AttachmentStore
import com.ospchat.shared.data.avatar.AvatarStore
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
) {
    @Volatile private var engine: ApplicationEngine? = null

    @Volatile private var boundPort: Int = 0
    private val lock = Any()

    suspend fun start(
        uuid: String,
        nickname: String,
    ): Int {
        synchronized(lock) {
            if (engine != null) return boundPort
        }
        val identity = ServerIdentity(uuid = uuid, nickname = nickname)

        val server =
            embeddedServer(CIO, port = 0, host = "0.0.0.0") {
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
            val port = server.resolvedConnectors().first().port
            boundPort = port
            return port
        } catch (t: Throwable) {
            synchronized(lock) {
                if (engine === server) {
                    engine = null
                    boundPort = 0
                }
            }
            runCatching { server.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS) }
            throw t
        }
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
        const val STOP_TIMEOUT_MS = 1_000L
    }
}
