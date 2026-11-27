// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 615b0c5 (EpisodeDirectory.kt); GPL-3.0-only.
package com.hongguotv.core

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分集目录（2026-11-27 平台改版）。
 *
 * 网站详情页从这一天起只暴露**前 3 集**，而且 vid 换成了混淆形式
 * （`v1_TeqlRcHZ9tfN6rJscA8R5dH8DQY` 而不是 137 个数字 id）。旧解析要求「全部数字
 * 且数量完整」，于是所有剧的详情页都报「分集数据异常」。
 *
 * 策略：网页元数据照用（标题/封面/简介/标签），但**完整分集目录**改走 App 目录接口
 * `/reading/bookapi/directory/all_items/v/`（浏览签名即可，不需要登录或播放设备注册），
 * 取 `data.item_data_list[].item_id`。
 *
 * 顺序必须保持原样：排序或丢弃坏 id 会改变本地保存的集号索引。
 */
internal object EpisodeDirectory {
    private val numericId = Regex("[0-9]{1,30}")

    /** 声明的总集数（网页两处字段取最大，容忍其中一处缺失）。 */
    fun expectedCount(detail: JSONObject): Int = maxOf(
        0,
        detail.optInt("episode_cnt", 0),
        detail.optJSONObject("series_episode_info")?.optInt("episode_cnt", 0) ?: 0,
    )

    /** 网页里的 vid_list 是否**完整有效**；无效（混淆/截断/重复）返回 null。 */
    fun fromWeb(detail: JSONObject): List<String>? {
        val raw = detail.optJSONArray("vid_list") ?: return null
        val ids = (0 until raw.length()).map { raw.optString(it) }
        return ids.takeIf { valid(it, expectedCount(detail)) }
    }

    /** 解析 App 目录接口（`item_data_list[].item_id`）。 */
    fun parse(body: String, expectedCount: Int): List<String> {
        val root = try { JSONObject(body) } catch (_: org.json.JSONException) {
            throw IOException("完整分集响应格式异常")
        }
        val success = when {
            root.has("code") -> root.optInt("code", -1) == 0
            root.has("Code") -> root.optInt("Code", -1) == 0
            else -> root.optString("message").equals("SUCCESS", ignoreCase = true)
        }
        if (!success) throw IOException("完整分集接口暂不可用")
        val rows = root.optJSONObject("data")?.optJSONArray("item_data_list") ?: JSONArray()
        val ids = (0 until rows.length()).map { rows.optJSONObject(it)?.str("item_id").orEmpty() }
        if (!valid(ids, expectedCount)) throw IOException("完整分集数据缺失或异常")
        return ids
    }

    private fun valid(ids: List<String>, expectedCount: Int): Boolean =
        ids.isNotEmpty() && ids.size >= expectedCount &&
            ids.all { it.matches(numericId) } && ids.distinct().size == ids.size
}
