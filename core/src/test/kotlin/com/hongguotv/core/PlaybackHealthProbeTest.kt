// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * 线上播放守护探针（每日巡检 automation 专用，2026-11-19 建）。
 *
 * 默认套件不执行：只有 `PROBE_LIVE=1` 时才真跑，否则 Assume 跳过、不计失败
 * （避免 :core:test 每次都打真实接口）。
 *   PROBE_LIVE=1 gradlew :core:test --tests "com.hongguotv.core.PlaybackHealthProbeTest"
 *
 * 判据取「用户视角」而不是「单一通道」——2026-11-19 实测：
 * v5 原始通道 /player/v1 返回 200+0B，旧 video_tab 签名链路返回 200+23KB，
 * 全链路仍然取到 1080P 且媒体 HEAD 200。这说明平台侧随时可能在通道间来回切，
 * 单通道 0B 只是 App 靠通道回退自愈的正常过程（见 MEMORY.md「v5 签名协议」）。
 * 因此：
 *  - PASS = 全链路 ladder/stream 拿到可播 URL，且媒体地址 HEAD 200 + video 类型；
 *  - 各通道原始字节数只打印用于诊断，单独为 0 不判失败。
 */
class PlaybackHealthProbeTest {
    @Test
    fun playbackStillServes() {
        Assume.assumeTrue(
            "线上守护探针：需 PROBE_LIVE=1（默认套件不打真实接口）",
            System.getenv("PROBE_LIVE") == "1")

        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        try {
            // 与历史探针同一集，便于纵向对比（糯糯下山 ep1）。
            val videoId = "7677804745699888153"
            val transport = AppApiTransport(http)
            val source = MultiVideoModelStreamSource(transport, http)

            // 通道诊断：只打印，不判失败。
            val ladder = runCatching {
                source.ladder(videoId, 1080, ContentType.SHORT, emptyList(), false)
            }
            ladder.onSuccess { println("PROBE ladder tiers=${it.size}") }
                .onFailure { println("PROBE ladder failed=${it.javaClass.simpleName} msg=${it.message}") }

            // 全链路判据一：能否拿到可播 URL。
            val info = source.stream(videoId, 1080, ContentType.SHORT)
            println("PROBE channel=${source.name} quality=${info.quality} drmKey=${info.key?.size ?: 0}B")
            assertTrue("播放 URL 为空", info.url.startsWith("http"))

            // 全链路判据二：媒体地址真的能出流（10 秒试看片段也会 200，故再验时长）。
            val request = Request.Builder().url(info.url).head().build()
            http.newCall(request).execute().use { response ->
                val type = response.header("Content-Type").orEmpty()
                println("PROBE media HEAD HTTP ${response.code} type=$type")
                assertTrue("媒体地址不可达：HTTP ${response.code}", response.isSuccessful)
                assertTrue("媒体返回非视频：$type", type.startsWith("video"))
            }
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
