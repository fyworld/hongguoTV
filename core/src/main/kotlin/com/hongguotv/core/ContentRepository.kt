// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "", val heat: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags).put("heat",heat)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags"),o.str("heat")) }
}
data class Detail(val series: Series, val episodes: List<String>, val contentType: ContentType = ContentType.SHORT)
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null, val warning: String = "")
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

/**
 * Content repository rebuilt on the working provider chain from HongguoTV-Phone-TV-NoAds:
 *  - catalog: App landpage API with the lightweight X-Khronos/X-Gorgon signature
 *    (transport in AppApiTransport)
 *  - detail:  website /detail page (App video_detail currently returns an empty body)
 *  - stream:  channel scheduler over StreamSource implementations
 *    (App API unsigned video_model first, website public MP4 fallback)
 *  - search / ranking / suggestions: website pages and JSON endpoints
 * The old drpys multi_video_model chain (x-argus/x-ladon/x-helios/x-medusa) is retired.
 */
class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val site = "https://hongguoduanju.com"
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

    // ---------- App API transport + playback channels (AppApiTransport / StreamSource) ----------

    /** Shared App API transport: device identity, query profile, X-Khronos/X-Gorgon signature. */
    private val appApi = AppApiTransport(http, clock)

    /** Playback fallback chain: App API (multi-quality + CENC) -> public MP4 (360p). */
    private val streams = StreamScheduler(StreamSource.default(appApi, http, webUa, site))

    private fun appRequest(path: String, payload: String?): JSONObject =
        appApi.request(path, payload, signed = true)

    // ---------- catalog (App landpage) ----------

    private val catalogCursors = linkedMapOf<String, Pair<Int,String>>() // genre key -> (offset, session)

    private fun card(item: JSONObject): Series {
        val o = if (item.optJSONObject("video_data") != null) item.optJSONObject("video_data")!! else item
        val count = o.optInt("episode_cnt", o.optJSONObject("series_episode_info")?.optInt("episode_cnt", 0) ?: 0)
        // The landpage feed rows carry category_schema (a JSON string of {category_id,name}
        // pairs) instead of tags/category_list; parse it so the card keeps its tag row.
        val tags = o.optJSONArray("tags") ?: o.optJSONArray("category_list")
            ?: runCatching { JSONArray(o.str("category_schema")) }.getOrDefault(JSONArray())
        return Series(
            o.str("series_id_str").ifEmpty { o.str("series_id") },
            o.str("series_title").ifEmpty { o.str("series_name").ifEmpty { o.str("title").ifEmpty { item.str("name") } } },
            cleanUrl(o.str("series_cover").ifEmpty { o.str("cover") }),
            o.str("series_intro").ifEmpty { o.str("description") },
            o.str("episode_right_text").ifEmpty { if(count>0) "全 $count 集" else "" },
            (0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) },
            playCountText(o.optLong("play_cnt", 0)))
    }

    /** Feed rows carry a raw play_cnt; render it the way the site does (万 units). */
    private fun playCountText(count: Long): String = when {
        count <= 0 -> ""
        count < 10000 -> "$count 播放"
        else -> {
            val wan = count / 10000.0
            val text = if (wan >= 100) wan.toInt().toString() else String.format("%.1f", wan)
            "${text}万播放"
        }
    }

    /** True when a feed row's category_schema contains [name] (e.g. 穿越). */
    private fun hasCategory(item: JSONObject, name: String): Boolean {
        val o = if (item.optJSONObject("video_data") != null) item.optJSONObject("video_data")!! else item
        val schema = o.optJSONArray("category_schema") ?: runCatching { JSONArray(o.str("category_schema")) }.getOrNull() ?: return false
        return (0 until schema.length()).any { schema.optJSONObject(it)?.str("name") == name }
    }

    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }

    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        // The App feed carries one channel per content type (phone-version hongguoAppGenres):
        // short_play (real drama) and ai_series (AI drama). Each channel needs its own
        // req_scene as well, otherwise the server falls back to the real-drama feed and
        // the tabs would show the same content.
        // COMIC is different: its feed is a recommendation stream that rotates and runs
        // dry (all duplicates) after ~6 pages (~108 entries), while the website category
        // page is a true paginated catalog (24/page, 34 pages = 800 entries). The comic
        // "全部" grid therefore reads the website page; the feed channel stays reserved
        // for theme filtering (category_dim_theme).
        if (type == ContentType.COMIC)
            return ThemeCategory.parse(webText("$site/category/comic-drama?page=$page"),page,
                Theme("all","全部"),ContentType.COMIC)
        return feedPage(page, feedGenre(type), feedScene(type), emptyList())
    }

    /** One landpage feed page.
     *  [themeIds] carries a numeric category_dim_theme filter (e.g. "37" for 穿越) —
     *  honoured on the short_play and comic_series channels only.
     *  [categoryFilter] filters rows locally by their category_schema name (e.g. 穿越) —
     *  used on the ai_series channel, where the server ignores the theme filter. */
    private fun feedPage(page: Int, genre: String, scene: String, themeIds: List<String>, categoryFilter: String? = null): CatalogPage {
        val cursorKey = "category:$genre${if(themeIds.isEmpty() && categoryFilter==null) "" else ":theme${themeIds.joinToString(",")}${categoryFilter ?: ""}"}"
        // The App feed is cursor-based: the server returns next_offset + session_id and
        // may return ANY number of items per call (5, 14, 23… — it used to be a fixed 18).
        // Page 1 keeps fetching forward until a full shelf (12+) is collected, so the home
        // shelves and the short-drama grid always open with a proper page of content.
        // With a local category filter the raw feed is thinned out (only ~1 in 6 rows
        // carries 穿越), so the fill-up loop runs on every page, not just page 1.
        var cursor = synchronized(catalogCursors) { catalogCursors[cursorKey] }
        var offset = if (page == 1) 0 else cursor?.first ?: (page - 1) * 18
        var sessionId = if (page == 1) "" else cursor?.second ?: ""
        val all = mutableListOf<Series>()
        var hasMore = false
        var round = 0
        while (true) {
            val payload = JSONObject()
                .put("req_scene", scene).put("offset", offset).put("limit", 18)
                .put("req_type", "only_content").put("need_selector_panel", false)
                .put("client_req_type", if (offset > 0) 2 else 3)
                .put("session_id", sessionId).put("filter_ids", "")
                .put("select_items", JSONObject()
                    .put("genre", JSONArray().put(genre)).put("sort", JSONArray().put("online_time"))
                    .put("gender", JSONArray()).put("category_dim_theme", JSONArray(themeIds))
                    .put("category_dim_role", JSONArray()).put("category_dim_epoch", JSONArray())
                    .put("online_time", JSONArray()).put("creation_status", JSONArray()))
            val data = appRequest("/reading/distribution/category/landpage/v/", payload.toString())
            val rows = data.optJSONObject("data")?.optJSONArray("video_data") ?: throw IOException("首页数据暂不可用")
            all += valid(rows.objects().filter { categoryFilter==null || hasCategory(it,categoryFilter) }.map(::card))
            hasMore = data.optJSONObject("data")?.optBoolean("has_more", false) ?: false
            offset = data.optJSONObject("data")?.optInt("next_offset", offset + 18) ?: offset + 18
            sessionId = data.optJSONObject("data")?.str("session_id") ?: sessionId
            synchronized(catalogCursors) { catalogCursors[cursorKey] = offset to sessionId }
            round++
            // Enough content, nothing more, or a safety cap on follow-up rounds.
            val target = if (categoryFilter==null) 12 else 18
            if ((page != 1 && categoryFilter==null) || all.size >= target || !hasMore || round >= (if (categoryFilter==null) 3 else 6)) break
        }
        return CatalogPage(all, hasMore && page < 100)
    }

    fun comicRanking(page: Int = 1): CatalogPage = ranking(RankBoard.COMIC,page)

    // ---------- theme sub-categories (website category pages) ----------

    /** Website category root path for a content type (real-drama / comic-drama / ai-drama). */
    private fun categoryPath(type: ContentType) = when(type) {
        ContentType.COMIC -> "comic-drama"
        ContentType.AI -> "ai-drama"
        else -> "real-drama"
    }

    private val themeCache=linkedMapOf<String, Pair<Long,List<Theme>>>()
    private val themeTtlMs=30*60*1000L

    /** Theme list for a content type: read from the category root page (selectorList,
     *  follows site changes); falls back to the built-in list when the page fails.
     *  Search-backed themes the site omits (穿越) are appended either way. */
    fun themes(type: ContentType): List<Theme> {
        val key="themes:${type.storedValue}"
        val now=System.currentTimeMillis()
        synchronized(themeCache) {
            val cached=themeCache[key]
            if(cached!=null && now<cached.first) return cached.second
        }
        val themes=runCatching { ThemeCategory.parseThemes(webText("$site/category/${categoryPath(type)}"),type) }
            .getOrNull()
        val list=themes?.let { ThemeCategory.withSearchBacked(it,type) } ?: ThemeCategory.fallback(type)
        if(themes!=null) synchronized(themeCache) { themeCache[key]=now+themeTtlMs to list }
        return list
    }

    /** One theme's series page.
     *  Feed-backed themes (穿越 on real/comic tabs) route to the landpage feed with a
     *  numeric category_dim_theme filter — the website search's ?page= is ignored (it
     *  only ever renders 10 rows), so the feed is the only chain that paginates.
     *  On the AI tab the server IGNORES the theme filter (verified: it returns the
     *  comic list), so 穿越 there walks the AI feed and filters rows locally by their
     *  category_schema tag. The slug check is defensive: a stale cached Theme without
     *  either marker must still route away from /category/{type}/chuanyue (a 404). */
    fun themeCatalog(type: ContentType, theme: Theme, page: Int = 1): CatalogPage {
        require(theme.slug.matches(Regex("[a-z0-9-]{1,40}")) && page in 1..100)
        if(theme.feedThemeId!=null && type!=ContentType.AI)
            return feedPage(page, feedGenre(type), feedScene(type), listOf(theme.feedThemeId))
        if(theme.feedCategory!=null)
            return feedPage(page, feedGenre(type), feedScene(type), emptyList(), theme.feedCategory)
        if(theme.searchKeyword!=null || theme.slug in ThemeCategory.searchBackedSlugs)
            return searchTheme(theme.searchKeyword ?: theme.label,page,type)
        return ThemeCategory.parse(webText("$site/category/${categoryPath(type)}/${theme.slug}?page=$page"),page,theme,type)
    }

    private fun feedGenre(type: ContentType) = when(type) {
        ContentType.COMIC -> "comic_series"; ContentType.AI -> "ai_series"; else -> "short_play"
    }
    private fun feedScene(type: ContentType) = when(type) {
        ContentType.COMIC -> "comic_series"; ContentType.AI -> "ai_series"; else -> "default"
    }

    /**
     * Search-backed theme catalog: website search for the theme keyword. The site's
     * search mixes real and AI dramas and carries no reliable per-type marker (the
     * category_list id system differs per page, genre is always 205), so the results
     * are shown unfiltered — same approach as the phone-version's keyword themes.
     * The SSR page ignores ?page= and always renders the same first 10 rows, so there
     * is no real pagination: hasMore is false (a second page would only re-serve
     * duplicates and spin the infinite scroll).
     */
    private fun searchTheme(keyword: String, page: Int, type: ContentType): CatalogPage {
        val encoded = appApi.urlEscape(keyword)
        val data = extractRouter(webText("$site/search/$encoded?page=$page"))
        val loader = data.optJSONObject("loaderData") ?: throw IOException("${keyword}数据暂不可用")
        val section = (loader.keys().asSequence().mapNotNull { loader.optJSONObject(it) }
            .firstOrNull { it.length() > 0 && it.optJSONArray("searchList") != null })
            ?: throw IOException("${keyword}数据暂不可用")
        val rows = section.optJSONArray("searchList") ?: throw IOException("${keyword}数据结构已变化")
        val items = valid(rows.objects().mapNotNull { it.optJSONObject("video_data")?.let(::card) })
        return CatalogPage(items, false)
    }

    /** The four website boards: 总热播榜 / 真人剧榜 / 漫剧榜 / AI剧榜.
     *  Each board holds 100 entries (5 pages x 20); the whole board is fetched at once —
     *  the TV grid renders all cards immediately and the infinite scroll simply has
     *  nothing more to append. */
    fun ranking(board: RankBoard, page: Int = 1): CatalogPage {
        require(page in 1..100)
        val first=ComicRank.parse(webText("$site/rank/${board.path}?page=$page"),page,board)
        val totalPages=first.ranking?.totalPages ?: 1
        if(totalPages<=page) return first
        // Fetch the remaining pages and merge items + positions into one full board.
        val items=first.items.toMutableList()
        val positions=first.ranking!!.positions.toMutableMap()
        for(next in page+1..minOf(totalPages,100)) {
            runCatching { ComicRank.parse(webText("$site/rank/${board.path}?page=$next"),next,board) }
                .onSuccess { part ->
                    val known=items.map { it.id }.toHashSet()
                    items+=part.items.filterNot { it.id in known }
                    positions+=part.ranking?.positions.orEmpty()
                }
        }
        return CatalogPage(items,false,ComicRanking(positions,totalPages,first.ranking!!.updatedText))
    }

    // ---------- search (website) ----------

    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        // The website search does not constrain content type; use it for both.
        val encoded = appApi.urlEscape(keyword)
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
        val encoded = appApi.urlEscape(keyword)
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
        val encoded = appApi.urlEscape(keyword)
        val body = text("$site/incent_resource/suggestion?app_id=8662&query=$encoded&count=50", mapOf(
            "User-Agent" to webUa, "Referer" to "$site/", "Accept" to "application/json", "Accept-Language" to "zh-CN,zh;q=0.9"))
        val records = SearchSuggest.parseRecords(body)
        return SearchSuggest.nameSearchDramas(records)
    }

    // ---------- detail (website page) ----------

    fun detail(id: String, type: ContentType = ContentType.SHORT): Detail {
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
        return Detail(series, episodes, type)
    }

    // ---------- stream: channel scheduler (App API first, public MP4 fallback) ----------

    fun stream(id: String, maxQuality: Int = 1080, type: ContentType = ContentType.SHORT): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        // Channels live in StreamSource implementations; when an endpoint changes,
        // fix the affected channel (or add a new one to StreamSource.default) - this
        // method and the app never change.
        return streams.stream(id, maxQuality, type)
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
