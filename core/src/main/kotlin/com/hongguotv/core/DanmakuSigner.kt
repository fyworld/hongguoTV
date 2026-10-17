// Kotlin port of the Hongguo comment signature chain (X-Gorgon / X-Ladon / X-Argus),
// from upstream HongguoTV-Phone-TV-NoAds provider_hongguo_comment_sign.go and the
// end-to-end probe upstream/probe_danmaku_signed.py. GPL-3.0-only.
package com.hongguotv.core

import java.security.SecureRandom

/**
 * Comment API request signatures (danmaku list endpoint).
 *
 * The comment endpoints require the full app signature chain (unlike the catalog
 * endpoints which only need the lightweight X-Khronos/X-Gorgon pair):
 *  - X-Gorgon: RC4-variant stream cipher over a 20-byte seed (query MD5 + timestamp)
 *  - X-Ladon:  MD5-seeded 34-round 64-bit mixing, custom Feistel, base64
 *  - X-Argus:  protobuf-style varint message + SM3 hashes + 72-round stream cipher
 *    + AES-CBC container, base64
 */
internal class DanmakuSigner(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    /** One signing round: fresh nonce + timestamp + the three signature headers. */
    data class Signed(val query: String, val timestamp: Int, val headers: Map<String, String>)

    private fun rotl8(v: Int, n: Int) = ((v shl n) or (v ushr (8 - n))) and 0xff
    private fun rev8(v: Int): Int {
        var r = 0
        for (i in 0 until 8) if (v and (1 shl i) != 0) r = r or (1 shl (7 - i))
        return r
    }
    private fun rotl64(x: Long, n: Int): Long {
        val m = ((n % 64) + 64) % 64
        return if (m == 0) x else (x shl m) or (x ushr (64 - m))
    }
    private fun uvarint(out: java.io.ByteArrayOutputStream, value: Long) {
        var n = value
        while (n >= 0x80) { out.write(((n and 0x7f) or 0x80).toInt()); n = n ushr 7 }
        out.write(n.toInt())
    }
    private fun pkcs7(data: ByteArray): ByteArray {
        val pad = 16 - data.size % 16
        return data + ByteArray(pad) { pad.toByte() }
    }

    // ---------- X-Ladon ----------

    private fun ladon(aid: String, timestamp: Int, prefix: ByteArray): String {
        val h = digest("MD5", prefix + aid.toByteArray())
        val hexText = h.hex().toByteArray()
        var left = hexText.i64(0, true)
        var right = hexText.i64(8, true)
        val queue = ArrayDeque<Long>()
        queue.add(hexText.i64(16, true))
        queue.add(hexText.i64(24, true))
        val keys = mutableListOf<Long>()
        for (i in 0 until 34) {
            keys.add(left)
            val mixed = (rotl64(right, -8) + left).toLong() xor i.toLong()
            queue.add(mixed)
            left = mixed xor rotl64(left, 3)
            right = queue.removeFirst()
        }
        var data = pkcs7("$timestamp-1611921764-3019".toByteArray())
        for (off in data.indices step 16) {
            var l = data.i64(off, true)
            var r = data.i64(off + 8, true)
            for (key in keys) {
                r = key xor (l + rotl64(r, -8)).toLong()
                l = r xor rotl64(l, 3)
            }
            data = data.copyOf().also { d ->
                le64(l).copyInto(d, off)
                le64(r).copyInto(d, off + 8)
            }
        }
        return (prefix + data).base64()
    }

    // ---------- X-Argus ----------

    private fun argus(query: String, timestamp: Int, nonce: ByteArray): String {
        val key = hex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c")
        val msg = java.io.ByteArrayOutputStream()
        uvarint(msg, 1L * 8)
        uvarint(msg, 0x20200929L * 2)
        uvarint(msg, 2L * 8)
        uvarint(msg, 2L)
        uvarint(msg, 3L * 8)
        // Python: struct.unpack("<I", nonce[6:10]) - little-endian u32.
        uvarint(msg, ((nonce[6].u().toLong()) or (nonce[7].u().toLong() shl 8) or (nonce[8].u().toLong() shl 16) or (nonce[9].u().toLong() shl 24)))
        fun bytesField(field: Int, value: ByteArray) {
            uvarint(msg, field.toLong() * 8 + 2)
            uvarint(msg, value.size.toLong())
            msg.write(value)
        }
        bytesField(4, "3019".toByteArray())
        bytesField(6, "1611921764".toByteArray())
        bytesField(7, "6.8.1.32".toByteArray())
        bytesField(8, "v04.07.01-ml-android".toByteArray())
        uvarint(msg, 9L * 8)
        uvarint(msg, 135135744L)
        bytesField(10, ByteArray(8))
        uvarint(msg, 12L * 8)
        uvarint(msg, timestamp.toLong() * 2)
        val bodyHash = sm3(ByteArray(16))
        val queryHash = sm3(query.toByteArray())
        bytesField(13, bodyHash.copyOf(6))
        bytesField(14, queryHash.copyOf(6))
        val nested = java.io.ByteArrayOutputStream()
        nested.write(8)
        nested.write(2 + (nonce[10].u() % 49) * 2)
        for (field in intArrayOf(2, 3, 4)) {
            uvarint(nested, field.toLong() * 8)
            uvarint(nested, 1388734L)
        }
        bytesField(15, nested.toByteArray())
        bytesField(20, "none".toByteArray())
        uvarint(msg, 21L * 8)
        uvarint(msg, 738L)
        var data = pkcs7(msg.toByteArray())
        val seed = key + byteArrayOf(0x3c, 0xcc.toByte(), 0x56, 0x7b) + key
        val derived = sm3(seed)
        val rounds = mutableListOf<Long>()
        for (i in 0 until 4) rounds.add(derived.i64(i * 8, true))
        for (i in 4 until 72) {
            var mixed = rotl64(rounds[i - 1], -3) xor rounds[i - 3]
            mixed = mixed xor rotl64(mixed, -1)
            val bit = (0x3DC94C3A046D678BL ushr ((i - 4) % 62)) and 1L
            rounds.add((rounds[i - 4].inv() xor mixed xor bit xor 3L))
        }
        for (off in data.indices step 16) {
            var l = data.i64(off, true)
            var r = data.i64(off + 8, true)
            for (rnd in rounds) {
                val newL = r
                r = l xor (rotl64(r, 1) and rotl64(r, 8)) xor rotl64(r, 2) xor rnd
                l = newL
            }
            data = data.copyOf().also { d ->
                le64(l).copyInto(d, off)
                le64(r).copyInto(d, off + 8)
            }
        }
        val xorMask = byteArrayOf(0xd0.toByte(), 0x4f, 0xfd.toByte(), 0xff.toByte(), 0xd0.toByte(), 0x4f, 0xfd.toByte(), 0xff.toByte())
        data = ByteArray(data.size) { (data[it].u() xor xorMask[it % 8].u()).toByte() }
        data = xorMask + data
        data = data.reversedArray()
        var container = byteArrayOf(0xa6.toByte(), 0xe7.toByte(), 0x83.toByte(), 0xee.toByte(), 0x70, 0x01, 0x10, 0x09, 0x18) + data + byteArrayOf(0x56, 0x7b)
        container = pkcs7(container)
        val aesKey = digest("MD5", key.copyOf(16))
        val iv = digest("MD5", key.copyOfRange(16, 32))
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(aesKey, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        val enc = cipher.doFinal(container)
        return (byteArrayOf(0x3c, 0xcc.toByte()) + enc).base64()
    }

    // ---------- X-Gorgon (comment variant, RC4 over 20-byte seed) ----------

    private fun gorgon(query: String, timestamp: Int, nonce: ByteArray): String {
        val head2 = ((nonce[0].u() % 7) + 1) shl 5
        val head3 = 0xe0 or (nonce[1].u() and 0x0f)
        val seed = ByteArray(20)
        digest("MD5", query.toByteArray()).copyOf(4).copyInto(seed, 0)
        byteArrayOf(0, 1, 7, 4).copyInto(seed, 12)
        be32(timestamp).copyInto(seed, 16)
        val key = byteArrayOf(0x4a, 0x00, 0x16, head3.toByte(), 0x47, 0x6c, 0x00, head2.toByte())
        val box = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + box[i] + key[i and 7].u()) and 0xff
            val t = box[i]; box[i] = box[j]; box[j] = t
        }
        j = 0
        for (offset in 0 until 20) {
            val i = (offset + 1) and 0xff
            j = (j + box[i]) and 0xff
            val t = box[i]; box[i] = box[j]; box[j] = t
            seed[offset] = (seed[offset].u() xor box[(box[i] + box[j]) and 0xff]).toByte()
        }
        for (i in 0 until 20) {
            val mixed = rotl8(seed[i].u(), 4) xor seed[(i + 1) % 20].u()
            seed[i] = ((rev8(mixed and 0xff) xor 0xff xor 20) and 0xff).toByte()
        }
        return (byteArrayOf(0x84.toByte(), 0x04, head2.toByte(), head3.toByte(), 0x00, 0x00) + seed).hex()
    }

    /** Sign a query string for the comment API; returns headers to attach. */
    fun sign(query: String): Signed {
        val now = clock()
        val ts = (now / 1000).toInt()
        val nonce = ByteArray(11); random.nextBytes(nonce)
        val headers = linkedMapOf(
            "X-Khronos" to ts.toString(),
            "X-Gorgon" to gorgon(query, ts, nonce),
            "X-Ladon" to ladon("8662", ts, nonce.copyOfRange(2, 6)),
            "X-Argus" to argus(query, ts, nonce),
            "X-SS-Req-Ticket" to now.toString(),
        )
        return Signed(query, ts, headers)
    }
}
