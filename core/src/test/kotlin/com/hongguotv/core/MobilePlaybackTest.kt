// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 6672b55 via tsukiyo-zero/hongguoTV 0236976; GPL-3.0-only.
package com.hongguotv.core

import java.io.IOException
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * v5 通道的请求构造与响应解析（离线，全部打桩）。
 *
 * 2026-11-23（上游 41ba81c）：本类只发「一个形态的一次请求」——路径由调用方给，
 * 不再自己做路径回退（轮换在 MultiVideoModelStreamSource + PlaybackShapeMemory）。
 */
class MobilePlaybackTest {
    private val device = mapOf("device_id" to "123456", "iid" to "654321", "cdid" to "synthetic")
    private val signer = MobilePlaybackSigner { url, _, _ -> MobileSignedRequest(url, emptyMap()) }

    private fun model(typed: Boolean, encoded: Boolean, id: String = "123"): String {
        val model = JSONObject("""{"video_list":[{"main_url":"https://example.test/video"}]}""")
        val entries = JSONObject().put(id, JSONObject().put("video_model", if (encoded) model.toString() else model))
        return JSONObject().put("code", 0)
            .put("data", if (typed) JSONObject().put("1004", entries) else entries).toString()
    }

    /** GET 形态的响应：data.video_model_data[id].video_model。 */
    private fun tabModel(encoded: Boolean, id: String = "123"): String {
        val model = JSONObject("""{"video_list":[{"main_url":"https://example.test/video"}]}""")
        return JSONObject().put("code", 0).put("data", JSONObject()
            .put("video_model_data", JSONObject().put(id, JSONObject()
                .put("video_model", if (encoded) model.toString() else model)))).toString()
    }

    private fun http(body: String, inspect: (okhttp3.Request) -> Unit = {}) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()

    @Test fun exactEpisodeSelectionSupportsBothMapsAndEncodedModels() {
        // data["1004"][id] 与平铺 data[id] 两种形态、video_model 为对象或 JSON 字符串都要认；
        // 但集号必须精确匹配 —— 拿别的集模型去播是最难查的一类 bug。
        for (typed in listOf(false, true)) for (encoded in listOf(false, true)) {
            val list = MobilePlayback.model("123", device, http(model(typed, encoded)), signer, 1000)
                .getJSONObject("video_model").getJSONArray("video_list")
            assertEquals(1, list.length())
        }
        assertThrows(IOException::class.java) {
            MobilePlayback.model("123", device, http(model(false, false, "456")), signer, 1000)
        }
    }

    @Test fun fallbackOnlyModelIsAccepted() {
        val body = JSONObject().put("code", 0).put("data", JSONObject().put("123", JSONObject()
            .put("video_model", JSONObject().put("fallback_api", "https://api.fqnovel.com/x")))).toString()
        val model = MobilePlayback.model("123", device, http(body), signer, 1000).getJSONObject("video_model")
        assertFalse(model.has("video_list"))
        assertTrue(model.has("fallback_api"))
    }

    @Test fun signedCompressedBytesAndIdentityAreExactlyWhatIsSent() {
        var signed: ByteArray? = null
        var target: String? = null
        val signing = MobilePlaybackSigner { url, bytes, identity ->
            assertEquals(device, identity)
            signed = bytes.copyOf(); target = url
            MobileSignedRequest(url, mapOf("x-medusa" to "synthetic"))
        }
        MobilePlayback.model("123", device, http(model(false, false)) { request ->
            val bytes = Buffer().apply { request.body!!.writeTo(this) }.readByteArray()
            // 签名绑定的是压缩后的字节，不是明文 JSON。
            assertArrayEquals(signed, bytes)
            assertEquals(target, request.url.toString())
            // 默认路径是 player 变体（上游 41ba81c：调用方按形态传 segment）。
            assertEquals(MobilePlayback.PLAYER_PATH, request.url.encodedPath)
            assertEquals("123456", request.url.queryParameter("device_id"))
            // 官方 74532 App 画像（上游 N3urda ef328f3）：这几个字段一旦退回旧值
            // （vivo_8662_64 / PGT-AN10 / os 12）就会 200+0B，与签名无关。
            assertEquals("7.4.5.32", request.url.queryParameter("version_name"))
            assertEquals("74532", request.url.queryParameter("version_code"))
            assertEquals("74532", request.url.queryParameter("manifest_version_code"))
            assertEquals("update_64", request.url.queryParameter("channel"))
            assertEquals("25053RT47C", request.url.queryParameter("device_type"))
            assertEquals("Redmi", request.url.queryParameter("device_brand"))
            assertEquals("16", request.url.queryParameter("os_version"))
            assertEquals("36", request.url.queryParameter("os_api"))
            assertEquals("arm64-v8a", request.url.queryParameter("host_abi"))
            assertEquals("gzip", request.header("Content-Encoding"))
            assertEquals("synthetic", request.header("x-medusa"))
            assertNull(request.header("Cookie"))
            assertEquals(digest("MD5", bytes).hex().uppercase(), request.header("x-ss-stub"))
            val payload = JSONObject(GZIPInputStream(bytes.inputStream()).reader().readText())
            assertEquals(1, payload.getJSONObject("biz_param").getInt("device_level"))
            assertEquals("123", payload.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
            // v1 路径不带 dr_scene（上游 573d75a：带上会被路由到 :dr_scene 变体 → 空体）。
            assertFalse(payload.has("dr_scene"))
        }, signing, 1000)
    }

    @Test fun segmentParameterSelectsThePath() {
        // 上层的形态矩阵要能把同一个 v5 签名打在不同路径上（41ba81c 起）。
        val hits = mutableListOf<String>()
        val http = http(model(false, false)) { hits += it.url.encodedPath }
        MobilePlayback.model("123", device, http, signer, 1000, segment = MobilePlayback.MODEL_SEGMENT)
        assertEquals(listOf("/${MobilePlayback.MODEL_SEGMENT}"), hits)
    }

    @Test fun officialVideoModelEndpointUsesFlatBodyAndTrailingSlash() {
        // 上游 eb960ff：官方 7.4.5.32 起改打 /novel/player/video_model/v1/（带尾斜杠）；
        // 实测它的请求体是**扁平 snake_case**（旧 map 体会被回 code=100001 video_id invalid），
        // 响应则是 `data.video_model` 直接给完整模型（字符串）。
        var path: String? = null
        val http = http(model(false, false)) { request ->
            path = request.url.encodedPath
            val body = Buffer().apply { request.body!!.writeTo(this) }.readByteArray()
            val payload = JSONObject(GZIPInputStream(body.inputStream()).reader().readText())
            assertEquals("123", payload.getString("video_id"))
            assertEquals(0, payload.getInt("video_id_type"))
            assertEquals(1, payload.getInt("device_level"))
            assertTrue(payload.getBoolean("need_all_video_definition"))
            assertEquals(1024, payload.getInt("video_platform"))
            // 旧的两段式字段必须不存在，否则服务端判 invalid param。
            assertFalse(payload.has("biz_param"))
            assertFalse(payload.has("mixed_video_id_map"))
            assertFalse(payload.has("dr_scene"))
        }
        MobilePlayback.model(
            "123", device, http, signer, 1000,
            segment = MobilePlayback.VIDEO_MODEL_SEGMENT, body = "flat")
        assertEquals("/novel/player/video_model/v1/", path)
    }

    @Test fun flatEndpointResponseModelIsParsedFromDataVideoModel() {
        // 官方 video_model/v1 的响应：data.video_model 是 JSON 字符串（不是 per-episode map）。
        val inner = JSONObject("""{"video_list":[{"main_url":"https://example.test/v"}]}""")
        val body = JSONObject().put("code", 0).put("data", JSONObject()
            .put("video_height", 720).put("video_model", inner.toString())).toString()
        val parsed = MobilePlayback.model(
            "123", device, http(body), signer, 1000,
            segment = MobilePlayback.VIDEO_MODEL_SEGMENT, body = "flat")
            .getJSONObject("video_model")
        assertEquals(1, parsed.getJSONArray("video_list").length())
    }

    @Test fun currentSingleModelUsesVideoIdPlusBizParamOnReadingHost() {
        // 上游 d859244（0.1.40，2026-11-25）：单集 `video_model/v1/` 走 reading.snssdk.com，
        // 体是 `video_id` + `biz_param`（device_level=4），响应 `data.video_model` 直接给模型
        //（字符串或对象都可能）。这是当前唯一能取到 1080P 多档的形态。
        var path: String? = null
        var host: String? = null
        val inner = JSONObject("""{"video_list":[{"main_url":"https://example.test/v"}]}""")
        val body = JSONObject().put("code", 0)
            .put("data", JSONObject().put("video_id", "123").put("video_model", inner.toString())).toString()
        val http = http(body) { request ->
            path = request.url.encodedPath
            host = request.url.host
            val bytes = Buffer().apply { request.body!!.writeTo(this) }.readByteArray()
            val payload = JSONObject(GZIPInputStream(bytes.inputStream()).reader().readText())
            assertEquals("123", payload.getString("video_id"))
            val biz = payload.getJSONObject("biz_param")
            assertEquals(4, biz.getInt("device_level"))
            assertTrue(biz.getBoolean("need_all_video_definition"))
            assertEquals(1024, biz.getInt("video_platform"))
            // 单集体不能带批量字段 / 预加载场景标记
            assertFalse(payload.has("mixed_video_id_map"))
            assertFalse(payload.has("dr_scene"))
        }
        val parsed = MobilePlayback.model(
            "123", device, http, signer, 1000,
            segment = MobilePlayback.VIDEO_MODEL_SEGMENT, host = MobilePlayback.PRELOAD_BASE,
            deviceLevel = 4, body = "single")
            .getJSONObject("video_model")
        assertEquals(1, parsed.getJSONArray("video_list").length())
        assertEquals("/novel/player/video_model/v1/", path)
        assertEquals("reading.snssdk.com", host)
    }

    @Test fun currentSingleModelRejectsAnotherEpisodesResponse() {
        // data.video_id 回显的不是请求的集 → 必须换形态，绝不播错集（上游 d859244）。
        val mismatched = """{"code":0,"data":{"video_id":"456","video_model":{"video_list":[{}]}}}"""
        assertThrows(IOException::class.java) {
            MobilePlayback.model(
                "123", device, http(mismatched), signer, 1000,
                segment = MobilePlayback.VIDEO_MODEL_SEGMENT, host = MobilePlayback.PRELOAD_BASE,
                deviceLevel = 4, body = "single")
        }
    }

    @Test fun getVideoTabUsesV5SignatureAndServiceEndpoint() {
        var signedBytes: ByteArray? = null
        val signing = MobilePlaybackSigner { url, bytes, identity ->
            assertEquals(device, identity)
            signedBytes = bytes
            MobileSignedRequest(url, mapOf("x-khronos" to "synthetic", "x-ss-stub" to "must-be-dropped"))
        }
        MobilePlayback.getVideoTab("123", device, http(tabModel(false)) { request ->
            assertEquals("GET", request.method)
            assertEquals("/${MobilePlayback.GET_TAB_SEGMENT}", request.url.encodedPath)
            assertEquals(MobilePlayback.GET_TAB_BASE.substringAfter("//"), request.url.host)
            // 与 POST 形态共用 74532 官方画像（上游 ff4d1d2 / ef328f3）。
            assertEquals("74532", request.url.queryParameter("version_code"))
            assertEquals("7.4.5.32", request.url.queryParameter("version_name"))
            assertEquals("update_64", request.url.queryParameter("channel"))
            assertEquals("25053RT47C", request.url.queryParameter("device_type"))
            assertEquals("123", request.url.queryParameter("video_id"))
            assertEquals("1", request.url.queryParameter("device_level"))
            assertEquals("synthetic", request.header("x-khronos"))
            assertNull(request.header("x-argus"))
            assertNull(request.header("Cookie"))
            // GET 没有请求体：空字节只是签名输入，这些 POST 头会暴露伪造特征。
            assertNull(request.header("x-ss-stub"))
            assertNull(request.header("Content-Type"))
            assertNull(request.header("Content-Encoding"))
            assertEquals(MobilePlayback.GET_TAB_UA, request.header("User-Agent"))
        }, signing, 1000)
        // GET 无请求体：签名绑定空字节。
        assertNotNull(signedBytes)
        assertTrue(signedBytes!!.isEmpty())
    }

    @Test fun preloadVariantCarriesOfficialHostLevelAndScene() {
        // 官方现行端点（上游 N3urda ef328f3 / e2f9ef9）：reading.snssdk.com + preload/v1，
        // 体里 device_level=4（能力等级）且带 dr_scene=preload，video_platform 保持 1024。
        var seen = 0
        MobilePlayback.model(
            "123", device, http(model(false, false)) { request ->
                seen++
                assertEquals("reading.snssdk.com", request.url.host)
                assertEquals("/${MobilePlayback.PRELOAD_SEGMENT}", request.url.encodedPath)
                val body = Buffer().apply { request.body!!.writeTo(this) }.readByteArray()
                val payload = JSONObject(GZIPInputStream(body.inputStream()).reader().readText())
                assertEquals(4, payload.getJSONObject("biz_param").getInt("device_level"))
                assertEquals(1024, payload.getJSONObject("biz_param").getInt("video_platform"))
                assertEquals("preload", payload.getString("dr_scene"))
            }, signer, 1000,
            segment = MobilePlayback.PRELOAD_SEGMENT, host = MobilePlayback.PRELOAD_BASE,
            drScene = "preload", deviceLevel = 4)
        assertEquals(1, seen)
    }

    @Test fun getVideoTabParsesBothModelShapes() {
        for (encoded in listOf(false, true)) {
            val list = MobilePlayback.getVideoTab("123", device, http(tabModel(encoded)), signer, 1000)
                .getJSONObject("video_model").getJSONArray("video_list")
            assertEquals(1, list.length())
        }
        // 别的集不能拿来顶替。
        assertThrows(IOException::class.java) {
            MobilePlayback.getVideoTab("123", device, http(tabModel(false, "456")), signer, 1000)
        }
    }

    @Test fun businessFailureEmptyOversizedAndMalformedBodiesRemainFailures() {
        // 业务码（110001 风控）、坏 JSON、超 2MiB 都必须失败；空正文走可自愈的空响应异常。
        val rejection = assertThrows(AppApiRejectedException::class.java) {
            MobilePlayback.model("123", device, http("""{"code":110001}"""), signer, 1000)
        }
        assertEquals(110001, rejection.code)
        for (body in listOf("bad json", " ".repeat(2 * 1024 * 1024 + 1))) {
            assertThrows(IOException::class.java) { MobilePlayback.model("123", device, http(body), signer, 1000) }
        }
        assertThrows(EmptyPlaybackResponseException::class.java) {
            MobilePlayback.model("123", device, http(""), signer, 1000)
        }
        assertThrows(EmptyPlaybackResponseException::class.java) {
            MobilePlayback.getVideoTab("123", device, http(""), signer, 1000)
        }
    }

    @Test fun modelWithoutAnyResourceIsAFailure() {
        val body = JSONObject().put("code", 0).put("data", JSONObject().put("123", JSONObject()
            .put("video_model", JSONObject()))).toString()
        assertThrows(IOException::class.java) { MobilePlayback.model("123", device, http(body), signer, 1000) }
    }

    @Test fun emptyBodyIsSingleShotNoInternalPathRetry() {
        // 单次语义：一次调用只打一条路径（轮换由上层负责）。否则上层再轮换会翻倍消耗配额。
        val hits = mutableListOf<String>()
        val http = http("") { hits += it.url.encodedPath }
        assertThrows(EmptyPlaybackResponseException::class.java) {
            MobilePlayback.model("123", device, http, signer, 1000)
        }
        assertEquals(1, hits.size)
    }

    private fun digest(algorithm: String, bytes: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance(algorithm).digest(bytes)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}