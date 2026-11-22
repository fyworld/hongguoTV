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

/** v5 通道的请求构造与响应解析（离线，全部打桩）。 */
class MobilePlaybackTest {
    private val device = mapOf("device_id" to "123456", "iid" to "654321", "cdid" to "synthetic")
    private val signer = MobilePlaybackSigner { url, _, _ -> MobileSignedRequest(url, emptyMap()) }

    /** 路径记忆是进程级静态状态，用例之间必须复位。 */
    @org.junit.Before fun resetPathMemory() = MobilePlayback.resetPathMemory()

    private fun model(typed: Boolean, encoded: Boolean, id: String = "123"): String {
        val model = JSONObject("""{"video_list":[{"main_url":"https://example.test/video"}]}""")
        val entries = JSONObject().put(id, JSONObject().put("video_model", if (encoded) model.toString() else model))
        return JSONObject().put("code", 0)
            .put("data", if (typed) JSONObject().put("1004", entries) else entries).toString()
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
            // 默认先打 /player/v1（2026-11-22 实测唯一活着的 v5 路径），见 orderedPaths()。
            assertEquals(MobilePlayback.PLAYER_PATH, request.url.encodedPath)
            assertEquals("123456", request.url.queryParameter("device_id"))
            // 7.4.5.32 起按新版客户端协商（上游 573d75a）；74332 会让 v1 路径回空体。
            assertEquals("7.4.5.32", request.url.queryParameter("version_name"))
            assertEquals("74532", request.url.queryParameter("version_code"))
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
    }

    @Test fun modelWithoutAnyResourceIsAFailure() {
        val body = JSONObject().put("code", 0).put("data", JSONObject().put("123", JSONObject()
            .put("video_model", JSONObject()))).toString()
        assertThrows(IOException::class.java) { MobilePlayback.model("123", device, http(body), signer, 1000) }
    }

    /** 按路径分别回答，用来验证路径回退（2026-11-22：platform 在两个 v5 路径间来回切）。 */
    private fun pathHttp(bodies: Map<String, String>, hits: MutableList<String>) =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            hits += request.url.encodedPath
            val body = bodies[request.url.encodedPath].orEmpty()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()

    @Test fun emptyFirstPathFallsBackToTheOtherPath() {
        val hits = mutableListOf<String>()
        val http = pathHttp(mapOf(MobilePlayback.MODEL_PATH to model(false, false)), hits)
        val entry = MobilePlayback.model("123", device, http, signer, 1000)
        assertEquals(1, entry.getJSONObject("video_model").getJSONArray("video_list").length())
        // /player/v1 空 → 换平铺 v1 拿到模型
        assertEquals(listOf(MobilePlayback.PLAYER_PATH, MobilePlayback.MODEL_PATH), hits)
    }

    @Test fun thePathThatWorkedIsTriedFirstNextTime() {
        val hits = mutableListOf<String>()
        val http = pathHttp(mapOf(MobilePlayback.MODEL_PATH to model(false, false)), hits)
        MobilePlayback.model("123", device, http, signer, 1000) // 记忆 v1
        hits.clear()
        MobilePlayback.model("123", device, http, signer, 1000)
        // 稳态只打一个请求，且先打上次成功的路径
        assertEquals(listOf(MobilePlayback.MODEL_PATH), hits)
    }

    @Test fun allPathsEmptyRaisesTheRecoverableEmptyResponse() {
        val hits = mutableListOf<String>()
        assertThrows(EmptyPlaybackResponseException::class.java) {
            MobilePlayback.model("123", device, pathHttp(emptyMap(), hits), signer, 1000)
        }
        // 两条路径都试过，才向上报空响应（外层据此轮换身份）
        assertEquals(2, hits.size)
        assertEquals(hits.distinct().size, hits.size)
    }

    @Test fun businessRejectionDoesNotBurnASecondPath() {
        // 业务码是身份/风控层的事，换路径没有意义 —— 只打一次就抛。
        val hits = mutableListOf<String>()
        val http = pathHttp(mapOf(MobilePlayback.PLAYER_PATH to """{"code":110001}"""), hits)
        assertThrows(AppApiRejectedException::class.java) {
            MobilePlayback.model("123", device, http, signer, 1000)
        }
        assertEquals(1, hits.size)
    }
}
