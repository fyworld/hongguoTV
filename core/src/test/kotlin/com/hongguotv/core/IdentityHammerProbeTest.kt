package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Live probe (2026-11-06 event): simulate the TV failure pattern - ONE registered
 * identity, REPEATED requests for the same episode (the user retries playback on
 * the TV; the app keeps the identity for the whole process). Upstream's finding:
 * the server gates playback per "identity x path" - continuous requests from one
 * identity can go empty on the whole path, a fresh identity recovers instantly.
 *
 * This probe: register once, hammer the tab GET 12 times, then rotate the identity
 * and try once more. Prints each response size so the blocking pattern is visible.
 * Run manually (hits the live API):
 *   gradlew :core:test --tests "com.hongguotv.core.IdentityHammerProbeTest"
 */
class IdentityHammerProbeTest {
    @org.junit.Test
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val transport = AppApiTransport(http)
        val session = PlaybackDeviceSession()
        val identity = session.profile(http)
        println("identity: device_id=${identity.values["device_id"]?.take(6)}... iid=${identity.values["iid"]?.take(6)}...")
        val videoId = "7673860661994064920" // ep46 - the episode the TV cannot play
        val extra = mapOf(
            "video_id" to videoId, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "")

        var empty = 0
        for (i in 1..12) {
            val signed = transport.sign("${AppApiTransport.APP_BASE}${MultiVideoModelStreamSource.TAB_PATH}",
                identity.values + extra, null,
                "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)")
            val request = okhttp3.Request.Builder().url(signed.url)
                .apply {
                    signed.headers.forEach { (k, v) -> header(k, v) }
                    if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
                }
                .get().build()
            http.newCall(request).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                if (bytes.isEmpty()) empty++
                println("tab[$i]: HTTP ${r.code} bytes=${bytes.size}${if (bytes.isEmpty()) "  <-- EMPTY" else ""}")
            }
            Thread.sleep(400)
        }
        println("same-identity tab: ${12 - empty}/12 non-empty, $empty/12 empty")

        // Rotate: fresh identity, one request - should recover instantly per upstream.
        val rotated = session.rotate(http)
        println("rotate: $rotated")
        val fresh = session.profile(http)
        val signed2 = transport.sign("${AppApiTransport.APP_BASE}${MultiVideoModelStreamSource.TAB_PATH}",
            fresh.values + extra, null,
            "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)")
        val request2 = okhttp3.Request.Builder().url(signed2.url)
            .apply {
                signed2.headers.forEach { (k, v) -> header(k, v) }
                if (fresh.cookie.isNotBlank()) header("Cookie", fresh.cookie)
            }
            .get().build()
        http.newCall(request2).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            println("rotated-identity tab: HTTP ${r.code} bytes=${bytes.size}${if (bytes.isEmpty()) "  <-- STILL EMPTY" else "  (recovered)"}")
        }
    }
}
