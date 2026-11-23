// SPDX-License-Identifier: GPL-3.0-only
// Adapted from tsukiyo-zero/hongguoTV kotlin-tv 41ba81c (PlaybackShapesTest.kt); GPL-3.0-only.
package com.hongguotv.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 形态（路径 × 签名）记忆：顺序、恢复、成功与失败降级。 */
class PlaybackShapesTest {
    private val getV5 = PlaybackShape(PlaybackPath.GET_VIDEO_TAB, PlaybackSigner.V5)
    private val playerV5 = PlaybackShape(PlaybackPath.POST_PLAYER, PlaybackSigner.V5)
    private val v1Argus = PlaybackShape(PlaybackPath.POST_V1, PlaybackSigner.ARGUS)

    @After
    fun reset() {
        PlaybackShapeMemory.restore(null)
        PlaybackShapeMemory.onChange = null
    }

    @Test
    fun coversAllPathAndSignerCombinations() {
        // 4 条路径 × 2 种签名 = 8 种形态，缺一种就少一条兜底。
        assertEquals(8, PlaybackShape.all.size)
        assertEquals(PlaybackShape.all.size, PlaybackShape.all.map { it.label }.toSet().size)
        PlaybackPath.entries.forEach { path ->
            PlaybackSigner.entries.forEach { signer ->
                assertTrue(
                    "missing ${path.label}-${signer.label}",
                    PlaybackShape.all.contains(PlaybackShape(path, signer)),
                )
            }
        }
    }

    @Test
    fun preloadPathCarriesItsOwnRequestBody() {
        // preload 是官方现行端点，自带 host / dr_scene / device_level（上游 N3urda ef328f3）。
        assertEquals(MobilePlayback.PRELOAD_BASE, PlaybackPath.POST_PRELOAD.host)
        assertEquals("preload", PlaybackPath.POST_PRELOAD.drScene)
        assertEquals(2, PlaybackPath.POST_PRELOAD.deviceLevel)
        assertEquals(MobilePlayback.MODEL_BASE, PlaybackPath.POST_PLAYER.host)
        assertNull(PlaybackPath.POST_PLAYER.drScene)
    }

    @Test
    fun defaultOrderStartsWithRecentlyVerifiedCombination() {
        PlaybackShapeMemory.restore(null)
        val order = PlaybackShapeMemory.order()
        // 平台 11-23 起只放行 GET video_tab + v5 签名（上游 ff4d1d2 实测 200/22KB）。
        assertEquals(getV5, order.first())
        assertEquals(PlaybackShape.all.size, order.size)
    }

    @Test
    fun preferredShapeMovesToFront() {
        PlaybackShapeMemory.restore(v1Argus)
        assertEquals(v1Argus, PlaybackShapeMemory.order().first())
    }

    @Test
    fun successUpdatesPreferenceAndNotifiesOnce() {
        var changes = 0
        PlaybackShapeMemory.onChange = { changes++ }
        PlaybackShapeMemory.success(playerV5)
        PlaybackShapeMemory.success(playerV5)
        assertEquals(1, changes)
        assertEquals(playerV5, PlaybackShapeMemory.current())
    }

    @Test
    fun repeatedFailureClearsPreference() {
        PlaybackShapeMemory.restore(playerV5)
        PlaybackShapeMemory.failure(playerV5)
        assertEquals(playerV5, PlaybackShapeMemory.current())
        PlaybackShapeMemory.failure(playerV5)
        assertNull(PlaybackShapeMemory.current())
    }

    @Test
    fun successResetsFailureCounters() {
        PlaybackShapeMemory.restore(playerV5)
        PlaybackShapeMemory.failure(playerV5)
        PlaybackShapeMemory.success(playerV5)
        PlaybackShapeMemory.failure(playerV5)
        assertEquals(playerV5, PlaybackShapeMemory.current())
    }

    @Test
    fun parseRoundTripsLabels() {
        PlaybackShape.all.forEach { assertEquals(it, PlaybackShape.parse(it.label)) }
        assertNull(PlaybackShape.parse("unknown"))
        assertNull(PlaybackShape.parse(null))
        // 旧版只存了路径名，解析不出来的按「无记忆」处理。
        assertNull(PlaybackShape.parse("POST_PLAYER"))
    }
}