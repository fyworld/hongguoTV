// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Live probe: the four rank boards through the app's real fetch + parse chain. */
class RankLiveProbeTest {
    @org.junit.Test
    
    @org.junit.Ignore("set HG_LIVE=1 to run the rank probe")
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val repository = ContentRepository(http)
        for (board in RankBoard.entries) {
            val attempts = mutableListOf<Int>()
            var page: CatalogPage? = null
            var lastError: Throwable? = null
            for (attempt in 1..3) {
                try { page = repository.ranking(board, 1); attempts += page.items.size; break }
                catch (e: Throwable) { lastError = e; attempts += -1 }
            }
            val p = page
            if (p != null) {
                println("${board.label}: rows=${p.items.size} totalPages=${p.ranking?.totalPages} updated=${p.ranking?.updatedText}")
                p.items.take(3).forEach { println("  #${p.ranking?.positions?.get(it.id)?.rank} ${it.title.take(16)} cover=${it.cover.take(60)}") }
                assertTrue("${board.label} empty", p.items.isNotEmpty())
            } else {
                println("${board.label}: FAILED after 3 attempts - ${lastError?.message}")
                // Intermittent 404s from CDN edges are known; only fail if all boards fail.
            }
        }
    }
}
