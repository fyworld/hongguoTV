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
    private val preloadV5 = PlaybackShape(PlaybackPath.POST_PRELOAD, PlaybackSigner.V5)
    private val videoModelV5 = PlaybackShape(PlaybackPath.POST_VIDEO_MODEL, PlaybackSigner.V5)

    @After
    fun reset() {
        PlaybackShapeMemory.restore(null)
        PlaybackShapeMemory.onChange = null
    }

    @Test
    fun coversAllPathAndSignerCombinations() {
        // 5 条路径 × 2 种签名 = 10 种形态，缺一种就少一条兜底。
        assertEquals(10, PlaybackShape.all.size)
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
    fun officialVideoModelPathKeepsTrailingSlash() {
        // 上游 eb960ff：官方客户端 11-23 深夜改打 /novel/player/video_model/v1/，
        // 尾部斜杠是官方 RpcOperation 原文，别在改配置时抹掉。
        val endpoint = PlaybackPath.POST_VIDEO_MODEL.resolve()
        assertEquals("/novel/player/video_model/v1/", endpoint.absolutePath)
        assertTrue(endpoint.absolutePath.endsWith("/"))
        // 上游 d859244（0.1.40）：同一个路径换到 reading.snssdk.com，请求体改成
        // `video_id` + `biz_param`（device_level=2）——同路径打 fqnovel 网关只回 720P 单档。
        assertEquals("reading.snssdk.com", endpoint.host.substringAfter("//"))
        assertEquals("single", endpoint.body)
        assertEquals(2, endpoint.deviceLevel)
        assertEquals(MobilePlayback.PRELOAD_BASE, endpoint.host)
        assertEquals("map", PlaybackPath.POST_PLAYER.resolve().body)
        // 旧端点不带尾斜杠，两者是不同的 path 特征。
        assertEquals("/novel/player/multi_video_model/v1", PlaybackPath.POST_V1.resolve().absolutePath)
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
        // 11-25（上游 d859244）起单集 video_model 是唯一能取到 1080P 多档的形态，排第一；
        // preload 仍活着但只回 720P 单档，退第二；GET 仍是 11-23 白天唯一活着的那条，第三。
        assertEquals(videoModelV5, order.first())
        assertEquals(preloadV5, order[1])
        assertEquals(getV5, order[2])
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