package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Real-signature probe (upstream 0.1.31 / 269c6d8, 2026-11-11 event): the server
 * now VALIDATES x-argus/x-ladon - placeholder values (x-argus = base64(ts)) plus
 * the fake x-gorgon/x-helios/x-medusa chain get the playback model silently
 * dropped (HTTP 200 + 0 bytes on every path). This probe sends the video_tab GET
 * with the REAL fqapp Argus/Ladon headers via AppApiTransport.sign() - if it
 * returns data, the port works.
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

        // Real-signature video_tab GET: AppApiTransport.sign() now layers the fqapp
        // x-argus/x-ladon over the final query and strips the placeholder chain.
        val extra = mapOf(
            "video_id" to videoId, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "")
        val signed = transport.sign(
            "${AppApiTransport.APP_BASE}${MultiVideoModelStreamSource.TAB_PATH}",
            identity.values + extra, null,
            "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)")
        println("signed url: ${signed.url.take(120)}...")
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
            val text = String(bytes, Charsets.UTF_8)
            println("SIGNED TAB: HTTP ${r.code} bytes=${bytes.size}")
            println("SIGNED head: ${text.take(400).replace("\n", " ")}")
            org.junit.Assert.assertTrue("signed tab still empty (HTTP ${r.code}, ${bytes.size}B)", bytes.size > 0)
        }
    }
}
