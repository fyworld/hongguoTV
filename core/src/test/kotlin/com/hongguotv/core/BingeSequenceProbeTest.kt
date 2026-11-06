package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Live probe (2026-11-06 event): the batched read + upcoming cache against the
 * real server. Plays the exact TV binge sequence - ep46, ep47, ep48 - where the
 * FIRST call batches all three ids into one POST; the next two calls must be
 * served from the cache (zero fresh requests, immune to the identity quota).
 *
 * Success = all three rounds resolve, and rounds 2-3 survive even though the
 * same identity would be quota-blocked for fresh requests.
 * Run manually (hits the live API):
 *   gradlew :core:test --tests "com.hongguotv.core.BingeSequenceProbeTest"
 */
class BingeSequenceProbeTest {
    @org.junit.Test
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val transport = AppApiTransport(http)
        val session = PlaybackDeviceSession()
        val source = MultiVideoModelStreamSource(transport, http, session, sleep = {})

        // ep46/47/48 of 满院亲戚全是上古大妖 (vid_list from the site detail page).
        val eps = listOf("7673860661994064920", "7673860541781134360", "7673860538333400088")

        for ((i, ep) in eps.withIndex()) {
            val ahead = eps.drop(i + 1).take(2)
            try {
                val info = source.stream(ep, 1080, ContentType.SHORT, ahead)
                println("ep${46 + i}: OK quality=${info.quality} url=${info.url.take(55)}")
            } catch (e: Exception) {
                println("ep${46 + i}: FAIL ${e.javaClass.simpleName}: ${e.message?.take(110)}")
            }
        }
        // Same-episode retry (recovery pattern): must also hit the cache.
        try {
            val info = source.stream(eps[0], 1080, ContentType.SHORT, emptyList())
            println("ep46-retry: OK quality=${info.quality}")
        } catch (e: Exception) {
            println("ep46-retry: FAIL ${e.message?.take(110)}")
        }
    }
}
