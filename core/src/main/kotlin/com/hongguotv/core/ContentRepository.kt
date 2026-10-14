// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Detail(val series: Series, val episodes: List<String>)
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null, val warning: String = "")
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

/**
 * Content repository rebuilt on the working provider chain from HongguoTV-Phone-TV-NoAds:
 *  - catalog: App landpage API with the lightweight X-Khronos/X-Gorgon signature
 *  - detail:  website /detail page (App video_detail currently returns an empty body)
 *  - stream:  App video_model/v1 API (verified: 5 qualities + CENC spade_a keys)
 *  - search / ranking / suggestions: website pages and JSON endpoints
 * The old drpys multi_video_model chain (x-argus/x-ladon/x-helios/x-medusa) is retired.
 */
class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val site = "https://hongguoduanju.com"
    private val appBase = "https://api5-normal-sinfonlineb.fqnovel.com"
    private val random = SecureRandom()
    private val deviceId = newDeviceId()
    private val installId = newDeviceId()
    private val appUa = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
    private val webUa = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1"

    // ---------- shared HTTP helpers ----------

    private fun text(url: String, headers: Map<String,String> = emptyMap()): String {
        val builder = Request.Builder().url(url)
        for ((k,v) in headers) builder.header(k,v)
        return RequestScope.execute(http.newCall(builder.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        }
    }

    private fun webText(url: String, referer: String = "$site/") = text(url, mapOf(
        "User-Agent" to webUa, "Referer" to referer, "Accept-Language" to "zh-CN,zh;q=0.9"))

    // ---------- App API with the lightweight signature (from provider_hongguo_sign.go) ----------

    private fun newDeviceId(): String {
        val bytes = ByteArray(8); random.nextBytes(bytes)
        var value = 0L; for (b in bytes) value = (value shl 8) or (b.toLong() and 0xff)
        return (1_000_000_000_000_000_000L + (value and 0x7fffffffffffffffL) % 8_000_000_000_000_000_000L).toString()
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
            "User-Agent" to appUa, "Accept" to "application/json", "X-XS-From-Web" to "0", "Sdk-Version" to "2",
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
    private fun encodeQuery(values: Map<String,String>): String {
        val sorted = TreeMap(values)
        return sorted.entries.joinToString("&") { (k,v) ->
            "${urlEscape(k)}=${urlEscape(v)}"
        }
    }
    private fun urlEscape(s: String): String {
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

    private fun appRequest(path: String, payload: String?): JSONObject {
        val values = linkedMapOf(
            "aid" to "8662", "app_name" to "novelread", "version_code" to "73532", "version_name" to "7.3.5.32",
            "manifest_version_code" to "73532", "update_version_code" to "73532", "channel" to "update_64",
            "device_platform" to "android", "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C",
            "device_brand" to "Redmi", "language" to "zh", "os_api" to "28", "os_version" to "9",
            "resolution" to "1280*2772", "dpi" to "520", "ac" to "wifi",
            "device_id" to deviceId, "iid" to installId,
            "_rticket" to clock().toString())
        val query = encodeQuery(values)
        val body = payload?.toByteArray(Charsets.UTF_8)
        val headers = appSign(query, body)
        val builder = Request.Builder().url("$appBase$path?$query")
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

    // ---------- catalog (App landpage) ----------

    private val catalogCursors = linkedMapOf<String, Pair<Int,String>>() // genre key -> (offset, session)

    private fun card(item: JSONObject): Series {
        val o = if (item.optJSONObject("video_data") != null) item.optJSONObject("video_data")!! else item
        val count = o.optInt("episode_cnt", o.optJSONObject("series_episode_info")?.optInt("episode_cnt", 0) ?: 0)
        val tags = o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(
            o.str("series_id_str").ifEmpty { o.str("series_id") },
            o.str("series_title").ifEmpty { o.str("series_name").ifEmpty { o.str("title").ifEmpty { item.str("name") } } },
            cleanUrl(o.str("series_cover").ifEmpty { o.str("cover") }),
            o.str("series_intro").ifEmpty { o.str("description") },
            o.str("episode_right_text").ifEmpty { if(count>0) "全 $count 集" else "" },
            (0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }

    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }

    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if (type == ContentType.COMIC) return comicRanking(page)
        val genre = "short_play"
        val cursorKey = "category:$genre"
        // The App feed is cursor-based: the server returns next_offset + session_id and
        // may return ANY number of items per call (5, 14, 23… — it used to be a fixed 18).
        // Page 1 keeps fetching forward until a full shelf (12+) is collected, so the home
        // shelves and the short-drama grid always open with a proper page of content.
        var cursor = synchronized(catalogCursors) { catalogCursors[cursorKey] }
        var offset = if (page == 1) 0 else cursor?.first ?: (page - 1) * 18
        var sessionId = if (page == 1) "" else cursor?.second ?: ""
        val all = mutableListOf<Series>()
        var hasMore = false
        var round = 0
        while (true) {
            val payload = JSONObject()
                .put("req_scene", "default").put("offset", offset).put("limit", 18)
                .put("req_type", "only_content").put("need_selector_panel", false)
                .put("client_req_type", if (offset > 0) 2 else 3)
                .put("session_id", sessionId).put("filter_ids", "")
                .put("select_items", JSONObject()
                    .put("genre", JSONArray().put(genre)).put("sort", JSONArray().put("online_time"))
                    .put("gender", JSONArray()).put("category_dim_theme", JSONArray())
                    .put("category_dim_role", JSONArray()).put("category_dim_epoch", JSONArray())
                    .put("online_time", JSONArray()).put("creation_status", JSONArray()))
            val data = appRequest("/reading/distribution/category/landpage/v/", payload.toString())
            val rows = data.optJSONObject("data")?.optJSONArray("video_data") ?: throw IOException("首页数据暂不可用")
            all += valid(rows.objects().map(::card))
            hasMore = data.optJSONObject("data")?.optBoolean("has_more", false) ?: false
            offset = data.optJSONObject("data")?.optInt("next_offset", offset + 18) ?: offset + 18
            sessionId = data.optJSONObject("data")?.str("session_id") ?: sessionId
            synchronized(catalogCursors) { catalogCursors[cursorKey] = offset to sessionId }
            round++
            // Enough content, nothing more, or a safety cap on follow-up rounds.
            if (page != 1 || all.size >= 12 || !hasMore || round >= 3) break
        }
        return CatalogPage(all, hasMore && page < 100)
    }

    fun comicRanking(page: Int = 1): CatalogPage = ranking(RankBoard.COMIC,page)

    /** The four website boards: 总热播榜 / 真人剧榜 / 漫剧榜 / AI剧榜. */
    fun ranking(board: RankBoard, page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(webText("$site/rank/${board.path}?page=$page"),page,board)
    }

    // ---------- search (website) ----------

    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        // The website search does not constrain content type; use it for both.
        val encoded = urlEscape(keyword)
        val data = extractRouter(webText("$site/search/$encoded?page=$page"))
        val loader = data.optJSONObject("loaderData") ?: throw IOException("搜索数据暂不可用")
        val section = (loader.keys().asSequence().mapNotNull { loader.optJSONObject(it) }
            .firstOrNull { it.length() > 0 && it.optJSONArray("searchList") != null })
            ?: throw IOException("搜索数据暂不可用")
        val rows = section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val items = valid(rows.objects().map(::card))
        return CatalogPage(items, items.isNotEmpty() && page < 100)
    }

    // ---------- search suggestions (pinyin-initials capable, from provider_hongguo_suggestions.go) ----------

    private val suggestionCache = linkedMapOf<String, Pair<Long,List<String>>>()

    /** Search suggestions for the TV keyboard: supports pinyin initials like "BFLC". Cached 2 minutes. */
    fun suggestions(query: String): List<String> {
        val keyword = SearchSuggest.normalizeKeyword(query)
        if (keyword.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        synchronized(suggestionCache) {
            suggestionCache.entries.removeAll { now >= it.value.first }
            if (suggestionCache.size >= 128) suggestionCache.remove(suggestionCache.keys.first())
            val cached = suggestionCache[keyword]
            if (cached != null && now < cached.first) return cached.second
        }
        val encoded = urlEscape(keyword)
        val body = text("$site/incent_resource/suggestion?app_id=8662&query=$encoded&count=10", mapOf(
            "User-Agent" to webUa, "Referer" to "$site/", "Accept" to "application/json", "Accept-Language" to "zh-CN,zh;q=0.9"))
        val records = SearchSuggest.parseRecords(body)
        if (records.isEmpty()) throw IOException("搜索联想暂不可用")
        val names = SearchSuggest.suggestionNames(records)
        synchronized(suggestionCache) { suggestionCache[keyword] = now + 2*60*1000L to names }
        return names
    }

    /** Enhanced search (phone-version): website page + name records merged, ranked by title match. */
    fun searchEnhanced(keyword: String, type: ContentType = ContentType.SHORT): CatalogPage {
        val query = SearchSuggest.normalizeKeyword(keyword)
        val pageResult = runCatching { search(query, 1, type) }
        val names = runCatching { nameSearch(query) }
        if (pageResult.isFailure && names.isFailure) throw IOException("搜索暂不可用，请稍后重试")
        var dramas = pageResult.getOrDefault(CatalogPage(emptyList(), false)).items
        dramas = SearchSuggest.mergeDramas(dramas, names.getOrDefault(emptyList()))
        // Season completion: query missing seasons through the name endpoint.
        if (dramas.isNotEmpty()) {
            val extraQueries = SearchSuggest.seasonQueries(query, dramas)
            for (extra in extraQueries) {
                val more = runCatching { nameSearch(extra) }.getOrDefault(emptyList())
                dramas = SearchSuggest.mergeDramas(dramas, more)
            }
        }
        val sorted = SearchSuggest.sortForQuery(dramas, query)
        val warning = when {
            pageResult.isFailure -> "红果综合搜索暂不可用，已保留名称匹配结果，可重试"
            names.isFailure -> "红果名称检索暂不可用，结果可能缺少部分剧集，可重试"
            else -> ""
        }
        return CatalogPage(sorted, sorted.isNotEmpty(), null, warning)
    }

    /** Name-search dramas from the suggestion endpoint (mirrors fetchHongguoSearchNames, count=50). */
    private fun nameSearch(keyword: String): List<Series> {
        val encoded = urlEscape(keyword)
        val body = text("$site/incent_resource/suggestion?app_id=8662&query=$encoded&count=50", mapOf(
            "User-Agent" to webUa, "Referer" to "$site/", "Accept" to "application/json", "Accept-Language" to "zh-CN,zh;q=0.9"))
        val records = SearchSuggest.parseRecords(body)
        return SearchSuggest.nameSearchDramas(records)
    }

    // ---------- detail (website page) ----------

    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val data = extractRouter(webText("$site/detail?series_id=$id"))
        val loader = data.optJSONObject("loaderData") ?: throw IOException("剧集详情暂不可用")
        val page = (loader.keys().asSequence().mapNotNull { loader.optJSONObject(it) }
            .firstOrNull { it.length() > 0 && it.optJSONObject("seriesDetail") != null })
            ?: throw IOException("剧集详情暂不可用")
        val o = page.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw = o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes = (0 until raw.length()).map { raw.getString(it) }
        if (episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size != episodes.size) throw IOException("分集数据异常")
        val count = o.optInt("episode_cnt", episodes.size)
        val tags = o.optJSONArray("tags") ?: JSONArray()
        val series = Series(
            id,
            o.str("series_name").ifEmpty { o.str("series_title") },
            cleanUrl(o.str("series_cover")),
            o.str("series_intro"),
            o.str("episode_right_text").ifEmpty { if(count>0) "全 $count 集" else "" },
            (0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
        return Detail(series, episodes)
    }

    // ---------- stream (App video_model/v1, verified working) ----------

    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val payload = JSONObject()
            .put("video_id", id).put("content_type", 1)
            .put("biz_param", JSONObject().put("need_all_video_definition", true).put("video_platform", 3))
        val data = appRequest("/novel/player/video_model/v1/", payload.toString())
        val map = data.optJSONObject("data") ?: throw IOException("播放信息为空")
        var model = map.opt("video_model")
        if (model is String) model = JSONObject(model)
        val modelObj = model as? JSONObject ?: throw IOException("播放信息格式异常")
        val videoList = modelObj.optJSONObject("video_list") ?: run {
            val list = modelObj.optJSONArray("video_list")
            if (list != null) { val obj = JSONObject(); for (i in 0 until list.length()) obj.put("$i", list.getJSONObject(i)); obj }
            else throw IOException("没有可用清晰度")
        }
        val rows = videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }
            .filter { it.second.str("main_url").isNotEmpty() }
            .map { (k, o) ->
                val meta = o.optJSONObject("video_meta") ?: JSONObject()
                val codec = meta.str("codec_type").lowercase(Locale.ROOT)
                val definition = meta.str("definition").toIntOrNull() ?: 0
                val height = meta.str("vheight").toIntOrNull() ?: 0
                val width = meta.str("vwidth").toIntOrNull() ?: 0
                val quality = if (definition > 0) definition else if (width > 0 && (height == 0 || width < height)) width else height
                Triple(quality, codec, o)
            }.sortedByDescending { it.first }.toList()
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor;
        // never select a known incompatible variant (kept from the original kotlin-tv logic).
        val playable = rows.filter { it.second !in setOf("bytevc2","bvc2") }
        // Prefer h264 for TV compatibility; fall back to others.
        val compatible = playable.filter { it.second == "h264" || it.second == "avc1" }
        val pool = if (compatible.isNotEmpty()) compatible else playable
        val selected = pool.firstOrNull { it.first in 1..maxQuality } ?: pool.firstOrNull()
            ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item = selected.third
        var url = cleanUrl(item.str("main_url"))
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            // main_url may be base64-encoded
            val decoded = runCatching { String(java.util.Base64.getDecoder().decode(url), Charsets.UTF_8).trim() }.getOrNull()
            if (decoded != null && (decoded.startsWith("https://") || decoded.startsWith("http://"))) url = decoded
            else throw IOException("播放地址无效")
        }
        val encryption = item.optJSONObject("encrypt_info")
        val spade = encryption?.str("spade_a").orEmpty()
        val key = if (spade.isNotEmpty()) runCatching { MediaCrypto.deriveKey(spade) }.getOrNull() else null
        val quality = if (selected.first > 0) "${selected.first}P" + (if (selected.first > maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url, key, quality)
    }

    companion object {
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
    }
}
