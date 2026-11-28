// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 1d44b62 (0.16.21) PlaybackCodecNegotiationTest; GPL-3.0-only.
package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 档位协商（上游 0.16.21 / 1d44b62）：首推模型没有一档本机能播时，
 * 必须拿 `fallback_api` 按 `codec_type=1` → `3` 协商，命中即停，
 * 并把协商到的档位交回播放器挑选。
 */
class PlaybackCodecNegotiationTest {

    private fun body(codec: String, width: Int, height: Int, type: String): JSONObject =
        JSONObject().put("video_info", JSONObject().put("data", JSONObject().put("video_list", JSONObject()
            .put("video_1", JSONObject()
                .put("codec_type", codec)
                .put("vwidth", width)
                .put("vheight", height)
                .put("main_url", "https://cdn.test/$type")))))

    /** 与主链路 tierRows/tierInfo 同构的极简解析：一份 video_list → 档位。 */
    private val tiers: (JSONObject, ByteArray?) -> List<StreamInfo> = { data, _ ->
        val list = data.optJSONObject("video_list") ?: JSONObject()
        list.keys().asSequence().mapNotNull { key ->
            val row = list.optJSONObject(key) ?: return@mapNotNull null
            val width = row.optInt("vwidth")
            val height = row.optInt("vheight")
            val qualityValue = if (width in 1 until height) width else height
            StreamInfo(
                url = row.optString("main_url"), key = null,
                quality = if (qualityValue > 0) "${qualityValue}P" else "自动",
                codec = row.optString("codec_type"), width = width, height = height,
                qualityValue = qualityValue,
            )
        }.toList()
    }

    @Test
    fun hevcOnlyPrimaryNegotiatesCodecTypesInOrderUntilAcceptable() {
        val requested = mutableListOf<String>()
        val fetch: (String) -> JSONObject = { url ->
            val type = url.substringAfter("codec_type=").substringBefore('&')
            requested += type
            if (type == "3") body("h264", 720, 1280, "3") else body("h265", 1080, 1920, "1")
        }
        val acceptable: (StreamInfo) -> Boolean = { it.qualityValue <= 720 && it.codec == "h264" }
        val rows = PlaybackNegotiation.negotiate(
            fallbackUrl = "https://vas.test/fplay?codec_type=4",
            seed = null,
            acceptable = acceptable,
            fetch = fetch,
            tiers = tiers,
        )
        // 1 拿回来仍是 HEVC 1080（不可播），继续 3；3 命中 h264 720 后停止。
        assertEquals(listOf("1", "3"), requested)
        assertTrue(rows.any(acceptable))
        assertEquals("h264", rows.first(acceptable).codec)
        assertEquals(720, rows.first(acceptable).qualityValue)
    }

    @Test
    fun unavailableCompatibleTierStopsAfterTwoAttemptsWithoutThrowing() {
        val requested = mutableListOf<String>()
        val rows = PlaybackNegotiation.negotiate(
            fallbackUrl = "https://vas.test/fplay",
            seed = null,
            acceptable = { it.qualityValue <= 720 },
            fetch = { url ->
                requested += url.substringAfter("codec_type=").substringBefore('&')
                body("h265", 1080, 1920, "x")
            },
            tiers = tiers,
        )
        assertEquals(listOf("1", "3"), requested)
        assertTrue(rows.isEmpty() || rows.none { it.qualityValue <= 720 })
    }

    @Test
    fun failedNegotiationIsSwallowedAndKeepsOtherTiersUsable() {
        val requested = mutableListOf<String>()
        val rows = PlaybackNegotiation.negotiate(
            fallbackUrl = "https://vas.test/fplay",
            seed = null,
            acceptable = { it.codec == "h264" },
            fetch = { url ->
                requested += "x"
                if (requested.size == 1) throw IOException("0B") else body("h264", 540, 960, "second")
            },
            tiers = tiers,
        )
        // 第一档 0B 不该让整次协商失败：第二档仍要尝试，并用它的结果。
        assertEquals(2, requested.size)
        assertEquals("h264", rows.single().codec)
    }

    @Test
    fun nonHttpsFallbackDoesNotRequestAnything() {
        val requested = mutableListOf<String>()
        val rows = PlaybackNegotiation.negotiate(
            fallbackUrl = "http://insecure.test/fplay",
            seed = null,
            acceptable = { false },
            fetch = { requested += it; body("h264", 540, 960, "x") },
            tiers = tiers,
        )
        assertTrue(rows.isEmpty())
        assertTrue(requested.isEmpty())
    }

    @Test
    fun negotiatedRowsAreDeduplicatedByUrl() {
        // 两个 codec_type 请求回同一地址（平台常回同一份兼容列表）时只保留一份。
        val rows = PlaybackNegotiation.negotiate(
            fallbackUrl = "https://vas.test/fplay",
            seed = null,
            acceptable = { false },
            fetch = { body("h265", 1080, 1920, "same") },
            tiers = tiers,
        )
        assertEquals(1, rows.size)
        assertEquals("https://cdn.test/same", rows.single().url)
    }
}
