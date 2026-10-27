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
 * query-string profile, and request signing.
 *
 * Signature history: the lightweight X-Khronos/X-Gorgon pair served all catalog
 * endpoints until the 2026-10 playback migration; the server now returns HTTP 200
 * + empty body for it on every signed endpoint (landpage feed included). All
 * signed calls therefore go through the six-header chain (x-gorgon/x-khronos/
 * x-ladon/x-argus/x-helios/x-medusa) in [Signer.signPlayback].
 *
 * Extracted from ContentRepository so playback channels (StreamSource implementations)
 * can share the same transport without reaching into the repository's internals.
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
    /** Six-header signature chain (shared with the playback channel). */
    private val signer = Signer(clock = { clock() })

    /** App client UA: must match the version_code/version_name in the query profile. */
    val userAgent = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    private fun newDeviceId(): String {
        // Server risk control rejects fixed/fake values with 110001; the App-side rule
        // is 1e15 + 48-bit random (16 digits) - upstream live probe verified this shape.
        val bytes = ByteArray(8); random.nextBytes(bytes)
        var value = 0L; for (b in bytes) value = (value shl 8) or (b.toLong() and 0xff)
        return (1_000_000_000_000_000L + (value and 0xffffffffffffL) % 1_000_000_000_000_000L).toString()
    }

    // Retired 2026-10: the lightweight X-Khronos/X-Gorgon signature (appSign) got the
    // HTTP-200-empty-body treatment on every signed endpoint; all signing now goes
    // through Signer.signPlayback (six headers). rotl8/rev8 helpers went with it.

    /** Mirrors Go url.Values.Encode(): sorted keys + URL escaping. */
    fun encodeQuery(values: Map<String,String>): String {
        val sorted = TreeMap(values)
        return sorted.entries.joinToString("&") { (k,v) ->
            "${urlEscape(k)}=${urlEscape(v)}"
        }
    }

    /** Current wall clock (ms) - the playback channel refreshes _rticket between retries. */
    fun clockMs(): Long = clock()

    /** Split a query string back into a map (for _rticket refresh on retry). */
    fun parseQuery(url: String): Map<String,String> {
        val query = url.substringAfter("?", "")
        if (query.isEmpty()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (key.isEmpty()) null
            else key to value
        }.toMap()
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

    /** Query-string profile shared by every App API call (also used by the danmaku signer).
     *  NO ts param: the landpage feed rejects queries carrying it (HTTP 200 empty body);
     *  the playback endpoint is ts-insensitive, so one profile serves both. */
    fun queryProfile(): Map<String,String> = linkedMapOf(
        "aid" to "8662", "app_name" to "novelread", "version_code" to "73532", "version_name" to "7.3.5.32",
        "manifest_version_code" to "73532", "update_version_code" to "73532", "channel" to "update_64",
        "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C",
        "device_brand" to "Redmi", "language" to "zh", "os_api" to "36", "os_version" to "16",
        "resolution" to "1280*2772", "dpi" to "520", "ac" to "wifi",
        "device_id" to deviceId, "iid" to installId,
        "_rticket" to clock().toString())

    /**
     * One App API call. [signed]=true adds the full six-header signature chain
     * (x-gorgon/x-khronos/x-ladon/x-argus/x-helios/x-medusa) - the lightweight
     * X-Khronos/X-Gorgon pair was retired server-side along with the playback
     * endpoint migration (2026-10): catalog feeds now return HTTP 200 + empty
     * body for it. [signed]=false sends only the plain client headers.
     *
     * [identity] carries a server-registered device (PlaybackDeviceSession): the
     * 2026-10-27 empty-body crackdown rejects unregistered device_ids on the
     * landpage feed too, so feed calls pass the registered values + cookies here.
     * Upstream pins the 73532 player versions on this endpoint either way.
     */
    fun request(path: String, payload: String?, signed: Boolean = true,
                identity: PlaybackDeviceSession.Profile? = null): JSONObject {
        val body = payload?.toByteArray(Charsets.UTF_8)
        if (!signed) {
            val query = encodeQuery(queryProfile())
            val headers = linkedMapOf(
                "User-Agent" to userAgent, "Accept" to "application/json", "X-XS-From-Web" to "0", "Sdk-Version" to "2")
            return requestUrl("$APP_BASE$path?$query", body, headers)
        }
        val values = if (identity != null) identity.values.toMutableMap().apply {
            for (k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) this[k] = "73532"
            this["version_name"] = "7.3.5.32"
        } else queryProfile()
        val signedReq = signer.signPlayback("$APP_BASE$path", values, body, userAgent)
        val headers = signedReq.headers.toMutableMap()
        if (identity != null && identity.cookie.isNotBlank()) headers["Cookie"] = identity.cookie
        return requestUrl(signedReq.url, body, headers)
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
