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

    /**
     * 远端配置源，按「国内可达性」排序，逐个尝试直到有一个成功。
     *
     * 2026-11-24 实测（本机全部 200/1980B）：raw、jsDelivr 四个 CNAME、ghproxy 三个中转都通。
     * 但国内网络对 `raw.githubusercontent.com` 大多不通、`cdn.jsdelivr.net` 也常被干扰，
     * 所以顺序上把 jsDelivr 的国内 CNAME（testingcf 走腾讯云）和 GitHub 加速中转放前面，
     * raw 作为最后兜底。**多源不是银弹**：真正常年稳定的是自己的对象存储（COS/OSS），
     * 有账号时可以在这里再加一条，优先级放到最前。
     */
    internal val SOURCES = listOf(
        // GitHub 加速中转：实时回源（无 CDN 缓存），配置更新立刻可见。放最前，
        // 因为「平台换了参数 → 我们希望几小时内生效」，jsDelivr 的分支缓存会拖到 12h。
        "https://ghfast.top/https://raw.githubusercontent.com/fyworld/hongguoTV/main/configs/playback.json",
        "https://gh-proxy.com/https://raw.githubusercontent.com/fyworld/hongguoTV/main/configs/playback.json",
        "https://ghproxy.net/https://raw.githubusercontent.com/fyworld/hongguoTV/main/configs/playback.json",
        // jsDelivr：最稳定，但 `@main` 是分支引用（最长 12h 缓存），只作兜底
        "https://gcore.jsdelivr.net/gh/fyworld/hongguoTV@main/configs/playback.json",
        "https://fastly.jsdelivr.net/gh/fyworld/hongguoTV@main/configs/playback.json",
        "https://testingcf.jsdelivr.net/gh/fyworld/hongguoTV@main/configs/playback.json",
        "https://cdn.jsdelivr.net/gh/fyworld/hongguoTV@main/configs/playback.json",
        // 直连 raw：海外/已代理网络最快，国内通常最后才用到
        "https://raw.githubusercontent.com/fyworld/hongguoTV/main/configs/playback.json",
    )

    /** 常规检查间隔（成功后）。 */
    private const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** 失败退避：比 24h 短得多，镜像临时不可用/网络抖动后能较快重试。 */
    private const val FAILURE_BACKOFF_MS = 30 * 60 * 1000L

    /** 自愈（空响应触发）的最小间隔，避免每次起播都去拉配置。 */
    private const val HEAL_INTERVAL_MS = 5 * 60 * 1000L

    /** 一次 refresh 的总预算：多源顺序尝试时不能无限叠加（每个源都要 3~5s）。 */
    private const val TOTAL_BUDGET_MS = 10_000L

    @Volatile private var active: PlaybackConfig = PlaybackConfig.defaults()
    @Volatile private var activeRaw: String? = null
    @Volatile private var appVersionCode: Int = 0
    @Volatile private var lastAttemptAt: Long = 0L

    /** 下一次允许常规拉取的时间：成功 +24h、失败 +30min。 */
    @Volatile private var nextRetryAt: Long = 0L

    private var cacheFile: File? = null
    private val lock = Any()

    /** 当前生效配置（永远非空）。 */
    fun current(): PlaybackConfig = active

    /**
     * 是否允许访问远端配置源。app 启动时置 true；单元测试保持 false，
     * 这样核心测试套件永远不打外网、也不会被后台线程干扰。
     */
    @Volatile var remoteEnabled: Boolean = false

    /**
     * 配置自检器（app 层注入）：用**候选配置**真打一次接口，返回是否可用。
     *
     * 注入后，远端配置必须自检通过才会上线——推错配置最坏等于没更新。
     * 测试里置 null 即跳过自检（默认行为）。
     */
    @Volatile var verifier: ((PlaybackConfig) -> Boolean)? = null

    /**
     * 启用内置自检（app 启动时调用一次）：用共享身份 + 探针集，按候选配置真打一次接口。
     * 自检失败 → 该配置不上线，保持旧配置（日志 `playback config self-check failed`）。
     */
    fun useBuiltInSelfCheck(http: OkHttpClient) {
        verifier = { _ ->
            runCatching {
                val client = http.newBuilder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                    .build()
                val device = PlaybackDeviceSession.shared.profile(client).values
                PlaybackConfigSelfCheck.run(client, device)
            }.getOrDefault(false)
        }
    }

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
     * 安装一份配置文本：校验 → 自检（可选）→ 版本单调 → 切换 → 落盘。
     * @param verify 远端安装时传 true：先按候选配置真打一次接口，通过才上线
     * @return 是否被接受
     */
    internal fun install(
        raw: String,
        from: String,
        now: Long = System.currentTimeMillis(),
        verify: Boolean = false,
    ): Boolean {
        val parsed = runCatching { PlaybackConfig.parse(raw, appVersionCode) }
            .getOrElse {
                log.warning("playback config rejected ($from): ${it.message}")
                return false
            }
        val check = if (verify) verifier else null
        synchronized(lock) {
            val previous = active
            if (parsed.configVersion < previous.configVersion) {
                log.warning("playback config downgrade ignored: ${parsed.configVersion} < ${previous.configVersion}")
                return false
            }
            if (check != null) {
                // 候选配置临时生效，自检才能按新画像/新路径发请求；失败立刻回滚。
                // 窗口内并发的播放请求会用到候选配置——若它确实是坏的，那些请求本来也会失败。
                active = parsed
                val ok = runCatching { check(parsed) }.getOrDefault(false)
                if (!ok) {
                    active = previous
                    log.warning("playback config self-check failed: v${parsed.configVersion} from $from")
                    return false
                }
            }
            val changed = parsed.configVersion != previous.configVersion
            active = parsed
            activeRaw = raw
            source = from
            // 配置换了，按旧配置学到的「上次成功形态」很可能已经失效：清空，
            // 避免下一次起播先拿旧形态空转两次（那两次也是配额）。
            if (changed) PlaybackShapeMemory.onConfigChanged()
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
     * 拉取远端配置。成功后 24h 节流、失败后 30 分钟退避；[force]（空响应自愈）
     * 只受 5 分钟最小间隔约束，无视退避。
     *
     * 多源顺序尝试，整体预算 [TOTAL_BUDGET_MS]（单源 2s 连接 / 4s 读取），
     * 避免镜像都不通时把起播拖成半分钟。
     */
    fun refresh(http: OkHttpClient, force: Boolean = false, now: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            // 自愈：只保证不刷得太勤
            if (force && lastAttemptAt > 0 && now - lastAttemptAt < HEAL_INTERVAL_MS) return false
            // 常规：成功后 24h、失败后 30 分钟才允许下一次
            if (!force && nextRetryAt > 0 && now < nextRetryAt) return false
            lastAttemptAt = now
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_BUDGET_MS)
        for (url in SOURCES) {
            val leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (leftMs < 500) break
            val client = http.newBuilder()
                .connectTimeout(minOf(leftMs, 2_000L), TimeUnit.MILLISECONDS)
                .readTimeout(minOf(leftMs, 4_000L), TimeUnit.MILLISECONDS)
                .callTimeout(minOf(leftMs, 5_000L), TimeUnit.MILLISECONDS)
                .build()
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
                if (install(raw, "remote:$url", now, verify = true)) {
                    nextRetryAt = now + REFRESH_INTERVAL_MS
                    return true
                }
            } catch (e: IOException) {
                log.fine("playback config fetch failed ($url): ${e.message}")
            } catch (e: RuntimeException) {
                log.fine("playback config fetch failed ($url): ${e.message}")
            }
        }
        nextRetryAt = now + FAILURE_BACKOFF_MS
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
            nextRetryAt = 0L
            verifier = null
        }
    }

    /** 当前生效配置的原始 JSON（诊断用；内置基线时为 null）。 */
    internal fun raw(): String? = activeRaw
}
