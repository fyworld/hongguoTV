// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume
import org.junit.Test

/**
 * 身份层诊断（2026-11-20 断播事件）：确认匿名注册是否还拿得到 device_id/install_id。
 * 播放接口的空响应有两种可能——身份没注册成功，或签名/参数被拒。这个探针只回答第一个。
 *
 *   PROBE_LIVE=1 gradlew :core:test --tests "com.hongguotv.core.IdentitySmokeProbeTest"
 */
class IdentitySmokeProbeTest {
    @Test
    fun registrationStillWorks() {
        Assume.assumeTrue(
            "身份探针：需 PROBE_LIVE=1（默认套件不打真实接口）",
            System.getenv("PROBE_LIVE") == "1")

        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        try {
            for (round in 1..2) {
                val body = DeviceRegister.payload(VendorConstants.device)
                val builder = Request.Builder().url(DeviceRegister.url())
                DeviceRegister.headers(body, VendorConstants.device).forEach { (k, v) -> builder.header(k, v) }
                val request = builder.post(body.toRequestBody("application/octet-stream".toMediaType())).build()
                val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
                runCatching {
                    transport.newCall(request).execute().use { r ->
                        val text = r.body?.string().orEmpty()
                        val parsed = DeviceRegister.parse(text, r.headers("Set-Cookie"))
                        println("IDENT round=$round HTTP ${r.code} bytes=${text.length} " +
                            "device_id=${parsed?.deviceId} iid=${parsed?.installId} new=${parsed?.newUser}")
                        if (parsed == null) println("IDENT body: ${text.take(300)}")
                    }
                }.onFailure { println("IDENT round=$round EX ${it.javaClass.simpleName} ${it.message}") }
            }
            // 顺带看一眼目录 feed 是否正常（同一身份层，能区分「全站屏蔽」与「仅播放屏蔽」）
            runCatching {
                val repo = ContentRepository(http)
                val feed = repo.home()
                println("FEED home items=${feed.items.size} warn=${feed.warning}")
            }.onFailure { println("FEED home FAILED ${it.javaClass.simpleName} ${it.message}") }
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}