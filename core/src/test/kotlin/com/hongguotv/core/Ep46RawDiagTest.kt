package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Deep probe: dump the RAW responses for ep46 (videoId 7673860661994064920) on both
 * the tab endpoint and the old POST path - the TV saw JSONException (non-empty,
 * non-JSON), the local probe saw EmptyPlaybackResponseException (empty). Two
 * different failure shapes on the same episode means the server is returning
 * something in between (truncated? HTML error page? gzip corruption?).
 * Run manually (hits the live API):
 *   gradlew :core:test --tests "com.hongguotv.core.Ep46RawDiagTest"
 */
class Ep46RawDiagTest {
    @org.junit.Test
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val transport = AppApiTransport(http)
        val session = PlaybackDeviceSession()
        val identity = session.profile(http)
        val videoId = "7673860661994064920"

        // 1) Tab endpoint raw response.
        val extra = "&video_id=$videoId&video_id_type=0&device_level=1&need_all_video_definition=1" +
            "&need_mp4_align=0&use_os_player=0&video_platform=1024&source=0&from_video_id="
        val tabQuery = transport.encodeQuery(identity.values + mapOf("_rticket" to transport.clockMs().toString()))
        val tabUrl = "${AppApiTransport.APP_BASE}${MultiVideoModelStreamSource.TAB_PATH}?$tabQuery$extra"
        val tabReq = okhttp3.Request.Builder().url(tabUrl)
            .header("User-Agent", "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)")
            .header("Accept", "application/json; charset=utf-8")
            .apply { if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie) }
            .get().build()
        http.newCall(tabReq).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val text = String(bytes, Charsets.UTF_8)
            println("TAB: HTTP ${r.code} bytes=${bytes.size} content-type=${r.header("Content-Type")}")
            println("TAB head: ${text.take(300).replace("\n", " ")}")
        }

        // 2) Old POST path raw response.
        val payload = org.json.JSONObject()
            .put("biz_param", org.json.JSONObject()
                .put("detail_page_version", 0).put("device_level", 3).put("disable_digg_stat", false)
                .put("need_all_video_definition", true).put("need_mp4_align", false)
                .put("use_os_player", false).put("use_server_dns", false).put("video_platform", 1024))
            .put("mixed_video_id_map", org.json.JSONObject().put("1004", org.json.JSONArray().put(videoId)))
        val postQuery = transport.encodeQuery(identity.values + mapOf("_rticket" to transport.clockMs().toString()))
        val postUrl = "${AppApiTransport.APP_BASE}${MultiVideoModelStreamSource.PATH}?$postQuery"
        val postReq = okhttp3.Request.Builder().url(postUrl)
            .header("User-Agent", "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)")
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("Content-Type", "application/json; charset=utf-8")
            .apply { if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie) }
            .post(payload.toString().toByteArray(Charsets.UTF_8)
                .toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(postReq).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val text = String(bytes, Charsets.UTF_8)
            println("POST: HTTP ${r.code} bytes=${bytes.size} content-type=${r.header("Content-Type")}")
            println("POST head: ${text.take(300).replace("\n", " ")}")
        }

        // 3) Compare with a KNOWN-GOOD episode (ep1 of the same series) - is the
        //    failure episode-specific or identity-wide?
        runCatching {
            val repo = ContentRepository(http)
            val detail = repo.detail("7673855639302442008", ContentType.SHORT)
            println("ep1 videoId: ${detail.episodes.first()}")
            // Same fresh identity, same endpoints, ep1 of the SAME series.
            val ep1Url = "${AppApiTransport.APP_BASE}${MultiVideoModelStreamSource.TAB_PATH}?$tabQuery" +
                "&video_id=${detail.episodes.first()}&video_id_type=0&device_level=1&need_all_video_definition=1" +
                "&need_mp4_align=0&use_os_player=0&video_platform=1024&source=0&from_video_id="
            val ep1Req = okhttp3.Request.Builder().url(ep1Url)
                .header("User-Agent", "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)")
                .header("Accept", "application/json; charset=utf-8")
                .apply { if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie) }
                .get().build()
            http.newCall(ep1Req).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                println("SAME-SERIES EP1 TAB: HTTP ${r.code} bytes=${bytes.size}")
                println("EP1 head: ${String(bytes, Charsets.UTF_8).take(200).replace("\n", " ")}")
            }
        }.onFailure { println("ep1 compare failed: ${it.message}") }

        // 4) Known-good cross-series control (糯糯下山 ep1, green at 19:23).
        runCatching {
            val controlUrl = "${AppApiTransport.APP_BASE}${MultiVideoModelStreamSource.TAB_PATH}?$tabQuery" +
                "&video_id=7677804745699888153&video_id_type=0&device_level=1&need_all_video_definition=1" +
                "&need_mp4_align=0&use_os_player=0&video_platform=1024&source=0&from_video_id="
            val controlReq = okhttp3.Request.Builder().url(controlUrl)
                .header("User-Agent", "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)")
                .header("Accept", "application/json; charset=utf-8")
                .apply { if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie) }
                .get().build()
            http.newCall(controlReq).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                println("CONTROL 糯糯下山 TAB: HTTP ${r.code} bytes=${bytes.size}")
                println("CONTROL head: ${String(bytes, Charsets.UTF_8).take(200).replace("\n", " ")}")
            }
        }.onFailure { println("control failed: ${it.message}") }
    }
}
