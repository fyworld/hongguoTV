// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 播放配置的运行时容器：内置基线 → 本地缓存 → 远端热更新。
 *
 * 设计要点（对应 2026-11 反复断播的教训）：
 *  1. **永不裸奔**：任何时候 [current] 都返回一份可用配置；远端失败/校验失败/版本不兼容
 *     一律回退到上一次有效配置（首次即 [PlaybackConfig.defaults] 内置基线）。
 *  2. **只接受更高版本**：`configVersion` 必须 >= 当前，避免配置回滚或乱序投递把线上打回旧值。
 *  3. **空响应自愈**：播放返回空响应时调用 [maybeRefreshAfterEmpty]，强制拉一次配置
 *     （限频 5 分钟、总超时 ~8 秒），拿到新配置后下一次播放立即生效——不需要发版。
 *  4. **双源**：GitHub raw + jsDelivr，任一可用即可；都失败就静默保持现状。
 *
 * 缓存文件由 app 层通过 [bindCache] 注入（core 不依赖 Android Context）。
 */
object PlaybackConfigStore {
    private val log = Logger.getLogger("com.hongguotv.core.playback")

    /** 远端配置源（同一份内容，双 CDN）。放在仓库 main 分支，push 即生效。 */
    internal val SOURCES = listOf(
        "https://raw.githubusercontent.com/fyworld/hongguoTV/main/configs/playback.json",
        "https://cdn.jsdelivr.net/gh/fyworld/hongguoTV@main/configs/playback.json",
    )

    /** 常规检查间隔。 */
    private const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** 自愈（空响应触发）的最小间隔，避免每次起播都去拉配置。 */
    private const val HEAL_INTERVAL_MS = 5 * 60 * 1000L

    @Volatile private var active: PlaybackConfig = PlaybackConfig.defaults()
    @Volatile private var activeRaw: String? = null
    @Volatile private var appVersionCode: Int = 0
    @Volatile private var lastAttemptAt: Long = 0L

    private var cacheFile: File? = null
    private val lock = Any()

    /** 当前生效配置（永远非空）。 */
    fun current(): PlaybackConfig = active

    /**
     * 是否允许访问远端配置源。app 启动时置 true；单元测试保持 false，
     * 这样核心测试套件永远不打外网、也不会被后台线程干扰。
     */
    @Volatile var remoteEnabled: Boolean = false

    /** app 层启动时注入自身 versionCode，用于 `minAppVersion` 校验。 */
    fun setAppVersion(code: Int) {
        appVersionCode = code
    }

    /** 测试 / 诊断用：当前配置来自哪里（builtin / cache / remote:<url>）。 */
    @Volatile var source: String = "builtin"
        private set

    /**
     * 绑定缓存文件并尝试载入。载入失败（文件不存在/被改坏）保持内置基线。
     */
    fun bindCache(file: File?) {
        cacheFile = file
        if (file == null || !file.isFile) return
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return
        runCatching { PlaybackConfig.parse(raw, appVersionCode) }
            .onSuccess {
                active = it
                activeRaw = raw
                source = "cache"
            }
            .onFailure { log.warning("cached playback config rejected: ${it.message}") }
    }

    /**
     * 安装一份配置文本：校验 → 版本单调 → 切换 → 落盘。
     * @return 是否被接受
     */
    internal fun install(raw: String, from: String, now: Long = System.currentTimeMillis()): Boolean {
        val parsed = runCatching { PlaybackConfig.parse(raw, appVersionCode) }
            .getOrElse {
                log.warning("playback config rejected ($from): ${it.message}")
                return false
            }
        synchronized(lock) {
            if (parsed.configVersion < active.configVersion) {
                log.warning("playback config downgrade ignored: ${parsed.configVersion} < ${active.configVersion}")
                return false
            }
            active = parsed
            activeRaw = raw
            source = from
        }
        cacheFile?.let { file ->
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(raw, Charsets.UTF_8)
            }.onFailure { log.warning("playback config cache write failed: ${it.message}") }
        }
        log.info("playback config active: v${parsed.configVersion} from $from (${parsed.notes})")
        return true
    }

    /**
     * 拉取远端配置。默认 24h 节流；[force]（空响应自愈）时 5 分钟节流。
     * 网络/HTTP/校验失败全部返回 false，不抛。
     */
    fun refresh(http: OkHttpClient, force: Boolean = false, now: Long = System.currentTimeMillis()): Boolean {
        val interval = if (force) HEAL_INTERVAL_MS else REFRESH_INTERVAL_MS
        synchronized(lock) {
            if (lastAttemptAt > 0 && now - lastAttemptAt < interval) return false
            lastAttemptAt = now
        }
        val client = http.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        for (url in SOURCES) {
            try {
                val request = Request.Builder().url(url)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .build()
                val raw = RequestScope.execute(client.newCall(request)) { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body?.string() ?: ""
                }
                if (raw.isBlank()) continue
                if (install(raw, "remote:$url", now)) return true
            } catch (e: IOException) {
                log.fine("playback config fetch failed ($url): ${e.message}")
            } catch (e: RuntimeException) {
                log.fine("playback config fetch failed ($url): ${e.message}")
            }
        }
        return false
    }

    /**
     * 播放返回空响应时的自愈钩子：**后台**强制拉一次配置，供下一次尝试使用。
     *
     * 不阻塞当前失败返回（否则会把 8 秒预算再拖长 8 秒）；内部限频 5 分钟，
     * 失败静默——自愈是尽力而为，绝不能拖垮本来能成功的重试。
     */
    fun healAfterEmptyAsync(http: OkHttpClient) {
        if (!remoteEnabled) return
        Thread({ runCatching { refresh(http, force = true) } }, "playback-config-heal").apply {
            isDaemon = true
            start()
        }
    }

    /** 测试钩子：回到内置基线（不删缓存文件）。 */
    internal fun reset() {
        synchronized(lock) {
            active = PlaybackConfig.defaults()
            activeRaw = null
            source = "builtin"
            lastAttemptAt = 0L
        }
    }

    /** 当前生效配置的原始 JSON（诊断用；内置基线时为 null）。 */
    internal fun raw(): String? = activeRaw
}
