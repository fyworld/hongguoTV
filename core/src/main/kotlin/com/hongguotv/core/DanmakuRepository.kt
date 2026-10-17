// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** One danmaku comment at its video-relative position. */
data class DanmakuItem(val id: String, val timeMs: Long, val text: String)

/** One window of the danmaku list (30s cursor pagination upstream). */
data class DanmakuPage(val items: List<DanmakuItem>, val nextCursorMs: Long)

/**
 * Danmaku list client: POST /novel/commentapi/comment/list/{videoID}/v1/
 * with the full comment signature chain (DanmakuSigner).
 *
 * Pagination: the response carries data.extra.next_query_danmaku_list_time
 * (a millisecond cursor into the video timeline); the next call passes it as
 * business_param.start_offset_time to fetch the following window.
 */
internal class DanmakuRepository(
    private val http: OkHttpClient,
    private val transport: AppApiTransport,
    private val signer: DanmakuSigner = DanmakuSigner(),
) {
    /**
     * Fetch one window. [startOffsetMs] starts at 0 and follows
     * [DanmakuPage.nextCursorMs]; [durationMs] is the episode duration
     * (business_param.playlet_item_duration).
     */
    fun page(videoId: String, seriesId: String, startOffsetMs: Long, durationMs: Long): DanmakuPage {
        val payload = JSONObject()
            .put("comment_source", 601).put("server_channel", 1000)
            .put("group_id", videoId).put("group_type", 30)
            .put("comment_type", 20).put("sort", 1).put("count", 90)
            .put("cursor", "").put("aid", 8662).put("compliance_status", 0)
            .put("business_param", JSONObject()
                .put("book_id", seriesId)
                .put("start_offset_time", startOffsetMs)
                .put("playlet_item_duration", durationMs)
                .put("need_danmaku_guide_type", org.json.JSONArray().put(1).put(3).put(4).put(2)))
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val query = transport.encodeQuery(transport.queryProfile())
        val signed = signer.sign(query)
        val builder = Request.Builder()
            .url("${AppApiTransport.APP_BASE}/novel/commentapi/comment/list/$videoId/v1/?$query")
            .header("User-Agent", transport.userAgent)
            .header("Accept", "application/json")
            .header("X-XS-From-Web", "0").header("Sdk-Version", "2")
            .header("Comment-Source", "601").header("Server-Channel", "1000")
            .header("X-SS-STUB", "")
        for ((k, v) in signed.headers) builder.header(k, v)
        builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return RequestScope.execute(http.newCall(builder.build())) { response ->
            if (!response.isSuccessful) throw IOException("弹幕接口请求失败（HTTP ${response.code}）")
            val text = response.body?.string() ?: throw IOException("弹幕接口响应为空")
            if (text.isEmpty()) throw IOException("弹幕接口返回空数据")
            val data = JSONObject(text)
            val code = data.optInt("code", data.optInt("Code", 0))
            if (code != 0) throw IOException("弹幕接口暂不可用（$code）")
            val rows = data.optJSONObject("data")?.optJSONArray("data_list") ?: org.json.JSONArray()
            val items = rows.objects().mapNotNull { row ->
                val comment = row.optJSONObject("comment") ?: return@mapNotNull null
                val common = comment.optJSONObject("common") ?: return@mapNotNull null
                val textContent = common.optJSONObject("content")?.str("text") ?: ""
                if (textContent.isEmpty()) return@mapNotNull null
                val id = comment.str("comment_id").ifEmpty { return@mapNotNull null }
                val timeMs = comment.optJSONObject("expand")?.str("offset_time")?.toLongOrNull() ?: return@mapNotNull null
                DanmakuItem(id, timeMs, textContent)
            }
            val next = data.optJSONObject("data")?.optJSONObject("extra")?.optLong("next_query_danmaku_list_time", -1L) ?: -1L
            DanmakuPage(items, next)
        }
    }
}
