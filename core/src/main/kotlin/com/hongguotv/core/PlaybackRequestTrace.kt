// SPDX-License-Identifier: GPL-3.0-only
// Adapted 2026-11-07 from upstream tsukiyo-zero/hongguoTV 0.1.26 PlaybackRequestTrace.kt (GPL-3.0-only).
package com.hongguotv.core

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.Protocol

/**
 * One bounded round of playback-model requests, summarized for the user-facing
 * error line (upstream 0.1.26): every route's outcome lands in the EmptyPlayback
 * message, so a TV screenshot shows exactly which host answered what - the
 * diagnostic that made the 2026-11-05/07 events tractable.
 *
 * Records stage (GET tab / POST legacy), host tag (b/hl/lf), HTTP status, body
 * bytes, wire protocol (h2/h1), and failure kinds (timeout/dns/cancel/io).
 * Never records addresses, headers, identity values, or exception messages -
 * a leaked URL can carry device_id/cookie in the query string.
 */
internal class PlaybackRequestTrace {
    enum class Stage { GET, POST }

    private val entries = mutableListOf<String>()

    fun response(stage: Stage, host: String, status: Int, bytes: Int?, protocol: Protocol) {
        val wire = if (protocol == Protocol.HTTP_2 || protocol == Protocol.H2_PRIOR_KNOWLEDGE) "h2" else "h1"
        add(stage, host, "$status/${bytes?.toString() ?: "?"}B/$wire")
    }

    fun failed(stage: Stage, host: String, error: IOException) {
        val kind = when (error) {
            is SocketTimeoutException -> "timeout"
            is UnknownHostException -> "dns"
            is java.io.InterruptedIOException -> "cancel"
            else -> "io"
        }
        add(stage, host, kind)
    }

    /** Mark the last entry as rejected (business code / invalid shape). */
    fun rejected(code: Int? = null) {
        if (entries.isNotEmpty()) entries[entries.lastIndex] += if (code == null) "/invalid" else "/c$code"
    }

    private fun add(stage: Stage, host: String, result: String) {
        if (entries.size >= 5) return
        val tag = PlaybackRoutes.tag(host)
        entries += "${if (stage == Stage.GET) "G" else "P"}:$tag=$result"
    }

    fun summary() = entries.joinToString(" ")
}
