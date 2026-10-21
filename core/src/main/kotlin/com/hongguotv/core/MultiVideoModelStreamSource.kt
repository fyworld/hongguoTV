// Kotlin port of upstream HongguoTV-Phone-TV-NoAds 6e43927 (multi_video_model/v1);
// GPL-3.0-only. See kotlin-tv/NOTICE.md.
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Primary channel (upstream 0.1.24 / 6e43927): the playback model endpoint moved to
 * /novel/player/multi_video_model/v1/ - the old /novel/player/video_model/v1/ now
 * returns HTTP 200 with an empty body for every request shape.
 *
 * Differences carried over from the upstream fix:
 *  - request body: biz_param + mixed_video_id_map (slot key fixed "1004",
 *    video_platform fixed 1024; other values return code 100001)
 *  - the endpoint REQUIRES the full six-header signature chain
 *    (x-gorgon/x-khronos/x-ladon/x-argus/x-helios/x-medusa); any missing header
 *    yields HTTP 200 + empty body (no error code)
 *  - the response shape is not fixed: data[vid].video_model, data.video_model, or a
 *    single-key entry; video_model may carry video_list directly or only a
 *    fallback_api (plain GET -> video_info.data, addresses protected by key_seed)
 */
internal class MultiVideoModelStreamSource(
    private val transport: AppApiTransport,
    private val http: OkHttpClient,
) : StreamSource {
    override val name = "multi-video-model"

    private val signer = Signer()

    /** Fallback_api hosts are pinned to the vendor's own domains (upstream keeps the same guard). */
    private val fallbackDomains = listOf("fqnovel.com", "snssdk.com")

    override fun stream(id: String, maxQuality: Int, type: ContentType): StreamInfo {
        val payload = JSONObject()
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0)
                .put("device_level", 3)
                .put("disable_digg_stat", false)
                .put("need_all_video_definition", true)
                .put("need_mp4_align", false)
                .put("use_os_player", false)
                .put("use_server_dns", false)
                .put("video_platform", 1024))
            .put("mixed_video_id_map", JSONObject().put("1004", org.json.JSONArray().put(id)))
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        // The six-header chain signs the FINAL url bytes; build the query first, bump
        // _rticket until the hash branch leaves the server-rejected value 1.
        val base = "${AppApiTransport.APP_BASE}$PATH"
        // UA must match the query profile's version_code (73532) or risk control
        // answers 110001.
        val values = transport.queryProfile()
        val signed = signer.signPlayback(base, values, body, transport.userAgent)
        val data = transport.requestUrl(signed.url, body, signed.headers)
        val map = data.optJSONObject("data") ?: throw IOException("播放信息为空")
        val model = resolveModel(map, id)
        if (hasVideoList(model)) return select(model, maxQuality, null)
        // Second shape: only a fallback_api URL; plain GET (no signature) -> video_info.data.
        val fallback = model.str("fallback_api")
        if (fallback.isEmpty()) throw IOException("红果 App 接口未返回本集片源地址")
        val outer = fallbackJson(fallback)
        val info = outer.optJSONObject("video_info")
        val inner = info?.optJSONObject("data") ?: outer.optJSONObject("data")
            ?: throw IOException("红果 App 接口未返回本集片源模型")
        val seed = inner.str("key_seed").takeIf { it.isNotEmpty() }?.let { s ->
            runCatching { decode64(s) }.getOrNull() ?: throw IOException("红果 App 媒体地址密钥无效")
        }
        val merged = JSONObject()
        model.keys().forEach { k -> merged.put(k, model.get(k)) }
        inner.keys().forEach { k -> merged.put(k, inner.get(k)) }
        return select(merged, maxQuality, seed)
    }

    /** Three response shapes: data[vid].video_model, data.video_model, single-key entry. */
    private fun resolveModel(data: JSONObject, id: String): JSONObject {
        data.optJSONObject(id)?.let { entry ->
            entry.opt("video_model")?.let { return normalizeModel(it) }
        }
        data.opt("video_model")?.let { return normalizeModel(it) }
        for (key in data.keys()) {
            val entry = data.optJSONObject(key) ?: continue
            val candidate = entry.opt("video_model") ?: continue
            runCatching { return normalizeModel(candidate) }
        }
        throw IOException("红果 App 接口未返回该集播放信息")
    }

    private fun normalizeModel(value: Any): JSONObject = when (value) {
        is JSONObject -> value
        is String -> JSONObject(value)
        else -> throw IOException("播放信息格式异常")
    }

    private fun hasVideoList(model: JSONObject): Boolean {
        model.optJSONObject("video_list")?.let { return it.length() > 0 }
        model.optJSONArray("video_list")?.let { return it.length() > 0 }
        return false
    }

    /** Plain GET on the fallback_api host (no signature headers, UA only). */
    private fun fallbackJson(rawUrl: String): JSONObject {
        val host = rawUrl.substringAfter("https://", "").substringBefore('/').lowercase(Locale.ROOT)
        if (!fallbackDomains.any { host == it || host.endsWith(".$it") }) throw IOException("红果片源地址不在允许的域名内")
        val request = Request.Builder().url(rawUrl)
            .header("User-Agent", transport.userAgent)
            .header("Accept", "application/json")
            .build()
        return RequestScope.execute(http.newCall(request)) { response ->
            if (!response.isSuccessful) throw IOException("红果片源接口 HTTP ${response.code}")
            val text = response.body?.string() ?: throw IOException("红果片源接口返回空正文")
            if (text.isEmpty()) throw IOException("红果片源接口返回空正文")
            JSONObject(text)
        }
    }

    /** Pick a variant from video_list; decrypt key_seed-protected addresses when present. */
    private fun select(model: JSONObject, maxQuality: Int, seed: ByteArray?): StreamInfo {
        val videoList = model.optJSONObject("video_list") ?: run {
            val list = model.optJSONArray("video_list")
            if (list != null) { val obj = JSONObject(); for (i in 0 until list.length()) obj.put("$i", list.getJSONObject(i)); obj }
            else throw IOException("没有可用清晰度")
        }
        val rows = videoList.keys().asSequence().mapNotNull { videoList.optJSONObject(it) }
            .map { o ->
                val meta = o.optJSONObject("video_meta")
                val codec = (meta?.str("codec_type") ?: o.str("codec_type")).lowercase(Locale.ROOT)
                val definition = (meta?.str("definition") ?: o.str("definition")).substringBefore('p').toIntOrNull() ?: 0
                val height = (meta?.str("vheight") ?: o.str("vheight")).toIntOrNull() ?: 0
                val width = (meta?.str("vwidth") ?: o.str("vwidth")).toIntOrNull() ?: 0
                val quality = if (definition > 0) definition else if (width > 0 && (height == 0 || width < height)) width else height
                Triple(quality, codec, o)
            }.sortedByDescending { it.first }.toList()
        val playable = rows.filter { it.second !in setOf("bytevc2", "bvc2") }
        val compatible = playable.filter { it.second == "h264" || it.second == "avc1" }
        val pool = if (compatible.isNotEmpty()) compatible else playable
        val selected = pool.firstOrNull { it.first in 1..maxQuality } ?: pool.firstOrNull()
            ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item = selected.third
        var url = cleanUrl(item.str("main_url"))
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            // main_url may be base64, or key_seed-encrypted (spade layout a8 .. 01 00)
            val decoded = runCatching { decode64(url) }.getOrNull()
            val plain = when {
                decoded != null && seed != null && decoded.size >= 5 && decoded[0].u() == 0xa8 ->
                    runCatching { MediaCrypto.decryptUrl(url, seed) }.getOrNull()
                else -> null
            } ?: decoded?.let { runCatching { String(it, Charsets.UTF_8).trim() }.getOrNull() }
            if (plain != null && (plain.startsWith("https://") || plain.startsWith("http://"))) url = plain
            else throw IOException("播放地址无效")
        }
        val encryption = item.optJSONObject("encrypt_info")
        val spade = encryption?.str("spade_a")?.ifEmpty { item.str("spade_a") } ?: item.str("spade_a")
        val key = if (spade.isNotEmpty()) runCatching { MediaCrypto.deriveKey(spade) }.getOrNull() else null
        val quality = if (selected.first > 0) "${selected.first}P" + (if (selected.first > maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url, key, quality)
    }

    companion object {
        const val PATH = "/novel/player/multi_video_model/v1/"
    }
}
