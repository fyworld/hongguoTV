// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import org.junit.Assume
import org.junit.Test

/**
 * v5 播放通道参数矩阵诊断（2026-11-20，上游 0.1.33 断播事件排查用）。
 *
 * 背景：11-20 起平台对「任意身份 × 任意通道」返回 200 + 0 字节。上游 573d75a 的结论是
 * 路径回平铺 `v1` + 版本参数升 74532，但我们实测同样的组合仍是 0 字节。本探针把
 * {路径} × {版本参数} × {dr_scene 有无} 全组合各打一次，把每一行的真实状态打出来，
 * 用于判定「是参数问题还是签名/身份问题」。
 *
 * 手动运行（会打真实接口）：
 *   PROBE_LIVE=1 gradlew :core:test --tests "com.hongguotv.core.V5MatrixProbeTest"
 */
class V5MatrixProbeTest {
    @Test
    fun matrix() {
        Assume.assumeTrue(
            "参数矩阵探针：需 PROBE_LIVE=1（默认套件不打真实接口）",
            System.getenv("PROBE_LIVE") == "1")

        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val videoId = "7677804745699888153" // 糯糯下山 ep1
        val identity = PlaybackDeviceSession().profile(http)
        val paths = listOf("/novel/player/multi_video_model/v1", "/novel/player/multi_video_model/player/v1")
        val versions = listOf("74532" to "7.4.5.32", "74332" to "7.4.3.32")
        val scenes = listOf(null, "player")

        for (path in paths) for ((code, name) in versions) for (scene in scenes) {
            val payload = """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$videoId"]}${if (scene != null) ",\"dr_scene\":\"$scene\"" else ""}}"""
            val gz = java.io.ByteArrayOutputStream()
            GZIPOutputStream(gz).use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val body = gz.toByteArray()
            val now = System.currentTimeMillis()
            val query = linkedMapOf(
                "aid" to "8662", "app_name" to "novelread",
                "version_code" to code, "version_name" to name,
                "channel" to "vivo_8662_64", "device_platform" to "android",
                "device_type" to "PGT-AN10", "os_version" to "12",
                "update_version_code" to code,
                "iid" to identity.values.getValue("iid"),
                "cdid" to identity.values.getValue("cdid"),
                "device_id" to identity.values.getValue("device_id"),
                "_rticket" to now.toString())
            val signed = MobilePlayback.signer.sign(
                "${MobilePlayback.MODEL_BASE}$path?" + Signer.encode(query), body, identity.values)
            val request = Request.Builder().url(signed.url)
                .header("User-Agent", MobilePlayback.PLAYBACK_UA)
                .header("Accept", "application/json")
                .header("Content-Encoding", "gzip")
                .apply { signed.headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val label = "path=$path ver=$code scene=${scene ?: "-"}"
            runCatching {
                http.newCall(request).execute().use { r ->
                    val bytes = r.body?.bytes() ?: ByteArray(0)
                    println("MATRIX $label -> HTTP ${r.code} bytes=${bytes.size}")
                    if (bytes.isNotEmpty()) println("MATRIX head: ${String(bytes).take(160).replace("\n", " ")}")
                }
            }.onFailure { println("MATRIX $label -> EX ${it.javaClass.simpleName} ${it.message}") }
        }
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}