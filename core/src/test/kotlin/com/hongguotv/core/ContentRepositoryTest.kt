package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        val rows=JSONObject()
        variants.forEachIndexed { index,(height,codec,url) ->
            rows.put("video_$index",JSONObject().put("video_meta",JSONObject().put("vheight",height).put("codec_type",codec)).put("main_url",url))
        }
        JSONObject().put("code",0).put("data",JSONObject().put("video_model",JSONObject().put("video_list",rows))).toString()
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

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(IOException::class.java) { repo.stream("123",720) }
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

    @Test fun videoModelStringIsAccepted() {
        val repo=repository { request ->
            val rows=JSONObject()
            rows.put("video_0",JSONObject().put("video_meta",JSONObject().put("vheight",720).put("codec_type","h264")).put("main_url","https://example.com/avc"))
            JSONObject().put("code",0).put("data",JSONObject().put("video_model",JSONObject().put("video_list",rows).toString())).toString()
        }
        assertEquals("720P",repo.stream("123",1080).quality)
    }

    @Test fun appRequestCarriesSignatureHeaders() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("data",JSONObject().put("video_data",JSONArray())).toString()
        }
        repo.home(1,ContentType.SHORT)
        val request=requests.single()
        assertEquals("POST",request.method)
        assertNotNull(request.header("X-Gorgon"))
        assertNotNull(request.header("X-Khronos"))
        assertNotNull(request.header("X-SS-Req-Ticket"))
        assertEquals("8662",request.url.queryParameter("aid"))
        assertNotNull(request.url.queryParameter("device_id"))
        assertNotNull(request.url.queryParameter("_rticket"))
    }

    @Test fun homeParsesLandpageFeedAndPagination() {
        val repo=repository { request ->
            val row=JSONObject()
                .put("series_id","123").put("title","测试短剧").put("episode_cnt",12)
                .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            JSONObject().put("code",0).put("data",JSONObject()
                .put("video_data",JSONArray().put(row))
                .put("has_more",true).put("next_offset",18).put("session_id","session-1")).toString()
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals(listOf("123"),result.items.map { it.id })
        assertEquals("测试短剧",result.items.single().title)
        assertEquals("https://example.com/cover.jpg",result.items.single().cover)
        assertEquals("全 12 集",result.items.single().badge)
        assertTrue(result.hasMore)
    }

    @Test fun homeSecondPageUsesOffsetCursor() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("data",JSONObject()
                .put("video_data",JSONArray()).put("has_more",false).put("next_offset",36)).toString()
        }
        repo.home(1,ContentType.SHORT)
        repo.home(2,ContentType.SHORT)
        assertEquals(2,requests.size)
        val payload=JSONObject(okio.Buffer().apply { requests[1].body!!.writeTo(this) }.readUtf8())
        assertEquals(18,payload.getInt("offset"))
        assertEquals(2,payload.getInt("client_req_type"))
    }

    @Test fun homeRejectsInvalidRows() {
        val repo=repository { request ->
            JSONObject().put("code",0).put("data",JSONObject()
                .put("video_data",JSONArray().put(JSONObject().put("series_id","abc").put("title","无效")))).toString()
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
        val repo=repository { request ->
            assertEquals("/search/keyword",request.url.encodedPath)
            """<script>_ROUTER_DATA={"loaderData":{"search_(keyword)/page":{"searchList":[{"series_id":"456","series_name":"测试结果","episode_cnt":8}],"totalCount":10}}}};</script>"""
        }
        val result=repo.search("keyword",1,ContentType.SHORT)
        assertEquals(listOf("456"),result.items.map { it.id })
        assertEquals("测试结果",result.items.single().title)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
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
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
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

    @Test fun comicHomeUsesDedicatedRank() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun legacyRankIsStillSupported() {
        val repo=repository { script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload())) }
        assertTrue(repo.home(1,ContentType.COMIC).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    // ---------- four-board ranking (总热播榜 / 真人剧榜 / 漫剧榜 / AI剧榜) ----------

    @Test fun rankingBoardsRequestTheirOwnPaths() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            val board=RankBoard.entries.firstOrNull { request.url.encodedPath.endsWith(it.path) } ?: RankBoard.HOT
            """<script>_ROUTER_DATA={"loaderData":{"rank_${board.path}/page":{"rankKey":"${board.upstreamKey}","pageNum":1,"updatedText":"今日已更新"}}};</script>"""+currentRank(rankPayload(),board)
        }
        repo.ranking(RankBoard.HOT,1)
        assertEquals("/rank/hot-drama",requests.single().url.encodedPath)
        repo.ranking(RankBoard.REAL,1)
        assertEquals("/rank/hot-real-drama",requests.last().url.encodedPath)
        repo.ranking(RankBoard.COMIC,1)
        assertEquals("/rank/hot-comic-drama",requests.last().url.encodedPath)
        repo.ranking(RankBoard.AI,1)
        assertEquals("/rank/hot-ai-drama",requests.last().url.encodedPath)
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
        assertTrue(result.hasMore)
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
        assertTrue(result.hasMore)
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
            else """<script>_ROUTER_DATA={"loaderData":{"search_(keyword)/page":{"searchList":[{"video_data":${pageRow}}],"totalCount":1}}};</script>"""
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
            else """<script>_ROUTER_DATA={"loaderData":{"search_(keyword)/page":{"searchList":$pageRows,"totalCount":3}}};</script>"""
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
