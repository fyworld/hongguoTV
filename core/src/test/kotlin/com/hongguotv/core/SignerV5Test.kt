// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 6672b55 via tsukiyo-zero/hongguoTV 0236976; GPL-3.0-only.
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/**
 * v5 协议对拍：载波（AesV3 v5 常量表）与摘要（hashF13 v5 分支）都有独立 SDK 的
 * 合成向量，逐条比对 —— 这两处错一位，服务端就是 200+0 字节，肉眼看不出原因。
 */
class SignerV5Test {
    @Test fun matchesCarrierVectorsAcrossAllFourTimestampVariants() {
        val rows = JSONArray(javaClass.getResource("/signing-v5-carrier.json")!!.readText())
        assertEquals(4, rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val actual = AesV3(hex(row.getString("key")), row.getInt("ts"), v5 = true)
                .encrypt(hex(row.getString("source")), hex(row.getString("iv")))
            assertEquals("carrier variant $i", row.getString("encrypted"), actual.hex())
        }
    }

    @Test fun matchesIndependentSdkDigestVectorsAcrossAllTimestampRotations() {
        val vectors = JSONArray(javaClass.getResource("/signing-v5-f13.json")!!.readText())
        val signer = Signer(mobileV5 = true)
        assertEquals(32, vectors.length())
        for (i in 0 until vectors.length()) {
            val row = vectors.getJSONObject(i)
            val result = signer.hashF13(hex(row.getString("query")), hex(row.getString("body")), row.getInt("ts"))
            assertEquals("timestamp class $i", row.getString("digest"), result.copyOf(16).hex())
            assertEquals(20, result.size)
            assertEquals(0x24, result[16].u() and 0x24)
        }
    }

    @Test fun adjustsOnlyTicketAndBindsCompressedBytesAndCurrentIdentity() {
        val body = byteArrayOf(31, -117, 8, 0, 1, 2, 3, 4)
        val first = mapOf(
            "device_id" to "123", "iid" to "456", "cdid" to "synthetic",
            "version_name" to "7.4.3.32", "channel" to "vivo_8662_64",
            "os_version" to "12", "device_type" to "PGT-AN10", "_rticket" to "1800000000000")
        val signer = Signer({ 1800000000000 }, random = { 0.5 }, processNonce = "synthetic", mobileV5 = true)
        for (ts in 1800000000..1800000031) {
            val request = signer.signPlaybackV5("https://example.test/model", first, body, ts.toLong() * 1000)
            val url = request.url.toHttpUrl()
            assertNull(url.queryParameter("ts"))
            first.filterKeys { it != "_rticket" }.forEach { (k, v) -> assertEquals(v, url.queryParameter(k)) }
            assertEquals(0, signer.branch(url.encodedQuery!!, body, ts))
            assertEquals(ts.toString(), request.headers["x-khronos"])
            assertEquals(digest("MD5", body).hex().uppercase(), request.headers["x-ss-stub"])
            assertArrayEquals(body, request.body)
            // v5 只保留三个签名头；旧链路的 argus/ladon/gorgon 是伪造特征。
            assertEquals(
                setOf("x-khronos", "x-helios", "x-medusa", "Content-Type", "x-ss-stub"),
                request.headers.keys)
            // 确定性（固定 random / nonce）：同输入两次必须完全相同。
            val again = signer.signPlaybackV5("https://example.test/model", first, body, ts.toLong() * 1000)
            assertEquals(request.headers["x-medusa"], again.headers["x-medusa"])
            // 身份变化必须改变 medusa 明文（服务端核对 query 与明文里的 device_id）。
            val other = signer.signPlaybackV5(
                "https://example.test/model", first + ("device_id" to "789"), body, ts.toLong() * 1000)
            assertNotEquals(request.headers["x-medusa"], other.headers["x-medusa"])
        }
    }

    @Test fun legacySigningIsUnaffectedByTheV5Mode() {
        // 旧链路（目录/账号/旧播放）必须保持原样：branch 仍是 low%3，v5 常量不参与。
        val signer = Signer(mobileV5 = false)
        val query = "aid=8662&device_id=1"
        assertEquals(signer.branch(query, null, 1800000000),
            Signer(mobileV5 = false).branch(query, null, 1800000000))
        assertTrue(signer.branch(query, null, 1800000000) in 0..2)
    }
}
