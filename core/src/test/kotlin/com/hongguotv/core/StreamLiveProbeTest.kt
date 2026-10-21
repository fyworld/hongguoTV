package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * JVM end-to-end probe: fetch a real playback URL through the new
 * multi_video_model/v1 + six-header signature chain.
 * Run manually (not part of the test suite - hits the live API):
 *   gradlew :core:test --tests "com.hongguotv.core.StreamLiveProbeTest"
 */
class StreamLiveProbeTest {
    @org.junit.Test
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val transport = AppApiTransport(http)
        val source = MultiVideoModelStreamSource(transport, http)
        // 糯糯下山 第 1 集（与 DanmakuLiveProbeTest 同一真实剧集）
        val videoId = "7677804745699888153"
        val info = source.stream(videoId, 1080, ContentType.SHORT)
        println("channel=${source.name}")
        println("quality=${info.quality}")
        println("url=${info.url.take(120)}...")
        println("drmKey=${info.key?.let { it.size.toString() } ?: "none"} bytes")
        org.junit.Assert.assertTrue("playback url is empty", info.url.startsWith("http"))
        // HEAD the media URL to confirm it actually serves bytes
        val head = okhttp3.Request.Builder().url(info.url).head().build()
        http.newCall(head).execute().use { response ->
            println("media HEAD: HTTP ${response.code} content-type=${response.header("Content-Type")}")
            org.junit.Assert.assertTrue("media url unreachable: HTTP ${response.code}", response.isSuccessful)
        }
    }
}
