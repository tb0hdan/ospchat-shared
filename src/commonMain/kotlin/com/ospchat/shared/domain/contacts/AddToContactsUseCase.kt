package com.ospchat.shared.domain.contacts

import com.ospchat.shared.data.peers.PeerRepository

/**
 * Promotes the peer identified by [uuid] to a saved contact. Idempotent —
 * calling on an already-saved contact is a no-op `UPDATE` on a single row.
 */
class AddToContactsUseCase(
    private val peerRepository: PeerRepository,
) {
    suspend operator fun invoke(uuid: String) {
        peerRepository.setIsContact(uuid = uuid, isContact = true)
    }
}
