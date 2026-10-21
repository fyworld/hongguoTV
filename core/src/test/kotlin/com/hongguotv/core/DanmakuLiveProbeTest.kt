package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * JVM end-to-end probe: fetch real danmaku through the Kotlin signature chain.
 * Run manually (not part of the test suite - hits the live API):
 *   gradlew :core:test --tests "com.hongguotv.core.DanmakuLiveProbeTest"
 */
class DanmakuLiveProbeTest {
    @org.junit.Test
    @org.junit.Ignore("live probe - run explicitly when needed (shares device id, trips rate limits in the full suite)")
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val transport = AppApiTransport(http)
        val repo = DanmakuRepository(http, transport)
        // 糯糯下山 第 1 集（video_detail 拿到的真实 vid）
        val videoId = "7677804745699888153"
        val seriesId = "7677801492920667198"
        var cursor = 0L; var total = 0
        for (round in 1..8) {
            val page = repo.page(videoId, seriesId, cursor, 90000)
            println("window $round: items=${page.items.size} next=${page.nextCursorMs}")
            page.items.take(3).forEach { println("  [${it.timeMs}ms] ${it.text}") }
            total += page.items.size
            if (page.nextCursorMs <= cursor || page.nextCursorMs >= 90000) break
            cursor = page.nextCursorMs
        }
        println("total=$total")
        org.junit.Assert.assertTrue("no danmaku returned", total > 0)
    }
}
