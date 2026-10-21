// Kotlin port of drpys 22261adfa31435e3b3ef5a730b8a2a75bcaf1715; GPL-3.0-only.
// Only hash branch 0 is emitted: adjust the request ticket before signing.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class Signer(private val clock: () -> Long = System::currentTimeMillis, private val random: () -> Double = Math::random) {
    data class Request(val url: String, val headers: Map<String, String>, val body: ByteArray?)
    private fun rand() = (random() * 4294967296.0).toLong().toInt()
    private fun unsigned(v: Int) = v.toLong() and 0xffffffffL
    private fun varint(value: Long): ByteArray {
        var n = value
        return ByteArrayOutputStream().apply { while (n > 127) { write(((n and 127) or 128).toInt()); n = n ushr 7 }; write(n.toInt()) }.toByteArray()
    }
    private data class Field(val tag: Int, val value: Any, val type: String = "string")
    private fun f(tag: Int, value: Any, type: String = "string") = Field(tag, value, type)
    private fun proto(vararg fields: Field): ByteArray = ByteArrayOutputStream().apply {
        for ((tag, value, type) in fields) {
            when (type) {
                "sint" -> { val n = (value as Number).toLong(); write(varint(tag.toLong() shl 3)); write(varint(if (n < 0) -n * 2 - 1 else n * 2)) }
                "float" -> { write(varint((tag.toLong() shl 3) or 5)); write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat((value as Number).toFloat()).array()) }
                else -> { val bytes = if (value is ByteArray) value else value.toString().toByteArray(); if (bytes.isNotEmpty()) { write(varint((tag.toLong() shl 3) or 2)); write(varint(bytes.size.toLong())); write(bytes) } }
            }
        }
    }.toByteArray()
    private fun getIv(initial: Int, data: ByteArray): Int {
        var value = initial
        for (i in data.indices) value = if (i and 1 == 0) (value ushr 4) xor value xor (value shl 6) xor data[i].u() else ((value ushr 7) xor value xor (data[i].u() or (value shl 12))).inv()
        return value
    }
    private fun iv(query: ByteArray, body: ByteArray, ts: Int) = getIv(getIv(getIv(0x20230928, query), body), le32(ts))
    fun branch(query: String, body: ByteArray?, ts: Int): Int {
        val ivV = iv(sm3(query.toByteArray()), body?.let { digest("MD5", it) } ?: ByteArray(16), ts)
        val low = ivV and 15
        return low - (((low * 171) shr 9) * 3)
    }
    private fun sumMd5(data: ByteArray): Int {
        var check = 0x20220420
        for (i in 0 until 12) {
            val temp = (check ushr (if (i and 1 == 0) 3 else 5)) xor check
            check = if (i and 1 == 0) data[i].u() xor (check shl 7) else (data[i].u() or (check shl 11)).inv()
            check = check xor temp
        }
        return (check or 4) xor 0x1000000
    }
    fun hashF13(query: ByteArray, body: ByteArray, ts: Int): ByteArray {
        val ivV = iv(query, body, ts)
        val low = ivV and 15
        val ivV0 = (low * 171) shr 9
        val branch = low - ivV0 * 3
        if (branch == 2) return branch2F13(ivV, query, body, ts)
        if (branch != 0) {
            // Short path: sm3 head + body md5 + checksum (36 bytes).
            val sum = sumMd5(query + body)
            return query.copyOf(16) + body + le32(sum)
        }
        val ivV1 = intArrayOf(0xc4a78580.toInt(),0xb3c0fd39.toInt(),0xc58c5686.toInt(),0xc9aa3ba7.toInt(),0xf5a7adf2.toInt(),0x963c2ed1.toInt())[ivV0]
        val countV1 = (ivV1 + ts + 1) and 255
        val countV2 = ivV1 + ts
        val tt = VendorConstants.hashTable.map { ror(it, countV1) }
        val shift = (countV2 + 2) and 7
        val pad = intArrayOf(0xfa,0x45,0x61,0xd7).map { ((it or (it shl 8)) shr shift).toByte() }.toByteArray()
        val data = query + body + le32(ts) + pad + hex("00000000000001a0")
        val di = (data.indices step 4).map { data.i32(it) }.toMutableList()
        val di0 = di[0]
        for (i in 0 until 112) {
            val x = di[i + 1]; val y = di[i + 14]
            di.add(di0 + di[i + 9] + (rol(x,14) xor rol(x,25) xor (x ushr 3)) + (rol(y,13) xor rol(y,15) xor (y ushr 10)))
        }
        val initial = intArrayOf(0x7aba4fc8,0x67166507,0x6403fa00,0x340f512f,984304912,3005047866L.toInt(),2874125293L.toInt(),2152413264L.toInt()).map { ror(it, countV2) }
        val chosen = arrayOf(intArrayOf(101,5,7,6,3,2,1,0,5,4,3),intArrayOf(96,0,6,7,5,3,2,1,5,4,4),intArrayOf(96,7,6,2,1,4,0,5,4,3,5),intArrayOf(99,3,6,2,4,5,1,0,0,7,6),intArrayOf(96,0,5,6,7,3,1,2,5,4,4),intArrayOf(100,2,0,3,5,4,6,7,2,1,5))[ivV0]
        val d = initial.toMutableList()
        for (i in 0 until chosen[0]) {
            val base = ivV1 + i
            val n1 = ((d[chosen[3]] xor d[chosen[4]]) and d[chosen[1]]) xor d[chosen[3]]
            val n2 = rol(d[chosen[1]],26) xor rol(d[chosen[1]],21) xor rol(d[chosen[1]],7)
            val n4 = di[base and 127] + n1 + n2 + tt[base and 63] + d[chosen[5]]
            val n5 = rol(d[chosen[2]],30) xor rol(d[chosen[2]],19) xor rol(d[chosen[2]],10)
            val n6 = (d[chosen[2]] and d[chosen[6]]) or ((d[chosen[2]] or d[chosen[6]]) and d[chosen[7]])
            val old = d[chosen[9]]
            val last = d[7]
            for (k in 7 downTo 1) d[k] = d[k - 1]
            d[0] = last
            d[chosen[10]] = n5 + n6 + n4
            d[chosen[8]] = old + n4
        }
        val ret = (0..7).fold(byteArrayOf()) { a, i -> a + be32(d[i] + initial[i]) }
        for (i in 0 until 16) ret[i] = (ret[i].u() xor ret[i + 16].u()).toByte()
        return ret.copyOf(16) + le32(sumMd5(ret.copyOf(16)))
    }

    // hashF13 branch 2: md5V3-variant compression driven by the Branch2 tables.
    private fun branch2F13(ivV: Int, query: ByteArray, body: ByteArray, ts: Int): ByteArray {
        val v = (ivV and 13) * 86
        var ivV0 = ((v shr 15) and 255) + ((v shr 8) and 255)
        if (ivV0 >= BRANCH2_N1.size) ivV0 = BRANCH2_N1.size - 1
        val n1 = BRANCH2_N1[ivV0]
        val countV1 = (n1 + ts + 1) and 255
        val countV2 = n1 + ts
        val shift = (countV2 + 5) and 7
        val pad = ByteArray(8) { i -> ((BRANCH2_SEED[i].u() or (BRANCH2_SEED[i].u() shl 8)) shr shift).toByte() }
        val input = query + body + le32(ts) + pad + hex("a0010000")
        val from = (ivV0 shl 6).coerceAtMost(BRANCH2_ORDERS.size)
        val to = ((ivV0 + 1) shl 6).coerceAtMost(BRANCH2_ORDERS.size)
        val orders = BRANCH2_ORDERS.copyOfRange(from, to)
        return md5SumV3(input, countV2, orders, countV1)
    }

    private fun md5V3Step(kind: Int, a: Int, b: Int, c: Int, d: Int, m: Int, shift: Int, constant: Int): Int {
        val f = when (kind) {
            0 -> (b and c) or (b.inv() and d)
            1 -> (b and d) or (c and d.inv())
            2 -> b xor c xor d
            else -> c xor (b or d.inv())
        }
        return b + rol(a + f + m + constant, shift)
    }

    private fun md5SumV3(message: ByteArray, countV2: Int, orders: ByteArray, countV1: Int): ByteArray {
        val sv = BRANCH2_SV.map { ror(it, countV1) }
        val start = intArrayOf(0x79e0f2fb.toInt(), 0xc8b52570.toInt(), 0xebc2f8cd.toInt(), 0x7c104d93.toInt()).map { ror(it, countV2) }
        val endCount = (countV2 + 6) and 255
        val end = intArrayOf(0x19be4866.toInt(), 0xe85986b4.toInt(), 0xe19b326e.toInt(), 0x71d1d7d4.toInt()).map { ror(it, endCount) }
        val m = (0 until 16).map { message.i32(it * 4, true) }
        var (a, b, c, d) = listOf(start[0], start[1], start[2], start[3])
        val shifts = arrayOf(
            intArrayOf(7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22),
            intArrayOf(5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20),
            intArrayOf(4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23),
            intArrayOf(6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21))
        for (kind in 0..3) {
            for (i in 0 until 16) {
                val phase = i and 3
                val order = orders.getOrNull(kind * 16 + i)?.u() ?: 0
                val mm = m[order and 255]
                val cc = sv[kind * 16 + i]
                when (phase) {
                    0 -> a = md5V3Step(kind, a, b, c, d, mm, shifts[kind][i], cc)
                    1 -> d = md5V3Step(kind, d, a, b, c, mm, shifts[kind][i], cc)
                    2 -> c = md5V3Step(kind, c, d, a, b, mm, shifts[kind][i], cc)
                    else -> b = md5V3Step(kind, b, c, d, a, mm, shifts[kind][i], cc)
                }
            }
        }
        val ret = le32(start[0] + a xor end[0]) + le32(start[1] + b xor end[1]) + le32(start[2] + c xor end[2]) + le32(start[3] + d xor end[3])
        return ret + le32(sumMd5(ret))
    }
    fun gorgon(query: String, body: ByteArray?, ts: Int, random: Int): String {
        val input = digest("MD5",query.toByteArray()).copyOf(4) + (body?.let { digest("MD5",it).copyOf(4) } ?: ByteArray(4)) + ByteArray(4) + le32(67503104) + be32(ts)
        val key = intArrayOf(0x4a,0x40,0x16,(random shr 8) and 255,0x47,0x6c,1,random and 255)
        val s = IntArray(256) { it }; var j = 0
        for (i in 0..255) { j = (j + s[i] + key[i % 8]) and 255; s[i] = s[j] }
        j = 0
        val out = ByteArray(input.size) { n -> val i = n + 1; j += s[i]; val y = s[j and 255]; s[i] = y; (input[n].u() xor s[(y + y) and 255]).toByte() }
        for (i in out.indices) {
            val value = ((out[i].u() shr 4) or (out[i].u() shl 4)) and 255
            val next = out[if (i + 1 < out.size) i + 1 else 0].u()
            val reversed = Integer.reverse((next xor value) and 255) ushr 24
            out[i] = (reversed xor 20).inv().toByte()
        }
        return (hex("8404") + byteArrayOf(random.toByte(),(random shr 8).toByte(),0x40,1) + out).hex()
    }
    private fun deviceProto(deviceId: String) = proto(f(1,1,"sint"),f(2,2,"sint"),f(3,"8662"),f(4,deviceId),f(5,"Ai6svO3PyrwDOUSmO6ZcResxu"),f(6,"!noperm!"),f(7,-888888,"sint"),f(8,-888888,"sint"),f(9,3,"sint"),f(10,-888888,"sint"),f(11,"!notset!"),f(12,"Asia/Shanghai,8"),f(13,"zh_CN"),f(14,4,"sint"),f(16,255.24993896484375,"float"),f(17,35.58599090576172,"float"),f(18,3.467449188232422,"float"),f(19,3.467449188232422,"float"),f(20,255.1754913330078,"float"),f(21,42.17544174194336,"float"),f(22,"16"),f(23,41,"sint"),f(24,36,"sint"),f(25,1728388016635L,"sint"),f(26,1728388016635L,"sint"),f(27,1728388016635L,"sint"),f(28,1728388016637L,"sint"),f(29,-1,"sint"),f(30,"25053RT47C"),f(31,"Redmi"),f(32,"25053RT47C"),f(33,"25053RT47C"),f(34,"Xiaomi"),f(35,"Redmi"),f(36,"Redmi"),f(38,31,"sint"))
    private fun xmxor(data: ByteArray, key: ByteArray): ByteArray {
        val encoded = IntArray(data.size)
        for (i in data.indices) {
            val at = (i * 4) and 28; val d0 = key[at].u(); val d1 = key[at + 1].u(); val v = data[i].u()
            var d2 = (((v shl 4) or (v ushr 4)) and 255) + d0
            d2 = d2.inv() xor d1; d2 = (((d2 and 255) shl 3) or ((d2 and 255) ushr 5)) and 255
            d2 = (d2 + d1) and 255; d2 = (d2 xor d0) and 255; encoded[data.size-i-1] = d2.inv() and 255
        }
        val last = encoded.last() xor encoded[encoded.size-2]; val first = encoded[0]
        encoded[0] = (last.inv() + first) and 255
        encoded[1] = ((encoded[0] xor encoded.last() xor 254) + encoded[1]) and 255
        encoded[2] = (encoded[2] + ((last-first) xor (((encoded[1] shl 3) or (encoded[1] ushr 5)) and 255) xor 2)) and 255
        for (i in 0 until encoded.size-4) {
            val temp = (((encoded[i+2] shl 3) or (encoded[i+2] ushr 5)) and 255) xor encoded[i+1] xor (i+3)
            encoded[i+3] = (temp.inv() + encoded[i+3]) and 255
        }
        encoded[encoded.size-1] = encoded.last() xor encoded[encoded.size-2]
        encoded[0] = ((encoded[0] xor encoded[1]) + encoded.drop(1).sum()) and 255
        return encoded.map { it.toByte() }.toByteArray()
    }
    fun medusa(url: String, body: ByteArray?, ts: Int, deviceId: String = VendorConstants.device.getValue("device_id"), versionName: String = VendorConstants.device.getValue("version_name")): String {
        val bodyMd5 = body?.let { digest("MD5",it) } ?: ByteArray(16)
        val query = url.substringAfter('?').toByteArray(); val querySm3 = sm3(query)
        val queryBodyTs = hashF13(querySm3,bodyMd5,ts)
        val nested = proto(f(1,111,"sint"),f(2,10,"sint"),f(3,694367,"sint"),f(5,586952199,"sint"))
        val messageRand = unsigned(rand()); val envLaunch = (random()*21).toInt()+100; val envPid = (random()*2000).toInt()+10001
        val env = proto(f(1,envLaunch,"sint"),f(2,146331399,"sint"),f(3,146331396,"sint"),f(5,7,"sint"),f(6,"v04.06.04.03-bugfix"),f(7,envPid,"sint"),f(12,deviceProto(deviceId)),f(13,proto(f(1,clock()/1000,"sint"),f(2,-2,"sint"),f(4,200,"sint"))),f(14,versionName))
        val queryHash = sm3(query+bodyMd5+"none".toByteArray())
        val message = proto(f(1,hex("f7e85ffad7d7dc3bd62ac87057cf6118")),f(2,3,"sint"),f(3,messageRand,"sint"),f(4,"8662"),f(5,deviceId),f(6,"1588093228"),f(7,versionName),f(8,"v04.06.04-ml-android"),f(9,67503104,"sint"),f(10,hex("4001000000000000")),f(12,ts,"sint"),f(13,queryBodyTs),f(14,querySm3.copyOf(6)),f(15,nested),f(16,"AXYQOS6n2m60x1fVZHIrH3iol"),f(17,ts,"sint"),f(19,queryHash),f(20,"none"),f(21,312,"sint"),f(23,env),f(24,"""{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}"""))
        val r = rand(); val signKey = hex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5")
        val hash = sm3(signKey+le32(r)+signKey); val d1 = (r shr 16) and 255
        val seed = le32((((d1 shl 11) or (r ushr 24)) xor (d1 shr 5) xor d1).inv())
        val transformed = (hex("4001000000000000")+xmxor(message,hash)).reversedArray()
        for (i in transformed.indices) transformed[i] = (transformed[i].u() xor seed[i.inv() and 3].u()).toByte()
        val check = ((querySm3[0].u() and 63) shl 14) or 0x18000001 or ((queryBodyTs[0].u() and 63) shl 8)
        val packed = byteArrayOf(0x35)+le32(rand())+le32(check)+transformed+byteArrayOf((r shr 16).toByte(),(r shr 24).toByte())
        val encrypted = AesV3(hex("f1593376766ea98d34f31b057a9d5be4"),ts).encrypt(packed,hex("1fe109a4125283f418de9e051a969e12"))
        val versionBytes = hex("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
        val prefix = (0 until 20 step 4).fold(byteArrayOf()) { a,i -> a+le32(versionBytes.i32(i,true) xor ts) }
        return (prefix+byteArrayOf(r.toByte(),(r shr 8).toByte(),0,1)+encrypted).base64()
    }
    fun helios(ts: Int): String {
        val random = rand()
        val ascii = digest("MD5",le32(random)+"8662".toByteArray()).hex().toByteArray()
        val words = (0..3).map { ascii.i64(it*8,true) }.toMutableList(); val table = mutableListOf(words[0])
        var b0 = words.removeAt(0); var b8 = words.removeAt(0)
        for (i in 0 until 34) {
            var x8 = (java.lang.Long.rotateRight(b8,8)+b0) xor i.toLong()
            words.add(x8); x8 = x8 xor java.lang.Long.rotateRight(b0,61); table.add(x8); b0=x8; b8=words.removeAt(0)
        }
        val text = "$ts-1588093228-8662".toByteArray(); val padding = 16 - text.size % 16
        val data = text+ByteArray(padding) { padding.toByte() }
        var output = le32(random)
        for (at in data.indices step 16) {
            var a = data.i64(at,true); var b = data.i64(at+8,true)
            for (i in 0 until 34) { b = table[i] xor (a + java.lang.Long.rotateRight(b,8)); a = b xor java.lang.Long.rotateRight(a,61) }
            output += le64(a)+le64(b)
        }
        return output.base64()
    }
    fun sign(base: String, values: Map<String,String>, body: ByteArray? = null): Request {
        val now = clock(); val ts = (now / 1000).toInt()
        var ticket = now; var query: String
        for (offset in 0..127) {
            ticket = now + offset; query = encode(values + mapOf("ts" to ts.toString(), "_rticket" to ticket.toString()))
            if (branch(query,body,ts) == 0) {
                val url = "$base?$query"
                val random = (random()*65536).toInt()
                val headers = linkedMapOf("User-Agent" to (if (body != null) VendorConstants.VIDEO_UA else VendorConstants.APP_UA),"Accept" to "application/json; charset=utf-8,application/x-protobuf", "x-ss-req-ticket" to ticket.toString(),"x-tt-request-tag" to "t=0;n=0","sdk-version" to "2","passport-sdk-version" to "50561","x-xs-from-web" to "0","x-khronos" to ts.toString(),"x-ladon" to be32(ts).base64(),"x-argus" to le32(ts).base64(),"x-gorgon" to gorgon(query,body,ts,random),"x-helios" to helios(ts),"x-medusa" to medusa(url,body,ts),"x-tt-dt" to "")
                if (body != null) { headers["Content-Type"]="application/json; charset=UTF-8"; headers["x-vc-bdturing-sdk-version"]="3.7.2.cn"; headers["x-ss-stub"]=digest("MD5",body).hex().uppercase() }
                return Request(url,headers,body)
            }
        }
        error("无法生成请求签名")
    }

    /**
     * Playback-model signing (upstream 6e43927): multi_video_model/v1 requires the six
     * signature headers; the hash branch must avoid the server-rejected value 1 (the
     * legacy [sign] pins branch 0 for the retired drpys chain - keep that behavior).
     * The query profile comes from the caller (AppApiTransport's 73532 profile).
     */
    fun signPlayback(base: String, values: Map<String,String>, body: ByteArray?, userAgent: String = VendorConstants.VIDEO_UA): Request {
        val now = clock(); val ts = (now / 1000).toInt()
        var ticket = now; var query: String
        for (offset in 0..127) {
            ticket = now + offset; query = Signer.encode(values + mapOf("_rticket" to ticket.toString()))
            if (branch(query, body, ts) != 1) {
                val url = "$base?$query"
                val random = (random()*65536).toInt()
                // medusa must bind the SAME device id / version as the query string
                // (upstream hgParamOf(query, "device_id"/"version_name")) or risk
                // control answers 110001.
                val headers = linkedMapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json; charset=utf-8,application/x-protobuf",
                    "x-ss-req-ticket" to ticket.toString(),
                    "sdk-version" to "2",
                    "x-xs-from-web" to "0",
                    "x-khronos" to ts.toString(),
                    "x-ladon" to be32(ts).base64(),
                    "x-argus" to le32(ts).base64(),
                    "x-gorgon" to gorgon(query, body, ts, random),
                    "x-helios" to helios(ts),
                    "x-medusa" to medusa(url, body, ts,
                        deviceId = values["device_id"] ?: "0",
                        versionName = values["version_name"] ?: "7.1.3.32"))
                if (body != null) { headers["Content-Type"] = "application/json; charset=utf-8"; headers["x-ss-stub"] = digest("MD5", body).hex().uppercase() }
                return Request(url, headers, body)
            }
        }
        error("无法生成播放签名")
    }
    companion object {
        val BRANCH2_SV = intArrayOf(
            0xa7aefe20.toInt(),0x7149f1d6.toInt(),0x47e4ca07.toInt(),0xe9b58f67.toInt(),0x93b924de.toInt(),0xc614d0f5.toInt(),0x38afe0ef.toInt(),0xb2bbad73.toInt(),
            0xe24444c3.toInt(),0x9d3aec9b.toInt(),0xdf7b37e4.toInt(),0xd8b16d40.toInt(),0xf8ac31b8.toInt(),0x76b9a90b.toInt(),0x31d833ee.toInt(),0x953fce64.toInt(),
            0x353595a4.toInt(),0x4609c13b.toInt(),0x36925008.toInt(),0x8c6d0925.toInt(),0x5df5c177.toInt(),0x1cfbf52b.toInt(),0x8a4fa7f0.toInt(),0x114ca35e.toInt(),
            0x8193f984.toInt(),0x7a7a8733.toInt(),0x316ab4d5.toInt(),0x3c20cfc9.toInt(),0xa6d84453.toInt(),0x3a18500c.toInt(),0x798ec47a.toInt(),0x97a76b28.toInt(),
            0x66c4ff96.toInt(),0x51716443.toInt(),0x0dd2fc3b.toInt(),0xb5696da7.toInt(),0xbbeb3ac5.toInt(),0x5c53d204.toInt(),0xd32608ce.toInt(),0x7279b9ec.toInt(),
            0xf4188ecf.toInt(),0xf7d793db.toInt(),0x332cc491.toInt(),0xab76ae15.toInt(),0x9bebe727.toInt(),0x18a01384.toInt(),0x5be9f8a7.toInt(),0x5f90a754.toInt(),
            0x39b663c0.toInt(),0x36673c83.toInt(),0x7c92f514.toInt(),0x9d7d94d7.toInt(),0xe2e8d9aa.toInt(),0x5f7e9ea9.toInt(),0x7abd4551.toInt(),0x569e05da.toInt(),
            0x40a25632.toInt(),0x3df5a9a5.toInt(),0xbab37d80.toInt(),0x454286dc.toInt(),0x3f5d4e78.toInt(),0x03d7b75d.toInt(),0xb1fe4af7.toInt(),0xa5ab26a3.toInt())
        val BRANCH2_ORDERS = hex("0f0704000908030a060b050d0e010c02" +
                "0f05080c0009020103070e060b0a0d04" +
                "060500070c000a04080f010b0d09020e" +
                "060b02050403080101070a000d0c090e" +
                "0d070e0f0b0208030c0509010004060a" +
                "0d0902060f0b0a040807000c0503010e" +
                "0c090f07060f030e020d0405010b0a00" +
                "0c050a090e0802040407030f01060b00" +
                "0b0f0408020a0700090d06010e03050c" +
                "0b060a0508020c03070f0e090d000104" +
                "0906080f050800040a0b030d01020c0e" +
                "090d0c0604070a03030f00080105020e" +
                "01000d0f090a0b0e0402080703060c05" +
                "01080a0c0f0905060b000304020e070d" +
                "04080f000c0f0e0d0a01060207090503" +
                "040205080d0b0a0606000e0f070c0903" +
                "0a08040f000b01060d0c0709030e0502" +
                "0a070b050f00020e0108030d0c060904" +
                "0d070f08050f06040b0a0e0c09000203" +
                "0d0c020704010b0e0e08060f09050003")
        val BRANCH2_N1 = intArrayOf(0x8980f29b.toInt(),0xeb549c7f.toInt(),0xb08726db.toInt(),0xd40cb5e6.toInt(),0xe8f559e4.toInt())
        val BRANCH2_SEED = byteArrayOf(0x84.toByte(),0x96.toByte(),0x77.toByte(),0x9d.toByte(),0xd4.toByte(),0x15.toByte(),0x0b.toByte(),0xf8.toByte())
        fun encode(values: Map<String,String>) = values.entries.joinToString("&") { (k,v) -> "${encode(k)}=${encode(v)}" }
        fun encode(value: String): String = URLEncoder.encode(value,"UTF-8").replace("+","%20").replace("%7E","~")
    }
}
