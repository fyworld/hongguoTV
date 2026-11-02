// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * A website theme sub-category: slug + Chinese display name (from selectorList).
 * [searchKeyword] marks a search-backed theme: the site has no category page for it
 * (e.g. 穿越/time-travel), so its catalog is served from the website search with a
 * category-system filter instead of /category/{type}/{slug}.
 * [feedThemeId] marks an App-feed-backed theme: the landpage feed accepts a numeric
 * category_dim_theme id (穿越=37) and paginates properly (the website search does
 * not — its ?page= is ignored and it only ever renders 10 rows).
 */
data class Theme(val slug: String, val label: String, val searchKeyword: String? = null, val feedThemeId: String? = null)

/**
 * Parses the website's theme sub-category pages (/category/real-drama/{theme},
 * /category/comic-drama/{theme}). Same _ROUTER_DATA source as the rank pages:
 * loaderData["category_$"] carries selectorList (theme list with Chinese names),
 * recommendList (24 series per page) and pagination.
 * The theme list is read from the page itself so it follows site changes; the
 * built-in fallback below only serves when the site is unreachable or re-shaped.
 */
internal object ThemeCategory {
    // Fallback theme lists (verified 2026-10): real-drama 24, comic-drama 8, ai-drama 8.
    // 穿越 is NOT listed here: retired from every tab on 2026-11-02 (no stable source
    // — see searchBacked below), and it must never appear as a plain category-page
    // theme — /category/{type}/chuanyue is a 404 on all three paths.
    private val fallbackReal = listOf(
        "romance" to "爱情", "period" to "年代", "comeback" to "逆袭", "legend" to "传奇",
        "growth" to "成长", "family" to "家庭", "clan" to "家族", "cute-kids" to "萌宝",
        "suspense" to "悬疑", "thriller" to "惊悚", "horror" to "恐怖", "supernatural" to "志怪",
        "costume" to "古装", "fantasy" to "玄幻", "wonder" to "奇幻", "urban" to "都市",
        "youth" to "青春", "comedy" to "喜剧", "sci-fi" to "科幻", "disaster" to "灾难",
        "action-adventure" to "动作冒险", "war" to "战争", "variety" to "综艺", "drama" to "剧情")
    private val fallbackComic = listOf(
        "creative" to "脑洞", "fantasy" to "玄幻", "drama" to "剧情", "apocalypse" to "末世",
        "wealthy-family" to "豪门", "wonder" to "奇幻", "sci-fi" to "科幻", "adventure" to "冒险")
    private val fallbackAi = listOf(
        "creative" to "脑洞", "fantasy" to "玄幻", "drama" to "剧情", "apocalypse" to "末世",
        "wealthy-family" to "豪门", "wonder" to "奇幻", "sci-fi" to "科幻", "adventure" to "冒险")

    /** 2026-11-02: the 穿越 theme is RETIRED from every tab (user decision after the
     *  full-source diagnosis). Background for the record:
     *  - real-drama tab: the website search served it (215 hits, genre=205 filter,
     *    true pagination) — worked, but retired along with the rest.
     *  - comic tab: the landpage feed with category_dim_theme=37 — intermittently
     *    risk-controlled (18 pure rows one minute, empty body the next; re-verified
     *    twice within 15 minutes on 2026-11-02).
     *  - AI tab: NO usable source at all — /category/ai-drama/chuanyue is a 404,
     *    the search's full 108-row walk for 穿越 carries ZERO genre=207 rows, and
     *    the ai_series feed channel is IP-risk-controlled to empty bodies.
     *  No theme is injected on any tab; the strip shows exactly what the site's own
     *  selector list carries. The routing markers and the defensive slug check
     *  below stay: stale cached 穿越 themes (Library disk cache, 7-day TTL) must
     *  still route to the search instead of a 404 category page. */
    private val chuanyueSlug = "chuanyue"
    internal fun searchBacked(@Suppress("UNUSED_PARAMETER") type: ContentType): List<Theme> = emptyList()
    /** Slugs that must never hit /category/{type}/{slug} (their category pages are 404s):
     *  defensive routing for stale cached themes — they divert to the search chain.
     *  No tab injects 穿越 anymore (2026-11-02), but Library's disk cache can still
     *  hand a stale one to themeCatalog for up to 7 days after an upgrade. */
    internal fun searchBackedSlugs(type: ContentType): Set<String> = when(type) {
        ContentType.AI -> emptySet()
        ContentType.COMIC -> setOf(chuanyueSlug)
        else -> setOf(chuanyueSlug)
    }

    /** Prepends the search/feed-backed themes that a parsed site list is missing.
     *  穿越 leads the strip (right after 全部) instead of trailing 24 site themes:
     *  it is the theme users actually browse (three rounds of empty-feed reports were
     *  all about it), and on a TV remote a buried 26th button is effectively hidden. */
    internal fun withSearchBacked(themes: List<Theme>, type: ContentType): List<Theme> {
        val known=themes.map { it.slug }.toHashSet()
        return searchBacked(type).filterNot { it.slug in known } + themes
    }

    fun fallback(type: ContentType): List<Theme> =
        withSearchBacked((when(type) {
            ContentType.COMIC -> fallbackComic
            ContentType.AI -> fallbackAi
            else -> fallbackReal
        }).map { Theme(it.first,it.second) },type)

    private fun routeNode(html: String): JSONObject {
        val node=ContentRepository.extractRouter(html).optJSONObject("loaderData")
            ?.optJSONObject("category_\$")
            ?: throw IOException("页面没有分类数据")
        if(!node.optBoolean("isSuccess")) throw IOException("分类数据暂不可用")
        return node
    }

    /** Theme list from the category root page; null when the page has no usable list. */
    fun parseThemes(html: String, type: ContentType): List<Theme>? {
        val node=routeNode(html)
        val rows=node.optJSONArray("selectorList") ?: return null
        for(i in 0 until rows.length()) {
            val row=rows.optJSONObject(i) ?: continue
            val items=row.optJSONArray("items") ?: continue
            val themes=(0 until items.length()).mapNotNull { j ->
                val item=items.optJSONObject(j) ?: return@mapNotNull null
                val slug=item.str("selector_item_id")
                val label=item.str("show_name")
                if(slug.isNotBlank() && label.isNotBlank()) Theme(slug,label) else null
            }.distinctBy { it.slug }
            if(themes.size>=4) return themes
        }
        return null
    }

    /** Series page for one theme; validates pagination like ComicRank does.
     *  An EMPTY category (totalPages=0, no rows - e.g. 动作冒险 carries no real-drama
     *  content as of 2026-11) is a valid empty page, not an error: the grid shows the
     *  empty state instead of a misleading 分页信息异常 toast. */
    fun parse(html: String, page: Int, theme: Theme, type: ContentType): CatalogPage {
        val node=routeNode(html)
        val rows=node.optJSONArray("recommendList") ?: throw IOException("${theme.label}数据格式已变化")
        val pagination=node.optJSONObject("pagination") ?: throw IOException("${theme.label}分页信息缺失")
        val totalPages=pagination.optInt("totalPages",0)
        if(rows.length()==0 && totalPages==0)
            return CatalogPage(emptyList(),false,null,"")
        if(totalPages<1 || pagination.optInt("pageNum",page)!=page) throw IOException("${theme.label}分页信息异常")
        val items=rows.objects().map { row ->
            val count=row.optInt("episode_cnt",row.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
            val tags=row.optJSONArray("tags") ?: JSONArray()
            Series(row.str("series_id"),row.str("series_name"),cleanUrl(row.str("series_cover")),
                row.str("series_intro"),
                row.str("episode_right_text").ifEmpty { if(count>0) "全 $count 集" else "" },
                (0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
        }.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
        return CatalogPage(items,items.isNotEmpty() && page<minOf(totalPages,100),null,"")
    }
}
