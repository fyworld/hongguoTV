// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 配置自检：拿到远端新配置后，**先用它真打一次接口**，通过才上线。
 *
 * 为什么需要（2026-11-24）：配置热更新把「改错了」的代价放大了——以前改错要发版，
 * 现在 push 一个 JSON 就能影响所有人。自检把「推错配置」变成「推送被客户端拒绝」，
 * 最坏等于没更新。
 *
 * 做法：按候选配置的**前两个形态**各试一次（只读一集已知可播的探针），
 * 任一次拿到模型即通过。不碰 [PlaybackShapeMemory]（那是线上学习状态，自检不该改它）。
 *
 * 调用方（[PlaybackConfigStore.install]）已把候选配置临时设为 current，
 * 所以这里直接用 [MobilePlayback] 的常规入口即可。
 */
internal object PlaybackConfigSelfCheck {
    /** 与健康探针同一集（糯糯下山 ep1），历史上长期可播。 */
    const val PROBE_VIDEO_ID = "7677804745699888153"

    /** 最多试两个形态：首选可用就 1 次请求，避免自检本身烧配额。 */
    private const val MAX_SHAPES = 2

    fun run(http: OkHttpClient, device: Map<String, String>, config: PlaybackConfig): Boolean {
        val client = http.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
        val signer = MobilePlayback.signerFor(config)
        for (shape in PlaybackShape.allFor(config).take(MAX_SHAPES)) {
            val endpoint = shape.path.resolve(config)
            val entry = runCatching {
                when (shape.path) {
                    PlaybackPath.GET_VIDEO_TAB -> MobilePlayback.getVideoTab(
                        PROBE_VIDEO_ID, device, client, signer,
                        System.currentTimeMillis(), host = endpoint.host, segment = endpoint.segment,
                        config = config)
                    else -> MobilePlayback.model(
                        PROBE_VIDEO_ID, device, client, signer,
                        System.currentTimeMillis(), segment = endpoint.segment,
                        host = endpoint.host, drScene = endpoint.drScene,
                        deviceLevel = endpoint.deviceLevel, body = endpoint.body,
                        config = config)
                }
            }.getOrNull() ?: continue
            val model = entry.optJSONObject("video_model") ?: continue
            if ((model.optJSONObject("video_list")?.length() ?: 0) > 0 ||
                (model.optJSONArray("video_list")?.length() ?: 0) > 0 || model.has("fallback_api"))
                return true
        }
        return false
    }
}
