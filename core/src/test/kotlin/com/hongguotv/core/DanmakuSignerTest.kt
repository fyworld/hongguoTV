package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/**
 * Cross-checks the Kotlin danmaku signature port against the Python reference
 * (upstream/probe_danmaku_signed.py, deterministic nonce/timestamp via
 * upstream/gen_sign_reference.py): same inputs must produce identical
 * X-Gorgon / X-Ladon / X-Argus.
 */
class DanmakuSignerTest {
    private class FixedRandom(private val bytes: ByteArray) : SecureRandom() {
        private var used = false
        override fun nextBytes(buf: ByteArray) {
            if (used) { super.nextBytes(buf); return }
            System.arraycopy(bytes, 0, buf, 0, bytes.size)
            used = true
        }
    }

    private fun signer() = DanmakuSigner({ 1_760_000_000_000L }, FixedRandom(byteArrayOf(1,2,3,4,5,6,7,8,9,10,11)))

    @Test fun `gorgon matches python reference`() {
        assertEquals("840440e20000bc30348dbe45aa61c5973d76e01a90230dbd44ff", signer().sign("aid=8662&app_name=novelread").headers["X-Gorgon"])
    }

    @Test fun `ladon matches python reference`() {
        assertEquals("AwQFBlRM+xZDXtNDpKFV6DraIyNnfrwAzFAlv0Cqi9J16ndL", signer().sign("aid=8662&app_name=novelread").headers["X-Ladon"])
    }

    @Test fun `argus matches python reference`() {
        assertEquals("PMxPj+JImxTKEfQuAsVmWpqW1EmFZjY8HzNwVyz05a/etQNkCi9aSV/jb2aiYJieykkq3zatvgBLRv1hd4UEv3DJ68CDI2Qxbh/NoKr8SDplENBLunuchJ4miHYVyIxPyc/f+19MiiQtY9qkrZTcLaDe5vFmOCdncLgsxHpgczdr3p7TvV+JKZop1nMecgl8fPtCBX1ulDLpERjq1yPh/ZHK", signer().sign("aid=8662&app_name=novelread").headers["X-Argus"])
    }
}
