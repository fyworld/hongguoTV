// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 1d44b62 (0.16.21) `MobilePlayback.compatibleLadder`; GPL-3.0-only.
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException

/**
 * 片源档位协商（上游 N3urda 1d44b62 / 0.16.21，2026-11-28）。
 *
 * 背景：现行 `video_model/v1/` + `device_level=4` 只回 HEVC 1080P（少数集）。
 * 如果这台电视没有 HEVC 解码器，或者用户在设置里把画质上限压到 720P，
 * 首推模型虽然「有档位」，但没有一档是**本机能播**的 —— 以前这种情况直接把
 * 一堆播不了的档位交给播放器，最终只剩 `NoPlayableQualityException`。
 *
 * 官方播放器的做法：拿模型里的 `fallback_api` 再要一次，把 `codec_type`
 * 换成 1 和 3（兼容档），返回的 `video_info.data` 里通常带 h264/更低分辨率的档位。
 * 上游 0.16.21 把这个顺序固化成「1 → 3，命中即停」，并且**把首推档位和协商档位
 * 合并**返回 —— 播放器仍按自己的解码能力挑，但至少有一档能解。
 *
 * 与上游的差异（按功能对应）：
 *  - 上游直接在 `MobilePlayback.compatibleLadder` 里发请求；我们把它拆成纯逻辑
 *    + 注入的 `fetch`/`tiers`，便于离线回归测试（本项目没有 MockWebServer）。
 *  - 单次协商失败（0B / 非 JSON / HTTP 错）不再上抛 —— 首推档位仍要交回去，
 *    播放器还能尝试软件解码，这比整集直接失败好。
 */
internal object PlaybackNegotiation {

    /** 上游 `listOf("1", "3")` 原序：先要通用 H.264 档，再要最低兼容档。 */
    internal val CODEC_TYPES = listOf("1", "3")

    /**
     * 用 `fallback_api` 协商可播档位。
     *
     * @param fallbackUrl 模型里的 fallback_api（必须是 https，域名白名单由调用方校验）
     * @param seed 首推模型解出的 key_seed；协商回来的模型自带 key_seed 时以它为准
     * @param acceptable 播放器视角的「这一档本机能不能播（含画质上限）」判据
     * @param fetch 取一档 fallback 数据（调用方负责域名白名单与体积上限）
     * @param tiers 把一个 `video_info.data` 解析成档位列表（复用主链路的解析）
     * @return 协商到的档位（去重、按 codec_type 顺序）；没有可播档位时是空列表
     */
    internal fun negotiate(
        fallbackUrl: String,
        seed: ByteArray?,
        acceptable: (StreamInfo) -> Boolean,
        fetch: (String) -> JSONObject,
        tiers: (JSONObject, ByteArray?) -> List<StreamInfo>,
    ): List<StreamInfo> {
        if (!fallbackUrl.startsWith("https://")) return emptyList()
        val candidates = mutableListOf<StreamInfo>()
        for (codec in CODEC_TYPES) {
            if (Thread.currentThread().isInterrupted) return candidates
            val url = runCatching {
                fallbackUrl.toHttpUrl().newBuilder().setQueryParameter("codec_type", codec).build().toString()
            }.getOrNull() ?: continue
            val outer = try {
                fetch(url)
            } catch (_: IOException) {
                continue
            } catch (_: RuntimeException) {
                continue
            }
            val data = outer.optJSONObject("video_info")?.optJSONObject("data")
                ?: outer.optJSONObject("data") ?: continue
            val rowSeed = data.str("key_seed").takeIf { it.isNotEmpty() }
                ?.let { runCatching { decode64(it) }.getOrNull() } ?: seed
            val rows = try {
                tiers(data, rowSeed)
            } catch (_: IOException) {
                emptyList()
            } catch (_: RuntimeException) {
                emptyList()
            }
            candidates += rows.filter { row -> candidates.none { it.url == row.url } }
            if (rows.any(acceptable)) return candidates
        }
        return candidates
    }
}
