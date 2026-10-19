// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.hongguotv.core.DanmakuPrefs

/**
 * 弹幕渲染层（方案 B 第一步：假数据驱动，验证视觉参数）。
 *
 * 顶部横向飘字：每条弹幕从右缘进入、匀速左移、左缘消失。自绘 View 而非
 * TextView 动画——TV 上大量 View 动画开销大且掉帧，单 Canvas 绘制一批
 * 文本性能可控（短剧弹幕密度低，同屏通常 <10 条）。
 *
 * 视觉参数由 [Params] 注入（档位来自设置），播放中可 [restyle] 热更新：
 * - 字号/速度/行数/透明度四项档位见 DanmakuPrefs
 * - 顶部留白 = 8% 屏高再上移一行（避开标题区但不压得太低）
 * - 半透明白字 + 细黑描边（浅色画面可读）
 */
class DanmakuOverlay(context: Context) : View(context) {

    /** 档位参数（sizeSp/travelSec/rows/alphaIndex 来自设置档位）。 */
    data class Params(val sizeSp: Float, val travelSec: Float, val rows: Int, val alphaIndex: Int)

    data class DanmakuStyle(
        val textSizePx: Float,
        val speedPxPerSec: Float,
        val lineHeightPx: Float,
        val maxRows: Int,
        val topMarginPx: Float,
        val alpha: Int,
    ) {
        companion object {
            fun from(context: Context, params: Params): DanmakuStyle {
                val density = context.resources.displayMetrics.density
                val width = context.resources.displayMetrics.widthPixels
                val lineHeight = params.sizeSp * density * 2.2f
                return DanmakuStyle(
                    textSizePx = params.sizeSp * density,
                    // 全程时长按档位：速度 = 屏宽+文本宽近似 / travelSec
                    speedPxPerSec = (width + 400f * density) / params.travelSec,
                    lineHeightPx = lineHeight,
                    maxRows = params.rows,
                    // 8% 屏高再上移一行，贴顶但不顶出屏幕
                    topMarginPx = (context.resources.displayMetrics.heightPixels * 0.08f - lineHeight).coerceAtLeast(density * 4f),
                    alpha = DanmakuPrefs.alphas[params.alphaIndex],
                )
            }
        }
    }

    /** 一条已发射的弹幕（进入滚动状态）。 */
    private class Shot(
        val text: String,
        val textWidth: Float,
        var x: Float,
        val row: Int,
        val startAtMs: Long,
    )

    /** 待发射队列（按 timeMs 排序，播放位置到达时发射）。 */
    private data class Pending(val timeMs: Long, val text: String)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(160, 0, 0, 0)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private var style = DanmakuStyle.from(context, DEFAULT_PARAMS)
    private var params = DEFAULT_PARAMS

    /** 播放中热更新视觉参数（档位变化即时生效，不清屏）。 */
    fun restyle(params: Params) {
        this.params = params
        style = DanmakuStyle.from(context, params)
        invalidate()
    }

    companion object {
        /** 默认档位（与设置默认一致：18sp / 11s / 2 行 / alpha 档 1）。 */
        val DEFAULT_PARAMS = Params(sizeSp = DanmakuPrefs.size(1), travelSec = DanmakuPrefs.travelSec(1), rows = DanmakuPrefs.rows(1), alphaIndex = 1)

        /** 真实弹幕下一条距当前 8s 以上视为“稀疏”，启动循环补位。 */
        private const val GAP_FILL_MS = 8_000L
    }
    private val shots = mutableListOf<Shot>()
    private val pending = mutableListOf<Pending>()
    /** 弹幕池：真实弹幕耗尽后循环回收投放（冷门剧弹幕稀疏时保证画面始终有弹幕流）。 */
    private val pool = mutableListOf<String>()
    private var loopIndex = 0
    /** 下次循环补位投放的播放位置（playhead 时间戳；<=0 表示待初始化，seek 跳变时归零重置）。 */
    private var nextLoopEmitMs = 0L
    private var running = false
    private var lastFrameMs = 0L
    /** 播放位置（由宿主每帧推进，假数据模式下由内部时钟模拟）。 */
    private var playheadMs = 0L
    private var playheadRate = 1f
    private var playheadMoving = false
    private var playheadAnchorRealtime = 0L
    private var playheadAnchorMs = 0L

    // ---------- 宿主接口 ----------

    /** 装载一批弹幕（timeMs 为该条在视频内应出现的毫秒位置）。 */
    fun load(items: List<Pair<Long, String>>) {
        pending.clear()
        pending.addAll(items.map { Pending(it.first, it.second) }.sortedBy { it.timeMs })
        shots.clear()
    }

    /** 追加一批弹幕（分页拉取合并；已发射的滚动条不受影响）。 */
    fun append(items: List<Pair<Long, String>>) {
        items.forEach { pool.add(it.second) }
        pending.addAll(items.map { Pending(it.first, it.second) })
        pending.sortBy { it.timeMs }
        if (items.isNotEmpty()) ensureRunning()
    }

    /** 播放位置推进（playing=false 时暂停发射与滚动）。宿主 tick 每秒调用。 */
    fun update(positionMs: Long, rate: Float, playing: Boolean) {
        playheadRate = rate
        playheadMoving = playing
        playheadAnchorRealtime = SystemClock.elapsedRealtime()
        playheadAnchorMs = positionMs
        if (positionMs < playheadMs) {  // seek 回退清屏，循环补位节奏重新起算
            shots.clear()
            nextLoopEmitMs = 0L
        }
        playheadMs = positionMs
        if (playing) ensureRunning()
    }

    /** 关闭层（切集/退出播放时）。播放头锚点必须一并归零——否则复用层时
     *  currentPlayhead() 仍按上一集片尾外推，新集首批弹幕被一次性“到点发射”，
     *  弹道全忙遭批量丢弃（连播只出第一条的根因）。 */
    fun shutdown() {
        running = false
        pending.clear()
        shots.clear()
        pool.clear()
        loopIndex = 0
        nextLoopEmitMs = 0L
        playheadMs = 0
        playheadMoving = false
        playheadRate = 1f
        playheadAnchorMs = 0
        playheadAnchorRealtime = SystemClock.elapsedRealtime()
        invalidate()
    }

    // ---------- 内部 ----------

    private fun ensureRunning() {
        if (running) return
        running = true
        lastFrameMs = SystemClock.elapsedRealtime()
        postInvalidateOnAnimation()
    }

    private fun currentPlayhead(): Long {
        if (!playheadMoving) return playheadMs
        val elapsed = SystemClock.elapsedRealtime() - playheadAnchorRealtime
        return playheadAnchorMs + (elapsed * playheadRate).toLong()
    }

    private fun rowBottom(row: Int): Float =
        style.topMarginPx + (row + 1) * style.lineHeightPx

    private fun rowOccupied(row: Int, nowMs: Long, width: Float): Boolean {
        // 行占用：该行最后一条的尾部尚未离开右缘 + 行距余量
        return shots.any { s ->
            s.row == row && (nowMs - s.startAtMs) * style.speedPxPerSec / 1000f < s.textWidth + style.lineHeightPx * 0.6f
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val nowMs = SystemClock.elapsedRealtime()
        val dt = (nowMs - lastFrameMs).coerceAtLeast(0)
        lastFrameMs = nowMs
        val width = width.toFloat()
        // 字号先于测量设置（发射时要量文本宽，避免用旧档位尺寸测量）
        paint.textSize = style.textSizePx
        strokePaint.textSize = style.textSizePx

        // 1. 推进滚动
        val dx = style.speedPxPerSec * dt / 1000f
        shots.removeAll { s -> s.x + s.textWidth < -style.lineHeightPx }
        shots.forEach { it.x -= dx }

        // 2. 发射到达的真实弹幕
        val head = currentPlayhead()
        while (pending.isNotEmpty() && pending.first().timeMs <= head) {
            val item = pending.removeAt(0)
            emitShot(item.text, nowMs, width)
        }

        // 3. 循环补位：真实弹幕耗尽或稀疏（下一条 8s 开外）时，从池中轮询回收投放，
        //    冷门剧画面上始终保有弹幕流；节奏随池子大小自适应（池大则密）。
        if (playheadMoving && pool.isNotEmpty()) {
            val nextRealMs = pending.firstOrNull()?.timeMs ?: Long.MAX_VALUE
            if (head + GAP_FILL_MS < nextRealMs) {
                if (nextLoopEmitMs <= 0L) nextLoopEmitMs = head
                if (head >= nextLoopEmitMs) {
                    emitShot(pool[loopIndex % pool.size], nowMs, width)
                    loopIndex++
                    nextLoopEmitMs = head + loopIntervalMs()
                }
            }
        }

        // 4. 绘制
        if (shots.isEmpty() && pending.isEmpty() && (pool.isEmpty() || !playheadMoving)) {
            running = false
            return
        }
        paint.color = Color.argb(style.alpha, 255, 255, 255)
        for (s in shots) {
            val y = rowBottom(s.row) - style.lineHeightPx * 0.35f
            canvas.drawText(s.text, s.x, y, strokePaint)
            canvas.drawText(s.text, s.x, y, paint)
        }
        postInvalidateOnAnimation()
    }

    /** 发射一条弹幕进滚动状态（行全忙时丢弃——短剧弹幕密度低，罕见）。 */
    private fun emitShot(text: String, nowMs: Long, width: Float) {
        val textWidth = paint.measureText(text)
        var row = -1
        for (r in 0 until style.maxRows) {
            if (!rowOccupied(r, nowMs, width)) { row = r; break }
        }
        if (row >= 0) shots.add(Shot(text, textWidth, width, row, nowMs))
    }

    /** 循环补位投放间隔：池子越大节奏越密，2–6s 自适应。 */
    private fun loopIntervalMs(): Long = (60_000L / pool.size).coerceIn(2_000L, 6_000L)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        style = DanmakuStyle.from(context, params)
    }
}
