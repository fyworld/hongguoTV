package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Live differential probe (2026-11-06 late night): map which playback request
 * shapes still survive. The TV reports "still cannot play" after the batch+cache
 * fix; the evening probes show the batched POST that worked at 21:00 now returns
 * empty at 23:35 - the server may have killed the multi-id batch shape.
 *
 * Matrix on ONE fresh identity, raw transport calls (no source logic):
 *   1. tab GET single ep46          (the official path)
 *   2. tab GET single ep46 again    (same-path quota check)
 *   3. POST single [ep46]           (legacy path, single id)
 *   4. POST single [ep47]           (second single on same identity)
 *   5. POST batch [ep46,ep47,ep48]  (the multi-id shape v1.0.27 relies on)
 * then rotate the identity and retry the shapes that failed.
 * Run manually:
 *   gradlew :core:test --tests "com.hongguotv.core.ShapeMatrixProbeTest"
 */
class ShapeMatrixProbeTest {
    @org.junit.Test
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val transport = AppApiTransport(http)
        val session = PlaybackDeviceSession()
        val playbackUa = "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"
        val eps = listOf("7673860661994064920", "7673860541781134360", "7673860538333400088")

        fun tab(id: String, label: String) {
            val identity = session.profile(http)
            val extra = mapOf(
                "video_id" to id, "video_id_type" to "0", "device_level" to "1",
                "need_all_video_definition" to "1", "need_mp4_align" to "0",
                "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
                "from_video_id" to "")
            val signed = transport.sign("https://${MultiVideoModelStreamSource.PLAY_HOSTS[0]}${MultiVideoModelStreamSource.TAB_PATH}",
                identity.values + extra, null, playbackUa)
            val request = okhttp3.Request.Builder().url(signed.url)
                .apply {
                    signed.headers.forEach { (k, v) -> header(k, v) }
                    if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
                }.get().build()
            http.newCall(request).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                println("$label: HTTP ${r.code} bytes=${bytes.size}${if (bytes.isEmpty()) "  <-- EMPTY" else ""}")
            }
        }

        fun post(ids: List<String>, label: String) {
            val identity = session.profile(http)
            val payload = org.json.JSONObject()
                .put("biz_param", org.json.JSONObject()
                    .put("detail_page_version", 0).put("device_level", 3)
                    .put("disable_digg_stat", false).put("need_all_video_definition", true)
                    .put("need_mp4_align", false).put("use_os_player", false)
                    .put("use_server_dns", false).put("video_platform", 1024))
                .put("mixed_video_id_map", org.json.JSONObject().put("1004", org.json.JSONArray(ids)))
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            val host = MultiVideoModelStreamSource.PLAY_HOSTS[0]
            val signed = transport.sign("https://$host${MultiVideoModelStreamSource.PATH}", identity.values, body, playbackUa)
            val headers = signed.headers.toMutableMap()
            if (identity.cookie.isNotBlank()) headers["Cookie"] = identity.cookie
            headers["Connection"] = "close"
            try {
                val data = transport.requestUrl(signed.url, body, headers)
                println("$label: OK code=${data.optInt("code")} dataKeys=${data.optJSONObject("data")?.keys()?.asSequence()?.take(3)?.toList()}")
            } catch (e: Exception) {
                println("$label: ${e.javaClass.simpleName}: ${e.message?.take(60)}")
            }
        }

        println("=== fresh identity ===")
        tab(eps[0], "1. tab ep46")
        tab(eps[0], "2. tab ep46 again")
        post(listOf(eps[0]), "3. POST single [ep46]")
        post(listOf(eps[1]), "4. POST single [ep47]")
        post(eps, "5. POST batch [46,47,48]")

        println("=== after rotate ===")
        session.rotate(http)
        tab(eps[0], "6. tab ep46 (rotated)")
        post(eps, "7. POST batch [46,47,48] (rotated)")
        post(listOf(eps[1]), "8. POST single [ep47] (rotated)")
        tab(eps[2], "9. tab ep48 (rotated, after 2 reqs)")
    }
}
