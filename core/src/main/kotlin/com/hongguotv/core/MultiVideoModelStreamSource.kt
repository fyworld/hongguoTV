// Adapted 2026-10-27 from tsukiyo-zero/hongguoTV kotlin-tv (GPL-3.0-only).
// Kotlin port of upstream HongguoTV-Phone-TV-NoAds 6e43927 (multi_video_model/v1);
// GPL-3.0-only. See kotlin-tv/NOTICE.md.
package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Primary channel: the playback model endpoint /novel/player/multi_video_model/v1/.
 *
 * 2026-10-27: the server started rejecting unregistered devices - every signed request
 * from a random device_id now returns HTTP 200 + an EMPTY body (the same treatment the
 * old lightweight signature got). The fix follows upstream tsukiyo-zero/hongguoTV
 * kotlin-tv: register the device once via /service/2/device_register/ (anonymous,
 * ttEncrypt(gzip(json)) body, NO signature headers), then call the playback endpoint
 * with the server-issued device_id/install_id in the query and the registration
 * cookies attached. No x-gorgon chain on the playback call itself.
 *
 * 2026-11-02: the server retired the POST /novel/player path entirely - all eight
 * api5/api3 domains return HTTP 200 + 0 bytes for every request variant (upstream
 * probed official UA, full signature headers, empty body, no cookie, h2/h1.1,
 * browser and curl fingerprints). The official client now reads
 * GET /reading/bookapi/video_tab/multi_video_model/v1/ (found in its RpcOperation;
 * params are GetVideoModelRequest's snake_case fields - missing any returns
 * 100103 PARAM_INVALID). This source tries the video_tab endpoint first (single
 * attempt, main domain); on any failure it falls back to the old POST flow, whose
 * empty-response retry and identity semantics stay unchanged. Known limits: the
 * new endpoint serves bytevc2 lows + bytevc1 1080P only (after the bytevc2 filter
 * just 1080P remains) and takes one video_id per call.
 *
 * Response shapes kept from the earlier migration:
 *  - request body: biz_param + mixed_video_id_map (slot key fixed "1004",
 *    video_platform fixed 1024; other values return code 100001)
 *  - the response shape is not fixed: data[vid].video_model, data.video_model, or a
 *    single-key entry; video_model may carry video_list directly or only a
 *    fallback_api (plain GET -> video_info.data, addresses protected by key_seed)
 */
internal class MultiVideoModelStreamSource(
    private val transport: AppApiTransport,
    private val http: OkHttpClient,
    private val session: PlaybackDeviceSession = PlaybackDeviceSession.shared,
    /** Route health memory; process-wide by default so the prefetch's fresh
     *  repository reuses the route the main playback just succeeded on. */
    private val routes: PlaybackRoutes = PlaybackRoutes.shared,
    /** Retry backoff injection point (tests pass a no-op so empty rounds stay instant). */
    private val sleep: (Long) -> Unit = Thread::sleep,
) : StreamSource {
    override val name = "multi-video-model"

    /** Upstream pins this UA on the playback call (Android 9 SM-N9860 profile). */
    private val playbackUa =
        "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"

    /** Fallback_api hosts are pinned to the vendor's own domains (upstream keeps the same guard). */
    private val fallbackDomains = listOf("fqnovel.com", "snssdk.com")

    // 空响应身份屏蔽自愈状态（2026-11-01 事件实测：屏蔽按设备身份生效，换域名与新连接
    // 无法逃逸，全新身份即时恢复）。触发条件（upstream 0.1.26）：连续整轮空响应达到阈值，
    // 或共享路由图彻底耗尽（每条路由都空响应且在冷却 = 该身份的每 host 配额花光）——后者
    // 允许在同一调用内中途轮换。任何一次成功即复位；轮换后 30s（成功）/5s（注册失败）内
    // 不重复轮换。本类实例与 ContentRepository 同生命周期（主播放链单实例存活），prefetch
    // 用的短命 ContentRepository 自带独立实例，轮次计数互不串扰（路由图是进程共享的）。
    private val identityLock = Any()
    private var emptyRounds = 0
    private var rotatedThisPeriod = false
    private var rotatedAt = 0L
    private var rotationFailed = false

    // Upcoming-episode model cache (upstream streamLadder): one batched POST reads the
    // current episode plus the next two; switching episodes then costs ZERO requests.
    // Process-wide shared (2026-11-06 TV report): the prefetch worker's short-lived
    // ContentRepository must see the main playback's cached rows - an instance cache
    // would force a fresh POST per episode and burn the per-identity quota.
    private val upcoming: UpcomingModelCache = UpcomingModelCache.shared

    override fun stream(id: String, maxQuality: Int, type: ContentType, metadataAhead: List<String>, speculative: Boolean): StreamInfo {
        // Batched read (upstream streamLadder): the caller passes the next episodes so
        // one POST covers current + next two; switching then needs no fresh request.
        // The cache also serves the recovery retries of the SAME episode (a failed
        // playback retries with a cached model instead of burning another request
        // against the quota).
        upcoming.claim(id)?.let { cached ->
            try {
                val model = cached.optJSONObject("video_model") ?: JSONObject(cached.str("video_model"))
                if (hasVideoList(model)) return select(model, maxQuality, null)
            } catch (_: Exception) {
                // A stale/invalid cached row falls through to a fresh request.
            }
        }
        // Identity rotation moved BEFORE the preferred endpoint (upstream 94187f7):
        // the tab GET is the first request of the round - a blocked identity wastes
        // it. Rotating first lets the fresh identity serve the tab call directly.
        // Speculative reads never rotate (upstream 0.1.26): a background miss must
        // not burn the foreground's failure budget.
        if (!speculative) maybeRotateIdentity()
        // Diagnostic line (upstream 0.1.26 PlaybackRequestTrace): every route's
        // outcome lands in the error message, so a TV screenshot shows exactly
        // which host answered what (G:b=200/0B/h2 P:hl=200/17KB/h2 ...).
        val trace = PlaybackRequestTrace()
        // GET video_tab FIRST (upstream 0.1.26 PlaybackRoutes: Route(true, host(0))
        // leads every plan; the POST path is only a fallback). The 2026-11-07 probe
        // showed the batched POST path returning empty for every batch while the
        // tab GET on the same identity serves full models - the retired-path
        // treatment now covers batched reads too. With metadataAhead the tab call
        // still goes first; when it fails, the batched POST below is the fallback
        // (one request may still cover three episodes when the POST path works).
        tabVideoModel(id, maxQuality, speculative, trace)?.let {
            // Any success (tab included) proves the identity works - reset the
            // empty-round state so a later incident starts counting fresh.
            resetIdentityHealing()
            return it
        }
        // MID-CALL fast rotation (upstream 0.1.26 refreshIdentity): when the shared
        // route map is EXHAUSTED (every route answered empty and is cooling = the
        // identity's per-host quota is spent - typically: the speculative prefetch
        // cooled the GET, the dead batch-POST path drained the rest), rotate the
        // identity NOW and retry the tab GET on the fresh identity - the switch-
        // episode foreground recovers within ONE call instead of erroring through
        // two more ~25s rounds. Speculative reads never take this path.
        if (!speculative && routes.exhausted()) {
            maybeRotateIdentity(force = true)
            tabVideoModel(id, maxQuality, false, trace)?.let {
                resetIdentityHealing()
                return it
            }
        }
        // Speculative reads stop here (upstream 0.1.26: attempts < 1): the tab GET
        // is the one route a background read may try; falling through to the POST
        // path would burn the quota the foreground still needs.
        if (speculative) throw EmptyPlaybackResponseException("speculative")
        // Batch: current episode + up to two ahead (upstream streamLadder) - the
        // response rows for the next episodes land in the upcoming cache.
        val ids = (listOf(id) + metadataAhead.take(2)).distinct()
        val payload = JSONObject()
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0)
                .put("device_level", 1)
                .put("disable_digg_stat", false)
                .put("need_all_video_definition", true)
                .put("need_mp4_align", false)
                .put("use_os_player", false)
                .put("use_server_dns", false)
                .put("video_platform", 1024))
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray(ids)))
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        // Identity-blocklist self-healing (upstream b597f28, 2026-11-01 event probes:
        // empty responses block per device identity - no host or connection escape,
        // a fresh identity recovers instantly): after two consecutive fully-empty
        // rounds, rotate the anonymous identity once before this round.
        // (Rotation itself moved to the top of stream() - see above.)
        // Registered identity: server-issued device_id/install_id + registration cookies.
        // Signed playback (2026-11-05 event): the server now REQUIRES the six-header
        // chain (x-gorgon/x-khronos/x-ladon/x-argus/x-helios/x-medusa) on playback
        // calls - UA+Cookie-only requests get HTTP 200 + empty body, while the same
        // request signed returns full data (differential probe verified: 0B unsigned
        // vs 17.5KB signed on the same episode). The danmaku endpoint has used this
        // chain all along, which is why it kept working through the event.
        val identity = session.profile(http)
        val data = try {
            requestPlayback(payload.toString(), identity, trace)
        } catch (error: IOException) {
            // MID-CALL rotation, second checkpoint (upstream 0.1.26 refreshIdentity
            // lives INSIDE the attempt loop): the POST path just drained the last
            // untried routes; when the whole map is now exhausted (every route
            // answered EMPTY and is cooling = the identity's per-host quota is
            // spent), rotate the identity and retry the tab GET on the fresh one -
            // ONE call recovers instead of erroring through two more ~25s rounds.
            // A network blip never triggers this (exhausted() requires every
            // route's empty mark, not a timeout).
            if (routes.exhausted()) {
                maybeRotateIdentity(force = true)
                tabVideoModel(id, maxQuality, false, trace)?.let {
                    resetIdentityHealing()
                    return it
                }
            }
            if (error is EmptyPlaybackResponseException) {
                // A fully-empty round counts toward rotation; tag the error once this
                // failure period has rotated (screenshot shows "已换身份仍失败").
                // The trace summary rides along (upstream: trace.summary() + rotation).
                throw if (noteEmptyRound()) EmptyPlaybackResponseException(trace.summary() + " rotated=1")
                else EmptyPlaybackResponseException(trace.summary())
            }
            throw error
        }
        resetIdentityHealing()
        val map = data.optJSONObject("data") ?: throw IOException("播放信息为空")
        upcoming.store(map, ids)
        val model = resolveModel(map, id)
        if (hasVideoList(model)) return select(model, maxQuality, null)
        // Second shape: only a fallback_api URL; plain GET (no signature) -> video_info.data.
        val fallback = model.str("fallback_api")
        if (fallback.isEmpty()) throw IOException("红果 App 接口未返回本集片源地址")
        val outer = fallbackJson(fallback)
        val info = outer.optJSONObject("video_info")
        val inner = info?.optJSONObject("data") ?: outer.optJSONObject("data")
            ?: throw IOException("红果 App 接口未返回本集片源模型")
        // ByteVC2-compat model (upstream 265472f, issue #56): when EVERY offered variant
        // is bytevc2/bvc2 (TV decoders can't handle them), re-request the same fallback
        // URL with codec_type=0 + force_codec=h264 once - the official player's compat
        // path. The params don't guarantee h264 (issue #56 actually returned hvc1), so
        // selection below still judges by the actual codec labels.
        val merged = JSONObject()
        model.keys().forEach { k -> merged.put(k, model.get(k)) }
        val compatInner = bytevc2CompatModel(inner, fallback)
        val source = compatInner ?: inner
        val seed = source.str("key_seed").takeIf { it.isNotEmpty() }?.let { s ->
            runCatching { decode64(s) }.getOrNull() ?: throw IOException("红果 App 媒体地址密钥无效")
        }
        source.keys().forEach { k -> merged.put(k, source.get(k)) }
        return select(merged, maxQuality, seed)
    }

    /**
     * All-bytevc2 fallback compat request (upstream 265472f): returns the compat
     * model's data object when every offered variant is bytevc2/bvc2, else null
     * (keep the original). At most one extra request, no retry loop.
     */
    private fun bytevc2CompatModel(inner: JSONObject, fallback: String): JSONObject? {
        val videoList = inner.optJSONObject("video_list") ?: return null
        val offered = videoList.keys().asSequence().mapNotNull { videoList.optJSONObject(it) }.toList()
        if (offered.isEmpty()) return null
        if (!offered.all {
                it.str("main_url").isNotBlank() &&
                    it.str("codec_type").lowercase(Locale.ROOT) in setOf("bytevc2", "bvc2")
            }) return null
        val compatible = fallback.toHttpUrl().newBuilder()
            .setQueryParameter("codec_type", "0")
            .setQueryParameter("force_codec", "h264")
            .build().toString()
        val outer = fallbackJson(compatible)
        return outer.optJSONObject("video_info")?.optJSONObject("data")
            ?: outer.optJSONObject("data")
    }

    /**
     * The official client's current playback-model endpoint:
     * GET /reading/bookapi/video_tab/multi_video_model/v1/ (2026-11-02).
     *
     * Cross-host retry (upstream PlayRetryPlan, issue #38 probes 2026-10-31): empty
     * responses come in short clustered windows tied to "identity x path" - retries
     * on the same connection/host never escape (2026-11-06 hammer probe: same
     * identity, 1st request 17.6KB then 11 consecutive empties; fresh identity
     * recovered instantly). Each attempt rotates the host (b -> hl -> lf -> b) and
     * forces a fresh connection, so a retry lands on a different backend path.
     * Any failure (non-zero code, empty body, unexpected shape, network error)
     * returns null and the caller falls back; empty-response semantics stay owned
     * by the old path.
     *
     * 2026-11-05: signed with the six-header chain (same event as the POST path) -
     * the unsigned GET started returning empty bodies while the same request signed
     * returns full data.
     */
    private fun tabVideoModel(id: String, maxQuality: Int, speculative: Boolean = false, trace: PlaybackRequestTrace? = null): StreamInfo? {
        val identity = session.profile(http)
        val extra = mapOf(
            "video_id" to id, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "")
        // Route health plan (upstream 0.1.26 PlaybackRoutes): the GET route leads,
        // a route that just answered empty is skipped for 30s, and the route the
        // main playback last succeeded on comes first - the prefetch then lands
        // its single request on a known-good path instead of re-walking hosts.
        // Speculative reads take ONE route only (upstream: plan.take(1)).
        val plan = routes.plan().filter { it.get }.let { if (speculative) it.take(1) else it }
        for ((attempt, route) in plan.withIndex()) {
            val host = route.host
            var emptyBody = false
            val result = try {
                // signPlayback builds base + "?" + encoded query itself (fresh _rticket,
                // branch-0 signing); the six headers ride on the returned Request.
                val signed = transport.sign(
                    "https://$host$TAB_PATH", identity.values + extra, null, playbackUa)
                val request = okhttp3.Request.Builder()
                    .url(signed.url)
                    .apply {
                        signed.headers.forEach { (k, v) -> header(k, v) }
                        if (identity.cookie.isNotBlank()) header("Cookie", identity.cookie)
                        if (attempt > 0) header("Connection", "close")
                    }
                    .get()
                    .build()
                val text = RequestScope.execute(http.newCall(request)) { response ->
                    if (!response.isSuccessful) throw IOException("video_tab HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    trace?.response(PlaybackRequestTrace.Stage.GET, host, response.code, body.toByteArray(Charsets.UTF_8).size, response.protocol)
                    body
                }
                if (text.isEmpty()) {
                    emptyBody = true
                    null
                } else parseTabModel(text, id, maxQuality)
            } catch (e: IOException) {
                if (Thread.currentThread().isInterrupted) return null
                trace?.failed(PlaybackRequestTrace.Stage.GET, host, e)
                routes.failed(route, emptyAnswer = e is AppApiEmptyResponseException || e is EmptyPlaybackResponseException)
                if (attempt < plan.size - 1) sleep(700L * (attempt + 1))
                null
            } catch (e: RuntimeException) {
                trace?.failed(PlaybackRequestTrace.Stage.GET, host, IOException("parse"))
                routes.failed(route, emptyAnswer = false)
                if (attempt < plan.size - 1) sleep(700L * (attempt + 1))
                null
            }
            if (result != null) {
                routes.success(route)
                return result
            }
            if (emptyBody) routes.failed(route, emptyAnswer = true)
        }
        return null
    }

    /** Parse the tab GET response; null on any shape mismatch (caller falls back). */
    private fun parseTabModel(text: String, id: String, maxQuality: Int): StreamInfo? {
        val root = JSONObject(text)
        if (root.optInt("code", root.optInt("Code", -1)) != 0) return null
        val data = root.optJSONObject("data") ?: return null
        val rows = data.optJSONObject("video_model_data") ?: data
        val row = rows.optJSONObject(id)
            ?: rows.keys().asSequence().mapNotNull { rows.optJSONObject(it) }.firstOrNull()
            ?: return null
        val raw = row.opt("video_model") ?: return null
        val model = when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        } ?: return null
        if (!hasVideoList(model)) return null
        return select(model, maxQuality, null)
    }

    /**
     * Rotate the anonymous identity once per failure period. Triggers (upstream 0.1.26):
     *  - two consecutive fully-empty rounds (the legacy slow path), OR
     *  - the shared route map is EXHAUSTED (every route answered empty and is cooling
     *    = this identity's per-host quota is spent) - this fires MID-CALL, so a
     *    switch-episode foreground recovers within ONE call instead of erroring
     *    through two more ~25s rounds.
     * Whether the re-registration succeeds or not, this failure period never rotates
     * again (a failed registration retries after 5s, upstream cooldown); any success
     * resets the whole state.
     */
    private fun maybeRotateIdentity(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val shouldRotate = synchronized(identityLock) {
            if (rotatedThisPeriod) {
                // Failed registration retries after 5s (upstream cooldown); a successful
                // rotation never repeats within the period.
                rotationFailed && now - rotatedAt >= 5_000
            } else {
                (force || emptyRounds >= IDENTITY_ROTATE_ROUNDS)
            }
        }
        if (!shouldRotate) return
        // Network I/O outside the lock; success or failure, this period is done rotating.
        val rotated = session.rotate(http)
        synchronized(identityLock) {
            rotatedThisPeriod = true; rotatedAt = now; rotationFailed = !rotated
        }
        // A fresh identity starts with a clean route-health picture (upstream keeps
        // routes per Profile - rotation hands over brand-new routes): the empty
        // answers that triggered the rotation belonged to the OLD identity, and the
        // GET route must stay reachable for the new one. A FAILED rotation keeps the
        // old identity - keep the blocked picture too (re-walking known-dead routes
        // would only burn more quota).
        if (rotated) routes.reset()
    }

    /** One more fully-empty round: returns true when this period has already rotated. */
    private fun noteEmptyRound(): Boolean {
        synchronized(identityLock) {
            emptyRounds += 1
            return rotatedThisPeriod
        }
    }

    /** Any playback success (tab or POST path) resets the healing state. */
    private fun resetIdentityHealing() {
        synchronized(identityLock) {
            emptyRounds = 0
            rotatedThisPeriod = false
        }
    }

    /**
     * The legacy playback call (signed, 2026-11-05): registered identity + six-header
     * signature chain. Empty bodies are the server's "device not recognized / request
     * shape rejected" answer - retry a bounded number of times with a fresh signature
     * before surfacing the error.
     *
     * Route health (upstream 0.1.26): POST routes come from the shared PlaybackRoutes
     * plan - a route that just answered empty is skipped for 30s, and the route that
     * last succeeded is tried first.
     */
    private fun requestPlayback(payload: String, identity: PlaybackDeviceSession.Profile, trace: PlaybackRequestTrace? = null): JSONObject {
        var lastError: IOException? = null
        val plan = routes.plan().filter { !it.get }.take(3)
        for ((attempt, route) in plan.withIndex()) {
            try {
                // Each attempt signs fresh (new _rticket + timestamp-bound headers) on a
                // rotated host; retries force a new TCP+TLS connection.
                val host = route.host
                val data = transport.requestUrl(
                    "https://$host$PATH",
                    payload.toByteArray(Charsets.UTF_8),
                    signedPlaybackHeaders(PATH, identity, payload.toByteArray(Charsets.UTF_8), host))
                routes.success(route)
                return data
            } catch (e: EmptyPlaybackResponseException) {
                trace?.failed(PlaybackRequestTrace.Stage.POST, route.host, e)
                routes.failed(route, emptyAnswer = true)
                lastError = e
                if (attempt < plan.size - 1) sleep(700L * (attempt + 1))
            } catch (e: AppApiEmptyResponseException) {
                trace?.failed(PlaybackRequestTrace.Stage.POST, route.host, e)
                routes.failed(route, emptyAnswer = true)
                lastError = EmptyPlaybackResponseException()
                if (attempt < plan.size - 1) sleep(700L * (attempt + 1))
            } catch (e: IOException) {
                trace?.failed(PlaybackRequestTrace.Stage.POST, route.host, e)
                routes.failed(route, emptyAnswer = false)
                // Transient network blips get one retry; everything else surfaces now.
                val transient = e is java.net.SocketTimeoutException || e is java.net.SocketException
                if (!transient || attempt > 0) throw e
                lastError = e
            }
        }
        throw lastError ?: IOException("播放接口返回空数据")
    }

    /** Build the signed headers for a POST playback call on the given host. */
    private fun signedPlaybackHeaders(
        path: String,
        identity: PlaybackDeviceSession.Profile,
        body: ByteArray,
        host: String,
    ): Map<String, String> {
        val signed = transport.sign("https://$host$path", identity.values, body, playbackUa)
        val headers = signed.headers.toMutableMap()
        if (identity.cookie.isNotBlank()) headers["Cookie"] = identity.cookie
        headers["Connection"] = "close"
        return headers
    }

    /** Three response shapes: data[vid].video_model, data.video_model, single-key entry. */
    private fun resolveModel(data: JSONObject, id: String): JSONObject {
        data.optJSONObject(id)?.let { entry ->
            entry.opt("video_model")?.let { return normalizeModel(it) }
        }
        data.opt("video_model")?.let { return normalizeModel(it) }
        for (key in data.keys()) {
            val entry = data.optJSONObject(key) ?: continue
            val candidate = entry.opt("video_model") ?: continue
            runCatching { return normalizeModel(candidate) }
        }
        throw IOException("红果 App 接口未返回该集播放信息")
    }

    private fun normalizeModel(value: Any): JSONObject = when (value) {
        is JSONObject -> value
        is String -> JSONObject(value)
        else -> throw IOException("播放信息格式异常")
    }

    private fun hasVideoList(model: JSONObject): Boolean {
        model.optJSONObject("video_list")?.let { return it.length() > 0 }
        model.optJSONArray("video_list")?.let { return it.length() > 0 }
        return false
    }

    /** Plain GET on the fallback_api host (no signature headers, UA only). */
    private fun fallbackJson(rawUrl: String): JSONObject {
        val host = rawUrl.substringAfter("https://", "").substringBefore('/').lowercase(Locale.ROOT)
        if (!fallbackDomains.any { host == it || host.endsWith(".$it") }) throw IOException("红果片源地址不在允许的域名内")
        val request = Request.Builder().url(rawUrl)
            .header("User-Agent", playbackUa)
            .header("Accept", "application/json")
            .build()
        return RequestScope.execute(http.newCall(request)) { response ->
            if (!response.isSuccessful) throw IOException("红果片源接口 HTTP ${response.code}")
            val text = response.body?.string() ?: throw IOException("红果片源接口返回空正文")
            if (text.isEmpty()) throw IOException("红果片源接口返回空正文")
            try { JSONObject(text) } catch (e: org.json.JSONException) {
                throw IOException("红果片源接口返回了无法解析的内容", e)
            }
        }
    }

    /** Pick a variant from video_list; decrypt key_seed-protected addresses when present. */
    private fun select(model: JSONObject, maxQuality: Int, seed: ByteArray?): StreamInfo {
        val videoList = model.optJSONObject("video_list") ?: run {
            val list = model.optJSONArray("video_list")
            if (list != null) { val obj = JSONObject(); for (i in 0 until list.length()) obj.put("$i", list.getJSONObject(i)); obj }
            else throw IOException("没有可用清晰度")
        }
        val rows = videoList.keys().asSequence().mapNotNull { videoList.optJSONObject(it) }
            .map { o ->
                val meta = o.optJSONObject("video_meta")
                val codec = (meta?.str("codec_type") ?: o.str("codec_type")).lowercase(Locale.ROOT)
                val definition = (meta?.str("definition") ?: o.str("definition")).substringBefore('p').toIntOrNull() ?: 0
                val height = (meta?.str("vheight") ?: o.str("vheight")).toIntOrNull() ?: 0
                val width = (meta?.str("vwidth") ?: o.str("vwidth")).toIntOrNull() ?: 0
                val quality = if (definition > 0) definition else if (width > 0 && (height == 0 || width < height)) width else height
                Triple(quality, codec, o)
            }.sortedByDescending { it.first }.toList()
        val playable = rows.filter { it.second !in setOf("bytevc2", "bvc2") }
        val compatible = playable.filter { it.second == "h264" || it.second == "avc1" }
        val pool = if (compatible.isNotEmpty()) compatible else playable
        val selected = pool.firstOrNull { it.first in 1..maxQuality } ?: pool.firstOrNull()
            ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item = selected.third
        var url = cleanUrl(item.str("main_url"))
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            // main_url may be base64, or key_seed-encrypted (spade layout a8 .. 01 00)
            val decoded = runCatching { decode64(url) }.getOrNull()
            val plain = when {
                decoded != null && seed != null && decoded.size >= 5 && decoded[0].u() == 0xa8 ->
                    runCatching { MediaCrypto.decryptUrl(url, seed) }.getOrNull()
                else -> null
            } ?: decoded?.let { runCatching { String(it, Charsets.UTF_8).trim() }.getOrNull() }
            if (plain != null && (plain.startsWith("https://") || plain.startsWith("http://"))) url = plain
            else throw IOException("播放地址无效")
        }
        val encryption = item.optJSONObject("encrypt_info")
        val spade = encryption?.str("spade_a")?.ifEmpty { item.str("spade_a") } ?: item.str("spade_a")
        val key = if (spade.isNotEmpty()) runCatching { MediaCrypto.deriveKey(spade) }.getOrNull() else null
        val quality = if (selected.first > 0) "${selected.first}P" + (if (selected.first > maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url, key, quality)
    }

    companion object {
        const val PATH = "/novel/player/multi_video_model/v1/"

        /** Official client's current playback-model path (2026-11-02 migration). */
        const val TAB_PATH = "/reading/bookapi/video_tab/multi_video_model/v1/"

        /**
         * Playback host rotation (upstream PlayRetryPlan, issue #38): empty-response
         * windows are tied to "identity x path" - rotating the host (b -> hl -> lf)
         * and forcing a fresh connection lets retries land on a different backend.
         */
        internal val PLAY_HOSTS = listOf(
            "api5-normal-sinfonlineb.fqnovel.com",
            "api5-normal-hl.fqnovel.com",
            "api5-normal-lf.fqnovel.com")

        /** Consecutive fully-empty rounds before one identity rotation (upstream b597f28). */
        internal const val IDENTITY_ROTATE_ROUNDS = 2
    }
}

internal class EmptyPlaybackResponseException(val detail: String? = null) :
    IOException("播放接口暂时返回空响应，请稍后重试" + (if (detail != null) "（$detail）" else ""))
