// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 6672b55 via tsukiyo-zero/hongguoTV 0236976; GPL-3.0-only.
// 2026-11-23 再对齐 N3urda/hongguoTV ef328f3「纯 Kotlin 播放恢复」：
// 请求画像换成 74532 官方 App（channel=update_64 / 25053RT47C / Redmi / os16 / os_api36 /
// host_abi=arm64-v8a），配合 V5Constants.field21=312 —— 平台按「画像 + 签名」双重校验。
// 本文件只负责「单个形态的一次请求」，路径轮换由 MultiVideoModelStreamSource +
// PlaybackShapeMemory 负责（矩阵来自 tsukiyo-zero 41ba81c）。
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放模型 v5 通道（官方 App v04.10.03）。
 *
 * 演进（每次都是平台换校验维度，写死即死）：
 *  - 2026-11-16（上游 0.1.32 d338994）：旧六头签名全 0B，改 `/player/v1` + gzip + v5 签名
 *  - 2026-11-20（上游 0.1.33 573d75a）：`player/v1` 回 0B，改平铺 `v1` + 74532/7.4.5.32
 *  - 2026-11-22（上游 e096fe4）：路径再反转，改回 `player/v1`
 *  - 2026-11-23（上游 ff4d1d2/41ba81c）：GET video_tab 改用 v5 签名 + 路径 × 签名矩阵
 *  - **2026-11-23 晚（上游 N3urda ef328f3）**：平台改为校验**请求画像**——
 *    老的 `channel=vivo_8662_64 / device_type=PGT-AN10 / os_version=12` 全部 200+0B，
 *    必须连同 medusa 明文 wire field 21（377→312）一起换掉才能取到模型。
 *    实测：同一路径下只换画像即 200/23846B；三条路径（player / preload / video_tab）
 *    当前都通，UA 也不敏感（okhttp UA 与官方 App UA 都能过）。
 *
 * 与旧通道的差异（都必须保留，少一处就回到 0 字节）：
 *  - 请求体 gzip 压缩，签名绑定的是**压缩后**的字节，x-ss-stub 也是压缩后 MD5；
 *  - query 用官方 74532 App 画像（见 [currentAppQuery]），不用我们的设备表；
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

    /**
     * 官方 74532 App 请求画像（上游 N3urda ef328f3「current app protocol」）。
     *
     * 这些字段不是装饰：平台按 `channel / device_type / os_version / os_api / host_abi`
     * 判断「是不是当前官方客户端」。用旧的 `vivo_8662_64 / PGT-AN10 / os 12` 画像，
     * 即使签名完全正确也一律 200+0B。
     * `_rticket` 由调用方追加在末尾（与上游同序）。
     *
     * 2026-11-24：静态字段改为读 [PlaybackConfigStore]（可热更新），
     * `iid / cdid / device_id` 三个设备相关字段仍在运行时注入。
     */
    private fun currentAppQuery(device: Map<String, String>): LinkedHashMap<String, String> {
        val query = LinkedHashMap(PlaybackConfigStore.current().appQuery)
        query["iid"] = device.getValue("iid")
        query["cdid"] = device.getValue("cdid")
        query["device_id"] = device.getValue("device_id")
        return query
    }

    /**
     * 取一集的播放模型（POST 形态，v5 签名 + gzip 体）。
     *
     * [segment] 由调用方按形态指定（player/v1、preload/v1 或平铺 v1）。
     * [host] 默认为 a 域；preload 变体走 `reading.snssdk.com`（官方现行端点）。
     * [drScene] 非空时写入请求体 `dr_scene`（preload 变体需要；平铺 v1 带上会掉空体）。
     * [deviceLevel] preload 变体用 2（官方），其余用 1（TV 侧协商兼容档位）。
     *
     * 本函数只打一次：空响应/业务码/网络错误直接抛，路径轮换交给上层形态矩阵。
     */
    fun model(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        segment: String = PLAYER_SEGMENT,
        host: String = MODEL_BASE,
        drScene: String? = null,
        deviceLevel: Int = 1,
    ): JSONObject {
        require(id.matches(Regex("[0-9]{1,30}")))
        ensureActive()
        val bizParam = JSONObject()
            .put("detail_page_version", 0)
            .put("device_level", deviceLevel)
            .put("disable_digg_stat", false)
            .put("disable_video_relate_book", false)
            .put("need_all_video_definition", true)
            .put("need_mp4_align", false)
            .put("use_os_player", false)
            .put("use_server_dns", false)
            .put("video_platform", 1024)
        val payload = JSONObject()
            .put("biz_param", bizParam)
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(id)))
        if (drScene != null) payload.put("dr_scene", drScene)
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val url = "$host/${segment.trimStart('/')}".toHttpUrl().newBuilder().apply {
            currentAppQuery(device).forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("_rticket", now.toString())
        }.build()
        val request = Request.Builder().url(url)
            .header("User-Agent", postUserAgent())
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
            readModel(response, id, emptyDetail = "stage=mobile-model")
        }
    }

    /**
     * 形态「GET video_tab」（上游 ff4d1d2 / N3urda videoTabRequest）：与 [model] 共用同一套
     * v5 签名与 74532 画像，只是改成 GET + 查询参数。
     *
     * 与 POST 的三处差异（上游原样）：UA 用 73932 + 25053RT47C；Accept 带 charset +
     * Accept-Language；**必须去掉 x-ss-stub / Content-Type / Content-Encoding**
     * （空字节只是签名输入，GET 没有请求体，带上这些头会被当成伪造特征）。
     *
     * [host] / [segment] 由形态配置提供（`configs/playback.json`），默认值即内置基线。
     */
    fun getVideoTab(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        host: String = GET_TAB_BASE,
        segment: String = GET_TAB_SEGMENT,
    ): JSONObject {
        require(id.matches(Regex("[0-9]{1,30}")))
        ensureActive()
        val url = "$host/${segment.trimStart('/')}".toHttpUrl().newBuilder().apply {
            currentAppQuery(device).forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("video_id", id)
            addQueryParameter("video_id_type", "0")
            addQueryParameter("device_level", "1")
            addQueryParameter("need_all_video_definition", "1")
            addQueryParameter("need_mp4_align", "0")
            addQueryParameter("use_os_player", "0")
            addQueryParameter("video_platform", "1024")
            addQueryParameter("source", "0")
            addQueryParameter("from_video_id", "")
            addQueryParameter("_rticket", now.toString())
        }.build()
        val signed = signer.sign(url.toString(), ByteArray(0), device)
        val request = Request.Builder().url(signed.url).get()
            .header("User-Agent", PlaybackConfigStore.current().uaGetTab)
            .header("Accept", "application/json; charset=utf-8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply {
                signed.headers.forEach { (k, v) -> header(k, v) }
                // 空字节只是签名输入：GET 没有请求体，这些头会暴露伪造特征。
                removeHeader("x-ss-stub")
                removeHeader("Content-Type")
                removeHeader("Content-Encoding")
            }
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            readModel(response, id, emptyDetail = "shape=get")
        }
    }

    /**
     * 两种形态共用的响应解码：HTTP 状态、2MiB 上限、空体→可自愈异常、业务码→风控异常、
     * 按**配置的路径表**取目标集的 video_model（支持对象/JSON 字符串/数组下标），
     * 并校验至少有 video_list 或 fallback_api。
     *
     * 2026-11-24（P1-a）：取值路径由 `configs/playback.json` 的 `response.pick` 提供，
     * 平台再改返回结构（如 `1004` → `1005`、`video_model` → `video_model_v2`）不用发版。
     */
    private fun readModel(
        response: okhttp3.Response,
        id: String,
        emptyDetail: String,
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
        if (!root.has("data")) throw IOException("播放信息为空")
        val model = PlaybackResponse.modelOf(root, id, PlaybackConfigStore.current())
            ?: throw IOException("播放信息未包含指定剧集")
        if ((model.optJSONObject("video_list")?.length() ?: 0) == 0 &&
            (model.optJSONArray("video_list")?.length() ?: 0) == 0 && !model.has("fallback_api"))
            throw IOException("播放信息没有可用资源")
        return JSONObject().put("video_model", model)
    }

    /**
     * POST 形态的 UA：配置给了就用配置（可热更新），否则用系统 `http.agent`。
     * 上游 ef328f3 已证明 UA 不敏感，所以默认走系统值，最不显眼。
     */
    private fun postUserAgent(): String {
        val configured = PlaybackConfigStore.current().uaPost
        return if (configured.isNotBlank()) configured
        else System.getProperty("http.agent", "okhttp/4.12.0")
    }

    /** POST 形态的默认域名（a 域）。 */
    const val MODEL_BASE = "https://api5-normal-sinfonlinea.fqnovel.com"

    /** preload 变体的官方域名（上游 ef328f3 现行端点）。 */
    const val PRELOAD_BASE = "https://reading.snssdk.com"

    /** GET 形态走 b 域（上游 getVideoTab 原值）；POST 形态走 a 域。 */
    const val GET_TAB_BASE = "https://api5-normal-sinfonlineb.fqnovel.com"

    /** POST player 变体路径。 */
    const val PLAYER_SEGMENT = "novel/player/multi_video_model/player/v1"

    /** 官方现行 preload 端点（体需 device_level=2 + dr_scene=preload）。 */
    const val PRELOAD_SEGMENT = "novel/player/multi_video_model/preload/v1"

    /** 平铺 v1 路径（7.4.5.32 起上游曾回到这里，573d75a）。 */
    const val MODEL_SEGMENT = "novel/player/multi_video_model/v1"

    const val GET_TAB_SEGMENT = "reading/bookapi/video_tab/multi_video_model/v1"

    /** 兼容旧调用方/测试的绝对路径常量。 */
    const val MODEL_PATH = "/$MODEL_SEGMENT"
    const val PLAYER_PATH = "/$PLAYER_SEGMENT"

    /** 旧 v5 POST 请求体的 UA（仅诊断/对照保留；正式请求用系统 http.agent）。 */
    const val PLAYBACK_UA =
        "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"

    /** GET video_tab 的 UA：73932 + 25053RT47C（上游 videoTabRequest 原值）。 */
    const val GET_TAB_UA =
        "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
}