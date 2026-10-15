package com.xjtu.toolbox.agent

import com.xjtu.toolbox.util.isArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import com.xjtu.toolbox.util.requireArr
import com.xjtu.toolbox.util.stringValue

/**
 * 屁岱的图片输入。
 *
 * 走 OpenAI 兼容的 `content` 数组（`{"type":"image_url","image_url":{"url":"data:…;base64,…"}}`），
 * DeepSeek、OpenAI、以及绝大多数中转都是这一套。
 *
 * ### 为什么要在本地先压
 * 手机相册随手一张就是 4000×3000、5 MB 起步，而服务端拿到图后自己会缩到约 1300×1300
 * （每张图最多约 1024 token）——原图那几兆流量纯属白烧，在校园网上还容易直接超时。
 * 所以 [AgentHost] 那一侧统一缩到 1300 并转成 JPEG 再落盘。
 *
 * ### 为什么落盘而不是只留 content://
 * 相册给的 `content://` URI 是**临时授权**，进程重启、甚至用户在相册里删掉原图之后就读不到了，
 * 而会话是要存盘、下次还能翻回来的。所以选中即拷一份压缩件到应用私有目录，
 * 消息里存的是这份拷贝的路径。
 *
 * ## 这里只有「拿到路径之后怎么用」
 *
 * 压缩落盘是平台的事（`:app` = `ImageDecoder` +
 * `Bitmap` + `contentResolver`，见那个模块的 `AndroidAgentHost.attachImage`），
 * 而「这个模型认不认图」「怎么拼成 content 数组」「什么时候把老图摘掉」是纯逻辑。
 * [userContent] 要读文件字节时问 [AgentHost.imageDataUri]（`:core` 没有文件 API）。
 */
object AgentVision {

    /** 单条消息最多带几张。多了既费 token 也让模型抓不住重点。 */
    const val MAX_IMAGES_PER_MESSAGE = 4

    /**
     * 带图轮数超过 `PRUNE_AT` 才裁到最近 `KEEP_IMAGE_TURNS` 轮：不裁每轮都重传全部图片，
     * 裁一次又会让此后的前缀缓存失效，所以攒够了再裁。
     */
    private const val KEEP_IMAGE_TURNS = 2
    private const val PRUNE_AT = 6

    /**
     * 这个模型认不认图片。
     *
     * 按模型 ID 猜，不额外发探测请求：猜错的代价是按钮多显示/少显示一个，
     * 而为每次换模型多打一轮网络请求，代价更大。
     * - DeepSeek：`deepseek-flash` 支持（`deepseek-v4-pro` 官方未列为视觉模型）。
     * - OpenAI：4o 及之后的主线模型都支持，`*-audio`、`*-realtime` 这些除外。
     * - 自定义端点：无从判断，一律放行——用户自己填的中转，他比我们清楚。
     *
     * 注意：这只回答「模型认不认」。**这一端有没有相册**是另一档，见 `AgentCapability.IMAGE_INPUT`。
     */
    fun supportsVision(config: AgentConfig): Boolean {
        val model = config.effectiveModel.lowercase()
        return when (config.provider) {
            AgentConfig.PROVIDER_DEEPSEEK -> "flash" in model
            AgentConfig.PROVIDER_OPENAI ->
                ("gpt-4o" in model || "gpt-4.1" in model || "gpt-5" in model || "o3" in model || "o4" in model) &&
                    "audio" !in model && "realtime" !in model
            else -> true
        }
    }

    /**
     * 组装一条带图的 user 消息内容。
     *
     * 没有可用图片时返回**纯字符串**而不是单元素数组：纯文本对话的历史结构不该因为
     * "这个版本支持图片了"而整体改变形状，那会让所有旧会话的前缀缓存一次性失效。
     */
    fun userContent(text: String, imagePaths: List<String>, host: AgentHost): JsonElement {
        val uris = imagePaths.mapNotNull { host.imageDataUri(it) }
        if (uris.isEmpty()) return JsonPrimitive(text)
        return buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
            uris.forEach { uri ->
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", uri) })
                })
            }
        }
    }

    /**
     * 带图轮数超过 `PRUNE_AT` 时把过老的图片从历史里摘掉，只留最近 `KEEP_IMAGE_TURNS` 轮。
     *
     * 就地改写传入的数组。被摘掉的那条 user 消息退回纯文本，并在末尾补一句说明，
     * 免得模型对着"用户明明发过图"的空气找图。
     */
    fun pruneOldImages(messages: MutableList<JsonElement>) {
        val imageTurns = (0 until messages.size)
            .filter { i ->
                val m = messages[i] as? JsonObject ?: return@filter false
                m.get("role")?.stringValue == "user" && m.get("content")?.isArray == true
            }
        if (imageTurns.size <= PRUNE_AT) return

        imageTurns.dropLast(KEEP_IMAGE_TURNS).forEach { i ->
            val m = messages[i].jsonObject
            val parts = m.requireArr("content")
            val text = (0 until parts.size)
                .mapNotNull { (parts[it] as? JsonObject) }
                .filter { it.get("type")?.stringValue == "text" }
                .joinToString("\n") { it.get("text")?.stringValue.orEmpty() }
            val count = (0 until parts.size)
                .count { (parts[it] as? JsonObject)?.get("type")?.stringValue == "image_url" }
            messages[i] = JsonObject(m + ("content" to JsonPrimitive("$text\n（此前随这条消息发送的 $count 张图片已从上下文中移除，如需再看请重新发送。）")))
        }
    }
}
