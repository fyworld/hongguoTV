package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * JVM live probe: reproduce the H96 PRO+ TV failure on "满院亲戚全是上古大妖第六季"
 * episode 46 - the diagnostic line says JSONException (a non-empty, non-JSON response
 * from the playback chain). Fetch the real response and dump its shape.
 * Run manually (hits the live API):
 *   gradlew :core:test --tests "com.hongguotv.core.Ep46DiagTest"
 */
class Ep46DiagTest {
    @org.junit.Test
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val transport = AppApiTransport(http)
        val source = MultiVideoModelStreamSource(transport, http)

        // 1) Find the series by title search (same chain the TV used).
        val repo = ContentRepository(http)
        val page = repo.search("满院亲戚全是上古大妖", 1, ContentType.SHORT)
        println("search hits: ${page.items.size}")
        val series = page.items.firstOrNull { it.title.contains("第六季") || it.title.contains("满院") }
        org.junit.Assert.assertNotNull("series not found in search", series)
        println("series: ${series!!.id} ${series.title}")

        // 2) Detail -> episode list.
        val detail = repo.detail(series.id, ContentType.SHORT)
        println("episodes: ${detail.episodes.size}")
        org.junit.Assert.assertTrue("episode 46 missing", detail.episodes.size >= 46)
        val ep46 = detail.episodes[45]
        println("ep46 videoId: $ep46")

        // 3) Stream it exactly like the app does - the FULL scheduler chain
        //    (MultiVideoModel -> AppApi -> PublicMp4), which surfaces the LAST
        //    channel's error. The TV saw JSONException - likely from the website
        //    MP4 fallback, not the App API.
        try {
            val info = repo.stream(ep46, 1080, ContentType.SHORT)
            println("STREAM OK: quality=${info.quality} url=${info.url.take(80)} key=${info.key?.size ?: 0}B")
        } catch (e: Exception) {
            println("STREAM FAILED: ${e.javaClass.simpleName}: ${e.message?.take(150)}")
            var cause = e.cause
            var depth = 0
            while (cause != null && depth < 5) {
                println("  caused by: ${cause.javaClass.simpleName}: ${cause.message?.take(150)}")
                cause = cause.cause; depth++
            }
        }
    }
}
