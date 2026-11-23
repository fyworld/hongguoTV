// SPDX-License-Identifier: GPL-3.0-only
// Adapted from tsukiyo-zero/hongguoTV kotlin-tv 41ba81c (PlaybackShapes.kt); GPL-3.0-only.
// See kotlin-tv/NOTICE.md.
package com.hongguotv.core

/**
 * 播放模型的请求路径。
 *
 * 平台在 2026-11 反复切换：11-20 早只放 v1、11-20 晚只放 GET、11-22 恢复 player、
 * 11-23 再次只放 GET；同日晚上解禁但改按「请求画像」校验（见 [PlaybackSigner]）。
 *
 * [host] / [drScene] / [deviceLevel] 是路径自带的请求体差异：
 * preload 变体是官方现行端点（`reading.snssdk.com`，体 device_level=2 + dr_scene=preload）。
 */
enum class PlaybackPath(
    val label: String,
    val segment: String,
    val host: String = MobilePlayback.MODEL_BASE,
    val drScene: String? = null,
    val deviceLevel: Int = 1,
    val method: String = "POST",
) {
    GET_VIDEO_TAB(
        "get", "reading/bookapi/video_tab/multi_video_model/v1",
        host = MobilePlayback.GET_TAB_BASE, method = "GET",
    ),
    POST_PLAYER("player", "novel/player/multi_video_model/player/v1"),
    POST_PRELOAD(
        "preload", "novel/player/multi_video_model/preload/v1",
        host = MobilePlayback.PRELOAD_BASE, drScene = "preload", deviceLevel = 2,
    ),
    POST_V1("v1", "novel/player/multi_video_model/v1"),
    ;

    companion object {
        fun byLabel(label: String?): PlaybackPath? = entries.firstOrNull { it.label == label }
    }
}

/**
 * 该路径当前的**运行时端点**：优先取配置（`configs/playback.json`），
 * 配置里没有这条就回退到 enum 内置值（等价于改配置前的硬编码行为）。
 */
fun PlaybackPath.resolve(): PlaybackEndpoint =
    PlaybackConfigStore.current().endpointFor(this)
        ?: PlaybackEndpoint(
            id = label, method = method, host = host, segment = segment,
            signer = "v5", drScene = drScene, deviceLevel = deviceLevel,
        )

/** 播放请求的签名规范：v5（x-khronos/x-ss-stub）与旧 Argus（x-argus/x-ladon）。 */
enum class PlaybackSigner(val label: String) {
    V5("v5"),
    ARGUS("argus"),
}

/**
 * 形态 = 路径 × 签名规范。
 *
 * 只换路径不换签名是不够的：11-23 的故障正是「路径没变、签名规范变了」，
 * 固定签名会让轮换在同一条路径上反复空转。因此把两者都纳入形态维度。
 */
data class PlaybackShape(val path: PlaybackPath, val signer: PlaybackSigner) {
    val label: String = "${path.label}-${signer.label}"

    companion object {
        /**
         * 内置矩阵（4 路径 × 2 签名，按历史验证过的优先级排列）。
         *
         * 2026-11-24：运行时顺序可由 `configs/playback.json` 的 `order` 调整
         * （见 [all]），这样平台换优先路径时不用发版。
         */
        private val BUILTIN_ALL: List<PlaybackShape> =
            listOf(
                PlaybackShape(PlaybackPath.GET_VIDEO_TAB, PlaybackSigner.V5),
                PlaybackShape(PlaybackPath.POST_PLAYER, PlaybackSigner.V5),
                PlaybackShape(PlaybackPath.POST_PRELOAD, PlaybackSigner.V5),
                PlaybackShape(PlaybackPath.POST_V1, PlaybackSigner.ARGUS),
                PlaybackShape(PlaybackPath.GET_VIDEO_TAB, PlaybackSigner.ARGUS),
                PlaybackShape(PlaybackPath.POST_V1, PlaybackSigner.V5),
                PlaybackShape(PlaybackPath.POST_PLAYER, PlaybackSigner.ARGUS),
                PlaybackShape(PlaybackPath.POST_PRELOAD, PlaybackSigner.ARGUS),
            )

        /** 当前生效顺序：配置的 `shapeOrder` 优先（未列出的保持内置顺序排在其后）。 */
        val all: List<PlaybackShape>
            get() {
                val configured = PlaybackConfigStore.current().shapeOrder
                if (configured.isEmpty()) return BUILTIN_ALL
                val byLabel = BUILTIN_ALL.associateBy { it.label }
                val ordered = configured.mapNotNull { byLabel[it] }
                return ordered + BUILTIN_ALL.filter { it.label !in configured }
            }

        fun parse(value: String?): PlaybackShape? = all.firstOrNull { it.label == value }
    }
}

/**
 * 记住最近一次成功的形态：进程内生效，[onChange] 由 app 层接到偏好存储上。
 *
 * 顺序规则：优先上次成功的形态，其余按 [PlaybackShape.all] 兜底；
 * 首选形态连续失败两次即清空，避免平台长期关掉某条组合时每次都白试。
 */
object PlaybackShapeMemory {
    @Volatile private var preferred: PlaybackShape? = null
    private val failures = mutableMapOf<PlaybackShape, Int>()

    /** 形态变化回调（null 表示清空记忆）；app 层用它把结果写进 SharedPreferences。 */
    @Volatile var onChange: ((PlaybackShape?) -> Unit)? = null

    fun current(): PlaybackShape? = preferred

    @Synchronized
    fun restore(shape: PlaybackShape?) {
        preferred = shape
        failures.clear()
    }

    @Synchronized
    fun order(): List<PlaybackShape> =
        buildList {
            preferred?.let { add(it) }
            PlaybackShape.all.filterTo(this) { it != preferred }
        }

    @Synchronized
    fun success(shape: PlaybackShape) {
        failures.clear()
        if (preferred == shape) return
        preferred = shape
        onChange?.invoke(shape)
    }

    @Synchronized
    fun failure(shape: PlaybackShape) {
        val count = (failures[shape] ?: 0) + 1
        failures[shape] = count
        if (count >= 2 && preferred == shape) {
            preferred = null
            failures.clear()
            onChange?.invoke(null)
        }
    }

    /** 测试钩子：清空记忆与失败计数。 */
    internal fun reset() {
        restore(null)
        onChange = null
    }
}
