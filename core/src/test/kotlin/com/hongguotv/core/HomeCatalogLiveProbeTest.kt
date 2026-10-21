// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Live probe: the "全部" grid for every content type now reads the website category
 * pages (jpg/webp covers, HEIC-free for Android 7/8 TVs). Gated by HG_LIVE=1.
 */
class HomeCatalogLiveProbeTest {
    @org.junit.Test
    @org.junit.Ignore("set HG_LIVE=1 to run the home catalog probe")
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val repository = ContentRepository(http)
        for (type in listOf(ContentType.SHORT, ContentType.COMIC, ContentType.AI)) {
            val page = repository.home(1, type)
            println("${type}: rows=${page.items.size} hasMore=${page.hasMore}")
            page.items.take(3).forEach {
                println("  ${it.id} ${it.title.take(18)} cover=${it.cover.take(80)}")
                assertTrue("cover should not be HEIC: ${it.cover}", !it.cover.contains(".heic", ignoreCase = true))
            }
            assertTrue("$type page empty", page.items.size >= 12)
            assertTrue("$type covers blank", page.items.all { it.cover.isNotBlank() })
        }
        // Page 2 must differ from page 1 (true pagination, not a rotating feed).
        val p1 = repository.home(1, ContentType.SHORT).items.map { it.id }.toSet()
        val p2 = repository.home(2, ContentType.SHORT).items.map { it.id }.toSet()
        println("short page1=${p1.size} page2=${p2.size} overlap=${p1.intersect(p2).size}")
        assertTrue("page 2 duplicates page 1", p1.intersect(p2).size < 3)
        // The feed channel stays alive for theme filtering (FeedLiveProbeTest covers it).
    }
}
