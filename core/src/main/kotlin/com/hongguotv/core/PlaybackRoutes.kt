// SPDX-License-Identifier: GPL-3.0-only
// Adapted 2026-11-07 from upstream tsukiyo-zero/hongguoTV 0.1.26 PlaybackRoutes.kt (GPL-3.0-only).
package com.hongguotv.core

/**
 * Per-process playback route health memory (upstream 0.1.26 PlaybackRoutes):
 * each route (GET tab / POST legacy, per host) remembers its last outcome.
 * Empty responses cool a route for 30s; a success clears the block and moves
 * the route to the front of the next plan.
 *
 * Why this matters (2026-11-07 rhythm probe): the prefetch worker's fresh
 * repository used to re-walk ALL hosts from scratch on every episode switch -
 * with the per-identity quota at ~1 request per host path, that burned the
 * quota the main playback still needed. With health memory the prefetch
 * reuses the route the main playback just SUCCEEDED on (one request, one hit)
 * and never re-hammers a route that just answered empty.
 *
 * Shared process-wide: the main playback and every short-lived prefetch
 * repository must see the same health picture (same rationale as
 * UpcomingModelCache.shared).
 */
internal class PlaybackRoutes(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Route(val get: Boolean, val host: String)

    private data class Health(var success: Long = Long.MIN_VALUE, var blockedUntil: Long = 0, var emptyAnswer: Boolean = false)

    /** Playback host rotation (upstream PlayRetryPlan): b -> hl -> lf. */
    private val hosts = listOf(
        "api5-normal-sinfonlineb.fqnovel.com",
        "api5-normal-hl.fqnovel.com",
        "api5-normal-lf.fqnovel.com")

    /** GET tab on the primary host leads; POST hosts follow (upstream order). */
    private val routes = listOf(Route(true, hosts[0])) +
        hosts.map { Route(false, it) }
    private val health = routes.associateWith { Health() }

    /** Ordered plan: healthy routes first (most recent success first), blocked routes skipped. */
    @Synchronized
    fun plan(): List<Route> {
        val now = clock()
        val available = routes.filter { health.getValue(it).blockedUntil <= now }
            .sortedByDescending { health.getValue(it).success }
        if (available.isNotEmpty()) {
            // Keep at least one POST probe even when only GETs are available (upstream
            // keeps the same guard: GET does not serve every shape).
            return if (available.none { !it.get })
                available + listOf(routes.filter { !it.get }.minBy { health.getValue(it).blockedUntil })
            else available
        }
        // Everything blocked: probe the earliest-to-recover POST route only.
        return listOf(routes.filter { !it.get }.minBy { health.getValue(it).blockedUntil })
    }

    @Synchronized
    fun success(route: Route) {
        health.getValue(route).apply { success = clock(); blockedUntil = 0; emptyAnswer = false }
    }

    @Synchronized
    fun failed(route: Route, emptyAnswer: Boolean) {
        health.getValue(route).apply {
            blockedUntil = clock() + if (emptyAnswer) 30_000 else 5_000
            this.emptyAnswer = emptyAnswer
        }
    }

    /**
     * True when EVERY route last answered EMPTY and is still cooling (upstream 0.1.26
     * exhausted): the identity's quota picture is fully spent. The foreground rotates
     * the identity instead of re-walking known-dead routes - the fast-recovery path
     * for the switch-episode rhythm (speculative prefetch miss cools the GET, the
     * dead batch-POST path drains the rest, the next foreground call rotates once
     * and retries instead of erroring through two more full rounds).
     */
    @Synchronized
    fun exhausted(): Boolean {
        val now = clock()
        return routes.all { health.getValue(it).let { h -> h.emptyAnswer && h.blockedUntil > now } }
    }

    /** Test hook: clear all health state (the shared instance is process-wide). */
    @Synchronized
    internal fun reset() {
        health.values.forEach { it.success = Long.MIN_VALUE; it.blockedUntil = 0; it.emptyAnswer = false }
    }

    companion object {
        /** Process-wide default: main playback + every prefetch share one picture. */
        val shared = PlaybackRoutes()
    }
}
