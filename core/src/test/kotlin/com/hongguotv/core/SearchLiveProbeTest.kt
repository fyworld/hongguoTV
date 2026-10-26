// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Live probe: the website search through the app's real fetch + parse chain, against
 * the XHR endpoint /api/search/result (the SSR search page went client-rendered
 * 2026-10). Run with -DHG_LIVE=1 (see RankLiveProbeTest for the pattern).
 */
class SearchLiveProbeTest {
    @org.junit.Test
    @org.junit.Ignore("set HG_LIVE=1 to run the search probe")
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val repository = ContentRepository(http)

        // 1. Plain search: rows parse, ids valid, covers are byteimg (not HEIC).
        val result = repository.search("总裁", 1, ContentType.SHORT)
        println("search 总裁: rows=${result.items.size} hasMore=${result.hasMore}")
        result.items.take(3).forEach { println("  ${it.title.take(16)} id=${it.id} cover=${it.cover.take(60)}") }
        assertTrue("search returned no rows", result.items.isNotEmpty())
        assertTrue("covers must not be HEIC", result.items.all { !it.cover.contains(".heic") })

        // 2. Pagination: page 2 must differ from page 1 (the server may inject a
        // recommendation row that repeats across pages, so allow a small overlap).
        val page2 = repository.search("总裁", 2, ContentType.SHORT)
        val ids1 = result.items.map { it.id }.toSet()
        val ids2 = page2.items.map { it.id }.toSet()
        println("page2: rows=${page2.items.size} overlap=${ids1.intersect(ids2).size}")
        assertTrue("page 2 must not repeat page 1 rows", ids1.intersect(ids2).size < ids2.size)

        // 3. Enhanced search: page + name chains merged, no warning.
        val enhanced = repository.searchEnhanced("总裁")
        println("searchEnhanced 总裁: rows=${enhanced.items.size} warning='${enhanced.warning}'")
        assertTrue("enhanced search returned no rows", enhanced.items.isNotEmpty())
        assertEquals("enhanced search must not warn when the XHR chain works", "", enhanced.warning)

        // 4. Search-backed theme (穿越 on AI tab) rides the same endpoint.
        val theme = repository.themeCatalog(ContentType.AI, Theme("chuanyue", "穿越", "穿越"), 1)
        println("theme 穿越: rows=${theme.items.size} hasMore=${theme.hasMore}")
        assertTrue("search-backed theme returned no rows", theme.items.isNotEmpty())
    }
}
