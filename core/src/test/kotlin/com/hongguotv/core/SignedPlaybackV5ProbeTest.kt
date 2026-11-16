package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * v5 播放通道线上探针（2026-11-16 事件 / 上游 0.1.32 d338994）。
 *
 * 同一匿名身份、同一集号下对照两条链路：
 *  - 旧链路 video_tab GET（六头签名）—— 11-16 起全部返回 200 + 0 字节；
 *  - v5 链路 /novel/player/multi_video_model/player/v1（gzip + x-medusa v5）。
 * 只有 v5 返回正文才算签名协议修好。
 *
 * 手动运行（会打真实接口）：
 *   gradlew :core:test --tests "com.hongguotv.core.SignedPlaybackV5ProbeTest"
 */
class SignedPlaybackV5ProbeTest {
    @org.junit.Test
    fun probe() {
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val videoId = "7677804745699888153" // 糯糯下山 ep1
        var best = 0
        var lastCode = 0

        for (round in 1..3) {
            val identity = PlaybackDeviceSession().profile(http)
            // 旧链路对照（不签名校验细节，只看字节数）
            val transport = AppApiTransport(http)
            val extra = mapOf(
                "video_id" to videoId, "video_id_type" to "0", "device_level" to "1",
                "need_all_video_definition" to "1", "need_mp4_align" to "0",
                "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
                "from_video_id" to "")
            runCatching {
                val signed = transport.sign(
                    "${AppApiTransport.APP_BASE}${MultiVideoModelStreamSource.TAB_PATH}",
                    identity.values + extra, null, MobilePlayback.PLAYBACK_UA)
                val request = Request.Builder().url(signed.url)
                    .apply {
                        signed.headers.forEach { (k, v) -> header(k, v) }
                        if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
                    }.get().build()
                http.newCall(request).execute().use { r ->
                    val bytes = r.body?.bytes() ?: ByteArray(0)
                    println("LEGACY TAB round=$round HTTP ${r.code} bytes=${bytes.size}")
                }
            }

            // v5 链路：手工重放一次，把 HTTP 状态和正文大小打出来（MobilePlayback 会吞掉细节）
            val payload = """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$videoId"]},"dr_scene":"player"}"""
            val raw = java.io.ByteArrayOutputStream()
            GZIPOutputStream(raw).use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val body = raw.toByteArray()
            val now = System.currentTimeMillis()
            val query = linkedMapOf(
                "aid" to "8662", "app_name" to "novelread",
                "version_code" to "74332", "version_name" to "7.4.3.32",
                "channel" to "vivo_8662_64", "device_platform" to "android",
                "device_type" to "PGT-AN10", "os_version" to "12",
                "update_version_code" to "74332",
                "iid" to identity.values.getValue("iid"),
                "cdid" to identity.values.getValue("cdid"),
                "device_id" to identity.values.getValue("device_id"),
                "_rticket" to now.toString())
            val signed = MobilePlayback.signer.sign(
                "${MobilePlayback.MODEL_BASE}${MobilePlayback.MODEL_PATH}?" +
                    Signer.encode(query), body, identity.values)
            println("V5 headers: ${signed.headers.keys}")
            val request = Request.Builder().url(signed.url)
                .header("User-Agent", MobilePlayback.PLAYBACK_UA)
                .header("Accept", "application/json")
                .header("Content-Encoding", "gzip")
                .apply { signed.headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                println("V5 MODEL round=$round HTTP ${r.code} bytes=${bytes.size}")
                if (bytes.size > best) {
                    best = bytes.size
                    val text = String(bytes, Charsets.UTF_8)
                    println("V5 head: ${text.take(240).replace("\n", " ")}")
                    val code = runCatching { org.json.JSONObject(text).optInt("code", -1) }.getOrDefault(-1)
                    lastCode = code
                    println("V5 code=$code")
                }
            }
            // 全链路（含精确集号解析与档位过滤）
            val model = runCatching {
                MobilePlayback.model(videoId, identity.values, http, MobilePlayback.signer, System.currentTimeMillis())
            }
            model.onSuccess {
                val vm = it.getJSONObject("video_model")
                val list = vm.optJSONObject("video_list")
                println("V5 MOBILE MODEL ok: tiers=${list?.length() ?: 0} fallback=${vm.has("fallback_api")}")
                return
            }.onFailure { println("V5 MOBILE MODEL failed: ${it.javaClass.simpleName} ${it.message}") }
        }
        org.junit.Assert.assertTrue(
            "v5 model still empty after 3 identities (last code=$lastCode, best=${best}B)", best > 0)
    }
}
