// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer

/**
 * Search helpers ported from HongguoTV-Phone-TV-NoAds (provider_hongguo_search*.go):
 *  - suggestion endpoint /incent_resource/suggestion (supports pinyin initials like "BFLC")
 *  - name-search records merged with the website search page
 *  - title match ranking: exact > prefix > contains > all-words
 *  - season completion ("第N季/部" with Chinese numerals)
 */
internal object SearchSuggest {
    /** (name, word_type, video_data) record from the suggestion endpoint. */
    data class Record(val name: String, val wordType: String, val videoData: JSONObject)
    private val numericId = Regex("[0-9]{1,30}")
    private val seasonSuffix = Regex("第\\s*([0-9零〇一二两兩三四五六七八九十百]+)\\s*([季部])[\\p{P}\\s]*$")

    /** Mirrors hongguoSearchKeyword: NFKC normalize + length/control validation. */
    fun normalizeKeyword(raw: String): String {
        val keyword = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        require(keyword.isNotEmpty() && keyword.length <= 80 && keyword.none { it.isISOControl() }) { "请输入 1 至 80 个字符的搜索词" }
        return keyword
    }

    /** Mirrors hongguoSearchText: lowercase, strip spaces and punctuation after NFKC. */
    fun searchText(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val filtered = buildString {
            for (c in normalized) {
                val type = Character.getType(c)
                if (c.isWhitespace() || type == Character.DASH_PUNCTUATION.toInt() ||
                    type == Character.START_PUNCTUATION.toInt() || type == Character.END_PUNCTUATION.toInt() ||
                    type == Character.CONNECTOR_PUNCTUATION.toInt() || type == Character.OTHER_PUNCTUATION.toInt() ||
                    type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || type == Character.FINAL_QUOTE_PUNCTUATION.toInt()) {
                    continue
                }
                append(c.lowercaseChar())
            }
        }
        return if (filtered.isEmpty()) text.trim().lowercase() else filtered
    }

    /** Mirrors hongguoTitleSearchRank: 0 exact, 1 prefix, 2 contains, 3 all words, 4 none. */
    fun titleRank(title: String, query: String): Int {
        val words = Normalizer.normalize(query, Normalizer.Form.NFKC).split(Regex("[\\s\\p{P}]+")).filter { it.isNotBlank() }
        val q = searchText(query); val t = searchText(title)
        return when {
            t == q -> 0
            t.startsWith(q) -> 1
            q in t -> 2
            words.isNotEmpty() && words.all { searchText(it) in t } -> 3
            else -> 4
        }
    }

    /** Parse suggestion records; returns (name, wordType, videoData) triples. */
    fun parseRecords(body: String): List<Record> {
        val root = JSONObject(body)
        var list = root.optJSONArray("suggest_list")
        if (list == null) {
            list = root.optJSONObject("data")?.optJSONArray("suggest_list")
        }
        val records = mutableListOf<Record>()
        if (list == null) return records
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val name = o.str("name")
            if (name.isBlank()) continue
            records.add(Record(name, o.str("word_type"), o.optJSONObject("video_data") ?: JSONObject()))
        }
        return records
    }

    /** Suggestion names for the keyboard UI (mirrors fetchHongguoSearchSuggestions). */
    fun suggestionNames(records: List<Record>): List<String> {
        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (record in records) {
            val name = record.name; val kind = record.wordType
            val valid = runCatching { normalizeKeyword(name) }.getOrNull() ?: continue
            val key = valid.lowercase()
            if (seen.contains(key)) continue
            seen.add(key)
            val type = if (kind in setOf("short_play_name","short_play_category","common_query","actor_name","short_play_actor")) kind else ""
            names.add(valid)
            if (type.isNotEmpty()) { /* keep typed entries too */ }
            if (names.size == 10) break
        }
        return names
    }

    /** Name-search dramas (mirrors fetchHongguoSearchNames): short_play_name records with a numeric series id. */
    fun nameSearchDramas(records: List<Record>): List<Series> {
        val dramas = mutableListOf<Series>()
        for (record in records) {
            val name = record.name; val kind = record.wordType; val videoData = record.videoData
            if (kind != "short_play_name") continue
            val id = videoData.str("series_id_str").ifEmpty { videoData.str("series_id") }
            if (!numericId.matches(id)) continue
            val title = videoData.str("series_title").ifEmpty { videoData.str("series_name").ifEmpty { videoData.str("title").ifEmpty { name } } }
            if (id.isNotEmpty() && title != id) dramas.add(cardFrom(videoData, title, id))
        }
        return dramas
    }

    /** Build a Series from a suggestion video_data object (mirrors hongguoDramaFromAny's core fields). */
    private fun cardFrom(o: JSONObject, title: String, id: String): Series {
        val count = o.optInt("episode_cnt", 0)
        val tags = o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(id, title,
            cleanUrl(o.str("series_cover").ifEmpty { o.str("cover") }),
            o.str("series_intro").ifEmpty { o.str("video_desc") },
            o.str("episode_right_text").ifEmpty { if (count > 0) "全 $count 集" else "" },
            (0 until minOf(tags.length(), 5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }

    // ---------- season handling (provider_hongguo_search_seasons.go) ----------

    /** Returns (base, season, unit, label) or null when the title has no 第N季/部 suffix. */
    fun parseSeason(title: String): SeasonInfo? {
        val normalized = Normalizer.normalize(title.trim(), Normalizer.Form.NFKC)
        val match = seasonSuffix.find(normalized) ?: return null
        val base = normalized.substring(0, match.range.first).trimEnd { it.isWhitespace() || Character.getType(it) == Character.OTHER_PUNCTUATION.toInt() || Character.getType(it) == Character.DASH_PUNCTUATION.toInt() }
        val label = match.groupValues[1]; val unit = match.groupValues[2]
        val number = parseChineseNumber(label) ?: return null
        if (base.isEmpty() || number < 1 || number > 200) return null
        return SeasonInfo(base, number, unit, label)
    }

    data class SeasonInfo(val base: String, val season: Int, val unit: String, val label: String)

    private val digits = mapOf('零' to 0,'〇' to 0,'一' to 1,'二' to 2,'两' to 2,'兩' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)

    /** Parses "3", "三", "十二", "两百零一" style season numbers. */
    fun parseChineseNumber(label: String): Int? {
        label.toIntOrNull()?.let { return it }
        if (label.isEmpty()) return null
        if (!label.any { it in "十百" }) {
            var number = 0
            for (c in label) {
                val value = digits[c] ?: return null
                number = number * 10 + value
                if (number > 200) return null
            }
            return number
        }
        var number = 0; var digit = 0; var previous = 1000
        for (c in label) {
            if (c in digits) { digit = digit * 10 + digits[c]!!; continue }
            val value = if (c == '百') 100 else 10
            if (value >= previous || digit > 9) return null
            number += maxOf(digit, 1) * value
            digit = 0; previous = value
        }
        return number + digit
    }

    /** Chinese numeral for a season number (mirrors hongguoSearchChineseSeason). */
    fun chineseNumber(number: Int): String {
        val d = "零一二三四五六七八九"
        if (number < 10) return d[number].toString()
        if (number < 100) {
            var prefix = ""
            if (number >= 20) prefix += d[number / 10]
            prefix += "十"
            if (number % 10 != 0) prefix += d[number % 10]
            return prefix
        }
        var prefix = d[number / 100] + "百"; val rest = number % 100
        if (rest == 0) return prefix
        if (rest < 10) prefix += "零" else if (rest < 20) prefix += "一"
        return prefix + chineseNumber(rest)
    }

    /**
     * Season completion: find series groups whose known seasons have holes, and produce
     * the extra queries to try (mirrors hongguoSearchSeriesGroups + nextQuery).
     * Returns a list of extra search keywords, at most [maxQueries].
     */
    fun seasonQueries(keyword: String, dramas: List<Series>, maxQueries: Int = 32): List<String> {
        if (parseSeason(keyword) != null) return emptyList()
        val query = searchText(keyword)
        data class Group(val title: String, val key: String, val unit: String, val known: MutableSet<Int> = mutableSetOf(), val labels: MutableMap<Int,String> = mutableMapOf(), var maximum: Int = 0)
        val groups = mutableListOf<Group>(); val byKey = mutableMapOf<String,Group>()
        for (drama in dramas) {
            val info = parseSeason(drama.title) ?: continue
            val key = searchText(info.base)
            if (!key.contains(query)) continue
            val groupKey = "$key:${info.unit}"
            val group = byKey.getOrPut(groupKey) { Group(info.base, key, info.unit).also { groups.add(it) } }
            group.known.add(info.season)
            group.maximum = maxOf(group.maximum, info.season)
            group.labels[info.season] = info.label
        }
        // also treat exact-title dramas as season 1
        for (drama in dramas) {
            val info = parseSeason(drama.title)
            if (info == null) {
                val key = searchText(drama.title)
                for (group in groups) if (group.key == key) { group.known.add(1); group.maximum = maxOf(group.maximum, 1) }
            }
        }
        val incomplete = groups.filter { it.known.size >= 2 && it.known.size < it.maximum }
            .sortedBy { titleRank(it.title, keyword) }
        if (incomplete.isEmpty()) return emptyList()
        val attempted = mutableSetOf(keyword)
        val queries = mutableListOf<String>()
        for (group in incomplete) {
            for (season in 1..group.maximum) {
                if (queries.size >= maxQueries) return queries
                if (season in group.known) continue
                val labels = mutableListOf(chineseNumber(season), season.toString())
                val nearest = group.labels.keys.filter { it != season }.minByOrNull { kotlin.math.abs(it - season) }
                if (nearest != null && group.labels[nearest]?.toIntOrNull() != null) labels.reverse()
                val candidates = mutableListOf<String>()
                if (season == 1) candidates.add(group.title)
                for (label in labels) candidates.add("${group.title}第$label${group.unit}")
                for (candidate in candidates) {
                    if (runCatching { normalizeKeyword(candidate) }.isSuccess && candidate !in attempted) {
                        attempted.add(candidate); queries.add(candidate); break
                    }
                }
            }
        }
        return queries
    }

    /** Merge two result lists by id, keeping the first occurrence's position (mirrors mergeHongguoSearchDramas). */
    fun mergeDramas(base: List<Series>, batch: List<Series>): List<Series> {
        if (batch.isEmpty()) return base
        val positions = base.withIndex().associate { (i, s) -> s.id to i }.toMutableMap()
        val merged = base.toMutableList()
        for (drama in batch) {
            if (drama.id.isEmpty()) continue
            val index = positions[drama.id]
            if (index != null) {
                val old = merged[index]
                merged[index] = if (old.cover.isBlank() && drama.cover.isNotBlank()) old.copy(cover = drama.cover) else old
            } else {
                positions[drama.id] = merged.size; merged.add(drama)
            }
        }
        return merged
    }

    /** Stable sort by title match rank, then season order within the same series (mirrors fetchHongguoSearch sort). */
    fun sortForQuery(dramas: List<Series>, keyword: String): List<Series> {
        return dramas.sortedWith(compareBy({ titleRank(it.title, keyword) }, { parseSeason(it.title)?.season ?: 0 }))
    }
}
