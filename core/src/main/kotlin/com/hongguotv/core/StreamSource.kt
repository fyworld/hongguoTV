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

    /**
     * Resolve the playback address for one episode. [metadataAhead] lists the next
     * episodes (at most 2) so a channel MAY batch them into one request and cache
     * the models - switching episodes then costs no fresh request (2026-11-06
     * event: the server grants each identity only ~1 request per host path, so
     * request economy is the difference between playing and failing).
     *
     * [speculative] marks a BACKGROUND read (upstream 0.1.26): try ONE route only,
     * never rotate the identity, never count empty rounds - a speculative miss
     * must not burn the foreground's failure budget or quota.
     */
    fun stream(id: String, maxQuality: Int, type: ContentType, metadataAhead: List<String> = emptyList(), speculative: Boolean = false): StreamInfo

    /**
     * Every playable tier of one episode (upstream streamLadder, 2026-11-14 capability
     * port). The default is the single resolved address - channels without a multi-tier
     * model keep working; the multi-video-model channel overrides with the full ladder
     * so the app can verify each tier against the device's real decoder capabilities
     * without re-requesting the model per tier switch.
     */
    fun ladder(id: String, maxQuality: Int, type: ContentType, metadataAhead: List<String> = emptyList(), speculative: Boolean = false): List<StreamInfo> =
        listOf(stream(id, maxQuality, type, metadataAhead, speculative))

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

    fun stream(id: String, maxQuality: Int, type: ContentType, metadataAhead: List<String> = emptyList(), speculative: Boolean = false): StreamInfo {
        var last: IOException? = null
        for (source in sources) {
            try {
                return source.stream(id, maxQuality, type, metadataAhead, speculative)
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

    /**
     * Same fallback chain for the full tier ladder (upstream streamLadder, 2026-11-14
     * capability port). The app walks the returned list against the device's REAL
     * decoder capabilities; channels without a multi-tier model return their single
     * address, so the verified-downgrade flow works on every channel.
     */
    fun ladder(id: String, maxQuality: Int, type: ContentType, metadataAhead: List<String> = emptyList(), speculative: Boolean = false): List<StreamInfo> {
        var last: IOException? = null
        for (source in sources) {
            try {
                val tiers = source.ladder(id, maxQuality, type, metadataAhead, speculative)
                if (tiers.isNotEmpty()) return tiers
            } catch (e: IOException) {
                last = e
                log.warning("channel ${source.name} ladder failed for $id: $e")
            } catch (e: RuntimeException) {
                last = IOException("播放通道 ${source.name} 返回了无法解析的内容", e)
                log.warning("channel ${source.name} ladder parse failure for $id: $e")
            }
        }
        throw last ?: IOException("没有可用的播放通道")
    }
}
