// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 2026-11-14 regression: the capability-first ladder must retry sporadic empty
 * answers on the signed channel and must NEVER fall through to the website's
 * 360p channel (which serves a 10-second preview clip that looks like "the
 * episode only plays 10s" because the player ends normally, without an error).
 */
class StreamSchedulerLadderTest {
    private class ScriptedSource(
        override val name: String,
        private val script: MutableList<() -> List<StreamInfo>>,
    ) : StreamSource {
        var calls = 0
        override fun stream(id: String, maxQuality: Int, type: ContentType, metadataAhead: List<String>, speculative: Boolean): StreamInfo =
            throw UnsupportedOperationException()
        override fun ladder(id: String, maxQuality: Int, type: ContentType, metadataAhead: List<String>, speculative: Boolean): List<StreamInfo> {
            calls++
            return script.removeAt(0)()
        }
    }

    private val hd = StreamInfo("https://signed/720.mp4", null, "720P", "h264", 720, 1280, 720)
    private val preview = StreamInfo("https://site/video/public/123/360p.mp4", null, "360P")

    @Test
    fun retriesTransientEmptyAnswersOnSignedChannel() {
        val signed = ScriptedSource("app-api", mutableListOf(
            { throw AppApiEmptyResponseException() },
            { throw EmptyPlaybackResponseException() },
            { listOf(hd) },
        ))
        val website = ScriptedSource("public-mp4", mutableListOf({ listOf(preview) }))
        val tiers = StreamScheduler(listOf(signed, website)).ladder("123", 1080, ContentType.SHORT)
        assertEquals(1, tiers.size)
        assertEquals("720P", tiers[0].quality)
        assertEquals(3, signed.calls)
        assertEquals(0, website.calls)
    }

    @Test
    fun neverServesWebsitePreviewWhenSignedChannelStaysEmpty() {
        val signed = ScriptedSource("app-api", MutableList(3) { { throw AppApiEmptyResponseException() } })
        val website = ScriptedSource("public-mp4", mutableListOf({ listOf(preview) }))
        val error = runCatching { StreamScheduler(listOf(signed, website)).ladder("123", 1080, ContentType.SHORT) }.exceptionOrNull()
        assertTrue("expected IOException, got $error", error is IOException)
        assertEquals(3, signed.calls)
        assertEquals(0, website.calls) // the 10s clip must never reach the player
    }

    @Test
    fun backgroundReadsDoNotBurnRetries() {
        val signed = ScriptedSource("app-api", mutableListOf({ throw AppApiEmptyResponseException() }, { listOf(hd) }))
        val website = ScriptedSource("public-mp4", mutableListOf({ listOf(preview) }))
        val error = runCatching {
            StreamScheduler(listOf(signed, website)).ladder("123", 1080, ContentType.SHORT, speculative = true)
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(1, signed.calls) // speculative: no retry, no quota burn
        assertEquals(0, website.calls)
    }
}
