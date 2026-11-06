package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Live probe (2026-11-06 event): replay the EXACT TV call sequence against the
 * production code path - one MultiVideoModelStreamSource instance (the main
 * repository's), consecutive stream() calls for the same episode (ep1 plays,
 * ep2's request hits the burned quota, auto-recovery retries). The rotation
 * logic should kick in on round 3 (2 empty rounds arm it) and recover.
 *
 * If this recovers, the TV failure is environmental (registration/DNS on the TV
 * network); if it doesn't, the rotation logic itself is broken.
 * Run manually (hits the live API):
 *   gradlew :core:test --tests "com.hongguotv.core.TvSequenceProbeTest"
 */
class TvSequenceProbeTest {
    @org.junit.Test
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val transport = AppApiTransport(http)
        val session = PlaybackDeviceSession()
        // The production path: one source instance, real rotation state.
        val source = MultiVideoModelStreamSource(transport, http, session, sleep = {})
        val videoId = "7673860661994064920" // ep46

        for (round in 1..5) {
            try {
                val info = source.stream(videoId, 1080, ContentType.SHORT)
                println("round[$round]: OK quality=${info.quality} url=${info.url.take(60)}")
            } catch (e: Exception) {
                println("round[$round]: FAIL ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            }
            Thread.sleep(500)
        }
    }
}
