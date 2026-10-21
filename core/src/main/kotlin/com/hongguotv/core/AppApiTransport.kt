// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.TreeMap

/**
 * Transport for the Hongguo App API (api5-normal-sinfonlineb.fqnovel.com): device identity,
 * query-string profile, and the lightweight X-Khronos/X-Gorgon request signature
 * (Kotlin port of upstream provider_hongguo_sign.go).
 *
 * Extracted from ContentRepository so playback channels (StreamSource implementations)
 * can share the same transport without reaching into the repository's internals.
 *
 * Signature compatibility note (upstream 0.1.10 / 8795bd04): the playback endpoint
 * /novel/player/video_model/v1/ REJECTS requests carrying the legacy signature headers
 * (HTTP 200 + empty body); it must be called UNSIGNED via [request] with
 * signed=false. Every other endpoint (landpage feed etc.) still requires the
 * signature and works with it.
 */
internal class AppApiTransport(
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()
    private val deviceId = newDeviceId()
    /** Upstream 6e43927 live probe pins iid to the device id; independent random
     *  install ids get flagged by risk control (110001). */
    private val installId = deviceId

    /** App client UA: must match the version_code/version_name in the query profile. */
    val userAgent = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    private fun newDeviceId(): String {
        // Server risk control rejects fixed/fake values with 110001; the App-side rule
        // is 1e15 + 48-bit random (16 digits) - upstream live probe verified this shape.
        val bytes = ByteArray(8); random.nextBytes(bytes)
        var value = 0L; for (b in bytes) value = (value shl 8) or (b.toLong() and 0xff)
        return (1_000_000_000_000_000L + (value and 0xffffffffffffL) % 1_000_000_000_000_000L).toString()
    }

    private fun rotl8(v: Int, n: Int) = ((v shl n) or (v ushr (8 - n))) and 0xff
    private fun rev8(v: Int): Int {
        var r = 0
        for (i in 0 until 8) if (v and (1 shl i) != 0) r = r or (1 shl (7 - i))
        return r
    }

    private fun appSign(query: String, body: ByteArray?): Map<String,String> {
        val now = clock() / 1000
        val nowMs = clock()
        val payload = ByteArray(20)
        val queryHash = java.security.MessageDigest.getInstance("MD5").digest(query.toByteArray())
        System.arraycopy(queryHash, 0, payload, 0, 4)
        if (body != null) {
            val bodyHash = java.security.MessageDigest.getInstance("MD5").digest(body)
            System.arraycopy(bodyHash, 0, payload, 4, 4)
        }
        payload[12] = 0; payload[13] = 6; payload[14] = 11; payload[15] = 28
        val ts = now.toInt()
        payload[16] = (ts ushr 24).toByte(); payload[17] = (ts ushr 16).toByte(); payload[18] = (ts ushr 8).toByte(); payload[19] = ts.toByte()
        val key = byteArrayOf(0x44,0xb9.toByte(),0xb9.toByte(),0xd9.toByte(),0xa4.toByte(),0xae.toByte(),0xf9.toByte(),0xfc.toByte(),0xa4.toByte(),0x93.toByte(),0xaa.toByte(),0x75.toByte(),0x7c.toByte(),0xa3.toByte(),0xc2.toByte(),0xc4.toByte(),0xa4.toByte(),0x96.toByte(),0x93.toByte(),0x8f.toByte())
        for (i in 0 until 20) payload[i] = (payload[i].toInt() xor key[i].toInt()).toByte()
        for (i in 0 until 20) {
            val mixed = rotl8(payload[i].toInt() and 0xff, 4) xor (payload[(i + 1) % 20].toInt() and 0xff)
            payload[i] = ((rev8(mixed) xor 0xff xor 20) and 0xff).toByte()
        }
        val signature = byteArrayOf(0x84.toByte(), 0x04, 0x40, 0x1c, 0, 0) + payload
        val gorgon = StringBuilder()
        for (b in signature) gorgon.append(String.format("%02x", b))
        val headers = linkedMapOf(
            "User-Agent" to userAgent, "Accept" to "application/json", "X-XS-From-Web" to "0", "Sdk-Version" to "2",
            "X-Khronos" to now.toString(), "X-Gorgon" to gorgon.toString(), "X-SS-Req-Ticket" to nowMs.toString())
        if (body != null) {
            headers["Content-Type"] = "application/json; charset=utf-8"
            val md = java.security.MessageDigest.getInstance("MD5").digest(body)
            val stub = StringBuilder(); for (b in md) stub.append(String.format("%02X", b))
            headers["X-SS-STUB"] = stub.toString()
        }
        return headers
    }

    /** Mirrors Go url.Values.Encode(): sorted keys + URL escaping. */
    fun encodeQuery(values: Map<String,String>): String {
        val sorted = TreeMap(values)
        return sorted.entries.joinToString("&") { (k,v) ->
            "${urlEscape(k)}=${urlEscape(v)}"
        }
    }
    fun urlEscape(s: String): String {
        val out = StringBuilder()
        for (c in s) when {
            c.isLetterOrDigit() -> out.append(c)
            c == '-' || c == '_' || c == '.' || c == '~' -> out.append(c)
            c == ' ' -> out.append('+')
            else -> {
                for (b in c.toString().toByteArray(Charsets.UTF_8)) out.append(String.format("%%%02X", b))
            }
        }
        return out.toString()
    }

    /** Query-string profile shared by every App API call (also used by the danmaku signer). */
    fun queryProfile(): Map<String,String> = linkedMapOf(
        "aid" to "8662", "app_name" to "novelread", "version_code" to "73532", "version_name" to "7.3.5.32",
        "manifest_version_code" to "73532", "update_version_code" to "73532", "channel" to "update_64",
        "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C",
        "device_brand" to "Redmi", "language" to "zh", "os_api" to "36", "os_version" to "16",
        "resolution" to "1280*2772", "dpi" to "520", "ac" to "wifi",
        "device_id" to deviceId, "iid" to installId,
        "ts" to (clock() / 1000).toString(),
        "_rticket" to clock().toString())

    /**
     * One App API call. [signed]=true adds the X-Khronos/X-Gorgon signature headers
     * (required by catalog endpoints); [signed]=false sends only the plain client
     * headers (required by the playback endpoint - see the class doc).
     */
    fun request(path: String, payload: String?, signed: Boolean = true): JSONObject {
        val query = encodeQuery(queryProfile())
        val body = payload?.toByteArray(Charsets.UTF_8)
        val headers = if (!signed) linkedMapOf(
            "User-Agent" to userAgent, "Accept" to "application/json", "X-XS-From-Web" to "0", "Sdk-Version" to "2")
        else appSign(query, body)
        return requestUrl("$APP_BASE$path?$query", body, headers)
    }

    /**
     * Pre-signed request (multi_video_model playback channel): the URL and headers
     * were produced by the caller's six-header signature chain; this only sends and
     * validates the response envelope.
     */
    fun requestUrl(url: String, body: ByteArray?, headers: Map<String,String>): JSONObject {
        val builder = Request.Builder().url(url)
        for ((k,v) in headers) builder.header(k,v)
        if (body != null) builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return RequestScope.execute(http.newCall(builder.build())) { response ->
            if (!response.isSuccessful) throw IOException("App 接口请求失败（HTTP ${response.code}）")
            val text = response.body?.string() ?: throw IOException("App 接口响应为空")
            if (text.isEmpty()) throw IOException("App 接口返回空数据")
            val data = JSONObject(text)
            val code = data.optInt("code", data.optInt("Code", 0))
            if (code != 0) throw IOException("App 接口暂不可用（$code）")
            data
        }
    }

    companion object {
        const val APP_BASE = "https://api5-normal-sinfonlineb.fqnovel.com"
    }
}
