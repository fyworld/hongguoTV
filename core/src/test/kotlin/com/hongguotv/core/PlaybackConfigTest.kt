// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 播放配置热更新（2026-11-24）。
 *
 * 背景：11 月六次断播里有四次变的是**数据**（画像 / 路径 / medusa 常量 / 顺序），
 * 却每次都要重新编译发版。这里锁住「拉取 → 校验 → 兜底 → 自愈」这条链路，
 * 保证远端配置出错时客户端永远还能用内置基线播。
 */
class PlaybackConfigTest {
    @Before fun reset() {
        PlaybackConfigStore.reset()
        PlaybackConfigStore.remoteEnabled = false
    }

    @After fun cleanup() {
        PlaybackConfigStore.reset()
        PlaybackConfigStore.remoteEnabled = false
    }

    private fun stub(body: (String) -> String) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val text = body(request.url.toString())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(text.toResponseBody()).build()
        }.build()

    private fun configJson(
        version: Int = 2,
        field21: Int = 312,
        channel: String = "update_64",
        order: String = "[\"player-v5\",\"get-v5\"]",
    ): String = """
        {
          "schema": 1, "configVersion": $version, "minAppVersion": 0, "notes": "t",
          "medusa": { "field21": $field21 },
          "appQuery": {
            "aid": "8662", "app_name": "novelread", "version_code": "74532",
            "version_name": "7.4.5.32", "channel": "$channel", "device_type": "25053RT47C",
            "os_version": "16", "os_api": "36"
          },
          "ua": { "getTab": "com.phoenix.read/73932 (Linux; U; Android 16)", "post": "" },
          "endpoints": [
            { "id": "get", "method": "GET", "host": "https://api5-normal-sinfonlineb.fqnovel.com",
              "segment": "reading/bookapi/video_tab/multi_video_model/v1", "signer": "v5" },
            { "id": "player", "method": "POST", "host": "https://api5-normal-sinfonlinea.fqnovel.com",
              "segment": "novel/player/multi_video_model/player/v1", "signer": "v5" }
          ],
          "shapeOrder": $order,
          "budgetFloorMs": 600
        }
    """.trimIndent()

    // ---------- 解析与校验 ----------

    @Test fun builtinBaselineEqualsTheShippedDefaults() {
        val builtin = PlaybackConfigStore.current()
        // 与 configs/playback.json 必须一致：远端与内置同源，只是投递方式不同。
        assertEquals(312, builtin.medusaField21)
        assertEquals("update_64", builtin.appQuery["channel"])
        assertEquals("25053RT47C", builtin.appQuery["device_type"])
        assertEquals(4, builtin.endpoints.size)
        assertEquals("builtin", PlaybackConfigStore.source)
    }

    @Test fun rejectsHostsOutsideTheWhitelist() {
        val bad = configJson().replace("api5-normal-sinfonlineb.fqnovel.com", "evil.example.com")
        assertFalse(PlaybackConfigStore.install(bad, "test"))
        assertEquals(312, PlaybackConfigStore.current().medusaField21)
        assertEquals("builtin", PlaybackConfigStore.source)
    }

    @Test fun rejectsUnknownQueryKeysAndBadSchema() {
        assertFalse(PlaybackConfigStore.install(configJson().replace("\"aid\": \"8662\"", "\"hack\": \"1\""), "test"))
        assertFalse(PlaybackConfigStore.install(configJson().replace("\"schema\": 1", "\"schema\": 9"), "test"))
        assertFalse(PlaybackConfigStore.install(configJson().replace("\"configVersion\": 2", "\"configVersion\": 0"), "test"))
    }

    @Test fun rejectsDowngradeButAcceptsSameOrHigherVersion() {
        assertTrue(PlaybackConfigStore.install(configJson(version = 5), "test"))
        assertFalse("低版本配置必须被忽略", PlaybackConfigStore.install(configJson(version = 4), "test"))
        assertTrue("同版本可重装（幂等）", PlaybackConfigStore.install(configJson(version = 5, field21 = 320), "test"))
        assertEquals(320, PlaybackConfigStore.current().medusaField21)
    }

    @Test fun minAppVersionHigherThanInstalledIsRejected() {
        PlaybackConfigStore.setAppVersion(10053)
        val future = configJson(version = 3).replace("\"minAppVersion\": 0", "\"minAppVersion\": 10099")
        assertFalse(PlaybackConfigStore.install(future, "test"))
        assertEquals(1, PlaybackConfigStore.current().configVersion)
    }

    // ---------- 生效点 ----------

    @Test fun installedConfigDrivesQueryField21AndOrder() {
        val device = mapOf("device_id" to "1", "iid" to "2", "cdid" to "3")
        assertTrue(PlaybackConfigStore.install(configJson(version = 7, field21 = 333, channel = "update_64_x"), "test"))
        val config = PlaybackConfigStore.current()
        assertEquals(333, config.medusaField21)
        assertEquals("update_64_x", config.appQuery["channel"])
        // 签名器读的是同一个值（medusa 明文 wire field 21）
        assertEquals(333, V5Constants.field21)
        // 形态顺序按配置重排：player-v5 提到最前
        assertEquals("player-v5", PlaybackShape.all.first().label)
        // 未列出的形态附加在后面，不会丢
        assertEquals(8, PlaybackShape.all.size)
        assertTrue(PlaybackShape.all.any { it.label == "preload-argus" })
        // 端点参数来自配置
        assertEquals("/novel/player/multi_video_model/player/v1", PlaybackPath.POST_PLAYER.resolve().absolutePath)
        assertEquals("reading.snssdk.com", PlaybackPath.POST_PRELOAD.resolve().host.substringAfter("//"))
        assertEquals(2, PlaybackPath.POST_PRELOAD.resolve().deviceLevel)
    }

    @Test fun deviceFieldsAreInjectedAtRuntimeNotFromConfig() {
        val device = mapOf("device_id" to "111", "iid" to "222", "cdid" to "333")
        assertTrue(PlaybackConfigStore.install(configJson(), "test"))
        val captured = ArrayList<String>()
        val http = stub { url -> captured += url; "{}" }
        runCatching {
            MobilePlayback.model("1", device, http, MobilePlayback.signer, 1000)
        }
        assertEquals(1, captured.size)
        val query = captured.first().substringAfter("?")
        assertTrue("device_id 必须来自运行时身份", query.contains("device_id=111"))
        assertTrue(query.contains("iid=222"))
        assertTrue(query.contains("cdid=333"))
        assertTrue("画像字段来自配置", query.contains("channel=update_64"))
    }

    // ---------- 远端拉取与兜底 ----------

    @Test fun refreshInstallsRemoteConfigFromTheFirstWorkingSource() {
        PlaybackConfigStore.remoteEnabled = true
        val http = stub { configJson(version = 9, field21 = 401) }
        assertTrue(PlaybackConfigStore.refresh(http, force = true))
        assertEquals(9, PlaybackConfigStore.current().configVersion)
        assertEquals(401, PlaybackConfigStore.current().medusaField21)
        assertTrue(PlaybackConfigStore.source.startsWith("remote:"))
    }

    @Test fun brokenRemoteKeepsTheLastGoodConfig() {
        PlaybackConfigStore.remoteEnabled = true
        assertTrue(PlaybackConfigStore.install(configJson(version = 4, field21 = 350), "test"))
        val http = stub { """{"schema":1,"configVersion":5}""" } // 缺字段 → 校验失败
        assertFalse(PlaybackConfigStore.refresh(http, force = true))
        assertEquals(350, PlaybackConfigStore.current().medusaField21)
        assertEquals(4, PlaybackConfigStore.current().configVersion)
    }

    @Test fun cacheFileRoundTripAndCorruptionFallback() {
        val dir = File(System.getProperty("java.io.tmpdir"), "playback-config-test-${System.nanoTime()}")
        dir.mkdirs()
        val file = File(dir, "playback-config.json")
        try {
            PlaybackConfigStore.bindCache(file) // 文件还不存在 → 保持内置基线
            assertTrue(PlaybackConfigStore.install(configJson(version = 6, field21 = 377), "test"))
            assertTrue(file.exists())
            // 模拟重启：内存清空后从缓存恢复
            PlaybackConfigStore.reset()
            PlaybackConfigStore.bindCache(file)
            assertEquals(6, PlaybackConfigStore.current().configVersion)
            assertEquals(377, PlaybackConfigStore.current().medusaField21)
            assertEquals("cache", PlaybackConfigStore.source)
            // 缓存被写坏 → 回落到内置基线，不崩
            file.writeText("{broken", Charsets.UTF_8)
            PlaybackConfigStore.reset()
            PlaybackConfigStore.bindCache(file)
            assertEquals("builtin", PlaybackConfigStore.source)
            assertEquals(312, PlaybackConfigStore.current().medusaField21)
        } finally {
            file.delete(); dir.delete()
        }
    }

    @Test fun refreshIsThrottledUnlessForced() {
        PlaybackConfigStore.remoteEnabled = true
        var hits = 0
        val http = stub { hits++; configJson(version = 3) }
        val now = 1_800_000_000_000L
        assertTrue(PlaybackConfigStore.refresh(http, force = false, now = now))
        assertFalse("24h 内不重复拉", PlaybackConfigStore.refresh(http, force = false, now = now + 1000))
        // 自愈（force）有 5 分钟节流
        assertFalse(PlaybackConfigStore.refresh(http, force = true, now = now + 1000))
        assertTrue(PlaybackConfigStore.refresh(http, force = true, now = now + 6 * 60 * 1000L))
        assertEquals(2, hits)
    }

    @Test fun sourcesCoverDomesticMirrorsAndAreAllHttps() {
        val sources = PlaybackConfigStore.SOURCES
        assertTrue("至少要有多个源", sources.size >= 4)
        assertTrue("必须全是 https", sources.all { it.startsWith("https://") })
        assertEquals("不能有重复源", sources.size, sources.distinct().size)
        // 国内网络 raw 直连基本不通，必须至少有一个 jsDelivr CNAME 和一个 GitHub 加速中转
        assertTrue(sources.any { it.contains("jsdelivr.net") })
        assertTrue(sources.any { it.contains("ghfast.top") || it.contains("gh-proxy.com") || it.contains("ghproxy.net") })
        // raw 兜底保留在最后（海外/已代理网络最快，但国内一般最后才用）
        assertTrue(sources.last().startsWith("https://raw.githubusercontent.com/"))
    }

    @Test fun failureBacksOffThirtyMinutesInsteadOfTwentyFourHours() {
        PlaybackConfigStore.remoteEnabled = true
        var hits = 0
        val http = stub { hits++; """{"schema":1}""" } // 校验必失败
        val now = 1_800_000_000_000L
        assertFalse(PlaybackConfigStore.refresh(http, now = now))
        assertEquals("每个源都要试一次", PlaybackConfigStore.SOURCES.size, hits)
        // 失败后 30 分钟内不重试（镜像临时抖动时不会一直空转）
        assertFalse(PlaybackConfigStore.refresh(http, now = now + 10 * 60_000L))
        assertEquals(PlaybackConfigStore.SOURCES.size, hits)
        // 超过退避期才重试
        assertFalse(PlaybackConfigStore.refresh(http, now = now + 31 * 60_000L))
        assertEquals(PlaybackConfigStore.SOURCES.size * 2, hits)
    }

    @Test fun installingANewConfigClearsTheLearnedShape() {
        // 旧配置下学到 get-v5 可用；配置一换，旧形态可能已失效，必须清空重学
        PlaybackShapeMemory.reset()
        PlaybackShapeMemory.restore(PlaybackShape(PlaybackPath.GET_VIDEO_TAB, PlaybackSigner.V5))
        assertEquals("get-v5", PlaybackShapeMemory.current()?.label)
        assertTrue(PlaybackConfigStore.install(configJson(version = 8), "test"))
        org.junit.Assert.assertNull("配置变更后形态记忆必须清空", PlaybackShapeMemory.current())
        // 同版本重装不算变更，不清记忆
        PlaybackShapeMemory.restore(PlaybackShape(PlaybackPath.POST_PLAYER, PlaybackSigner.V5))
        assertTrue(PlaybackConfigStore.install(configJson(version = 8), "test"))
        assertEquals("player-v5", PlaybackShapeMemory.current()?.label)
        PlaybackShapeMemory.reset()
    }

    @Test fun selfHealIsDisabledWhileRemoteIsOff() {        // remoteEnabled=false 时（单元测试默认）绝不能起后台线程去打外网
        PlaybackConfigStore.remoteEnabled = false
        PlaybackConfigStore.healAfterEmptyAsync(OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build())
        assertEquals("builtin", PlaybackConfigStore.source)
    }

    /**
     * 仓库里的 `configs/playback.json` 必须能被解析，且与内置基线等价。
     * 这条测试防的是「改了代码忘了改配置文件」——两者一旦漂移，远端下发就会把线上打坏。
     */
    @Test fun shippedConfigFileParsesAndMatchesBuiltin() {
        val candidates = listOf(
            File("../configs/playback.json"),          // 模块内运行：kotlin-tv/core → kotlin-tv/configs
            File("configs/playback.json"),             // 仓库根运行
            File("../../configs/playback.json"),       // 兼容旧布局
        )
        val file = candidates.firstOrNull { it.isFile }
        org.junit.Assume.assumeTrue(
            "configs/playback.json 不在预期位置（${candidates.first().absolutePath}）", file != null)
        val parsed = PlaybackConfig.parse(file!!.readText(Charsets.UTF_8))
        val builtin = PlaybackConfig.defaults()
        assertEquals(builtin.medusaField21, parsed.medusaField21)
        assertEquals(builtin.appQuery, parsed.appQuery)
        assertEquals(builtin.uaGetTab, parsed.uaGetTab)
        assertEquals(builtin.endpoints, parsed.endpoints)
        assertEquals(builtin.shapeOrder, parsed.shapeOrder)
        assertEquals(builtin.budgetFloorMs, parsed.budgetFloorMs)
    }
}
