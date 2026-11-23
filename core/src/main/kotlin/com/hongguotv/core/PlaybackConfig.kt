// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放接口的**可变部分**（请求画像 / 路径 / 签名常量 / 形态顺序）。
 *
 * 为什么要有这个东西（2026-11 的六次断播教训）：平台每次变的是**数据**——
 *  - 11-20：只放平铺 `v1`（路径）
 *  - 11-22：只放 `player/v1`（路径）
 *  - 11-23：路径全通，但改按「请求画像」校验；medusa 明文 field21 377→312
 *
 * 这三次全都只改参数，却每次都要重新编译 + 发 10MB APK。把这类数据抽出来之后，
 * 平台再变只需要 `git push` 一个 JSON，客户端拉下来即可生效（见 [PlaybackConfigStore]）。
 *
 * **不能**配置化的：签名算法本身（AesV3 载波、hashF13 分支、medusa 结构）——
 * 那类变更（如 11-16 的 v5 协议换代）仍然必须改代码发版。
 *
 * 校验规则见 [parse]：域名白名单、id 白名单、字段长度上限、总大小上限，
 * 防止这个远程入口变成任意请求/DoS 面。
 */
data class PlaybackConfig(
    val schema: Int,
    val configVersion: Int,
    val minAppVersion: Int,
    val notes: String,
    /** medusa 明文 wire field 21（官方 7.4.5.32 = 312；旧值 377 已失效）。 */
    val medusaField21: Int,
    /** 请求画像的**静态字段**（不含 iid/cdid/device_id，那三个是设备相关，运行时注入）。 */
    val appQuery: Map<String, String>,
    /** GET video_tab 形态的 UA。 */
    val uaGetTab: String,
    /** POST 形态的 UA；空串 = 用系统 `http.agent`（上游 ef328f3 证明 UA 不敏感）。 */
    val uaPost: String,
    val endpoints: List<PlaybackEndpoint>,
    /** 形态尝试顺序（如 "get-v5"）；空 = 用内置顺序。 */
    val shapeOrder: List<String>,
    /** 每条形态的最小超时预算（毫秒）。 */
    val budgetFloorMs: Long,
) {
    fun endpoint(id: String): PlaybackEndpoint? = endpoints.firstOrNull { it.id == id }

    /** 按 [PlaybackPath] 查对应端点（id 与 path.label 同名）。 */
    fun endpointFor(path: PlaybackPath): PlaybackEndpoint? = endpoint(path.label)

    companion object {
        const val SCHEMA = 1

        /** 允许的域名（子域 OK）；防止配置把请求引到任意主机。 */
        private val ALLOWED_HOSTS = listOf("fqnovel.com", "snssdk.com")

        /** appQuery 允许的字段白名单（设备相关三项由运行时注入，不进配置）。 */
        private val QUERY_KEYS = setOf(
            "aid", "app_name", "version_code", "version_name",
            "update_version_code", "manifest_version_code", "os", "ssmix",
            "channel", "device_platform", "device_type", "device_brand",
            "os_version", "os_api", "ac", "language", "host_abi", "pv_player",
        )

        private val ENDPOINT_IDS = setOf("get", "player", "preload", "v1")
        private val SHAPE_LABELS = ENDPOINT_IDS.flatMap { id -> listOf("$id-v5", "$id-argus") }.toSet()
        private val SEGMENT = Regex("/?[A-Za-z0-9_]+(/[A-Za-z0-9_]+){1,6}")
        private const val MAX_BYTES = 64 * 1024
        private const val MAX_QUERY_VALUE = 64

        /**
         * APK 内置基线：与 `configs/playback.json` 的内容应保持一致。
         * 远端配置拉不到 / 校验失败 / minAppVersion 不满足时一律用这一份，
         * 保证最坏情况等于「当前已能播的版本」。
         */
        fun defaults(): PlaybackConfig = PlaybackConfig(
            schema = SCHEMA,
            configVersion = 1,
            minAppVersion = 0,
            notes = "builtin baseline (2026-11-23 74532 profile + field21=312)",
            medusaField21 = 312,
            appQuery = linkedMapOf(
                "aid" to "8662", "app_name" to "novelread",
                "version_code" to "74532", "version_name" to "7.4.5.32",
                "update_version_code" to "74532", "manifest_version_code" to "74532",
                "os" to "android", "ssmix" to "a", "channel" to "update_64",
                "device_platform" to "android", "device_type" to "25053RT47C",
                "device_brand" to "Redmi", "os_version" to "16", "os_api" to "36",
                "ac" to "wifi", "language" to "zh", "host_abi" to "arm64-v8a",
            ),
            uaGetTab = MobilePlayback.GET_TAB_UA,
            uaPost = "",
            endpoints = listOf(
                PlaybackEndpoint("get", "GET", MobilePlayback.GET_TAB_BASE, MobilePlayback.GET_TAB_SEGMENT),
                PlaybackEndpoint("player", "POST", MobilePlayback.MODEL_BASE, MobilePlayback.PLAYER_SEGMENT),
                PlaybackEndpoint(
                    "preload", "POST", MobilePlayback.PRELOAD_BASE, MobilePlayback.PRELOAD_SEGMENT,
                    drScene = "preload", deviceLevel = 2),
                PlaybackEndpoint("v1", "POST", MobilePlayback.MODEL_BASE, MobilePlayback.MODEL_SEGMENT),
            ),
            shapeOrder = listOf(
                "get-v5", "player-v5", "preload-v5", "v1-argus",
                "get-argus", "v1-v5", "player-argus", "preload-argus",
            ),
            budgetFloorMs = 600L,
        )

        /**
         * 解析并校验远端/缓存配置。任何一条不合规都抛 [IllegalArgumentException]，
         * 调用方据此回退到 [defaults]（宁可用旧的不出错，也不要用新的踩雷）。
         */
        @Throws(IllegalArgumentException::class)
        fun parse(raw: String, appVersionCode: Int = 0): PlaybackConfig {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "配置过大" }
            val root = runCatching { JSONObject(raw) }
                .getOrElse { throw IllegalArgumentException("配置不是合法 JSON") }
            val schema = root.optInt("schema", -1)
            require(schema == SCHEMA) { "schema 不兼容：$schema" }
            val version = root.optInt("configVersion", 0)
            require(version >= 1) { "configVersion 非法" }
            val minApp = root.optInt("minAppVersion", 0)
            require(minApp <= 0 || appVersionCode <= 0 || appVersionCode >= minApp) {
                "需要更新的 App（minAppVersion=$minApp）"
            }

            val medusa = root.optJSONObject("medusa")
            val field21 = medusa?.optInt("field21", Int.MIN_VALUE) ?: Int.MIN_VALUE
            require(field21 in 1..100_000) { "medusa.field21 非法" }

            val queryJson = root.optJSONObject("appQuery") ?: throw IllegalArgumentException("缺少 appQuery")
            require(queryJson.length() in 3..24) { "appQuery 字段数量异常" }
            val query = linkedMapOf<String, String>()
            for (key in queryJson.keys()) {
                require(key in QUERY_KEYS) { "appQuery 不允许的字段：$key" }
                val value = queryJson.optString(key, "")
                require(value.length <= MAX_QUERY_VALUE) { "appQuery 字段过长：$key" }
                query[key] = value
            }

            val ua = root.optJSONObject("ua")
            val uaGetTab = ua?.optString("getTab", "").orEmpty()
            require(uaGetTab.startsWith("com.phoenix.read/")) { "ua.getTab 非法" }
            val uaPost = ua?.optString("post", "").orEmpty()
            require(uaPost.isEmpty() || uaPost.startsWith("com.phoenix.read/")) { "ua.post 非法" }

            val array = root.optJSONArray("endpoints") ?: throw IllegalArgumentException("缺少 endpoints")
            val endpoints = ArrayList<PlaybackEndpoint>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: throw IllegalArgumentException("endpoints[$i] 非法")
                val id = o.optString("id", "")
                require(id in ENDPOINT_IDS) { "未知 endpoint id：$id" }
                require(endpoints.none { it.id == id }) { "endpoint 重复：$id" }
                val method = o.optString("method", "POST").uppercase()
                require(method == "GET" || method == "POST") { "method 非法：$method" }
                val host = o.optString("host", "")
                require(allowedHost(host)) { "host 不在白名单：$host" }
                val segment = o.optString("segment", "")
                require(SEGMENT.matches(segment) && !segment.contains("..")) { "segment 非法：$segment" }
                val signer = o.optString("signer", "v5").lowercase()
                require(signer == "v5" || signer == "argus") { "signer 非法：$signer" }
                val drScene = o.optString("drScene", "").ifBlank { null }
                require(drScene == null || drScene.length <= 32) { "drScene 过长" }
                val level = o.optInt("deviceLevel", 1)
                require(level in 1..4) { "deviceLevel 非法" }
                endpoints += PlaybackEndpoint(id, method, host.trimEnd('/'), segment, signer, drScene, level)
            }
            require(endpoints.isNotEmpty()) { "endpoints 为空" }
            require(endpoints.any { it.method == "POST" }) { "至少需要一条 POST 形态" }

            // shapeOrder 可选：显式列出 "路径-签名" 组合的尝试顺序（平台换优先路径时免发版）。
            val orderJson = root.optJSONArray("shapeOrder")
            val shapeOrder = if (orderJson == null) emptyList()
            else (0 until orderJson.length()).map { orderJson.optString(it, "") }
            require(shapeOrder.all { SHAPE_LABELS.contains(it) }) { "shapeOrder 有未知组合" }
            require(shapeOrder.distinct().size == shapeOrder.size) { "shapeOrder 有重复" }

            val floor = root.optLong("budgetFloorMs", 600L)
            require(floor in 200L..5_000L) { "budgetFloorMs 非法" }

            return PlaybackConfig(
                schema = schema,
                configVersion = version,
                minAppVersion = minApp.coerceAtLeast(0),
                notes = root.optString("notes", "").take(200),
                medusaField21 = field21,
                appQuery = query,
                uaGetTab = uaGetTab,
                uaPost = uaPost,
                endpoints = endpoints,
                shapeOrder = shapeOrder,
                budgetFloorMs = floor,
            )
        }

        private fun allowedHost(host: String): Boolean {
            if (!host.startsWith("https://")) return false
            val name = host.removePrefix("https://").substringBefore('/').lowercase()
            if (name.isEmpty() || name.contains("..")) return false
            return ALLOWED_HOSTS.any { name == it || name.endsWith(".$it") }
        }
    }
}

/** 一个可配置的请求端点（路径 + 请求体差异）。 */
data class PlaybackEndpoint(
    val id: String,
    val method: String,
    val host: String,
    val segment: String,
    val signer: String = "v5",
    val drScene: String? = null,
    val deviceLevel: Int = 1,
) {
    val absolutePath: String get() = "/${segment.trimStart('/')}"
}
