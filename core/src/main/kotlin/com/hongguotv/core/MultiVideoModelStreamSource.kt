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
import java.util.concurrent.TimeUnit

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
 * 2026-11-21 (upstream 0.1.35 / 2a1a4cd): the anonymous POST variants (both
 * /multi_video_model/v1 and /multi_video_model/:dr_scene/v1) went fully silent again
 * on 2026-11-20 21:00 (HTTP 200 + 0 bytes) while GET
 * /reading/bookapi/video_tab/multi_video_model/v1 still served models. The model
 * lookup order is therefore inverted: GET video_tab first, the v5 POST only as a
 * fallback. Keeping v5 first (v1.0.49 behaviour) wastes a quota unit and an 8s
 * timeout on a path the platform no longer answers.
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

    /** One resolved playback model: the video_model row plus its key_seed (fallback shape). */
    private data class Resolved(val model: JSONObject, val seed: ByteArray? = null)

    override fun stream(id: String, maxQuality: Int, type: ContentType, metadataAhead: List<String>, speculative: Boolean): StreamInfo {
        val resolved = resolvePlaybackModel(id, metadataAhead, speculative)
        return select(resolved.model, maxQuality, resolved.seed)
    }

    /**
     * Every playable tier of one episode in TV-preference order (upstream streamLadder,
     * 2026-11-14 capability port): h264/avc1 tiers first (quality desc), then other
     * non-bytevc2 codecs. The app layer walks this ladder with the device's REAL
     * decoder capabilities (static probe + learned runtime failures) and opens the
     * first tier the box can actually decode - ONE model request covers the whole
     * verified-downgrade walk, no quota is burned per tier switch.
     */
    override fun ladder(id: String, maxQuality: Int, type: ContentType, metadataAhead: List<String>, speculative: Boolean): List<StreamInfo> {
        val resolved = resolvePlaybackModel(id, metadataAhead, speculative)
        val inline = ladderTiers(resolved.model, resolved.seed)
        if (inline.isNotEmpty()) return inline
        // All inline rows were bytevc2/bvc2 (VVC - no TV decoder). The official
        // player has a compat path (upstream streamLadder / 265472f): read
        // fallback_api, and when that model ALSO offers only bytevc2, re-request
        // the same URL with codec_type=0&force_codec=h264. This is exactly how
        // upstream plays these episodes at 540x960 HEVC ("960P") while we used
        // to return an empty ladder and fall through to the 10s preview clip.
        val compat = resolveFallbackTiers(resolved.model) ?: return emptyList()
        return ladderTiers(compat.model, compat.seed)
    }

    /** fallback_api may be a plain URL, a JSONObject, or a one-element JSONArray. */
    private fun fallbackUrl(model: JSONObject): String {
        val raw = model.opt("fallback_api")
        return when (raw) {
            is JSONObject -> raw.str("fallback_api")
            is JSONArray -> raw.optString(0)
            is String -> raw
            else -> ""
        }
    }

    /** Legacy fallback endpoint + all-bytevc2 compat request; null when unusable. */
    private fun resolveFallbackTiers(model: JSONObject): Resolved? {
        val fallback = fallbackUrl(model)
        if (!fallback.startsWith("https://")) return null
        val outer = runCatching { fallbackJson(fallback) }.getOrNull() ?: return null
        val inner = outer.optJSONObject("video_info")?.optJSONObject("data")
            ?: outer.optJSONObject("data") ?: return null
        val source = bytevc2CompatModel(inner, fallback) ?: inner
        val seed = source.str("key_seed").takeIf { it.isNotEmpty() }
            ?.let { runCatching { decode64(it) }.getOrNull() }
        val merged = JSONObject()
        model.keys().forEach { k -> merged.put(k, model.get(k)) }
        source.keys().forEach { k -> merged.put(k, source.get(k)) }
        return Resolved(merged, seed)
    }

    /**
     * The full model-resolution flow shared by stream() and ladder(): upcoming cache,
     * identity rotation, the six-shape rotation (path x signer), signed batch POST
     * fallback, both response shapes (video_list and fallback_api). Only the final
     * tier extraction differs.
     *
     * 2026-11-23（上游 41ba81c）：平台在「路径」与「签名规范」两条轴上反复切。
     * 11-20 早只放平铺 v1、11-20 晚只放 GET、11-22 恢复 player、11-23 又只放
     * GET（且只认 v5 签名，旧 Argus GET 被静默丢弃）。写死任何一条都要发版，
     * 因此按 [PlaybackShapeMemory] 的顺序逐个形态尝试，成功即记住。
     */
    private fun resolvePlaybackModel(id: String, metadataAhead: List<String>, speculative: Boolean): Resolved {
        // Batched read (upstream streamLadder): the caller passes the next episodes so
        // one POST covers current + next two; switching then needs no fresh request.
        // The cache also serves the recovery retries of the SAME episode (a failed
        // playback retries with a cached model instead of burning another request
        // against the quota).
        upcoming.claim(id)?.let { cached ->
            try {
                val model = cached.optJSONObject("video_model") ?: JSONObject(cached.str("video_model"))
                if (hasVideoList(model)) return Resolved(model)
            } catch (_: Exception) {
                // A stale/invalid cached row falls through to a fresh request.
            }
        }
        // Identity rotation moved BEFORE the preferred endpoint (upstream 94187f7):
        // the first shape is the first request of the round - a blocked identity
        // wastes it. Rotating first lets the fresh identity serve it directly.
        // Speculative reads never rotate (upstream 0.1.26): a background miss must
        // not burn the foreground's failure budget.
        if (!speculative) maybeRotateIdentity()
        // Diagnostic line (upstream 0.1.26 PlaybackRequestTrace): every route's
        // outcome lands in the error message, so a TV screenshot shows exactly
        // which host answered what (G:b=200/0B/h2 P:hl=200/17KB/h2 ...).
        val trace = PlaybackRequestTrace()
        // Foreground gets 8s, speculative 2s (upstream streamLadder). The budget is
        // split across the shapes still untried - the first shape otherwise eats the
        // whole allowance on timeout and the rest never get a chance (upstream 116608a).
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(if (speculative) 2_000L else 8_000L)
        // Speculative reads try ONE shape only (upstream: attempts < 1 on the legacy
        // loop): a background read must not drain the foreground's quota.
        val shapes = PlaybackShapeMemory.order().let { if (speculative) it.take(1) else it }
        var last: IOException? = null
        for ((index, shape) in shapes.withIndex()) {
            if (Thread.currentThread().isInterrupted) throw IOException("请求已取消")
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
            val budget = (left / (shapes.size - index)).coerceAtLeast(600L)
            val client = http.newBuilder()
                .callTimeout(budget, TimeUnit.MILLISECONDS)
                .readTimeout(minOf(budget, 4_000L), TimeUnit.MILLISECONDS)
                .connectTimeout(minOf(budget, 4_000L), TimeUnit.MILLISECONDS)
                .build()
            try {
                val model = requestShape(shape, id, client, trace)
                // Success proves both the identity and this combination work; remember
                // the shape (the app layer persists it) and reset the healing state.
                PlaybackShapeMemory.success(shape)
                resetIdentityHealing()
                return fromModel(model)
            } catch (error: IOException) {
                if (Thread.currentThread().isInterrupted) throw error
                PlaybackShapeMemory.failure(shape)
                last = error
            } catch (error: RuntimeException) {
                PlaybackShapeMemory.failure(shape)
                last = IOException("播放信息格式异常", error)
            }
        }
        // MID-CALL fast rotation (upstream 0.1.26 refreshIdentity): when the shared
        // route map is EXHAUSTED (every route answered empty and is cooling = the
        // identity's per-host quota is spent - typically: the speculative prefetch
        // cooled the GET, the dead batch-POST path drained the rest), rotate the
        // identity NOW and retry the remembered shape on the fresh identity - the
        // switch-episode foreground recovers within ONE call instead of erroring
        // through two more ~25s rounds. Speculative reads never take this path.
        if (!speculative && routes.exhausted()) {
            maybeRotateIdentity(force = true)
            val retry = http.newBuilder()
                .callTimeout(3_000L, TimeUnit.MILLISECONDS)
                .readTimeout(3_000L, TimeUnit.MILLISECONDS)
                .build()
            runCatching { requestShape(PlaybackShapeMemory.order().first(), id, retry, trace) }
                .getOrNull()?.let {
                    resetIdentityHealing()
                    return fromModel(it)
                }
        }
        // Speculative reads stop here (upstream 0.1.26: attempts < 1): one shape is
        // all a background read may try; more would burn the quota the foreground
        // still needs.
        if (speculative) throw EmptyPlaybackResponseException("speculative")
        // A fully-empty round counts toward identity rotation; tag the error once this
        // failure period has already rotated (screenshot shows "已换身份仍失败").
        // The trace summary rides along (upstream: trace.summary() + rotation).
        val failure = last ?: IOException("播放接口暂不可用")
        if (failure is EmptyPlaybackResponseException) {
            throw if (noteEmptyRound()) EmptyPlaybackResponseException(trace.summary() + " rotated=1")
            else EmptyPlaybackResponseException(trace.summary())
        }
        throw failure
    }

    /**
     * One 形态 = one request. GET/POST × v5/Argus 四种组合按 [shape] 分派：
     *  - GET + v5   → [MobilePlayback.getVideoTab]（上游 ff4d1d2：11-23 起唯一放行的组合）
     *  - GET + Argus→ [tabModel] 旧六头签名 GET（保留给 legacy 与诊断）
     *  - POST + v5  → [MobilePlayback.model]（gzip 体 + v5 签名）
     *  - POST + Argus→ [argusPostModel] 旧六头签名 POST（含跨 host 路由健康与批量预取）
     *
     * 返回的是裸 video_model 行；外层统一交给 [fromModel] 处理 fallback_api。
     */
    private fun requestShape(
        shape: PlaybackShape,
        id: String,
        client: OkHttpClient,
        trace: PlaybackRequestTrace,
        metadataAhead: List<String> = emptyList(),
    ): JSONObject {
        val identity = session.profile(client)
        val segment = shape.path.segment
        return when (shape.signer) {
            PlaybackSigner.V5 -> when (shape.path) {
                PlaybackPath.GET_VIDEO_TAB -> {
                    val entry = MobilePlayback.getVideoTab(
                        id, identity.values, client, MobilePlayback.signer, System.currentTimeMillis())
                    entry.optJSONObject("video_model") ?: normalizeModel(entry.get("video_model"))
                }
                else -> {
                    val entry = MobilePlayback.model(
                        id, identity.values, client, MobilePlayback.signer,
                        System.currentTimeMillis(), segment = segment)
                    entry.optJSONObject("video_model") ?: throw IOException("播放信息没有可用资源")
                }
            }
            PlaybackSigner.ARGUS -> when (shape.path) {
                PlaybackPath.GET_VIDEO_TAB ->
                    tabModel(id, speculative = false, trace = trace)
                        ?: throw EmptyPlaybackResponseException("shape=${shape.label}")
                else -> argusPostModel(id, metadataAhead, client, trace, segment)
            }
        }
    }

    /**
     * 旧六头签名 POST（Argus），保留我们的批量预取与路由健康：
     * [metadataAhead] 非空时一次请求覆盖当前集 + 后两集，响应行进 [upcoming] 缓存，
     * 换集因此不再消耗请求（上游 streamLadder 的批量优化；上游新版形态轮换里
     * 退化成单集，这里按功能对应保留）。
     */
    private fun argusPostModel(
        id: String,
        metadataAhead: List<String>,
        client: OkHttpClient,
        trace: PlaybackRequestTrace,
        segment: String = PATH.trim('/'),
    ): JSONObject {
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
        // Registered identity: server-issued device_id/install_id + registration cookies.
        // Real signature (2026-11-11 event, upstream 269c6d8): the server validates
        // x-argus/x-ladon - AppApiTransport.sign() rebuilds them with the fqapp
        // Argus/Ladon ports over the final query and strips the placeholder chain.
        val identity = session.profile(client)
        val data = requestPlayback(payload.toString(), identity, trace, client, segment)
        val map = data.optJSONObject("data") ?: throw IOException("播放信息为空")
        if (ids.size > 1) upcoming.store(map, ids)
        return resolveModel(map, id)
    }

    /**
     * 裸 video_model 行 → [Resolved]：有 inline video_list 直接用；只有 fallback_api 时
     * 走明文 GET（video_info.data）并处理 bytevc2 兼容重取。
     */
    private fun fromModel(model: JSONObject): Resolved {
        if (hasVideoList(model)) return Resolved(model)
        // Second shape: only a fallback_api URL; plain GET (no signature) -> video_info.data.
        val fallback = fallbackUrl(model)
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
        return Resolved(merged, seed)
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
    private fun tabModel(
        id: String,
        speculative: Boolean = false,
        trace: PlaybackRequestTrace? = null,
        client: OkHttpClient = http,
    ): JSONObject? {
        val identity = session.profile(client)
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
                // real-signature headers via AppApiTransport.sign); GET with no body.
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
                val text = RequestScope.execute(client.newCall(request)) { response ->
                    if (!response.isSuccessful) throw IOException("video_tab HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    trace?.response(PlaybackRequestTrace.Stage.GET, host, response.code, body.toByteArray(Charsets.UTF_8).size, response.protocol)
                    body
                }
                if (text.isEmpty()) {
                    emptyBody = true
                    null
                } else parseTabModel(text, id)
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

    /** Parse the tab GET response into the video_model row; null on any shape mismatch (caller falls back). */
    private fun parseTabModel(text: String, id: String): JSONObject? {
        val root = JSONObject(text)
        if (root.optInt("code", root.optInt("Code", -1)) != 0) return null
        val data = root.optJSONObject("data") ?: return null
        val rows = data.optJSONObject("video_model_data") ?: data
        val row = rows.optJSONObject(id)
            ?: rows.keys().asSequence().mapNotNull { rows.optJSONObject(it) }.firstOrNull()
            ?: return null
        val raw = row.opt("video_model") ?: return null
        return when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }?.takeIf { hasVideoList(it) }
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
                // Upstream refreshIdentity cooldown: a FAILED registration may retry
                // after 5s; a SUCCESSFUL rotation re-arms after 30s. The old code only
                // re-armed on failure, so one successful rotation pinned the app to
                // that identity for the whole process - once it got blocked, every
                // later playback stayed empty forever (2026-11-14 live incident).
                val cooldown = if (rotationFailed) 5_000L else 30_000L
                if (now - rotatedAt >= cooldown) {
                    rotatedThisPeriod = false
                    rotationFailed = false
                    (force || emptyRounds >= IDENTITY_ROTATE_ROUNDS)
                } else false
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

    /**
     * Scheduler hook (upstream refreshIdentity, 2026-11-14 port): before retrying a
     * transient empty answer, re-register the device and hand the retry a clean route
     * map. A fresh identity's FIRST playback request is the reliable escape from the
     * per-device empty-response shield - retrying on the same blocked device id just
     * burns requests. Verified live: same episode, same signature, fresh identities
     * answered 14596B / 14596B while the stale app identity kept returning 0B.
     */
    override fun refreshIdentity() {
        val rotated = try { session.rotate(http) } catch (_: Exception) { false }
        if (rotated) routes.reset()
        synchronized(identityLock) {
            emptyRounds = 0
            rotatedThisPeriod = true
            rotatedAt = System.currentTimeMillis()
            rotationFailed = !rotated
        }
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
    private fun requestPlayback(
        payload: String,
        identity: PlaybackDeviceSession.Profile,
        trace: PlaybackRequestTrace? = null,
        client: OkHttpClient = http,
        segment: String = PATH,
    ): JSONObject {
        // 旧链路常量 PATH 带尾斜杠（平台实测过的形态）；形态矩阵传进来的是不带斜杠的
        // segment，这里统一补成「/xxx/」。
        val path = "/" + segment.trim('/') + "/"
        var lastError: IOException? = null
        val plan = routes.plan().filter { !it.get }.take(3)
        for ((attempt, route) in plan.withIndex()) {
            try {
                // Each attempt signs fresh (new _rticket + timestamp-bound headers) on a
                // rotated host; retries force a new TCP+TLS connection.
                val host = route.host
                val body = payload.toByteArray(Charsets.UTF_8)
                val signed = transport.sign("https://$host$path", identity.values, body, playbackUa)
                val headers = signed.headers.toMutableMap()
                if (identity.cookie.isNotBlank()) headers["Cookie"] = identity.cookie
                headers["Connection"] = "close"
                val data = RequestScope.execute(client.newCall(
                    Request.Builder().url(signed.url).apply { headers.forEach { (k, v) -> header(k, v) } }
                        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build())) { response ->
                    if (!response.isSuccessful) throw IOException("App 接口请求失败（HTTP ${response.code}）")
                    val text = response.body?.string() ?: throw IOException("App 接口响应为空")
                    if (text.isEmpty()) throw AppApiEmptyResponseException()
                    val parsed = try { JSONObject(text) } catch (e: org.json.JSONException) {
                        throw IOException("App 接口返回了无法解析的内容", e)
                    }
                    val code = parsed.optInt("code", parsed.optInt("Code", 0))
                    if (code != 0) throw AppApiRejectedException(code)
                    parsed
                }
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

    /** Rows of one video_list: (numeric tier, codec, raw row), quality descending. */
    private fun tierRows(model: JSONObject): List<Triple<Int, String, JSONObject>> {
        val videoList = model.optJSONObject("video_list") ?: run {
            val list = model.optJSONArray("video_list")
            if (list != null) { val obj = JSONObject(); for (i in 0 until list.length()) obj.put("$i", list.getJSONObject(i)); obj }
            else throw IOException("没有可用清晰度")
        }
        return videoList.keys().asSequence().mapNotNull { videoList.optJSONObject(it) }
            .map { o ->
                val meta = o.optJSONObject("video_meta")
                val codec = (meta?.str("codec_type") ?: o.str("codec_type")).lowercase(Locale.ROOT)
                val definition = (meta?.str("definition") ?: o.str("definition")).substringBefore('p').toIntOrNull() ?: 0
                val height = (meta?.str("vheight") ?: o.str("vheight")).toIntOrNull() ?: 0
                val width = (meta?.str("vwidth") ?: o.str("vwidth")).toIntOrNull() ?: 0
                val quality = if (definition > 0) definition else if (width > 0 && (height == 0 || width < height)) width else height
                Triple(quality, codec, o)
            }.sortedByDescending { it.first }.toList()
    }

    private fun isTvCompatible(codec: String) = codec == "h264" || codec == "avc1"

    /** One tier row -> a playable StreamInfo; null when the address cannot be resolved. */
    private fun tierInfo(row: Triple<Int, String, JSONObject>, seed: ByteArray?): StreamInfo? {
        val item = row.third
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
            else return null
        }
        val encryption = item.optJSONObject("encrypt_info")
        val spade = encryption?.str("spade_a")?.ifEmpty { item.str("spade_a") } ?: item.str("spade_a")
        val key = if (spade.isNotEmpty()) runCatching { MediaCrypto.deriveKey(spade) }.getOrNull() else null
        val meta = item.optJSONObject("video_meta")
        val height = (meta?.str("vheight") ?: item.str("vheight")).toIntOrNull() ?: 0
        val width = (meta?.str("vwidth") ?: item.str("vwidth")).toIntOrNull() ?: 0
        val quality = if (row.first > 0) "${row.first}P" else "自动"
        return StreamInfo(url, key, quality, row.second, width, height, row.first)
    }

    /**
     * The full tier ladder (upstream StreamLadder.playbackOrder): TV-compatible codecs
     * (h264/avc1) first, then every other non-bytevc2 codec, both quality descending.
     * bytevc2 rows are filtered out entirely - TV decoders cannot handle them.
     */
    private fun ladderTiers(model: JSONObject, seed: ByteArray?): List<StreamInfo> {
        val rows = tierRows(model)
        val playable = rows.filter { it.second !in setOf("bytevc2", "bvc2") }
        val compatible = playable.filter { isTvCompatible(it.second) }
        val ordered = compatible + playable.filter { !isTvCompatible(it.second) }
        val tiers = ordered.mapNotNull { tierInfo(it, seed) }
        if (tiers.isEmpty() && rows.isNotEmpty()) {
            java.util.logging.Logger.getLogger("com.hongguotv.core.stream").warning(
                "no playable tier out of ${rows.size} rows; codecs=${rows.map { it.second }}; video_list=${model.opt("video_list")?.javaClass?.simpleName}; fallback=${model.str("fallback_api").take(60)}"
            )
        }
        return tiers
    }

    /** Legacy single-tier pick (stream() semantics, unchanged): best compatible tier at or below maxQuality. */
    private fun select(model: JSONObject, maxQuality: Int, seed: ByteArray?): StreamInfo {
        val rows = tierRows(model)
        val playable = rows.filter { it.second !in setOf("bytevc2", "bvc2") }
        val compatible = playable.filter { isTvCompatible(it.second) }
        val pool = if (compatible.isNotEmpty()) compatible else playable
        val selected = pool.firstOrNull { it.first in 1..maxQuality } ?: pool.firstOrNull()
            ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val info = tierInfo(selected, seed) ?: throw IOException("播放地址无效")
        val quality = if (selected.first > 0) "${selected.first}P" + (if (selected.first > maxQuality) "（兼容资源）" else "") else "自动"
        return info.copy(quality = quality)
    }

    companion object {
        const val PATH = "/novel/player/multi_video_model/v1/"

        /** v5 通道的固定域名（上游 MobilePlayback 同值）。 */
        internal const val v5Host = "api5-normal-sinfonlinea.fqnovel.com"

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
