package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Live probe (2026-11-06 TV report): the REAL TV binge rhythm - a full episode
 * (2 min here, 1-3 min on TV) elapses between switches, and the prefetch worker
 * builds a SHORT-LIVED ContentRepository (fresh instance, empty instance state)
 * for each episode's end-of-episode prefetch.
 *
 * What killed v1.0.26 on TV:
 *  1. 60s cache TTL expired long before the switch (probe was millisecond-fast)
 *  2. The prefetch's short-lived repository had its own EMPTY cache -> fresh
 *     POST per episode -> per-identity quota burned -> episode 2 dies, and
 *     switching BACK also dies (quota is spent, not per-episode)
 *
 * This probe reproduces that exact sequence against the live server:
 *  - ep46 via the MAIN repository (batched, caches ep47+ep48 rows)
 *  - 2-minute wait (episode playback time)
 *  - ep47 via a FRESH short-lived repository (the prefetch worker's pattern)
 *  - ep48 via ANOTHER fresh repository
 * Success = all three resolve, and rounds 2-3 fire ZERO playback requests
 * (served from the shared process-wide cache).
 * Run manually (hits the live API, takes ~2.5 min):
 *   gradlew :core:test --tests "com.hongguotv.core.TvRhythmProbeTest"
 */
class TvRhythmProbeTest {
    @org.junit.Test
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

        // ep46/47/48 of 满院亲戚全是上古大妖.
        val eps = listOf("7673860661994064920", "7673860541781134360", "7673860538333400088")

        // Main playback: batched read covers ep46+47+48, rows land in the shared cache.
        val main = ContentRepository(http)
        try {
            val info = main.stream(eps[0], 1080, ContentType.SHORT, eps.drop(1))
            println("ep46(main): OK quality=${info.quality}")
        } catch (e: Exception) {
            println("ep46(main): FAIL ${e.message?.take(110)}")
        }

        // A full episode plays on TV (1-3 min). Wait 2 minutes - far past the old
        // 60s TTL, well inside the new 10-min one.
        println("simulating episode playback: waiting 120s...")
        Thread.sleep(120_000)

        // The prefetch worker's pattern: a fresh, short-lived repository per episode.
        for ((i, ep) in listOf(1, 2).map { eps[it] }.withIndex()) {
            val shortLived = ContentRepository(OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS).build())
            try {
                val info = shortLived.stream(ep, 1080, ContentType.SHORT)
                println("ep${47 + i}(fresh-repo): OK quality=${info.quality} url=${info.url.take(55)}")
            } catch (e: Exception) {
                println("ep${47 + i}(fresh-repo): FAIL ${e.message?.take(110)}")
            }
        }
    }
}
