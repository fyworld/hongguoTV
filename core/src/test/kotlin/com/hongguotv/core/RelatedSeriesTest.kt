// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相关剧集归组（2026-11-21 线上问题回归）。
 *
 * 「魏家逆子成首辅」系列用的是**篇名分卷**（分宗篇/折桂篇）和**季号+副标题**
 * （第三季：翰林篇）。旧逻辑只认结尾的 `第N季/第N部`，于是基名退化成完整标题，
 * 明明两条搜索链都召回了整族，却全被归组判定丢掉 —— 详情页的相关剧集整块消失。
 */
class RelatedSeriesTest {
    private val family = listOf(
        "7656652749198593048" to "魏家逆子成首辅",
        "7654933376318589977" to "魏家逆子成首辅分宗篇",
        "7658579303021431870" to "魏家逆子成首辅折桂篇",
        "7661473744812002328" to "魏家逆子成首辅第三季：翰林篇",
        "7689342243148139582" to "魏家逆子成首辅第九季：始计篇",
        "7703934356443630654" to "魏家逆子成首辅第十季：兵势篇",
        "7709062627929902142" to "魏家逆子成首辅第十一季：半朝篇",
        // 另一部作品：基名不同，必须排除
        "7660074448455748632" to "魏家逆子成首辅3D版第一季",
        // 同前缀但不是同一系列（长尾标题），必须排除
        "7647788430239222808" to "魏家逆子成首辅，全族恭请我认祖",
        "7600000000000000001" to "完全无关的剧",
    )

    /** 打桩：两个真实链路按标题包含关系返回，模拟站内搜索/联想的召回。 */
    private fun repository(items: List<Pair<String, String>>) = ContentRepository(
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val query = request.url.queryParameter("query").orEmpty()
            val hits = items.filter { it.second.contains(query) }
            val body = when (request.url.encodedPath) {
                "/api/search/result" -> JSONObject().put("searchList", JSONArray().apply {
                    hits.forEach { (id, title) ->
                        put(JSONObject().put("video_data", JSONObject()
                            .put("series_id", id).put("series_title", title).put("episode_cnt", 80)))
                    }
                }).put("hasMore", false).toString()
                "/incent_resource/suggestion" -> JSONObject().put("suggest_list", JSONArray().apply {
                    hits.forEach { (id, title) ->
                        put(JSONObject().put("name", title).put("word_type", "short_play_name")
                            .put("video_data", JSONObject()
                                .put("series_id_str", id).put("series_title", title).put("episode_cnt", 80)))
                    }
                }).toString()
                else -> ""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("ok").body(body.toResponseBody()).build()
        }.build()
    )

    @Test fun volumeChapterTitlesShareTheSeriesBase() {
        assertEquals("魏家逆子成首辅", SearchSuggest.seriesBase("魏家逆子成首辅分宗篇"))
        assertEquals("魏家逆子成首辅", SearchSuggest.seriesBase("魏家逆子成首辅折桂篇"))
        // 季号后面还有副标题：第N季 必须在任意位置识别，而不是只认结尾
        assertEquals("魏家逆子成首辅", SearchSuggest.seriesBase("魏家逆子成首辅第三季：翰林篇"))
        assertEquals("魏家逆子成首辅", SearchSuggest.seriesBase("魏家逆子成首辅第十季:兵势篇"))
        // 老命名不受影响
        assertEquals("持械入宋", SearchSuggest.seriesBase("持械入宋第二季"))
        assertEquals("持械入宋", SearchSuggest.seriesBase("持械入宋第二部"))
        // 无后缀 → 自己就是基名
        assertEquals("魏家逆子成首辅", SearchSuggest.seriesBase("魏家逆子成首辅"))
        // 剥完不足 3 字就不剥（避免把正常剧名剥秃）
        assertEquals("帝篇", SearchSuggest.seriesBase("帝篇"))
    }

    @Test fun seasonIndexReadsBothPositionsAndIgnoresVolumeChapters() {
        assertEquals(3, SearchSuggest.seasonIndex("魏家逆子成首辅第三季：翰林篇"))
        assertEquals(11, SearchSuggest.seasonIndex("魏家逆子成首辅第十一季：半朝篇"))
        assertEquals(2, SearchSuggest.seasonIndex("魏家逆子成首辅第二季"))
        assertNull(SearchSuggest.seasonIndex("魏家逆子成首辅分宗篇"))
    }

    @Test fun relatedRowSurvivesVolumeChapterNaming() {
        val repo = repository(family)
        val ids = repo.relatedSeries("7654933376318589977", "魏家逆子成首辅分宗篇").map { it.id }.toSet()
        assertEquals(
            setOf(
                "7656652749198593048", // 基名
                "7658579303021431870", // 折桂篇
                "7661473744812002328", // 第三季：翰林篇
                "7689342243148139582", // 第九季：始计篇
                "7703934356443630654", // 第十季：兵势篇
                "7709062627929902142", // 第十一季：半朝篇
            ),
            ids,
        )
    }

    @Test fun relatedRowWorksFromATitledSeasonAndExcludesSpinoffs() {
        val repo = repository(family)
        val related = repo.relatedSeries("7661473744812002328", "魏家逆子成首辅第三季：翰林篇")
        val ids = related.map { it.id }.toSet()
        assertTrue("分宗篇必须出现", "7654933376318589977" in ids)
        assertTrue("折桂篇必须出现", "7658579303021431870" in ids)
        assertTrue("3D版是另一部作品，不能混进来", "7660074448455748632" !in ids)
        assertTrue("长尾标题不是同系列", "7647788430239222808" !in ids)
        assertTrue("无关剧不能混进来", "7600000000000000001" !in ids)
        // 篇名分卷没有季号 → 排在有季号的兄弟前面
        assertNull("首个应是篇名分卷（基名/折桂篇），不是带季号的", SearchSuggest.seasonIndex(related.first().title))
        // 当前剧（第三季）本身不在相关列表里；剩下有季号的按升序排
        assertEquals(listOf(9, 10, 11), related.mapNotNull { SearchSuggest.seasonIndex(it.title) })
    }

    @Test fun legacyTrailingSeasonNamingStillWorks() {
        val repo = repository(listOf(
            "1" to "持械入宋",
            "2" to "持械入宋第二季",
            "3" to "持械入宋第三季",
            "4" to "持械入宋外传",
        ))
        val ids = repo.relatedSeries("2", "持械入宋第二季").map { it.id }.toSet()
        assertEquals(setOf("1", "3"), ids)
    }
}
