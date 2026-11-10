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

// cloudResponse() now lives in CloudAccount.kt (upstream 0.1.26 shape): one shared
// bounded-body reader for device-register AND account endpoints.

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

    private var cdid = UUID.randomUUID().toString()
    private var ready: Profile? = null

    @Synchronized
    internal fun profile(http: OkHttpClient): Profile {
        ready?.let {
            return it
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return toProfile(registerDevice(http)).also { ready = it }
    }

    /**
     * 空响应身份屏蔽自愈（2026-11-01 事件实测：屏蔽按设备身份生效，换域名与新连接无法逃逸，
     * 全新身份即时恢复）：立即重新注册一次匿名设备。成功则替换内存身份（cdid 一并换新）并返回
     * true；注册失败、中断或结果非法保留原身份返回 false。沿用同一注册流程，不做指纹伪造；
     * 频次由调用方约束（同一连续失败期至多一次）。
     */
    @Synchronized
    internal fun rotate(http: OkHttpClient): Boolean {
        if (Thread.currentThread().isInterrupted) return false
        val device =
            try {
                register(http) ?: return false
            } catch (error: InterruptedIOException) {
                Thread.currentThread().interrupt()
                return false
            } catch (error: IOException) {
                return false
            }
        if (!validDevice(device)) return false
        cdid = UUID.randomUUID().toString()
        ready = toProfile(device)
        return true
    }

    private fun registerDevice(http: OkHttpClient): DeviceRegister.Registered {
        val device =
            try {
                register(http) ?: throw PlaybackDeviceRegistrationException()
            } catch (error: InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                throw PlaybackDeviceRegistrationException(error)
            }
        if (!validDevice(device)) throw PlaybackDeviceRegistrationException()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return device
    }

    private fun validDevice(device: DeviceRegister.Registered) =
        validId.matches(device.deviceId) && validId.matches(device.installId)

    private fun toProfile(device: DeviceRegister.Registered): Profile =
        Profile(
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
}

private val validId = Regex("[1-9][0-9]{0,29}")
