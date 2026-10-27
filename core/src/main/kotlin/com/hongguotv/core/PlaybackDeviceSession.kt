// Adapted 2026-10-27 from tsukiyo-zero/hongguoTV kotlin-tv (GPL-3.0-only).
// SPDX-License-Identifier: GPL-3.0-only.
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PlaybackDeviceRegistrationException(cause: Throwable? = null) :
    IOException("播放设备注册失败，请稍后重试", cause)

/** Read a device-register response body (bounded, UTF-8, must be non-blank). */
internal fun cloudResponse(response: okhttp3.Response): String {
    if (!response.isSuccessful) throw IOException("设备注册请求失败（HTTP ${response.code}）")
    val body = response.body ?: throw IOException("设备注册响应为空")
    val limit = 2 * 1024 * 1024L
    if (body.contentLength() > limit) throw IOException("设备注册响应过大")
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    val input = body.byteStream()
    while (out.size() <= limit) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    val bytes = out.toByteArray()
    if (bytes.size > limit) throw IOException("设备注册响应过大")
    val text = String(bytes, Charsets.UTF_8)
    if (text.isBlank()) throw IOException("设备注册返回空响应，请稍后重试")
    return text
}

/** Anonymous playback identity. Register once, then keep the same identity across repositories. */
class PlaybackDeviceSession(
    private val register: (OkHttpClient) -> DeviceRegister.Registered? = { http ->
        val body = DeviceRegister.payload(VendorConstants.device)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, VendorConstants.device).forEach { (key, value) ->
            request.header(key, value)
        }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        // Registration has no account cookies. Cancellation belongs to the requesting work scope.
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        RequestScope.execute(transport.newCall(request.build())) { response ->
            DeviceRegister.parse(cloudResponse(response), response.headers("Set-Cookie"))
        }
    }
) {
    companion object {
        val shared = PlaybackDeviceSession()
    }

    internal data class Profile(val values: Map<String, String>, val cookie: String)

    private val cdid = UUID.randomUUID().toString()
    private var ready: Profile? = null

    @Synchronized
    internal fun profile(http: OkHttpClient): Profile {
        ready?.let {
            return it
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        val device =
            try {
                register(http) ?: throw PlaybackDeviceRegistrationException()
            } catch (error: InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                throw PlaybackDeviceRegistrationException(error)
            }
        val validId = Regex("[1-9][0-9]{0,29}")
        if (!validId.matches(device.deviceId) || !validId.matches(device.installId))
            throw PlaybackDeviceRegistrationException()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return Profile(
                VendorConstants.device +
                    mapOf(
                        "device_id" to device.deviceId,
                        "iid" to device.installId,
                        "cdid" to cdid,
                    ),
                device.cookies
                    .filterKeys {
                        it in setOf("install_id", "ttreq", "store-region", "store-region-src")
                    }
                    .entries
                    .joinToString("; ") { (name, value) -> "$name=$value" },
            )
            .also { ready = it }
    }
}
