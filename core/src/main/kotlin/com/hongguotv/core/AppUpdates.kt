// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdate(val versionCode: Long, val versionName: String, val minSdk: Int,
                     val tag: String, val fileName: String, val size: Long, val sha256: String, val notes: String) {
    /** Version-name comparison (1.0.10 > 1.0.9 > 1.0.4): versionCode now increases with
     *  each release (Dangbei launchers only refresh their icon cache when it changes),
     *  and the update channel still decides "newer" by name, not by code. */
    fun newerThan(installedName: String, sdk: Int) = versionName != installedName && minSdk <= sdk && versionNewer(versionName, installedName)
    val downloadUrl get() = "$RELEASES/download/$tag/$fileName"
    companion object {
        const val REPOSITORY = "fyworld/hongguoTV"
        const val RELEASES = "https://github.com/$REPOSITORY/releases"
        const val MAX_APK_BYTES = 80L * 1024 * 1024
        /** Semantic comparison of dotted numeric versions (1.0.10 > 1.0.9). */
        fun versionNewer(a: String, b: String): Boolean {
            val left = a.split('.').map { it.toIntOrNull() ?: 0 }
            val right = b.split('.').map { it.toIntOrNull() ?: 0 }
            val n = maxOf(left.size, right.size)
            for (i in 0 until n) {
                val l = left.getOrNull(i) ?: 0; val r = right.getOrNull(i) ?: 0
                if (l != r) return l > r
            }
            return false
        }
        fun parse(json: String): AppUpdate {
            val o = JSONObject(json)
            require(o.getInt("schema") == 1 && o.getString("packageName") == "com.hongguotv.nativeapp") { "更新信息不匹配" }
            val apk = o.getJSONObject("apk")
            val update = AppUpdate(o.getLong("versionCode"), o.getString("versionName"), o.getInt("minSdk"),
                o.getString("tag"), apk.getString("name"), apk.getLong("size"), apk.getString("sha256").lowercase(), o.optString("notes").take(1500))
            require(update.versionCode in 1..Int.MAX_VALUE.toLong() && update.minSdk in 24..100)
            require(update.versionName.matches(Regex("[0-9][0-9A-Za-z.+-]{0,63}")))
            require(update.tag.matches(Regex("kotlin-v[0-9][0-9A-Za-z.+-]{0,80}")))
            // v1.0.0+ ships android7 builds (minSdk 24); the 0.9.x channel used android8.
            require(update.fileName.matches(Regex("hongguotv-kotlin-[0-9][0-9A-Za-z.+-]{0,80}-android[78]\\.apk")))
            require(update.size in 1..MAX_APK_BYTES && update.sha256.matches(Regex("[0-9a-f]{64}")))
            return update
        }
    }
}

/**
 * Update channel resolution for mainland-China networks where GitHub is often unreachable.
 * Order: GitHub direct -> mainland mirrors (verified 2026-10-30: all four serve the real
 * update.json AND full APK bytes with Range support). Each mirror proxies the canonical
 * GitHub URL, so the update.json content and APK bytes stay identical; the SHA-256
 * verification in UpdateFiles.receive still guards against tampering or partial downloads.
 *
 * Some mirrors reject plain non-browser user agents (verified: ghfast.top 403s a bare
 * "HongguoTV-Updater" UA), so every update request carries a browser UA.
 *
 * Timeout policy: GitHub direct is "best effort, fail fast". Mainland networks rarely
 * refuse the connection outright - they let it hang until a timeout fires, which made
 * every update check wait the full 25s call budget before reaching a mirror. The direct
 * channel therefore gets a short leash (tiny JSON: 8s; APK: floor + size at a minimal
 * acceptable speed) and hands over quickly, while the verified mirrors keep the
 * generous client timeouts.
 */
object UpdateMirrors {
    val prefixes: List<String> = listOf(
        "https://github.com",
        "https://ghfast.top/https://github.com",
        "https://gh.llkk.cc/https://github.com",
        "https://gh-proxy.com/https://github.com",
        "https://ghproxy.net/https://github.com"
    )
    /** Each prefix proxies the canonical GitHub URL: strip GitHub's origin and keep the
     *  path, so "https://github.com" + path and "https://gh-proxy.com/https://github.com"
     *  + path both resolve to a single hostname (the old removePrefix("https://") built
     *  doubled-host URLs like github.com/github.com/... which 404 on every channel). */
    fun downloadUrl(update: AppUpdate, prefix: String): String = prefix + update.downloadUrl.removePrefix("https://github.com")

    const val DIRECT_PREFIX = "https://github.com"
    /** update.json is a few hundred bytes: a reachable GitHub answers well within 8s. */
    const val DIRECT_CHECK_TIMEOUT_MS = 8_000L
    /** APK cap = floor + size at a minimal acceptable direct speed (10 MB -> ~163s),
     *  so a throttled-to-death direct download hands over to a mirror instead of
     *  crawling through the whole 5-minute client budget. */
    const val DIRECT_DOWNLOAD_FLOOR_MS = 60_000L
    const val DIRECT_DOWNLOAD_MIN_BYTES_PER_SEC = 100L * 1024
    fun directDownloadTimeoutMs(size: Long): Long =
        DIRECT_DOWNLOAD_FLOOR_MS + 1000L * size / DIRECT_DOWNLOAD_MIN_BYTES_PER_SEC
}

/** Streaming validation also protects a cached APK from partial downloads and stale metadata. */
object UpdateFiles {
    fun verify(file: File, update: AppUpdate): Boolean {
        if (!file.isFile || file.length() != update.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(32768); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) } == update.sha256
    }
    fun receive(input: InputStream, destination: File, update: AppUpdate, progress: (Int) -> Unit = {}) {
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(32768); var total = 0L; var last = -1
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    val n = input.read(buffer); if (n < 0) break
                    total += n
                    if (total > update.size) throw IOException("安装包长度异常")
                    output.write(buffer, 0, n)
                    val percent = (total * 100 / update.size).toInt()
                    if (percent != last) { last = percent; progress(percent) }
                }
                output.fd.sync()
            }
            if (!verify(partial, update)) throw IOException("安装包校验未通过，请重新下载")
            try {
                java.nio.file.Files.move(partial.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(partial.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { partial.delete() }
    }
}

class AppUpdateRepository : java.io.Closeable {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).addNetworkInterceptor { chain ->
            val url = chain.request().url
            // Follow GitHub's CDN redirects, never cleartext or arbitrary manifest-provided hosts.
            // Mirror hosts are fixed constants, not derived from update.json, so they cannot be hijacked.
            if (url.scheme != "https" || url.host !in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "ghfast.top", "gh.llkk.cc", "gh-proxy.com", "ghproxy.net"))
                throw IOException("更新下载地址不受支持")
            chain.proceed(chain.request())
        }.build()
    /** Browser UA: several mirrors 403 plain client UAs (ghfast.top verified). */
    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 7) HongguoTV-Updater")
        .header("Cache-Control", "no-cache").build()
    fun latest(): AppUpdate {
        // Try GitHub first, then mainland-friendly mirrors. First parseable response wins;
        // a mirror serving a tampered file would fail AppUpdate.parse validation anyway.
        var lastError: IOException? = null
        for (prefix in UpdateMirrors.prefixes) {
            try {
                val call = http.newCall(request("$prefix/${AppUpdate.REPOSITORY}/releases/latest/download/update.json"))
                // GitHub direct: fail fast (mainland networks hang instead of refusing,
                // so a long timeout just delays the handover to a mirror). Mirrors keep
                // the generous 25s budget.
                call.timeout().timeout(
                    if (prefix == UpdateMirrors.DIRECT_PREFIX) UpdateMirrors.DIRECT_CHECK_TIMEOUT_MS
                    else 25L, TimeUnit.SECONDS)
                return call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException(if (response.code == 404) "尚无可用的自动更新版本" else "无法检查更新，请稍后重试")
                    val bytes = response.body?.byteStream()?.use { input ->
                        val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                        while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > 65536) throw IOException("更新信息过大"); out.write(buffer, 0, n) }
                        out.toByteArray()
                    } ?: throw IOException("更新信息为空")
                    try { AppUpdate.parse(bytes.toString(Charsets.UTF_8)) } catch (_: Exception) { throw IOException("更新信息格式不正确") }
                }
            } catch (e: IOException) {
                if (e.message == "尚无可用的自动更新版本" || e.message == "更新信息格式不正确") throw e
                lastError = e
            }
        }
        throw lastError ?: IOException("无法检查更新，请稍后重试")
    }
    fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        if (UpdateFiles.verify(file, update)) return
        var lastError: IOException? = null
        for (prefix in UpdateMirrors.prefixes) {
            try {
                val call = http.newCall(request(UpdateMirrors.downloadUrl(update, prefix)))
                // GitHub direct: bounded by floor + size at a minimal acceptable speed, so
                // a throttled direct link hands over to a mirror instead of crawling
                // through the 5-minute client budget. Mirrors keep the client default.
                if (prefix == UpdateMirrors.DIRECT_PREFIX)
                    call.timeout().timeout(UpdateMirrors.directDownloadTimeoutMs(update.size), TimeUnit.MILLISECONDS)
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("安装包下载失败，请稍后重试")
                    val body = response.body ?: throw IOException("安装包为空")
                    if (body.contentLength() >= 0 && body.contentLength() != update.size) throw IOException("安装包长度异常")
                    body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
                }
                return
            } catch (e: IOException) {
                if (file.isFile) file.delete()
                lastError = e
            }
        }
        throw lastError ?: IOException("安装包下载失败，请稍后重试")
    }
    fun cancel() = http.dispatcher.cancelAll()
    override fun close() { cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
