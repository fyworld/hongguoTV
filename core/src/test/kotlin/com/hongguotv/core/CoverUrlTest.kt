// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Account-endpoint covers arrive as signed fqnovelpic HEIC URLs; Android 7/8 TVs
 * cannot decode HEIF (API 28+), so every synced card rendered blank (v1.0.37 and
 * earlier). coverUrl() rewrites the hash to byteimg's unsigned JPEG URL — see
 * ContentRepository.coverUrl for the live-verified CDN mapping.
 */
class CoverUrlTest {
    private val heicUrl =
        "https://p3-reading-sign.fqnovelpic.com/novel-pic/033873814a4b74d4365620010d16548e~tplv-81nmtwyey9-superreso-aifit:400:0.heic?lk3s=64477e16&x-expires=1797161568&x-signature=q6g0XatofVaCl7TP4mGra8u5KNc%3D"

    @Test
    fun signedHeicCoverIsRewrittenToUnsignedByteimgJpeg() {
        assertEquals(
            "https://p3-novel.byteimg.com/novel-pic/033873814a4b74d4365620010d16548e~tplv-shrink:640:0.image",
            coverUrl(heicUrl),
        )
    }

    @Test
    fun otherHostsAndFormatsPassThroughUnchanged() {
        assertEquals("https://example.com/a.jpg", coverUrl("https://example.com/a.jpg"))
        assertEquals(
            "https://p6-novel.byteimg.com/novel-pic/973dad4339e00fa9a5d1bf71e6431245~tplv-shrink:640:0.webp",
            coverUrl("https://p6-novel.byteimg.com/novel-pic/973dad4339e00fa9a5d1bf71e6431245~tplv-shrink:640:0.webp"),
        )
        assertEquals("", coverUrl(""))
    }

    @Test
    fun malformedHashesAreLeftAloneInsteadOfGuessing() {
        // No hex hash between novel-pic/ and ~: not a recognizable object key.
        val odd = "https://p3-reading-sign.fqnovelpic.com/novel-pic/not-a-hash~tplv-x:400:0.heic?sig=1"
        assertEquals(cleanUrl(odd), coverUrl(odd))
    }

    @Test
    fun favoritesParseRewritesHeicCoversEndToEnd() {
        val rows =
            CloudAccountClient.parseFavorites(
                JSONObject(
                    """{"data":{"video_shelf_info":[
                        {"video_data":{"series_id":"7701935931787316286","episode_cnt":80},
                         "series_title":"测试剧","series_cover_url":"$heicUrl","collect_time":100}
                    ]}}"""
                )
            )
        val cover = rows.single().series.cover
        assertTrue(cover.startsWith("https://p3-novel.byteimg.com/novel-pic/"))
        assertTrue(cover.endsWith("~tplv-shrink:640:0.image"))
        assertFalse(cover.contains("fqnovelpic"))
        assertFalse(cover.contains("x-signature"))
    }

    @Test
    fun reservationsParseRewritesHeicCoversEndToEnd() {
        val rows =
            CloudAccountClient.parseReservations(
                JSONObject(
                    """{"data":{"subscribe_items":[
                        {"item_id":"9001","series_title":"待上线测试","series_cover_url":"$heicUrl",
                         "schedule_publish_time":1792500000,"expected_rec_text":"预计12月上线"}
                    ]}}"""
                ),
                online = false,
            )
        val cover = rows.single().series.cover
        assertTrue(cover.startsWith("https://p3-novel.byteimg.com/novel-pic/"))
        assertFalse(cover.contains("fqnovelpic"))
    }

    @Test
    fun escapedUrlVariantsAreCleanedBeforeRewriting() {
        val escaped = heicUrl.replace("/", "\\u002F")
        assertEquals(
            "https://p3-novel.byteimg.com/novel-pic/033873814a4b74d4365620010d16548e~tplv-shrink:640:0.image",
            coverUrl(escaped),
        )
    }
}
