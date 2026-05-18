package com.ospchat.shared.domain.contacts

import com.ospchat.shared.data.peers.PeerRepository

/**
 * Demotes the peer identified by [uuid] back to a transient peer. The peer
 * row itself is preserved so message history and avatar caching survive.
 */
class RemoveFromContactsUseCase(
    private val peerRepository: PeerRepository,
) {
    suspend operator fun invoke(uuid: String) {
        peerRepository.setIsContact(uuid = uuid, isContact = false)
    }
}
