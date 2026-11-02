package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        // The playback model tries the video_tab endpoint first (2026-11-02); these
        // fixtures verify the OLD path's behavior, so answer the new endpoint with a
        // business error to push the flow back to the old POST chain.
        if (request.url.encodedPath.startsWith("/reading/bookapi/video_tab/"))
            """{"code":100103,"message":"PARAM_INVALID","data":null}"""
        else {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) ->
                rows.put("video_$index",JSONObject().put("video_meta",JSONObject().put("vheight",height).put("codec_type",codec)).put("main_url",url))
            }
            JSONObject().put("code",0).put("data",JSONObject().put("video_model",JSONObject().put("video_list",rows))).toString()
        }
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceFallsBackToPublicMp4() {
        // With the dual-channel stream (upstream 0.1.10), an App model with only
        // undecodable codecs falls back to the website's public MP4 path instead of
        // failing: playback continues at 360p rather than erroring out.
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            if(request.url.encodedPath=="/novel/player/video_model/v1/")
                JSONObject().put("code",0).put("data",JSONObject().put("video_model",JSONObject().put("video_list",JSONObject()
                    .put("v0",JSONObject().put("video_meta",JSONObject().put("vheight",720).put("codec_type","ByteVC2")).put("main_url","https://example.com/unsupported"))
                    .put("v1",JSONObject().put("video_meta",JSONObject().put("vheight",480).put("codec_type","bvc2")).put("main_url","https://example.com/unsupported2"))))).toString()
            else ""
        }
        val result=repo.stream("123",720)
        assertEquals("360P",result.quality)
        assertTrue(requests.any { it.url.encodedPath=="/video/public/123/360p.mp4" })
    }

    @Test fun videoModelStringIsAccepted() {
        val repo=repository { request ->
            if(request.url.encodedPath.startsWith("/reading/bookapi/video_tab/"))
                """{"code":100103,"message":"PARAM_INVALID","data":null}"""
            else {
                val rows=JSONObject()
                rows.put("video_0",JSONObject().put("video_meta",JSONObject().put("vheight",720).put("codec_type","h264")).put("main_url","https://example.com/avc"))
                JSONObject().put("code",0).put("data",JSONObject().put("video_model",JSONObject().put("video_list",rows).toString())).toString()
            }
        }
        assertEquals("720P",repo.stream("123",1080).quality)
    }

    @Test fun videoTabEndpointServesPlaybackFirst() {
        // 2026-11-02: the old POST /novel/player path is retired server-side (all
        // eight api5/api3 domains return 200 + 0 bytes); the official client reads
        // GET /reading/bookapi/video_tab/multi_video_model/v1/ with snake_case params
        // (missing any returns 100103). A healthy video_tab answer must serve the
        // playback WITHOUT touching the old POST path.
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            if(request.url.encodedPath=="/reading/bookapi/video_tab/multi_video_model/v1/") {
                // The endpoint's documented shape: data.video_model_data[id].video_model
                // (object or JSON string), five-tier video_list, spade_a keys.
                JSONObject().put("code",0).put("data",JSONObject()
                    .put("video_model_data",JSONObject().put("123",JSONObject()
                        .put("video_model",JSONObject().put("video_list",JSONObject()
                            .put("v0",JSONObject().put("video_meta",JSONObject().put("vheight",720).put("codec_type","bytevc2")).put("main_url","https://example.com/vtab-720"))
                            .put("v1",JSONObject().put("video_meta",JSONObject().put("vheight",1080).put("codec_type","bytevc1")).put("main_url","https://example.com/vtab-1080")))))))
                    .toString()
            }
            else throw AssertionError("video_tab hit must not fall back to the old path: "+request.url)
        }
        val result=repo.stream("123",1080)
        assertEquals("https://example.com/vtab-1080",result.url)
        assertEquals("1080P",result.quality)
        assertTrue(requests.none { it.url.encodedPath.startsWith("/novel/player/") })
    }

    @Test fun videoTabFailureFallsBackToOldPath() {
        // Any video_tab failure (business code, empty body, shape mismatch, network
        // error) falls back to the old POST flow; the old path's retry and identity
        // semantics stay unchanged.
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            if(request.url.encodedPath.startsWith("/reading/bookapi/video_tab/"))
                """{"code":100103,"message":"PARAM_INVALID","data":null}"""
            else if(request.url.encodedPath=="/novel/player/multi_video_model/v1/") {
                val rows=JSONObject()
                rows.put("video_0",JSONObject().put("video_meta",JSONObject().put("vheight",1080).put("codec_type","h264")).put("main_url","https://example.com/old-1080"))
                JSONObject().put("code",0).put("data",JSONObject().put("video_model",JSONObject().put("video_list",rows))).toString()
            }
            else throw AssertionError("unexpected "+request.url)
        }
        val result=repo.stream("123",1080)
        assertEquals("https://example.com/old-1080",result.url)
        assertEquals("1080P",result.quality)
        assertTrue(requests.any { it.url.encodedPath=="/reading/bookapi/video_tab/multi_video_model/v1/" })
        assertTrue(requests.any { it.url.encodedPath=="/novel/player/multi_video_model/v1/" })
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    @Test fun h264PreferredOverHigherByteVc1() {
        val repo=streamRepository(Triple(1080,"bytevc1","https://example.com/hevc"),Triple(720,"h264","https://example.com/avc"))
        assertEquals("720P",repo.stream("123",1080).quality)
        assertEquals("https://example.com/avc",repo.stream("123",1080).url)
    }

    @Test fun appRequestCarriesSignatureHeaders() {
        // The landpage feed path (feedPage) must carry the six-header signature chain.
        // No tab injects a feedThemeId theme anymore (穿越 retired 2026-11-02), but a
        // stale Library-cached one still routes here — the mechanism must stay signed.
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("data",JSONObject().put("video_data",JSONArray())).toString()
        }
        repo.themeCatalog(ContentType.SHORT,Theme("chuanyue","穿越",feedThemeId = "37"),1)
        val request=requests.single()
        assertEquals("POST",request.method)
        assertEquals("/reading/distribution/category/landpage/v/",request.url.encodedPath)
        assertNotNull(request.header("X-Gorgon"))
        assertNotNull(request.header("X-Khronos"))
        assertNotNull(request.header("X-Ladon"))
        assertNotNull(request.header("X-Argus"))
        assertNotNull(request.header("X-Helios"))
        assertNotNull(request.header("X-Medusa"))
        assertEquals("8662",request.url.queryParameter("aid"))
        assertNotNull(request.url.queryParameter("device_id"))
        assertNotNull(request.url.queryParameter("_rticket"))
    }

    @Test fun feedRequestPinsTheRegistrationVersion73932() {
        // 2026-10-30: the server started returning empty bodies for the old 73532 pin
        // (ai_series first, then short_play). The feed must pin 73932, the version the
        // device registers with, and the UA must follow (the signature chain binds UA
        // to the query version_code). Stale feedThemeId themes still exercise this.
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("data",JSONObject().put("video_data",JSONArray())).toString()
        }
        repo.themeCatalog(ContentType.SHORT,Theme("chuanyue","穿越",feedThemeId = "37"),1)
        val request=requests.single()
        assertEquals("73932",request.url.queryParameter("version_code"))
        assertEquals("73932",request.url.queryParameter("manifest_version_code"))
        assertEquals("73932",request.url.queryParameter("update_version_code"))
        assertEquals("7.3.9.32",request.url.queryParameter("version_name"))
        assertEquals("73932",request.url.queryParameter("pv_player"))
        assertTrue(request.header("User-Agent")!!.startsWith("com.phoenix.read/73932 "))
    }

    @Test fun feedMidLoopEmptyRoundKeepsCollectedRows() {
        // 2026-10-31: risk control can answer ANY single fill-up round with HTTP 200 +
        // empty body. The feed's fill-up loop spans several rounds (the server may
        // return 5/14/23 rows per call) — a mid-loop empty round must keep the rows
        // already collected instead of discarding the page with "App 接口返回空数据".
        // Only an empty FIRST round (channel unavailable) still propagates the error.
        var round=0
        val repo=repository { _ ->
            round++
            if(round==1) JSONObject().put("code",0).put("data",JSONObject()
                .put("video_data",JSONArray().put(JSONObject()
                    .put("series_id","770235").put("title","穿越剧A")))
                .put("has_more",true).put("next_offset",18).put("session_id","s1")).toString()
            else "" // follow-up round: HTTP 200 + empty body (risk control)
        }
        val page=repo.themeCatalog(ContentType.COMIC,Theme("chuanyue","穿越",feedThemeId = "37"),1)
        assertEquals(listOf("770235"),page.items.map { it.id })
        assertFalse(page.hasMore)
        assertTrue(round>=2) // the loop actually attempted a follow-up round
    }

    @Test fun feedFirstRoundEmptyStillThrows() {
        // The fault tolerance must not mask a dead channel: an empty FIRST round means
        // the feed itself is unavailable and the error still propagates.
        val repo=repository { _ -> "" }
        assertThrows(IOException::class.java) {
            repo.themeCatalog(ContentType.COMIC,Theme("chuanyue","穿越",feedThemeId = "37"),1)
        }
    }

    @Test fun homeParsesLandpageFeedAndPagination() {
        // 2026-10: every "全部" grid reads the website category page — the feed's cover
        // URLs switched to HEIC (API-28+ only, blank on Android 7/8 TVs), while the
        // website serves jpg/webp covers with true pagination.
        val repo=repository { request ->
            assertEquals("/category/real-drama",request.url.encodedPath)
            assertEquals("1",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"isSuccess":true,
                "recommendList":[{"series_id":"123","series_name":"测试短剧","series_cover":"https:\\u002F\\u002Fexample.com\\u002Fcover.jpg","episode_cnt":12,"tags":[{"name":"都市"}]}],
                "pagination":{"total":800,"pageNum":1,"pageSize":24,"totalPages":34}}}};</script>"""
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals(listOf("123"),result.items.map { it.id })
        assertEquals("测试短剧",result.items.single().title)
        assertEquals("https://example.com/cover.jpg",result.items.single().cover)
        assertEquals("全 12 集",result.items.single().badge)
        assertTrue(result.hasMore)
    }

    @Test fun homeSecondPageUsesOffsetCursor() {
        // The website catalog paginates with ?page= — page 2 must request page=2 and
        // the parser must reject a mismatched pageNum anchor (stale cache guard).
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"isSuccess":true,
                "recommendList":[{"series_id":"${requests.size}00","series_name":"第${requests.size}页","series_cover":"https:\\u002F\\u002Fexample.com\\u002Fc.jpg","episode_cnt":12}],
                "pagination":{"total":800,"pageNum":${requests.size},"pageSize":24,"totalPages":34}}}};</script>"""
        }
        repo.home(1,ContentType.SHORT)
        val result=repo.home(2,ContentType.SHORT)
        assertEquals(2,requests.size)
        assertEquals("2",requests[1].url.queryParameter("page"))
        assertEquals("第2页",result.items.single().title)
    }

    @Test fun emptyWebsiteCategoryIsAnEmptyPageNotAnError() {
        // 2026-11-01: 动作冒险 on the real-drama tab carries NO content on the site
        // (probed live: total=0, totalPages=0, recommendList=0 rows) - that is a valid
        // empty page, not a 分页信息异常 error. The grid shows its empty state instead
        // of a misleading retry toast.
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"isSuccess":true,
                "recommendList":[],
                "pagination":{"total":0,"pageNum":1,"pageSize":24,"totalPages":0}}}};</script>"""
        }
        val result=repo.themeCatalog(ContentType.SHORT,Theme("action-adventure","动作冒险"),1)
        assertTrue(result.items.isEmpty())
        assertFalse(result.hasMore)
    }

    @Test fun homeRejectsInvalidRows() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"isSuccess":true,
                "recommendList":[{"series_id":"abc","series_name":"无效","series_cover":"https:\\u002F\\u002Fexample.com\\u002Fc.jpg"}],
                "pagination":{"total":800,"pageNum":1,"pageSize":24,"totalPages":34}}}};</script>"""
        }
        assertTrue(repo.home(1,ContentType.SHORT).items.isEmpty())
    }

    @Test fun detailParsesWebsitePage() {
        val repo=repository { request ->
            assertEquals("/detail",request.url.encodedPath)
            assertEquals("123",request.url.queryParameter("series_id"))
            """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"123","series_name":"测试剧集","series_cover":"https://example.com/cover.jpg","series_intro":"简介","episode_cnt":2,"vid_list":["111","222"]}}}};</script>"""
        }
        val result=repo.detail("123")
        assertEquals("测试剧集",result.series.title)
        assertEquals(listOf("111","222"),result.episodes)
    }

    @Test fun detailRejectsDuplicateEpisodes() {
        val repo=repository { request ->
            """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"123","series_name":"测试","vid_list":["111","111"]}}}};</script>"""
        }
        assertThrows(IOException::class.java) { repo.detail("123") }
    }

    @Test fun searchParsesWebsitePage() {
        // The website search page went client-rendered (2026-10): search() now reads
        // the XHR endpoint /api/search/result?query=...&page=...
        val repo=repository { request ->
            assertEquals("/api/search/result",request.url.encodedPath)
            assertEquals("keyword",request.url.queryParameter("query"))
            assertEquals("1",request.url.queryParameter("page"))
            """{"searchList":[{"series_id":"456","series_name":"测试结果","episode_cnt":8}],"totalCount":10,"hasMore":true}"""
        }
        val result=repo.search("keyword",1,ContentType.SHORT)
        assertEquals(listOf("456"),result.items.map { it.id })
        assertEquals("测试结果",result.items.single().title)
    }

    @Test fun aiHomeUsesItsOwnAppFeedGenre() {
        // The AI "全部" grid reads the website ai-drama category page (HEIC-free
        // covers); the feed channel stays reserved for theme filtering.
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"isSuccess":true,
                "recommendList":[{"series_id":"123","series_name":"测试AI剧","series_cover":"https:\\u002F\\u002Fexample.com\\u002Fcover.jpg","episode_cnt":12}],
                "pagination":{"total":800,"pageNum":1,"pageSize":24,"totalPages":34}}}};</script>"""
        }
        repo.home(1,ContentType.AI)
        assertEquals("/category/ai-drama",requests.single().url.encodedPath)
        assertEquals("1",requests.single().url.queryParameter("page"))
    }

    @Test fun searchBackedThemeServesSearchResults() {
        // A theme with an explicit searchKeyword (stale-cache defensive routing path)
        // serves the website search. The real-drama tab filters rows to genre=205
        // (comic 203 / AI 207 rows that leak into the search are dropped); the XHR
        // endpoint paginates for real (10 rows/page with hasMore), so hasMore follows
        // the endpoint's answer. No tab injects a searchKeyword theme anymore
        // (穿越 retired 2026-11-02), but Library's disk cache can still hand one
        // over for up to 7 days after an upgrade.
        val repo=repository { request ->
            assertEquals("/api/search/result",request.url.encodedPath)
            assertEquals("穿越",request.url.queryParameter("query"))
            """{"searchList":[
                {"video_data":{"series_id":"111","series_title":"真人穿越","genre":205,"category_list":[{"category_id":"5000","name":"爱情"},{"category_id":"37","name":"穿越"}]}},
                {"video_data":{"series_id":"222","series_title":"真人穿越二","genre":205,"category_list":[{"category_id":"316","name":"剧情"},{"category_id":"37","name":"穿越"}]}}
            ],"totalCount":2,"hasMore":false}"""
        }
        val real=repo.themeCatalog(ContentType.SHORT,Theme("chuanyue","穿越",searchKeyword = "穿越"),1)
        assertEquals(listOf("111","222"),real.items.map { it.id })
        assertEquals("真人穿越",real.items.first().title)
        assertFalse(real.hasMore)
    }

    @Test fun noTabCarriesAChuanyueButton() {
        // 2026-11-02 (user decision after the full-source diagnosis): the 穿越 theme
        // is retired from EVERY tab. Background: real tab's search route worked but is
        // retired along with the rest; the comic feed route is intermittently
        // risk-controlled (18 rows one minute, empty the next — re-verified twice in
        // 15 minutes); the AI tab has NO usable source at all (category page 404,
        // search's full 108-row walk carries 0 genre=207 rows, ai_series feed
        // IP-blocked). The strips show exactly what the site's selector lists carry.
        val repo=repository { request ->
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"isSuccess":true,
                "selectorList":[{"row_id":1,"items":[
                    {"selector_item_id":"romance","show_name":"爱情"},
                    {"selector_item_id":"period","show_name":"年代"},
                    {"selector_item_id":"comeback","show_name":"逆袭"},
                    {"selector_item_id":"legend","show_name":"传奇"}]}]}}}};</script>"""
        }
        for(type in listOf(ContentType.SHORT,ContentType.COMIC,ContentType.AI)) {
            assertFalse("$type strip must not carry a 穿越 button",repo.themes(type).any { it.slug=="chuanyue" })
            assertFalse("$type fallback must not carry 穿越",ThemeCategory.fallback(type).any { it.slug=="chuanyue" })
        }
        // The site's own themes still parse through.
        assertEquals(listOf("romance","period","comeback","legend"),repo.themes(ContentType.SHORT).map { it.slug })
    }

    @Test fun comicFeedBackedChuanyueUsesTheLandpageThemeFilter() {
        // Stale-cache defensive route: a Library-cached comic 穿越 theme (feedThemeId
        // 37, retired from the strip 2026-11-02) still walks the landpage feed with
        // the numeric category_dim_theme filter — the mechanism must keep working for
        // the disk cache's 7-day tail.
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("data",JSONObject()
                .put("video_data",JSONArray().put(JSONObject()
                    .put("series_id","111").put("title","漫剧穿越")))
                .put("has_more",false).put("next_offset",18).put("session_id","s1")).toString()
        }
        val comic=repo.themeCatalog(ContentType.COMIC,Theme("chuanyue","穿越",feedThemeId = "37"),1)
        assertEquals(listOf("111"),comic.items.map { it.id })
        assertFalse(comic.hasMore)
        val comicPayload=JSONObject(okio.Buffer().apply { requests.last().body!!.writeTo(this) }.readUtf8())
        assertEquals(listOf("comic_series"),comicPayload.getJSONObject("select_items").getJSONArray("genre").let { arr -> (0 until arr.length()).map { arr.optString(it) } })
        assertEquals(listOf("37"),comicPayload.getJSONObject("select_items").getJSONArray("category_dim_theme").let { arr -> (0 until arr.length()).map { arr.optString(it) } })
    }

    @Test fun realChuanyueWalksTheSearchWithGenreFilter() {
        // Stale-cache defensive route: a Library-cached real-drama 穿越 theme
        // (searchKeyword, retired from the strip 2026-11-02) still walks the website
        // search with the genre=205 filter (comic 203 / AI 207 rows dropped) and the
        // 3-page merge window (one theme page ≈ the category pages' 24-row depth).
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            if(request.url.encodedPath=="/api/search/result") {
                requests+=request
                val searchPage=request.url.queryParameter("page")!!.toInt()
                when(searchPage) {
                    1 -> """{"searchList":[
                        {"video_data":{"series_id":"111","series_title":"真人穿越一","genre":205}},
                        {"video_data":{"series_id":"222","series_title":"漫剧混入","genre":203}},
                        {"video_data":{"series_id":"333","series_title":"AI混入","genre":207}}
                    ],"totalCount":3,"hasMore":true}"""
                    2 -> """{"searchList":[
                        {"video_data":{"series_id":"444","series_title":"真人穿越二","genre":205}},
                        {"video_data":{"series_id":"555","series_title":"真人穿越三","genre":205}}
                    ],"totalCount":3,"hasMore":true}"""
                    3 -> """{"searchList":[
                        {"video_data":{"series_id":"666","series_title":"真人穿越四","genre":205}}
                    ],"totalCount":3,"hasMore":false}"""
                    4 -> """{"searchList":[
                        {"video_data":{"series_id":"444","series_title":"真人穿越二","genre":205}},
                        {"video_data":{"series_id":"555","series_title":"真人穿越三","genre":205}}
                    ],"totalCount":3,"hasMore":true}"""
                    5 -> """{"searchList":[
                        {"video_data":{"series_id":"666","series_title":"真人穿越四","genre":205}}
                    ],"totalCount":3,"hasMore":true}"""
                    6 -> """{"searchList":[
                        {"video_data":{"series_id":"777","series_title":"真人穿越五","genre":205}}
                    ],"totalCount":3,"hasMore":false}"""
                    else -> throw AssertionError("search page $searchPage out of the 3-page merge window")
                }
            }
            else throw AssertionError("real 穿越 must not hit the App feed: "+request.url)
        }
        val page=repo.themeCatalog(ContentType.SHORT,Theme("chuanyue","穿越",searchKeyword = "穿越"),1)
        assertEquals(listOf("111","444","555","666"),page.items.map { it.id })
        assertTrue(page.hasMore)
        // Theme page 2 walks search pages 4-6.
        requests.clear()
        val second=repo.themeCatalog(ContentType.SHORT,Theme("chuanyue","穿越",searchKeyword = "穿越"),2)
        assertEquals(listOf("444","555","666","777"),second.items.map { it.id })
        assertEquals(listOf(4,5,6),requests.map { it.url.queryParameter("page")!!.toInt() })
    }

    @Test fun searchThemeMidMergeFailureKeepsCollectedRowsAndScrollsOn() {
        // A search page failing mid-merge (page 2 of 3) keeps the rows already
        // collected and keeps hasMore true so the infinite scroll can retry deeper.
        val repo=repository { request ->
            if(request.url.encodedPath=="/api/search/result") {
                val searchPage=request.url.queryParameter("page")!!.toInt()
                when(searchPage) {
                    1 -> """{"searchList":[
                        {"video_data":{"series_id":"111","series_title":"真人穿越一","genre":205}}
                    ],"hasMore":true}"""
                    2 -> throw IOException("mid-merge network failure")
                    3 -> """{"searchList":[
                        {"video_data":{"series_id":"666","series_title":"真人穿越四","genre":205}}
                    ],"hasMore":false}"""
                    else -> throw AssertionError("unexpected search page $searchPage")
                }
            }
            else throw AssertionError("unexpected "+request.url)
        }
        val page=repo.themeCatalog(ContentType.SHORT,Theme("chuanyue","穿越",searchKeyword = "穿越"),1)
        assertEquals(listOf("111","666"),page.items.map { it.id })
        assertTrue(page.hasMore)
    }

    @Test fun searchThemeFirstSearchPageFailureStillThrows() {
        // The first search page of the merge window failing means the theme itself
        // cannot load — the error surfaces (same contract as before the merge).
        val repo=repository { request ->
            if(request.url.encodedPath=="/api/search/result") throw IOException("search down")
            else throw AssertionError("unexpected "+request.url)
        }
        try {
            repo.themeCatalog(ContentType.SHORT,Theme("chuanyue","穿越",searchKeyword = "穿越"),1)
            fail("expected the theme page to fail when the first search page fails")
        } catch(e: IOException) { assertEquals("穿越数据结构已变化",e.message) }
    }

    @Test fun themesShowNoInjectedChuanyueOnAnyTab() {
        // 2026-11-02: no tab's strip carries 穿越 anymore (retired after the
        // full-source diagnosis — see noTabCarriesAChuanyueButton). themes() must
        // return exactly what the site's selector list parses, nothing appended.
        val repo=repository { request ->
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"isSuccess":true,
                "selectorList":[{"row_id":1,"items":[
                    {"selector_item_id":"romance","show_name":"爱情"},
                    {"selector_item_id":"period","show_name":"年代"},
                    {"selector_item_id":"comeback","show_name":"逆袭"},
                    {"selector_item_id":"legend","show_name":"传奇"}]}]}}}};</script>"""
        }
        for(type in listOf(ContentType.SHORT,ContentType.COMIC,ContentType.AI))
            assertFalse("$type strip must carry no injected 穿越",repo.themes(type).any { it.slug=="chuanyue" })
    }

    @Test fun staleChuanyueRoutesToSearchNotCategory404() {
        // Defensive slug routing (kept after the 2026-11-02 穿越 retirement): a
        // stale cached Theme WITHOUT any marker (Library disk cache, 7-day TTL)
        // must never hit /category/real-drama/chuanyue (a 404) — it falls through
        // to the search chain with the genre=205 filter.
        val repo=repository { request ->
            if(request.url.encodedPath=="/api/search/result")
                """{"searchList":[
                    {"video_data":{"series_id":"111","series_title":"穿越剧","genre":205,"category_list":[{"category_id":"37","name":"穿越"}]}},
                    {"video_data":{"series_id":"333","series_title":"漫剧穿越","genre":203,"category_list":[{"category_id":"37","name":"穿越"}]}}
                ],"totalCount":2,"hasMore":false}"""
            else throw AssertionError("stale 穿越 must not hit the category page: "+request.url)
        }
        val stale=Theme("chuanyue","穿越")
        val page=repo.themeCatalog(ContentType.SHORT,stale,1)
        // genre=203 rows (comic) are filtered out on the real-drama tab.
        assertEquals(listOf("111"),page.items.map { it.id })
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            // The App API now registers the device first (2026-10-27 crackdown on
            // unregistered device_ids): answer the registration endpoint with a
            // server-issued identity so feed/playback calls can proceed.
            val body = if (chain.request().url.encodedPath == "/service/2/device_register/")
                JSONObject().put("device_id","1905892595378490").put("install_id","1905892595382586").toString()
            else respond(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build(),clock
    )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject, board: RankBoard = RankBoard.COMIC) = script("mergeLoaderData",JSONArray()
        .put("rank_${board.path}/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            if(request.url.queryParameter("page")=="2") {
                """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
            } else {
                // Follow-up pages of the full-board fetch: same series, no rank field.
                val page=request.url.queryParameter("page")!!.toInt()
                """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":$page,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(rankPayload(page,5))
            }
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        // The whole board is merged at once: no further pages to append.
        assertFalse(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsNotInventedAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(null,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicHomeUsesTheWebsiteCategoryCatalog() {
        // The comic "全部" grid reads the website category page (24/page, 34 pages,
        // 800 entries, true pagination) — the comic_series feed is a recommendation
        // stream that runs dry (all duplicates) after ~6 pages.
        val repo=repository { request ->
            assertEquals("/category/comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"isSuccess":true,
                "recommendList":[{"series_id":"123","series_name":"测试漫剧","series_cover":"https:\\u002F\\u002Fexample.com\\u002Fcover.jpg","episode_cnt":12,"tags":[{"name":"玄幻"}]}],
                "pagination":{"total":800,"pageNum":2,"pageSize":24,"totalPages":34}}}};</script>"""
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertTrue(page.hasMore)
    }

    @Test fun comicRankingIsStillServedByTheBoard() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.comicRanking(2)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.comicRanking(1) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.comicRanking(2)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        // Full-board fetch: the follow-up page (3) fails its pagination anchor in the
        // mock (same HTML), is swallowed by runCatching, and the board is complete.
        assertFalse(page.hasMore)
    }

    // ---------- four-board ranking (总热播榜 / 真人剧榜 / 漫剧榜 / AI剧榜) ----------

    @Test fun rankingBoardsRequestTheirOwnPaths() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            // Single-page payload (totalPages=1) so ranking() makes exactly one request.
            val board=RankBoard.entries.firstOrNull { request.url.encodedPath.endsWith(it.path) } ?: RankBoard.HOT
            """<script>_ROUTER_DATA={"loaderData":{"rank_${board.path}/page":{"rankKey":"${board.upstreamKey}","pageNum":1,"updatedText":"今日已更新"}}};</script>"""+currentRank(rankPayload(1,1),board)
        }
        repo.ranking(RankBoard.HOT,1)
        assertEquals("/rank/hot-drama",requests.single().url.encodedPath)
        requests.clear()
        repo.ranking(RankBoard.REAL,1)
        assertEquals("/rank/hot-real-drama",requests.single().url.encodedPath)
        requests.clear()
        repo.ranking(RankBoard.COMIC,1)
        assertEquals("/rank/hot-comic-drama",requests.single().url.encodedPath)
        requests.clear()
        repo.ranking(RankBoard.AI,1)
        assertEquals("/rank/hot-ai-drama",requests.single().url.encodedPath)
    }

    @Test fun hotBoardParsesItsOwnRouteData() {
        val repo=repository { request ->
            assertEquals("/rank/hot-drama",request.url.encodedPath)
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-drama/page":{"rankKey":"hongguo","pageNum":1,"updatedText":"10月13日已更新"}}};</script>"""+currentRank(rankPayload(1,5),RankBoard.HOT)
        }
        val result=repo.ranking(RankBoard.HOT,1)
        assertEquals("测试漫剧",result.items.single().title)
        assertEquals(5,result.ranking!!.totalPages)
        assertEquals("10月13日已更新",result.ranking.updatedText)
        // Whole board fetched at once: nothing more to append.
        assertFalse(result.hasMore)
    }

    @Test fun realBoardRejectsComicRouteData() {
        // A page served for another board must not be parsed as this board's data.
        val repo=repository { currentRank(rankPayload(),RankBoard.COMIC) }
        assertThrows(IOException::class.java) { repo.ranking(RankBoard.REAL,1) }
    }

    @Test fun aiBoardParsesRenderedCards() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-ai-drama/page":{"rankKey":"ai","pageNum":1,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">1</span><h2 id="rank-title-123">AI剧</h2><p class="pc-metrics-example">800万热度</p></article>
                <nav aria-label="榜单分页"><span aria-current="page">1</span><a href="/rank/hot-ai-drama?page=2">2</a></nav>"""
        }
        val result=repo.ranking(RankBoard.AI,1)
        assertEquals("AI剧",result.items.single().title)
        assertEquals(RankPosition(1,"800万热度"),result.ranking!!.positions["123"])
        assertEquals(2,result.ranking.totalPages)
        // Whole board fetched at once: nothing more to append.
        assertFalse(result.hasMore)
    }

    @Test fun rankBoardRoundTripsStoredValues() {
        assertEquals(RankBoard.HOT,RankBoard.fromStored("hot"))
        assertEquals(RankBoard.REAL,RankBoard.fromStored("real"))
        assertEquals(RankBoard.COMIC,RankBoard.fromStored("comic"))
        assertEquals(RankBoard.AI,RankBoard.fromStored("ai"))
        assertEquals(RankBoard.COMIC,RankBoard.fromStored("unknown"))
        assertEquals(RankBoard.COMIC,RankBoard.fromStored(null))
    }

    // ---------- search suggestions + enhanced search (phone-version port) ----------

    private fun suggestionBody(vararg entries: JSONObject) = JSONObject()
        .put("suggest_list",JSONArray().apply { entries.forEach { put(it) } }).toString()

    private fun suggestionEntry(name: String,type: String,seriesId: String="") = JSONObject()
        .put("name",name).put("word_type",type)
        .put("video_data",if(seriesId.isEmpty()) JSONObject() else JSONObject().put("series_id_str",seriesId).put("series_title",name))

    @Test fun suggestionsParsePinyinInitialRecords() {
        val repo=repository { request ->
            assertEquals("/incent_resource/suggestion",request.url.encodedPath)
            assertEquals("BFLC",request.url.queryParameter("query"))
            suggestionBody(suggestionEntry("暴富：山城故事","short_play_name","7658596289956826136"),
                suggestionEntry("副本BOSS竟是老公","short_play_name","7670390620711554073"),
                suggestionEntry("白风列车","title"))
        }
        val names=repo.suggestions("BFLC")
        // NFKC normalization turns full-width colon into half-width, matching the Go port.
        assertEquals(listOf("暴富:山城故事","副本BOSS竟是老公","白风列车"),names)
    }

    @Test fun suggestionsDedupeAndCapAtTen() {
        val entries=(1..15).map { suggestionEntry("剧$it","short_play_name","100$it") }.toTypedArray()
        val repo=repository { suggestionBody(*entries) }
        assertEquals(10,repo.suggestions("测试").size)
    }

    @Test fun suggestionsRejectEmptyList() {
        val repo=repository { JSONObject().put("suggest_list",JSONArray()).toString() }
        assertThrows(IOException::class.java) { repo.suggestions("测试") }
    }

    @Test fun searchEnhancedMergesPageAndNameResults() {
        val pageRow=JSONObject().put("series_id","111").put("title","网页结果").put("episode_cnt",8)
        val nameEntry=suggestionEntry("联想结果","short_play_name","222")
        val repo=repository { request ->
            if(request.url.encodedPath=="/incent_resource/suggestion") suggestionBody(nameEntry)
            else """{"searchList":[{"video_data":${pageRow}}],"totalCount":1,"hasMore":true}"""
        }
        val result=repo.searchEnhanced("keyword")
        assertEquals(listOf("111","222"),result.items.map { it.id })
        assertEquals("",result.warning)
    }

    @Test fun searchEnhancedSurvivesPageFailureWithNamesOnly() {
        val repo=repository { request ->
            if(request.url.encodedPath=="/incent_resource/suggestion") suggestionBody(suggestionEntry("联想结果","short_play_name","222"))
            else throw IOException("网页搜索失败")
        }
        val result=repo.searchEnhanced("keyword")
        assertEquals(listOf("222"),result.items.map { it.id })
        assertTrue(result.warning.isNotBlank())
    }

    @Test fun searchEnhancedRanksExactMatchFirst() {
        val pageRows=JSONArray()
            .put(JSONObject().put("video_data",JSONObject().put("series_id","1").put("series_title","闪婚甜果")))
            .put(JSONObject().put("video_data",JSONObject().put("series_id","2").put("series_title","闪婚甜果第一季")))
            .put(JSONObject().put("video_data",JSONObject().put("series_id","3").put("series_title","无关剧集")))
        val repo=repository { request ->
            if(request.url.encodedPath=="/incent_resource/suggestion") suggestionBody()
            else """{"searchList":$pageRows,"totalCount":3,"hasMore":false}"""
        }
        val result=repo.searchEnhanced("闪婚甜果")
        assertEquals("闪婚甜果",result.items.first().title)
        assertTrue(result.items.indexOfFirst { it.title=="闪婚甜果第一季" } < result.items.indexOfFirst { it.title=="无关剧集" })
    }

    @Test fun seasonParsingHandlesArabicAndChineseNumerals() {
        assertEquals(SearchSuggest.SeasonInfo("暴富:山城故事",2,"季","2"),SearchSuggest.parseSeason("暴富：山城故事第2季"))
        assertEquals(SearchSuggest.SeasonInfo("飞狗MOCO",1,"季","一"),SearchSuggest.parseSeason("飞狗MOCO 第一季"))
        assertEquals(12,SearchSuggest.parseChineseNumber("十二"))
        assertEquals(21,SearchSuggest.parseChineseNumber("二十一"))
        assertEquals(105,SearchSuggest.parseChineseNumber("一百零五"))
        assertNull(SearchSuggest.parseSeason("没有季数后缀"))
        assertEquals(2,SearchSuggest.parseChineseNumber("两"))
    }

    @Test fun seasonQueriesFindMissingSeasons() {
        val dramas=listOf(Series("1","暴富：山城故事第2季"),Series("2","暴富：山城故事第3季"),Series("3","无关剧集"))
        val queries=SearchSuggest.seasonQueries("暴富",dramas)
        assertTrue(queries.any { it=="暴富:山城故事第1季" || it=="暴富:山城故事" })
    }

    @Test fun searchTextStripsPunctuationAndLowercases() {
        assertEquals("bfcssg",SearchSuggest.searchText("暴富：山城故事2季".replace(Regex("[^\\p{L}\\p{N}]"),"")) .take(0)+SearchSuggest.searchText("BFC:SSG"))
        assertEquals("bfc",SearchSuggest.searchText(" B F C "))
    }

    @Test fun titleRankOrdersExactPrefixContains() {
        assertEquals(0,SearchSuggest.titleRank("闪婚甜果","闪婚甜果"))
        assertEquals(1,SearchSuggest.titleRank("闪婚甜果第一季","闪婚甜果"))
        assertEquals(2,SearchSuggest.titleRank("我的闪婚甜果","闪婚甜果"))
        assertEquals(3,SearchSuggest.titleRank("闪婚之后甜果满满","闪婚 甜果"))
        assertEquals(4,SearchSuggest.titleRank("完全无关","闪婚甜果"))
    }
}
