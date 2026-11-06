package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Live probe (2026-11-06 event): the hammer probe showed same-identity same-host
 * requests go empty after the 1st. The fix in place rotates hosts (b -> hl -> lf).
 * THIS probe verifies the premise: with one identity blocked on host b, do the hl
 * and lf hosts still serve it? (Upstream issue #38 says clustered windows are
 * host-scoped; the 2026-11-01 event said blocked identities are empty on ALL
 * paths. Which one applies tonight decides whether host rotation helps.)
 * Run manually (hits the live API):
 *   gradlew :core:test --tests "com.hongguotv.core.HostEscapeProbeTest"
 */
class HostEscapeProbeTest {
    @org.junit.Test
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val transport = AppApiTransport(http)
        val session = PlaybackDeviceSession()
        val identity = session.profile(http)
        println("identity: device_id=${identity.values["device_id"]?.take(6)}...")
        val videoId = "7673860661994064920" // ep46
        val extra = mapOf(
            "video_id" to videoId, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "")

        fun tab(host: String, label: String) {
            val signed = transport.sign("https://$host${MultiVideoModelStreamSource.TAB_PATH}",
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
                println("$label: HTTP ${r.code} bytes=${bytes.size}${if (bytes.isEmpty()) "  <-- EMPTY" else ""}")
            }
        }

        val hosts = listOf(
            "api5-normal-sinfolineb.fqnovel.com".replace("sinfolineb", "sinfonlineb"),
            "api5-normal-hl.fqnovel.com",
            "api5-normal-lf.fqnovel.com")
        // Phase 1: burn the identity on host b (2 requests - 1st OK, 2nd empty = blocked).
        tab(hosts[0], "b[1] (burn)")
        tab(hosts[0], "b[2] (expect blocked)")
        // Phase 2: same identity, different hosts - the escape test.
        tab(hosts[1], "hl[1] (escape?)")
        tab(hosts[2], "lf[1] (escape?)")
        // Phase 3: back to b to confirm it's still blocked (control).
        tab(hosts[0], "b[3] (control)")
    }
}
