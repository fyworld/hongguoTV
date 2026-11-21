package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Real-signature probe (upstream 0.1.31 / 269c6d8, 2026-11-11 event): the server
 * VALIDATES x-argus/x-ladon - placeholder values (x-argus = base64(ts)) plus the
 * fake x-gorgon/x-helios/x-medusa chain get the playback model silently dropped
 * (HTTP 200 + 0 bytes on every path). This probe sends the video_tab GET with the
 * REAL fqapp Argus/Ladon headers via AppApiTransport.sign() - if it returns data,
 * the port works.
 *
 * 2026-11-14: retry with a FRESH device identity up to 3 times. The platform also
 * emits sporadic per-identity empty responses (the same "empty response shield"
 * the app heals with rotate()); a single 0-byte round is NOT a signature regression.
 * 2026-11-16: 该链路曾被平台下线（同机对照旧签名 200+0B、v5 签名 200+24345B，上游
 * issue #108 同现象），正式播放改走 v5 `/player/v1`。
 * 2026-11-21（上游 0.1.35 / 2a1a4cd）：平台反转 —— 匿名 POST 两个变体全部 200+0B，
 * 而本探针的 tab GET 回到 200+23KB，正式播放顺序改回「先 tab GET，失败再 v5 POST」。
 * 因此本探针重新成为播放主通道的健康判据，改为 PROBE_LIVE 门控（默认套件跳过）。
 * Run manually (hits the live API):
 *   PROBE_LIVE=1 gradlew :core:test --tests "com.hongguotv.core.SignedPlaybackProbeTest"
 */
class SignedPlaybackProbeTest {
    @org.junit.Test
    fun probe() {
        org.junit.Assume.assumeTrue(
            "tab GET 主通道探针：需 PROBE_LIVE=1（默认套件不打真实接口）",
            System.getenv("PROBE_LIVE") == "1")
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val transport = AppApiTransport(http)
        val videoId = "7677804745699888153" // 糯糯下山 ep1 - the control that went empty

        // Real-signature video_tab GET: AppApiTransport.sign() now layers the fqapp
        // x-argus/x-ladon over the final query and strips the placeholder chain.
        val extra = mapOf(
            "video_id" to videoId, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "")
        val ua = "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"

        var bestBytes = 0
        var bestCode = 0
        for (round in 1..3) {
            // A fresh anonymous identity each round, same as the app's rotate() self-heal.
            val identity = PlaybackDeviceSession().profile(http)
            val signed = transport.sign(
                "${AppApiTransport.APP_BASE}${MultiVideoModelStreamSource.TAB_PATH}",
                identity.values + extra, null, ua)
            println("signed headers: ${signed.headers.keys}")
            // Real chain present, placeholder chain absent.
            for (h in listOf("x-argus", "x-ladon", "x-khronos", "x-ss-req-ticket"))
                org.junit.Assert.assertNotNull("missing real header $h", signed.headers[h])
            for (h in listOf("x-gorgon", "x-helios", "x-medusa"))
                org.junit.Assert.assertNull("placeholder header $h must be gone", signed.headers[h])

            val request = okhttp3.Request.Builder().url(signed.url)
                .apply {
                    signed.headers.forEach { (k, v) -> header(k, v) }
                    if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
                }
                .get().build()
            http.newCall(request).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                println("SIGNED TAB round=$round HTTP ${r.code} bytes=${bytes.size}")
                if (r.code > bestCode) bestCode = r.code
                if (bytes.size > bestBytes) {
                    bestBytes = bytes.size
                    println("SIGNED head: ${String(bytes, Charsets.UTF_8).take(200).replace("\n", " ")}")
                }
                if (bytes.size > 0) return
            }
        }
        org.junit.Assert.assertTrue(
            "signed tab still empty after 3 identities (HTTP $bestCode, ${bestBytes}B)", bestBytes > 0)
    }
}
