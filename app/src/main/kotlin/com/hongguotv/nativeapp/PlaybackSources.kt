// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.Log
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.ContentType
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.StreamInfo
import com.hongguotv.core.VideoTracks
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.IOException

/**
 * One opened playback tier (upstream OpenedStream, 2026-11-14 port). Carries the whole
 * ladder plus the verified coded size, so a runtime decoder failure can learn the exact
 * failing geometry and step down without re-requesting the playback model.
 */
internal class OpenedStream(
    val video: RemoteVideo,
    val info: StreamInfo,
    val tiers: List<StreamInfo>,
    val track: VideoTracks.Track?,
) : Closeable {
    override fun close() = video.close()
}

/**
 * The ladder has tiers but none is decodable on this box (deterministic): retrying only
 * yields the same result, so no auto-recovery budget and no lower-tier button. Kept as a
 * manual retry - the server's transcode supply may flip later.
 */
internal class NoPlayableQualityException :
    IOException("本集所有档位均超出电视解码能力，可尝试其他集数")

/** Better tier = higher quality, h264/avc over other codecs (upstream StreamLadder.better). */
private val betterTier =
    compareBy<StreamInfo> { it.qualityValue }
        .thenBy { if (it.codec.lowercase() in setOf("h264", "avc", "avc1", "avc3")) 1 else 0 }

/**
 * Opens the first tier the device can ACTUALLY decode (upstream openStream, 2026-11-14
 * port): pick by API metadata + static/learned capability, then read the coded size from
 * the MP4 header and re-check - boxes often advertise AVC sizes they cannot really
 * decode (portrait 1080x1920 beyond the 1088 height cap), which is the ~10s-then-error
 * symptom. A rejected tier is closed and the next lower one is tried from the SAME model.
 */
internal fun openStream(
    http: OkHttpClient,
    repository: ContentRepository,
    id: String,
    maxQuality: Int,
    type: ContentType,
    supportsTier: (StreamInfo) -> Boolean,
    metadataAhead: List<String> = emptyList(),
    speculative: Boolean = false,
    supportsTrack: (VideoTracks.Track?) -> Boolean,
): OpenedStream? {
    val ladder = repository.playbackTiers(id, maxQuality, type, metadataAhead, speculative)
    val pool = ladder.filter(supportsTier).ifEmpty { ladder }
    val pick =
        pool.filter { it.qualityValue in 1..maxQuality }.maxWithOrNull(betterTier)
            ?: pool.filter { it.qualityValue <= 0 }.maxWithOrNull(betterTier)
            ?: pool.minWithOrNull(betterTier)
            ?: return null
    val alternates = pool.filter { it !== pick && it.qualityValue <= pick.qualityValue }
        .sortedWith(betterTier.reversed())
    for (tier in listOf(pick) + alternates) {
        val video = RemoteVideo(http, tier)
        try {
            video.prepare()
            val track = video.trackInfo()
            if (supportsTrack(track)) return OpenedStream(video, tier, ladder, track)
            Log.i(
                "HongguoTV",
                "Skipped ${tier.quality} ${tier.codec}: coded ${track?.width}x${track?.height} beyond decoder capability",
            )
            video.close()
        } catch (error: Exception) {
            video.close()
            throw error
        }
    }
    if (ladder.isNotEmpty()) throw NoPlayableQualityException()
    return null
}

/** Lowest ladder tier strictly below the attempted quality; null = no dead-end button. */
internal fun nextLowerQuality(attempted: Int, tiers: List<StreamInfo>): Int? =
    tiers.map { it.qualityValue }.filter { it in 1 until attempted }.minOrNull()

/** Auto-downgrade is worth another attempt while budget remains and a lower tier exists. */
internal fun shouldAutoDowngrade(
    attempts: Int,
    maxAttempts: Int,
    tiers: List<StreamInfo>,
    currentQuality: Int,
): Boolean =
    attempts < maxAttempts && currentQuality > 0 && tiers.any { it.qualityValue in 1 until currentQuality }
