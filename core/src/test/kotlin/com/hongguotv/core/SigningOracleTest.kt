package com.hongguotv.core

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/**
 * Golden vectors pinned from upstream tsukiyo-zero/hongguoTV 0.1.26 signing.json
 * (generated against the pinned JavaScript oracle with a fixed process nonce and
 * an LCG-seeded Math.random, seed=17). These pin the FULL signature chain:
 * branch selection, hashF13 branch-0 message expansion, gorgon, helios, and the
 * complete medusa envelope (proto encoding, xmxor, AesV3, check field).
 *
 * Why this test exists (2026-11-07 full-source diff): our port had THREE signing
 * bugs - (1) hashF13 di-expansion pinned di[0] instead of the running di[i],
 * (2) signPlayback selected branch 1 (short path) while upstream selects
 * branch 0 (main compression), (3) medusa message field 16 carried a hardcoded
 * nonce string contradicting the per-process nonce embedded in deviceProto
 * field 5. Every playback signature was wrong; the server's 2026-11-05
 * tightening turned that into HTTP 200 + empty body. Upstream 0.1.26 plays
 * fine on the TV, so these vectors are the ground truth the port must match
 * bit for bit.
 */
class SigningOracleTest {
    @Test
    fun matchesPinnedJavaScriptOracleAcrossTimestampVariants() {
        val rows = JSONArray(javaClass.getResource("/signing.json")!!.readText())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val ts = row.getInt("ts")
            val url = row.getString("url")
            val query = url.substringAfter('?')
            val body = if (row.isNull("body")) null else row.getString("body").toByteArray()
            var seed = 17L
            val signer = Signer(
                clock = { ts.toLong() * 1000 },
                processNonce = row.getString("nonce"),
                random = {
                    seed = (seed * 1664525 + 1013904223) and 0xffffffffL
                    seed / 4294967296.0
                },
            )
            assertEquals("branch $i", 0, signer.branch(query, body, ts))
            assertEquals(
                "hash $i",
                row.getString("hash"),
                signer.hashF13(
                    sm3(query.toByteArray()),
                    body?.let { digest("MD5", it) } ?: ByteArray(16),
                    ts,
                ).hex(),
            )
            assertEquals("gorgon $i", row.getString("gorgon"), signer.gorgon(query, body, ts, 42131))
            assertEquals("helios $i", row.getString("helios"), signer.helios(ts))
            assertEquals("medusa $i", row.getString("medusa"), signer.medusa(url, body, ts))
        }
    }
}
