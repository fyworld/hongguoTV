// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * The four website ranking boards, mirroring HongguoTV-Phone-TV-NoAds rankingBoards:
 * hot-drama / hot-real-drama / hot-comic-drama / hot-ai-drama.
 */
enum class RankBoard(val storedValue: String, val label: String, val path: String, val upstreamKey: String) {
    HOT("hot","总热播榜","hot-drama","hongguo"),
    REAL("real","真人剧榜","hot-real-drama","real"),
    COMIC("comic","漫剧榜","hot-comic-drama","comic"),
    AI("ai","AI剧榜","hot-ai-drama","ai");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: COMIC
    }
}
