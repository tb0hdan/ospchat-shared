package com.ospchat.shared.crypto

import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

actual object HmacSha1 {
    actual fun mac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val hmac = HMac(SHA1Digest())
        hmac.init(KeyParameter(key))
        hmac.update(data, 0, data.size)
        val out = ByteArray(hmac.macSize)
        hmac.doFinal(out, 0)
        return out
    }
}
