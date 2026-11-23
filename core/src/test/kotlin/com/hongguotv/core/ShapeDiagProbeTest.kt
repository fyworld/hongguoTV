// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.zip.GZIPOutputStream

/**
 * 六形态逐条诊断（2026-11-23 建）：只打印，不判失败。
 *
 * 形态 = 路径 × 签名，平台会在两者上同时切换；某条 0B 只说明该组合今天被关，
 * 不能据此改代码。真正的健康判据是 [PlaybackHealthProbeTest] 的全链路口径。
 *
 * 手动运行：
 *   PROBE_LIVE=1 gradlew :core:test --tests "com.hongguotv.core.ShapeDiagProbeTest"
 */
class ShapeDiagProbeTest {
    @org.junit.Test
    fun freshIdentityGetV5First() {
        org.junit.Assume.assumeTrue("诊断探针：需 PROBE_LIVE=1", System.getenv("PROBE_LIVE") == "1")
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val videoId = "7677804745699888153"
        // 每条都用全新匿名身份的 FIRST 请求打 GET+v5：排除「单身份配额/屏蔽」这个变量。
        for (round in 1..3) {
            val identity = PlaybackDeviceSession().profile(http)
            runCatching {
                val entry = MobilePlayback.getVideoTab(videoId, identity.values, http, MobilePlayback.signer, System.currentTimeMillis())
                val list = entry.getJSONObject("video_model").optJSONArray("video_list")
                println("FRESH GET+v5 round=$round device=${identity.values["device_id"]} OK tiers=${list?.length() ?: 0}")
            }.onFailure {
                println("FRESH GET+v5 round=$round device=${identity.values["device_id"]} FAIL ${it.javaClass.simpleName} ${it.message}")
            }
        }
    }

    @org.junit.Test
    fun probe() {
        org.junit.Assume.assumeTrue("诊断探针：需 PROBE_LIVE=1", System.getenv("PROBE_LIVE") == "1")
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val videoId = "7677804745699888153" // 糯糯下山 ep1
        val session = PlaybackDeviceSession()
        val identity = session.profile(http)
        val transport = AppApiTransport(http)
        val uaLegacy = MobilePlayback.PLAYBACK_UA
        println("=== identity device_id=${identity.values["device_id"]} iid=${identity.values["iid"]} ===")

        // 1) GET video_tab + v5 签名（上游 ff4d1d2 声称唯一可用）
        runCatching {
            val entry = MobilePlayback.getVideoTab(videoId, identity.values, http, MobilePlayback.signer, System.currentTimeMillis())
            val list = entry.getJSONObject("video_model").optJSONArray("video_list")
            println("GET+v5 : OK tiers=${list?.length() ?: 0} bytes=${entry.toString().length}")
        }.onFailure { println("GET+v5 : FAIL ${it.javaClass.simpleName} ${it.message}") }

        // 2) GET video_tab + 旧 Argus 签名
        runCatching {
            val extra = mapOf(
                "video_id" to videoId, "video_id_type" to "0", "device_level" to "1",
                "need_all_video_definition" to "1", "need_mp4_align" to "0",
                "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
                "from_video_id" to "")
            val signed = transport.sign(
                "https://${MultiVideoModelStreamSource.PLAY_HOSTS[0]}${MultiVideoModelStreamSource.TAB_PATH}",
                identity.values + extra, null, uaLegacy)
            val request = Request.Builder().url(signed.url)
                .apply {
                    signed.headers.forEach { (k, v) -> header(k, v) }
                    if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
                }.get().build()
            http.newCall(request).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                println("GET+argus: HTTP ${r.code} bytes=${bytes.size}${if (bytes.isEmpty()) "  <-- EMPTY" else ""}")
            }
        }.onFailure { println("GET+argus: FAIL ${it.javaClass.simpleName} ${it.message}") }

        // 3) POST 两条路径 × 两种签名
        val payload = """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$videoId"]}}"""
        val gz = java.io.ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val gzBytes = gz.toByteArray()

        for (segment in listOf(MobilePlayback.PLAYER_SEGMENT, MobilePlayback.MODEL_SEGMENT)) {
            // v5
            runCatching {
                val entry = MobilePlayback.model(videoId, identity.values, http, MobilePlayback.signer,
                    System.currentTimeMillis(), segment = segment)
                val list = entry.getJSONObject("video_model").optJSONArray("video_list")
                println("POST+v5  /$segment: OK tiers=${list?.length() ?: 0}")
            }.onFailure { println("POST+v5  /$segment: FAIL ${it.javaClass.simpleName} ${it.message}") }

            // argus
            runCatching {
                val host = MultiVideoModelStreamSource.PLAY_HOSTS[0]
                val path = "/$segment/"
                val signed = transport.sign("https://$host$path", identity.values, payload.toByteArray(Charsets.UTF_8), uaLegacy)
                val headers = signed.headers.toMutableMap()
                if (identity.cookie.isNotBlank()) headers["Cookie"] = identity.cookie
                headers["Connection"] = "close"
                val request = Request.Builder().url(signed.url)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .post(payload.toByteArray(Charsets.UTF_8).toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                http.newCall(request).execute().use { r ->
                    val bytes = r.body?.bytes() ?: ByteArray(0)
                    println("POST+argus /$segment: HTTP ${r.code} bytes=${bytes.size}${if (bytes.isEmpty()) "  <-- EMPTY" else ""}")
                }
            }.onFailure { println("POST+argus /$segment: FAIL ${it.javaClass.simpleName} ${it.message}") }
        }
    }
}