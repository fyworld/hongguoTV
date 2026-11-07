// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.Protocol
import org.junit.Assert.*
import org.junit.Test

/**
 * Diagnostic line (upstream 0.1.26 PlaybackRequestTraceTest): the summary must show
 * every route's outcome (stage/host tag/status/bytes/wire) but never leak addresses,
 * headers, identity values, or exception messages - a URL can carry device_id and
 * cookies in its query string.
 */
class PlaybackRequestTraceTest {
    @Test fun recordsPreferredRejectionAndFallbackWithoutSensitiveInputs() {
        val trace = PlaybackRequestTrace()
        trace.response(PlaybackRequestTrace.Stage.GET, "api5-normal-sinfonlineb.fqnovel.com", 200, 0, Protocol.HTTP_2)
        trace.response(PlaybackRequestTrace.Stage.POST, "api5-normal-hl.fqnovel.com", 503, null, Protocol.HTTP_1_1)
        trace.failed(PlaybackRequestTrace.Stage.POST, "private-host.test", IOException("cookie=secret https://private.test/?device_id=123"))
        assertEquals("G:b=200/0B/h2 P:hl=503/?B/h1 P:other=io", trace.summary())
        assertFalse(trace.summary().contains("secret"))
        assertFalse(trace.summary().contains("private"))
    }

    @Test fun includesOnlyNumericBusinessCodeAndCapsOneRound() {
        val trace = PlaybackRequestTrace()
        trace.response(PlaybackRequestTrace.Stage.GET, "api5-normal-sinfonlineb.fqnovel.com", 200, 42, Protocol.HTTP_2)
        trace.rejected(100103)
        repeat(8) { trace.failed(PlaybackRequestTrace.Stage.POST, "api5-normal-sinfonlineb.fqnovel.com", SocketTimeoutException("private")) }
        assertEquals(5, trace.summary().split(" ").size)
        assertTrue(trace.summary().startsWith("G:b=200/42B/h2/c100103"))
        assertTrue(trace.summary().endsWith("P:b=timeout"))
    }
}
