// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Fallback channel: the website's public MP4 path /video/public/{vid}/360p.mp4
 * (discovered in the site player's own JS). Plain, unencrypted (key=null walks
 * MediaCrypto's clear path), Range-capable, no Referer check. 360p is the only
 * quality served there - the site's own JS hardcodes it - but the channel is
 * immune to any future App API breakage, which is why it stays last in the chain.
 *
 * 2026-11-14: this path serves a 10-SECOND PREVIEW clip for some episodes
 * (514KB, mvhd duration 10.0s) while the signed App API model for the same
 * episode reports video_duration 63.169s. When a sporadic empty response makes
 * the scheduler fall through here, the player sees a perfectly valid 10s MP4,
 * ends normally and never raises an error - "this episode only plays 10s".
 * Reject short declared durations so the app retries the signed channel instead.
 */
internal class PublicMp4StreamSource(
    private val http: OkHttpClient,
    private val webUa: String,
    private val site: String,
) : StreamSource {
    override val name = "public-mp4"

    override fun stream(id: String, maxQuality: Int, type: ContentType, metadataAhead: List<String>, speculative: Boolean): StreamInfo {
        // Background reads never touch the website channel (upstream 0.1.26): a
        // speculative hit here would hand the player a 360p URL and short-circuit
        // the foreground's HD attempt on the App API path.
        if (speculative) throw IOException("speculative read not served by public-mp4")
        val url = "$site/video/public/$id/360p.mp4"
        // 128KB header probe: reads far enough to reach moov/mvhd so a dead link OR
        // a preview clip fails fast instead of mid-playback (the old 1-byte probe
        // could not tell a 10s clip from a full episode).
        val probe = Request.Builder().url(url).header("User-Agent", webUa).header("Referer", "$site/")
            .header("Range", "bytes=0-131071").header("Accept-Encoding", "identity").build()
        RequestScope.execute(http.newCall(probe)) { response ->
            if (response.code != 206 && response.code != 200) throw IOException("播放地址暂不可用（HTTP ${response.code}）")
            val head = response.body?.bytes() ?: ByteArray(0)
            val seconds = declaredSeconds(head)
            if (seconds != null && seconds in 0.1..30.0) {
                throw IOException("网站公开片源仅为试看片段（${seconds.toInt()} 秒），请重试以重新获取完整片源")
            }
            val range = response.header("Content-Range")
            if (range == null && response.header("Content-Length") == "0") throw IOException("播放地址暂不可用")
        }
        return StreamInfo(url, null, "360P")
    }

    /** Declared movie duration from mvhd; null when the header cannot be read. */
    private fun declaredSeconds(data: ByteArray): Double? {
        if (data.size < 32 || String(data, 4, 4, Charsets.US_ASCII) != "ftyp") return null
        val at = String(data, Charsets.ISO_8859_1).indexOf("mvhd")
        if (at < 4) return null
        val version = data[at + 4].toInt() and 0xff
        val (scale, duration) = when (version) {
            0 -> if (at + 24 <= data.size) data.u32(at + 16) to data.u32(at + 20) else return null
            1 -> if (at + 32 <= data.size) data.u32(at + 20) to data.i64(at + 24) else return null
            else -> return null
        }
        if (scale <= 0 || duration <= 0) return null
        return duration.toDouble() / scale.toDouble()
    }
}
