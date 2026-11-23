// SPDX-License-Identifier: GPL-3.0-only
// Adapted from tsukiyo-zero/hongguoTV kotlin-tv 41ba81c (PlaybackShapes.kt); GPL-3.0-only.
// See kotlin-tv/NOTICE.md.
package com.hongguotv.core

/**
 * 播放模型的请求路径。
 *
 * 平台在 2026-11 反复切换：11-20 早只放 v1、11-20 晚只放 GET、11-22 恢复 player、
 * 11-23 再次只放 GET。
 */
enum class PlaybackPath(val label: String, val segment: String) {
    GET_VIDEO_TAB("get", "reading/bookapi/video_tab/multi_video_model/v1"),
    POST_PLAYER("player", "novel/player/multi_video_model/player/v1"),
    POST_V1("v1", "novel/player/multi_video_model/v1"),
}

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
        /** 默认顺序：近期验证过的组合优先，其余作为兜底矩阵。 */
        val all: List<PlaybackShape> =
            listOf(
                PlaybackShape(PlaybackPath.GET_VIDEO_TAB, PlaybackSigner.V5),
                PlaybackShape(PlaybackPath.POST_PLAYER, PlaybackSigner.V5),
                PlaybackShape(PlaybackPath.POST_V1, PlaybackSigner.ARGUS),
                PlaybackShape(PlaybackPath.GET_VIDEO_TAB, PlaybackSigner.ARGUS),
                PlaybackShape(PlaybackPath.POST_V1, PlaybackSigner.V5),
                PlaybackShape(PlaybackPath.POST_PLAYER, PlaybackSigner.ARGUS),
            )

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
