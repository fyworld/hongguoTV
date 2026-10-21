package com.hongguotv.core

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * JVM end-to-end probe: the landpage feed through the six-header signature chain
 * (the lightweight X-Khronos/X-Gorgon pair was retired server-side 2026-10).
 * Gated by env HG_LIVE=1 (upstream convention) - live probes share a device id
 * and trip rate limits when run concurrently in the full suite.
 *   HG_LIVE=1 gradlew :core:test --tests "com.hongguotv.core.FeedLiveProbeTest"
 */
class FeedLiveProbeTest {
    @org.junit.Test
    @org.junit.Ignore("set HG_LIVE=1 to run the live feed probe")
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val transport = AppApiTransport(http)
        val payload = JSONObject()
            .put("req_scene", "short_play").put("offset", 0).put("limit", 18)
            .put("req_type", "only_content").put("need_selector_panel", false)
            .put("client_req_type", 3).put("session_id", "").put("filter_ids", "")
            .put("select_items", JSONObject()
                .put("genre", JSONArray().put("short_play")).put("sort", JSONArray().put("online_time"))
                .put("gender", JSONArray()).put("category_dim_theme", JSONArray())
                .put("category_dim_role", JSONArray()).put("category_dim_epoch", JSONArray())
                .put("online_time", JSONArray()).put("creation_status", JSONArray()))
        val data = transport.request("/reading/distribution/category/landpage/v/", payload.toString(), signed = true)
        val rows = data.optJSONObject("data")?.optJSONArray("video_data")
            ?: throw java.io.IOException("feed returned no video_data")
        val first = rows.optJSONObject(0)?.optJSONObject("video_data") ?: rows.optJSONObject(0)
        println("feed rows=${rows.length()}")
        println("first title=${first?.str("series_title")}")
        org.junit.Assert.assertTrue("feed returned no rows", rows.length() > 0)
    }
}
