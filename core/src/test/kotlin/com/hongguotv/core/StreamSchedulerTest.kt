package com.hongguotv.core

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * StreamScheduler hardening (2026-11-05 event): a channel throwing a RuntimeException
 * (JSONException from a non-JSON risk-control body) must NOT kill the fallback chain -
 * later channels still get their turn, and the surfaced error is an IOException.
 */
class StreamSchedulerTest {
    private class ThrowingChannel(override val name: String, private val error: () -> Nothing) : StreamSource {
        override fun stream(id: String, maxQuality: Int, type: ContentType): StreamInfo = error()
    }

    private class FixedChannel(override val name: String) : StreamSource {
        override fun stream(id: String, maxQuality: Int, type: ContentType): StreamInfo =
            StreamInfo("https://example.test/video", null, "720P")
    }

    @Test
    fun jsonExceptionInEarlyChannelFallsThroughToLaterChannels() {
        val scheduler = StreamScheduler(listOf(
            ThrowingChannel("first") { throw org.json.JSONException("not json - risk control HTML") },
            FixedChannel("second"),
        ))
        val info = scheduler.stream("123", 1080, ContentType.SHORT)
        assertEquals("https://example.test/video", info.url)
    }

    @Test
    fun allChannelsFailingWithRuntimeExceptionSurfacesIOException() {
        val scheduler = StreamScheduler(listOf(
            ThrowingChannel("first") { throw org.json.JSONException("not json") },
            ThrowingChannel("second") { throw IOException("播放接口暂时返回空响应，请稍后重试") },
        ))
        val error = assertThrows(IOException::class.java) { scheduler.stream("123", 1080, ContentType.SHORT) }
        // The LAST channel's error is surfaced (chain semantics unchanged).
        assertEquals("播放接口暂时返回空响应，请稍后重试", error.message)
    }

    @Test
    fun runtimeOnlyFailureSurfacesWrappedIOException() {
        val scheduler = StreamScheduler(listOf(
            ThrowingChannel("only") { throw IllegalStateException("unexpected shape") },
        ))
        assertThrows(IOException::class.java) { scheduler.stream("123", 1080, ContentType.SHORT) }
    }
}
