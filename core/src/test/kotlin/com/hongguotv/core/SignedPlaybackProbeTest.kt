package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Differential probe (2026-11-05 event): the danmaku endpoint (six-header signature)
 * works while the playback endpoints (UA+Cookie only) return empty bodies. Hypothesis:
 * the server now REQUIRES the signature chain on playback calls. This probe sends
 * the SAME video_tab GET request WITH the six-header signature - if it returns data,
 * the fix is confirmed before touching the source.
 * Run manually (hits the live API):
 *   gradlew :core:test --tests "com.hongguotv.core.SignedPlaybackProbeTest"
 */
class SignedPlaybackProbeTest {
    @org.junit.Test
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val transport = AppApiTransport(http)
        val session = PlaybackDeviceSession()
        val identity = session.profile(http)
        val videoId = "7677804745699888153" // 糯糯下山 ep1 - the control that went empty

        // Signed video_tab GET: same query values + six-header chain via signPlayback.
        val values = identity.values.toMutableMap().apply {
            for (k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) this[k] = "73932"
            this["version_name"] = "7.3.9.32"
        }
        val extra = mapOf(
            "video_id" to videoId, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "")
        val signed = Signer().let { signer ->
            // signPlayback builds base + "?" + query itself; pass the tab path as base.
            signer.signPlayback("${AppApiTransport.APP_BASE}${MultiVideoModelStreamSource.TAB_PATH}",
                values + extra, null, "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)")
        }
        println("signed url: ${signed.url.take(120)}...")
        println("signed headers: ${signed.headers.keys}")

        val request = okhttp3.Request.Builder().url(signed.url)
            .apply {
                signed.headers.forEach { (k, v) -> header(k, v) }
                if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
            }
            .get().build()
        http.newCall(request).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val text = String(bytes, Charsets.UTF_8)
            println("SIGNED TAB: HTTP ${r.code} bytes=${bytes.size}")
            println("SIGNED head: ${text.take(400).replace("\n", " ")}")
            org.junit.Assert.assertTrue("signed tab still empty (HTTP ${r.code}, ${bytes.size}B)", bytes.size > 0)
        }
    }
}
