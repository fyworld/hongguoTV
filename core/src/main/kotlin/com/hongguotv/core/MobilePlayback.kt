// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 6672b55 via tsukiyo-zero/hongguoTV 0236976; GPL-3.0-only.
// 2026-11-23 对齐上游 41ba81c：本文件只负责「单个形态的一次请求」，路径轮换由
// MultiVideoModelStreamSource + PlaybackShapeMemory 负责（getVideoTab 亦来自上游 ff4d1d2）。
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 播放模型 v5 通道（官方 App v04.10.03，2026-11-16 事件）。
 *
 * 2026-11-16 17:57 起旧链路（video_tab GET / 批量 POST，六头签名）对全部身份返回
 * HTTP 200 + 0 字节；上游同版本同网络也一样。上游 0.1.32 (d338994) 的结论是
 * 「接口和签名又换了」：新接口 /novel/player/multi_video_model/player/v1 +
 * gzip 请求体 + v5 签名（只带 x-khronos/x-helios/x-medusa）。对照实测同一集、
 * 同一匿名身份：旧签名 0 字节，v5 签名 24345 字节、码 0、5 档模型。
 *
 * 2026-11-20（上游 0.1.33 573d75a）：11-20 起 `player/v1` 形态对匿名请求回到
 * 200 + 0 字节（`stage=mobile-model`）。官方 7.4.5.32 客户端回到平铺的
 * /novel/player/multi_video_model/v1 路径（不带 dr_scene），并把版本号参数
 * 提升到 74532 / 7.4.5.32；实测该组合 + 同一套 v5 签名能取到 24KB 模型。
 *
 * 2026-11-23（上游 41ba81c）：平台在「路径」与「签名规范」两条轴上同时来回切
 * （11-23 起 GET video_tab 只认 v5 签名，旧 Argus 签名被静默丢弃）。因此本类
 * 不再自己记路径，只提供「按指定路径发一次请求」的原语：
 *  - [model]      POST，v5 签名，路径由调用方给（player/v1 或平铺 v1）；
 *  - [getVideoTab] GET，v5 签名 + 74532 参数（上游 ff4d1d2 新增）。
 * 轮换、记忆与预算均分在 MultiVideoModelStreamSource.resolvePlaybackModel。
 *
 * 与旧通道的差异（都必须保留，少一处就回到 0 字节）：
 *  - 请求体 gzip 压缩，签名绑定的是**压缩后**的字节，x-ss-stub 也是压缩后 MD5；
 *  - query 用官方手机端 profile（74532 / 7.4.5.32 / vivo_8662_64 / PGT-AN10 / os 12），
 *    不用我们的设备表；
 *  - 只读目标集，精确匹配 video_id（data["1004"][id].video_model，可平铺或字符串）；
 *  - 不带注册 Cookie，连接池现开现关。
 */
internal data class MobileSignedRequest(val url: String, val headers: Map<String, String>)

internal fun interface MobilePlaybackSigner {
    fun sign(url: String, body: ByteArray, device: Map<String, String>): MobileSignedRequest
}

internal object MobilePlayback {
    private val v5Signer = Signer.sharedV5 { System.currentTimeMillis() }

    val signer = MobilePlaybackSigner { url, body, _ ->
        val parsed = url.toHttpUrl()
        val values = parsed.queryParameterNames.associateWith { parsed.queryParameter(it)!! }
        val now = values.getValue("_rticket").toLong()
        val request = v5Signer.signPlaybackV5(
            parsed.newBuilder().query(null).build().toString(), values, body, now)
        MobileSignedRequest(request.url, request.headers)
    }

    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) throw IOException("请求已取消")
    }

    /** 74532 / 7.4.5.32 公共参数（POST 与 GET 形态共用，上游 ff4d1d2 起一致）。 */
    private fun commonQuery(device: Map<String, String>, now: Long): LinkedHashMap<String, String> =
        linkedMapOf(
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to "74532", "version_name" to "7.4.5.32",
            "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74532",
            "iid" to device.getValue("iid"), "cdid" to device.getValue("cdid"),
            "device_id" to device.getValue("device_id"), "_rticket" to now.toString())

    /** 取一集的播放模型（POST 形态，v5 签名 + gzip 体）。
     *
     * [segment] 由调用方按形态指定（player/v1 或平铺 v1）。本函数只打一次：
     * 空响应/业务码/网络错误直接抛，路径轮换交给上层（单次多路径会让每次起播
     * 白烧配额与超时，上游 41ba81c 已把轮换移到形态层）。
     */
    fun model(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        segment: String = PLAYER_SEGMENT,
    ): JSONObject {
        require(id.matches(Regex("[0-9]{1,30}")))
        ensureActive()
        val payload = JSONObject(
            """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}"""
        )
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val url = "$MODEL_BASE/${segment.trimStart('/')}".toHttpUrl().newBuilder()
            .apply { commonQuery(device, now).forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url)
            .header("User-Agent", PLAYBACK_UA)
            .header("Accept", "application/json")
            .header("Content-Encoding", "gzip")
            .header("x-ss-stub", MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) })
        val signed = signer.sign(url.toString(), bytes, device)
        request.url(signed.url)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        ensureActive()
        request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            readModel(response, id, emptyDetail = "stage=mobile-model") { root, data ->
                // POST 形态：data["1004"][id].video_model（也可平铺 data[id]）。
                val entries = data.optJSONObject("1004") ?: data
                entries.optJSONObject(id)?.opt("video_model")
            }
        }
    }

    /**
     * 形态「GET video_tab」（上游 ff4d1d2，2026-11-23）：与 [model] 共用同一套 v5
     * 签名与 7.4.5.32 参数，只是改成 GET + 查询参数。
     *
     * 平台从 11-23 起只放行这一条；旧的 Argus 签名 GET（[MultiVideoModelStreamSource.tabModel]）
     * 被静默丢弃（200+0B），保留给 legacy 流程与诊断使用。响应结构为
     * `data.video_model_data[id].video_model`。
     */
    fun getVideoTab(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
    ): JSONObject {
        require(id.matches(Regex("[0-9]{1,30}")))
        ensureActive()
        val query = commonQuery(device, now).apply {
            put("video_id", id); put("video_id_type", "0"); put("device_level", "1")
            put("need_all_video_definition", "1"); put("need_mp4_align", "0")
            put("use_os_player", "0"); put("video_platform", "1024")
            put("source", "0"); put("from_video_id", "")
        }
        val url = "$GET_TAB_BASE/${GET_TAB_SEGMENT.trimStart('/')}".toHttpUrl().newBuilder()
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val signed = signer.sign(url.toString(), ByteArray(0), device)
        val request = Request.Builder()
            .url(signed.url)
            .header("User-Agent", V5_TAB_UA)
            .header("Accept", "application/json")
            .apply { signed.headers.forEach { (k, v) -> header(k, v) } }
            .get()
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            readModel(response, id, emptyDetail = "shape=get") { _, data ->
                val rows = data.optJSONObject("video_model_data") ?: data
                rows.optJSONObject(id)?.opt("video_model")
            }
        }
    }

    /**
     * 两种形态共用的响应解码：HTTP 状态、2MiB 上限、空体→可自愈异常、业务码→风控异常、
     * 只取目标集的 video_model（对象或 JSON 字符串），并校验至少有 video_list 或 fallback_api。
     */
    private inline fun readModel(
        response: okhttp3.Response,
        id: String,
        emptyDetail: String,
        pick: (JSONObject, JSONObject) -> Any?,
    ): JSONObject {
        if (!response.isSuccessful) throw IOException("播放接口 HTTP ${response.code}")
        val body = response.body ?: throw IOException("播放信息格式异常")
        val limit = 2 * 1024 * 1024L
        if (body.contentLength() > limit) throw IOException("播放信息过大")
        val source = body.source()
        source.request(limit + 1)
        if (source.buffer.size > limit) throw IOException("播放信息过大")
        val text = source.readUtf8()
        if (text.isBlank()) throw EmptyPlaybackResponseException(emptyDetail)
        val root = try { JSONObject(text) } catch (_: Exception) {
            throw IOException("播放信息格式异常")
        }
        val code = root.optInt("Code", root.optInt("code", 0))
        if (code != 0) throw AppApiRejectedException(code)
        val data = root.optJSONObject("data") ?: throw IOException("播放信息为空")
        val raw = pick(root, data)
        val model = when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        } ?: throw IOException("播放信息未包含指定剧集")
        if ((model.optJSONObject("video_list")?.length() ?: 0) == 0 &&
            (model.optJSONArray("video_list")?.length() ?: 0) == 0 && !model.has("fallback_api"))
            throw IOException("播放信息没有可用资源")
        return JSONObject().put("video_model", model)
    }

    const val MODEL_BASE = "https://api5-normal-sinfonlinea.fqnovel.com"

    /** GET 形态走 b 域（上游 getVideoTab 原值）；POST 形态走 a 域。 */
    const val GET_TAB_BASE = "https://api5-normal-sinfonlineb.fqnovel.com"

    /** POST player 变体路径（[PlaybackPath.POST_PLAYER.segment]）。 */
    const val PLAYER_SEGMENT = "novel/player/multi_video_model/player/v1"

    /** 平铺 v1 路径（[PlaybackPath.POST_V1.segment]）；7.4.5.32 起上游回到这里（573d75a）。 */
    const val MODEL_SEGMENT = "novel/player/multi_video_model/v1"

    const val GET_TAB_SEGMENT = "reading/bookapi/video_tab/multi_video_model/v1"

    /** 兼容旧调用方/测试的绝对路径常量。 */
    const val MODEL_PATH = "/$MODEL_SEGMENT"
    const val PLAYER_PATH = "/$PLAYER_SEGMENT"

    /** v5 POST 请求体自己的 UA（上游 MobilePlayback 原值，不随签名器改写）。 */
    const val PLAYBACK_UA =
        "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"

    /** GET video_tab 的 v5 UA：上游 ff4d1d2 用 74532（与 query 参数版本一致）。 */
    const val V5_TAB_UA =
        "com.phoenix.read/74532 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"
}