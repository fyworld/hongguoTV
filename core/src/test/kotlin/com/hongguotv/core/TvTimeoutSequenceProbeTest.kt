package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Live probe (2026-11-06 event): the TV played ONE episode then died. The JVM
 * sequence probe (same source instance, 5 rounds) recovers via rotation. The
 * remaining difference: the TV app runs the playback in `work()` which CANCELS
 * the previous job before starting - the auto-recovery retries may be cancelled
 * mid-request before the rotation state accumulates. This probe adds a hard
 * 8s timeout per round (the TV's callTimeout is 45s but the recovery loop may
 * interrupt earlier) and replays 6 rounds to see whether rotation still fires.
 *
 * Also dumps the identity device_id prefix per round so identity changes are
 * visible (rotation events show as a new prefix).
 * Run manually (hits the live API):
 *   gradlew :core:test --tests "com.hongguotv.core.TvTimeoutSequenceProbeTest"
 */
class TvTimeoutSequenceProbeTest {
    @org.junit.Test
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val transport = AppApiTransport(http)
        val session = PlaybackDeviceSession()
        val source = MultiVideoModelStreamSource(transport, http, session, sleep = {})

        // Episode ids: ep46 (the reported one) and ep45 (previous - the one that played).
        val ep46 = "7673860661994064920"

        for (round in 1..6) {
            val t = Thread {
                try {
                    val info = source.stream(ep46, 1080, ContentType.SHORT)
                    println("round[$round]: OK quality=${info.quality} url=${info.url.take(50)}")
                } catch (e: Exception) {
                    println("round[$round]: FAIL ${e.javaClass.simpleName}: ${e.message?.take(110)}")
                }
            }
            t.isDaemon = true
            t.start()
            t.join(8000) // hard 8s budget per round (TV recovery cadence)
            if (t.isAlive) println("round[$round]: TIMEOUT (still running after 8s)")
            Thread.sleep(300)
        }
        // Show the identity the session ended with (rotation visible as fresh prefix).
        val id = session.profile(http)
        println("final identity: device_id=${id.values["device_id"]?.take(8)}...")
    }
}
