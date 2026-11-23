// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 响应里取 `video_model` 的路径解析（P1-a，2026-11-24）。
 *
 * 以前这一步写死在代码里：POST 认 `data["1004"][id].video_model`、GET 认
 * `data.video_model_data[id].video_model`。平台 11-02 把前者从 `video_model_data`
 * 改成 `1004` 时，这种"结构变更"就得发版。现在路径由 [PlaybackConfig.responsePick]
 * 提供，按顺序尝试，命中即停——结构再变，改配置即可。
 *
 * 路径语法（故意做得很小，避免配置变成表达式引擎）：
 *  - `data.1004.{id}.video_model` —— `{id}` 替换成当前集号
 *  - `data.list[0].video_model`   —— 数组下标
 *  - 任一层缺失/类型不符 → 返回 null，换下一条
 */
internal object PlaybackResponse {
    /**
     * 按配置顺序取出并解析 `video_model`；取到但字段校验不过也继续尝试下一条。
     * 全部失败返回 null（调用方抛「播放信息未包含指定剧集」）。
     */
    fun modelOf(root: JSONObject, id: String, config: PlaybackConfig): JSONObject? {
        for (path in config.responsePick) {
            val value = extract(root, path, id) ?: continue
            val model = when (value) {
                is JSONObject -> value
                is String -> runCatching { JSONObject(value) }.getOrNull()
                else -> null
            } ?: continue
            if (config.responseRequire.isEmpty() || config.responseRequire.any { model.has(it) }) return model
        }
        return null
    }

    /** 极简路径求值；任何一层不匹配返回 null。 */
    fun extract(root: Any?, path: String, id: String): Any? {
        var current: Any? = root
        for (raw in path.split('.')) {
            if (raw.isEmpty()) return null
            current = step(current, raw.replace("{id}", id))
            if (current == null) return null
        }
        return current
    }

    private fun step(node: Any?, segment: String): Any? {
        var key = segment
        val indexes = ArrayList<Int>(2)
        while (key.endsWith(']')) {
            val open = key.lastIndexOf('[')
            if (open <= 0) break
            val index = key.substring(open + 1, key.length - 1).toIntOrNull() ?: return null
            indexes.add(0, index)
            key = key.substring(0, open)
        }
        var current: Any? = node
        if (key.isNotEmpty()) {
            val obj = current as? JSONObject ?: return null
            if (!obj.has(key)) return null
            current = obj.opt(key)
        }
        for (index in indexes) {
            val array = current as? JSONArray ?: return null
            current = array.opt(index)
        }
        return current
    }
}
