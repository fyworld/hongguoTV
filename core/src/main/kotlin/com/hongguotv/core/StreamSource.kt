// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException

/**
 * One playback address channel. The playback chain has been reworked four times
 * (drpys x-argus -> signed App API -> website public MP4 -> unsigned App API), so each
 * channel lives in its own class: when an endpoint changes, fix or replace the affected
 * channel only, or register a new one in [Companion.default] - the repository and the
 * app never change.
 *
 * Implementations must be stateless per call (thread-safe) and throw [IOException]
 * with a user-facing message on failure; the scheduler falls through to the next
 * channel and surfaces the LAST error if all channels fail.
 */
interface StreamSource {
    /** Stable identifier for logging ("app-api", "public-mp4", ...). */
    val name: String

    /** Resolve the playback address for one episode. */
    fun stream(id: String, maxQuality: Int, type: ContentType): StreamInfo

    companion object {
        /**
         * Ordered fallback chain: multi_video_model first (current App endpoint,
         * six-header signature, multi-quality + CENC keys), the retired unsigned
         * video_model second (kept as a probe - the endpoint may come back), the
         * website's public MP4 last (360p, plain, immune to App API breakage).
         */
        internal fun default(transport: AppApiTransport, http: okhttp3.OkHttpClient, webUa: String, site: String): List<StreamSource> =
            listOf(
                MultiVideoModelStreamSource(transport, http),
                AppApiStreamSource(transport),
                PublicMp4StreamSource(http, webUa, site),
            )
    }
}

/**
 * Runs the fallback chain: first channel that resolves wins; failures are logged and
 * skipped. Throws the last channel's error when everything fails.
 *
 * 2026-11-05: also catches RuntimeException. The 2026-11-05 server event returned
 * non-empty, non-JSON bodies (risk-control HTML) on some networks - JSONObject(text)
 * threw JSONException, a RuntimeException that used to escape this loop and kill the
 * whole chain before later channels (public MP4) could run. Parse failures are
 * channel failures, not app crashes.
 */
internal class StreamScheduler(private val sources: List<StreamSource>) {
    private val log = java.util.logging.Logger.getLogger("com.hongguotv.core.stream")

    fun stream(id: String, maxQuality: Int, type: ContentType): StreamInfo {
        var last: IOException? = null
        for (source in sources) {
            try {
                return source.stream(id, maxQuality, type)
            } catch (e: IOException) {
                last = e
                log.warning("channel ${source.name} failed for $id: $e")
            } catch (e: RuntimeException) {
                last = IOException("播放通道 ${source.name} 返回了无法解析的内容", e)
                log.warning("channel ${source.name} parse failure for $id: $e")
            }
        }
        throw last ?: IOException("没有可用的播放通道")
    }
}
