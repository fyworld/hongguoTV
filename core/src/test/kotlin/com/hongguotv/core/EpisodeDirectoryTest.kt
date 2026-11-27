// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 分集目录（2026-11-27 平台改版回归）。
 *
 * 网站详情页从这天起只给 3 条混淆 vid（`v1_xxx`），完整目录改走 App 的
 * `/reading/bookapi/directory/all_items/v/`。这里锁住「什么时候该信网页、什么时候该走接口」。
 */
class EpisodeDirectoryTest {
    private fun detail(json: String) = JSONObject(json)

    @Test fun completeNumericListFromWebIsTrusted() {
        val o = detail("""{"episode_cnt":2,"vid_list":["111","222"]}""")
        assertEquals(listOf("111", "222"), EpisodeDirectory.fromWeb(o))
        assertEquals(2, EpisodeDirectory.expectedCount(o))
    }

    @Test fun obfuscatedPreviewIdsAreRejected() {
        // 2026-11-27 起网站返回的就是这种形式
        val o = detail("""{"episode_cnt":137,"vid_list":["v1_TeqlRcHZ9tfN6rJscA8R5dH8DQY","v1_FYkNy9-upj8KVFwcvctAqEKR9ec","v1_djWZWJ3cAl75KHASpJId5GEMBcw"]}""")
        assertNull("混淆 id 必须判为无效", EpisodeDirectory.fromWeb(o))
        assertEquals(137, EpisodeDirectory.expectedCount(o))
    }

    @Test fun truncatedDuplicateOrEmptyListsAreRejected() {
        assertNull(EpisodeDirectory.fromWeb(detail("""{"episode_cnt":137,"vid_list":["111","222","333"]}""")))
        assertNull(EpisodeDirectory.fromWeb(detail("""{"episode_cnt":2,"vid_list":["111","111"]}""")))
        assertNull(EpisodeDirectory.fromWeb(detail("""{"episode_cnt":2,"vid_list":[]}""")))
        // 未声明总数（episode_cnt=0）时不按数量判无效，与上游一致
        assertEquals(listOf("111"), EpisodeDirectory.fromWeb(detail("""{"episode_cnt":0,"vid_list":["111"]}""")))
        assertNull(EpisodeDirectory.fromWeb(detail("""{"episode_cnt":1}""")))
    }

    @Test fun expectedCountUsesTheLargerOfBothFields() {
        val o = detail("""{"episode_cnt":0,"series_episode_info":{"episode_cnt":88},"vid_list":[]}""")
        assertEquals(88, EpisodeDirectory.expectedCount(o))
    }

    @Test fun parsesAppDirectoryResponse() {
        val body = """{"code":0,"data":{"item_data_list":[{"item_id":"11"},{"item_id":"22"},{"item_id":"33"}]}}"""
        assertEquals(listOf("11", "22", "33"), EpisodeDirectory.parse(body, 3))
        // 无 code 时兼容 message=SUCCESS
        val alt = """{"message":"SUCCESS","data":{"item_data_list":[{"item_id":"11"},{"item_id":"22"}]}}"""
        assertEquals(listOf("11", "22"), EpisodeDirectory.parse(alt, 2))
    }

    @Test fun rejectsIncompleteOrRejectedDirectoryResponses() {
        // 数量不足
        assertThrows(IOException::class.java) {
            EpisodeDirectory.parse("""{"code":0,"data":{"item_data_list":[{"item_id":"11"}]}}""", 3)
        }
        // 业务码失败
        assertThrows(IOException::class.java) {
            EpisodeDirectory.parse("""{"code":100103,"message":"PARAM_INVALID"}""", 3)
        }
        // 坏 JSON
        assertThrows(IOException::class.java) { EpisodeDirectory.parse("not json", 3) }
        // 非数字 id
        assertThrows(IOException::class.java) {
            EpisodeDirectory.parse("""{"code":0,"data":{"item_data_list":[{"item_id":"v1_aaa"},{"item_id":"22"}]}}""", 2)
        }
    }
}
