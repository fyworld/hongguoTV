// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** 弹幕视觉档位表（索引存储；档位值与文案集中在此，UI 与渲染层共用）。 */
object DanmakuPrefs {
    /** 字号 sp 档：小 / 标准 / 大。 */
    val textSizes = listOf(16f, 18f, 20f)
    /** 全程滚动时长秒档：快 / 标准 / 慢。 */
    val travelSecs = listOf(8f, 11f, 14f)
    /** 行数档。 */
    val rowCounts = listOf(1, 2, 4)
    /** 透明度 alpha 档：淡 / 适中 / 不透明。 */
    val alphas = listOf(150, 230, 255)

    private val sizeNames = listOf("小", "标准", "大")
    private val speedNames = listOf("快", "标准", "慢")
    private val alphaNames = listOf("淡", "适中", "不透明")

    fun normalize(index: Int, count: Int) = index.coerceIn(0, count - 1)
    fun size(index: Int) = textSizes[normalize(index, textSizes.size)]
    fun travelSec(index: Int) = travelSecs[normalize(index, travelSecs.size)]
    fun rows(index: Int) = rowCounts[normalize(index, rowCounts.size)]
    fun alpha(index: Int) = alphas[normalize(index, alphas.size)]
    fun sizeLabel(index: Int) = "${sizeNames[normalize(index, textSizes.size)]} ${size(index).toInt()}sp"
    fun speedLabel(index: Int) = "${speedNames[normalize(index, travelSecs.size)]} ${travelSec(index).toInt()}s"
    fun rowsLabel(index: Int) = "${rows(index)} 行"
    fun alphaLabel(index: Int) = alphaNames[normalize(index, alphas.size)]
}
