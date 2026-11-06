// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

/**
 * Upcoming-episode playback models, shared process-wide (2026-11-06 event).
 *
 * The prefetch worker builds a SHORT-LIVED ContentRepository per episode - with an
 * instance-level cache every prefetch would fire a fresh playback POST and burn
 * the per-identity request quota (~1 request per identity per host path; the TV
 * report: episode 1 plays, episode 2 dies, switching back dies too). Sharing the
 * cache process-wide lets the prefetch consume the models the main playback's
 * batched POST already fetched - the binge chain costs ONE request per three
 * episodes.
 *
 * TTL 10 min: the TV binge rhythm (1-3 min episodes) consumes the ep+2 model up
 * to ~5.5 min after the batch - the upstream's 60s only covers millisecond-fast
 * phone switches. A dead URL never reaches the player: the prefetch's prepare()
 * downloads the first 256KB immediately and falls back to a fresh request.
 * Entries are claimed (removed) on read, so a retry never reuses an old address.
 */
internal class UpcomingModelCache(
    private val ttl: Long = 10 * 60_000L,
    private val max: Int = 6,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val row: JSONObject, val savedAt: Long)
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /** Claim (and remove) the cached row for one episode; null when absent/expired. */
    fun claim(id: String): JSONObject? = synchronized(lock) {
        val now = clock()
        entries.entries.removeAll { now - it.value.savedAt !in 0..ttl }
        return entries.remove(id)?.row
    }

    /** Cache the batched response's rows for the episodes AFTER the current one. */
    fun store(map: JSONObject, ids: List<String>) {
        synchronized(lock) {
            for (next in ids.drop(1)) {
                val row = map.optJSONObject(next) ?: continue
                val model = row.opt("video_model")
                val valid = (model is JSONObject || (model is String && model.isNotBlank())) &&
                    row.toString().toByteArray(Charsets.UTF_8).size <= 256 * 1024
                if (valid) entries[next] = Entry(row, clock())
            }
            while (entries.size > max) entries.remove(entries.keys.first())
        }
    }

    companion object {
        /** Process-wide default: the main playback and every prefetch share it. */
        val shared = UpcomingModelCache()
    }
}
