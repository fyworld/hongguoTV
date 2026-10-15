// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A website theme sub-category: slug + Chinese display name (from selectorList). */
data class Theme(val slug: String, val label: String)

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

    fun fallback(type: ContentType): List<Theme> =
        (when(type) {
            ContentType.COMIC -> fallbackComic
            ContentType.AI -> fallbackAi
            else -> fallbackReal
        }).map { Theme(it.first,it.second) }

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

    /** Series page for one theme; validates pagination like ComicRank does. */
    fun parse(html: String, page: Int, theme: Theme, type: ContentType): CatalogPage {
        val node=routeNode(html)
        val rows=node.optJSONArray("recommendList") ?: throw IOException("${theme.label}数据格式已变化")
        val pagination=node.optJSONObject("pagination") ?: throw IOException("${theme.label}分页信息缺失")
        val totalPages=pagination.optInt("totalPages",0)
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
