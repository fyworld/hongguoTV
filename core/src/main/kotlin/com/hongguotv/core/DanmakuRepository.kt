// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** One danmaku comment at its video-relative position. */
data class DanmakuItem(val id: String, val timeMs: Long, val text: String)

/** One window of the danmaku list (30s cursor pagination upstream). */
data class DanmakuPage(val items: List<DanmakuItem>, val nextCursorMs: Long)

/**
 * Danmaku list client: POST /novel/commentapi/comment/list/{videoID}/v1/
 * with the full comment signature chain (DanmakuSigner).
 *
 * Pagination: the response carries data.extra.next_query_danmaku_list_time
 * (a millisecond cursor into the video timeline); the next call passes it as
 * business_param.start_offset_time to fetch the following window.
 */
internal class DanmakuRepository(
    private val http: OkHttpClient,
    private val transport: AppApiTransport,
    private val signer: DanmakuSigner = DanmakuSigner(),
) {
    private companion object {
        /** Server-side campaign comments injected at offset 0 of every episode. */
        val PROMO_TEXTS = setOf(
            "参与弹幕活动，替TA发声！",
            "参与弹幕活动，替TA发声",
        )

        /** 平台表情码 → Unicode emoji（官方 App 渲染为贴图；TV 端转 emoji 显示）。 */
        val EMOTICON_MAP = mapOf(
            "[捂脸]" to "🤦",
            "[送花]" to "💐",
            "[尬笑]" to "😅",
            "[笑哭]" to "😂",
            "[泪]" to "😢",
            "[流泪]" to "😢",
            "[哭]" to "😭",
            "[大哭]" to "😭",
            "[笑]" to "😄",
            "[微笑]" to "🙂",
            "[大笑]" to "😆",
            "[偷笑]" to "🤭",
            "[憨笑]" to "😄",
            "[呲牙]" to "😁",
            "[可爱]" to "🥰",
            "[爱心]" to "❤️",
            "[心]" to "❤️",
            "[比心]" to "🫰",
            "[赞]" to "👍",
            "[强]" to "👍",
            "[点赞]" to "👍",
            "[鼓掌]" to "👏",
            "[握手]" to "🤝",
            "[ok]" to "👌",
            "[OK]" to "👌",
            "[加油]" to "💪",
            "[拳头]" to "✊",
            "[抱拳]" to "🙏",
            "[合十]" to "🙏",
            "[拜托]" to "🙏",
            "[疑问]" to "❓",
            "[问号]" to "❓",
            "[what]" to "❓",
            "[什么鬼]" to "🤨",
            "[汗]" to "😅",
            "[黑线]" to "😵",
            "[晕]" to "😵",
            "[发怒]" to "😡",
            "[怒]" to "😡",
            "[生气]" to "😡",
            "[抓狂]" to "🤯",
            "[鄙视]" to "👎",
            "[弱]" to "👎",
            "[差评]" to "👎",
            "[吃瓜]" to "🍉",
            "[狗头]" to "🐕",
            "[doge]" to "🐕",
            "[玫瑰]" to "🌹",
            "[花]" to "🌸",
            "[太阳]" to "☀️",
            "[月亮]" to "🌙",
            "[星星]" to "⭐",
            "[庆祝]" to "🎉",
            "[烟花]" to "🎆",
            "[爆竹]" to "🧨",
            "[礼物]" to "🎁",
            "[咖啡]" to "☕",
            "[啤酒]" to "🍺",
            "[干杯]" to "🥂",
            "[蛋糕]" to "🎂",
            "[666]" to "🤙",
            "[六六六]" to "🤙",
            "[牛]" to "🐂",
            "[牛牛]" to "🐂",
            "[哈欠]" to "🥱",
            "[睡]" to "😴",
            "[困]" to "🥱",
            "[思考]" to "🤔",
            "[想一下]" to "🤔",
            "[嘘]" to "🤫",
            "[闭嘴]" to "🤐",
            "[打脸]" to "🤦",
            "[惊讶]" to "😲",
            "[吃惊]" to "😲",
            "[震惊]" to "😱",
            "[恐惧]" to "😨",
            "[害怕]" to "😨",
            "[吓]" to "😱",
            "[委屈]" to "🥺",
            "[可怜]" to "🥺",
            "[失望]" to "😞",
            "[无语]" to "😑",
            "[白眼]" to "🙄",
            "[翻白眼]" to "🙄",
            "[哼]" to "😤",
            "[酷]" to "😎",
            "[得意]" to "😎",
            "[色]" to "😍",
            "[花痴]" to "😍",
            "[亲亲]" to "😘",
            "[飞吻]" to "😘",
            "[害羞]" to "😳",
            "[脸红]" to "😳",
            "[口罩]" to "😷",
            "[生病]" to "🤒",
            "[吐]" to "🤮",
            "[吐血]" to "🤮",
            "[便便]" to "💩",
            "[骷髅]" to "💀",
            "[蜡烛]" to "🕯️",
            "[祈祷]" to "🙏",
            "[再见]" to "👋",
            "[拜拜]" to "👋",
            "[挥手]" to "👋",
            "[鼓劲]" to "💪",
            "[胜利]" to "✌️",
            "[耶]" to "✌️",
            "[好耶]" to "🎉",
            "[来]" to "🫵",
            "[让我看看]" to "👀",
            "[看]" to "👀",
            "[看戏]" to "🍿",
            "[瓜子]" to "🍿",
            "[茶]" to "🍵",
            "[辣条]" to "🌶️",
            "[酸]" to "🍋",
            "[柠檬]" to "🍋",
            "[酸哭]" to "🍋",
            "[扎心]" to "💔",
            "[心碎]" to "💔",
            "[伤心]" to "💔",
            "[难过]" to "😔",
            "[叹气]" to "😔",
            "[衰]" to "😖",
            "[苦笑]" to "🫤",
            "[滑稽]" to "🤪",
            "[奸笑]" to "🤨",
            "[坏笑]" to "😏",
            "[阴险]" to "😏",
            "[偷看]" to "👀",
            "[斜眼]" to "😏",
            "[吐舌]" to "😜",
            "[鬼脸]" to "😜",
            "[吐舌头]" to "😜",
            "[转圈]" to "🔄",
            "[跳跳]" to "🤸",
            "[跳]" to "🤸",
            "[干饭]" to "🍚",
            "[饭]" to "🍚",
            "[鸡腿]" to "🍗",
            "[西瓜]" to "🍉",
            "[苹果]" to "🍎",
            "[桃子]" to "🍑",
            "[草莓]" to "🍓",
            "[樱桃]" to "🍒",
            "[香蕉]" to "🍌",
            "[葡萄]" to "🍇",
            "[锦鲤]" to "🐟",
            "[鱼]" to "🐟",
            "[红包]" to "🧧",
            "[钱]" to "💰",
            "[金币]" to "🪙",
            "[发]" to "🀅",
            "[福]" to "🧧",
            "[喜]" to "🥰",
            "[囍]" to "🥰",
            "[音乐]" to "🎵",
            "[音符]" to "🎶",
            "[话筒]" to "🎤",
            "[麦克风]" to "🎤",
            "[电影]" to "🎬",
            "[摄像机]" to "🎥",
            "[照相机]" to "📷",
            "[闪光灯]" to "📸",
            "[书]" to "📖",
            "[读书]" to "📖",
            "[笔]" to "✍️",
            "[写]" to "✍️",
            "[闹钟]" to "⏰",
            "[时钟]" to "⏰",
            "[沙漏]" to "⏳",
            "[锁]" to "🔒",
            "[钥匙]" to "🔑",
            "[灯泡]" to "💡",
            "[闪电]" to "⚡",
            "[火]" to "🔥",
            "[着火]" to "🔥",
            "[燃烧]" to "🔥",
            "[彩虹]" to "🌈",
            "[云]" to "☁️",
            "[雨]" to "🌧️",
            "[雪]" to "❄️",
            "[风]" to "🌬️",
            "[雷]" to "🌩️",
            "[雾]" to "🌫️",
            "[地球]" to "🌍",
            "[地球仪]" to "🌍",
            "[火箭]" to "🚀",
            "[飞机]" to "✈️",
            "[高铁]" to "🚄",
            "[汽车]" to "🚗",
            "[车]" to "🚗",
            "[船]" to "⛵",
            "[帆船]" to "⛵",
            "[自行车]" to "🚲",
            "[摩托车]" to "🏍️",
            "[房子]" to "🏠",
            "[家]" to "🏠",
            "[城堡]" to "🏰",
            "[岛]" to "🏝️",
            "[山]" to "⛰️",
            "[树]" to "🌳",
            "[草]" to "🌿",
            "[叶子]" to "🍃",
            "[花盆]" to "🪴",
            "[仙人掌]" to "🌵",
            "[向日葵]" to "🌻",
            "[郁金香]" to "🌷",
            "[四叶草]" to "🍀",
            "[幸运草]" to "🍀",
            "[枫叶]" to "🍁",
            "[麦穗]" to "🌾",
            "[鸟]" to "🐦",
            "[鸽子]" to "🕊️",
            "[鸡]" to "🐔",
            "[鸭]" to "🦆",
            "[鹅]" to "🦢",
            "[兔子]" to "🐰",
            "[猫]" to "🐱",
            "[狗]" to "🐶",
            "[猪]" to "🐷",
            "[羊]" to "🐑",
            "[马]" to "🐴",
            "[老虎]" to "🐯",
            "[狮子]" to "🦁",
            "[熊]" to "🐻",
            "[熊猫]" to "🐼",
            "[考拉]" to "🐨",
            "[狐狸]" to "🦊",
            "[猴子]" to "🐵",
            "[恐龙]" to "🦖",
            "[龙]" to "🐉",
            "[凤凰]" to "🦅",
            "[鹰]" to "🦅",
            "[狼]" to "🐺",
            "[鲨鱼]" to "🦈",
            "[鲸鱼]" to "🐋",
            "[海豚]" to "🐬",
            "[章鱼]" to "🐙",
            "[螃蟹]" to "🦀",
            "[虾]" to "🦐",
            "[贝壳]" to "🐚",
            "[蝴蝶]" to "🦋",
            "[蜜蜂]" to "🐝",
            "[蚂蚁]" to "🐜",
            "[蜘蛛]" to "🕷️",
            "[蜗牛]" to "🐌",
            "[蛇]" to "🐍",
            "[乌龟]" to "🐢",
            "[青蛙]" to "🐸",
            "[鳄鱼]" to "🐊",
            "[仓鼠]" to "🐹",
            "[老鼠]" to "🐭",
        )
    }
    /**
     * Fetch one window. [startOffsetMs] starts at 0 and follows
     * [DanmakuPage.nextCursorMs]; [durationMs] is the episode duration
     * (business_param.playlet_item_duration).
     */
    fun page(videoId: String, seriesId: String, startOffsetMs: Long, durationMs: Long): DanmakuPage {
        val payload = JSONObject()
            .put("comment_source", 601).put("server_channel", 1000)
            .put("group_id", videoId).put("group_type", 30)
            .put("comment_type", 20).put("sort", 1).put("count", 90)
            .put("cursor", "").put("aid", 8662).put("compliance_status", 0)
            .put("business_param", JSONObject()
                .put("book_id", seriesId)
                .put("start_offset_time", startOffsetMs)
                .put("playlet_item_duration", durationMs)
                .put("need_danmaku_guide_type", org.json.JSONArray().put(1).put(3).put(4).put(2)))
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val query = transport.encodeQuery(transport.queryProfile())
        val signed = signer.sign(query)
        val builder = Request.Builder()
            .url("${AppApiTransport.APP_BASE}/novel/commentapi/comment/list/$videoId/v1/?$query")
            .header("User-Agent", transport.userAgent)
            .header("Accept", "application/json")
            .header("X-XS-From-Web", "0").header("Sdk-Version", "2")
            .header("Comment-Source", "601").header("Server-Channel", "1000")
            .header("X-SS-STUB", "")
        for ((k, v) in signed.headers) builder.header(k, v)
        builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return RequestScope.execute(http.newCall(builder.build())) { response ->
            if (!response.isSuccessful) throw IOException("弹幕接口请求失败（HTTP ${response.code}）")
            val text = response.body?.string() ?: throw IOException("弹幕接口响应为空")
            if (text.isEmpty()) throw IOException("弹幕接口返回空数据")
            val data = JSONObject(text)
            val code = data.optInt("code", data.optInt("Code", 0))
            if (code != 0) throw IOException("弹幕接口暂不可用（$code）")
            val rows = data.optJSONObject("data")?.optJSONArray("data_list") ?: org.json.JSONArray()
            val items = rows.objects().mapNotNull { row ->
                val comment = row.optJSONObject("comment") ?: return@mapNotNull null
                val common = comment.optJSONObject("common") ?: return@mapNotNull null
                val textContent = common.optJSONObject("content")?.str("text") ?: ""
                if (textContent.isEmpty()) return@mapNotNull null
                // Server-side promo comments (参与弹幕活动，替TA发声！) ride every episode's
                // window at offset 0; they are ad copy, not viewer comments - drop them.
                if (textContent in PROMO_TEXTS) return@mapNotNull null
                val id = comment.str("comment_id").ifEmpty { return@mapNotNull null }
                val timeMs = comment.optJSONObject("expand")?.str("offset_time")?.toLongOrNull() ?: return@mapNotNull null
                // 平台表情码转 Unicode emoji（官方 App 渲染为贴图，TV 端无贴图资源）。
                // 无 '[' 的文本直接跳过（绝大多数弹幕不含表情码，省 250 次扫描）。
                val mapped = if (textContent.contains('['))
                    EMOTICON_MAP.entries.fold(textContent) { acc, (code, emoji) -> acc.replace(code, emoji) }
                else textContent
                DanmakuItem(id, timeMs, mapped)
            }
            val next = data.optJSONObject("data")?.optJSONObject("extra")?.optLong("next_query_danmaku_list_time", -1L) ?: -1L
            DanmakuPage(items, next)
        }
    }
}
