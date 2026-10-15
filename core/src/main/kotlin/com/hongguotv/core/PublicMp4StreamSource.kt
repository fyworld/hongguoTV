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
 */
internal class PublicMp4StreamSource(
    private val http: OkHttpClient,
    private val webUa: String,
    private val site: String,
) : StreamSource {
    override val name = "public-mp4"

    override fun stream(id: String, maxQuality: Int, type: ContentType): StreamInfo {
        val url = "$site/video/public/$id/360p.mp4"
        // Probe with a 1-byte range so a dead link fails fast instead of mid-playback.
        val probe = Request.Builder().url(url).header("User-Agent", webUa).header("Referer", "$site/")
            .header("Range", "bytes=0-0").header("Accept-Encoding", "identity").build()
        RequestScope.execute(http.newCall(probe)) { response ->
            if (response.code != 206 && response.code != 200) throw IOException("播放地址暂不可用（HTTP ${response.code}）")
            val range = response.header("Content-Range")
            if (range == null && response.header("Content-Length") == "0") throw IOException("播放地址暂不可用")
        }
        return StreamInfo(url, null, "360P")
    }
}
