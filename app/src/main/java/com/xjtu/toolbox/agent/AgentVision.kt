package com.xjtu.toolbox.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.util.UUID

/**
 * 屁岱的图片输入。
 *
 * 走 OpenAI 兼容的 `content` 数组（`{"type":"image_url","image_url":{"url":"data:…;base64,…"}}`），
 * DeepSeek、OpenAI、以及绝大多数中转都是这一套。
 *
 * ### 为什么要在本地先压
 * 手机相册随手一张就是 4000×3000、5 MB 起步，而服务端拿到图后自己会缩到约 1300×1300
 * （每张图最多约 1024 token）——原图那几兆流量纯属白烧，在校园网上还容易直接超时。
 * 所以这里统一缩到 [MAX_EDGE] 并转成 JPEG 再编码。
 *
 * ### 为什么落盘而不是只留 content://
 * 相册给的 `content://` URI 是**临时授权**，进程重启、甚至用户在相册里删掉原图之后就读不到了，
 * 而会话是要存盘、下次还能翻回来的。所以选中即拷一份压缩件到应用私有目录，
 * 消息里存的是这份拷贝的路径。
 */
object AgentVision {

    private const val TAG = "AgentVision"

    /** 压缩后最长边。对齐服务端的重采样尺寸，再大不会提高识别效果，只会更慢。 */
    private const val MAX_EDGE = 1300

    private const val JPEG_QUALITY = 88

    /** 单条消息最多带几张。多了既费 token 也让模型抓不住重点。 */
    const val MAX_IMAGES_PER_MESSAGE = 4

    /**
     * 历史里保留图片的**用户轮数**。
     *
     * 图片是按 token 计费的，而且每轮请求都会把整段历史重发一遍。不裁剪的话，
     * 一段聊了十轮、贴过五张图的对话，每问一句都要重传五张图。
     * 更早的图在正文里留一句占位说明，模型知道"这里曾经有图"就够了。
     */
    private const val KEEP_IMAGE_TURNS = 2

    private const val DIR_NAME = "agent_images"

    /**
     * 这个模型认不认图片。
     *
     * 按模型 ID 猜，不额外发探测请求：猜错的代价是按钮多显示/少显示一个，
     * 而为每次换模型多打一轮网络请求，代价更大。
     * - DeepSeek：`deepseek-flash` 支持（`deepseek-v4-pro` 官方未列为视觉模型）。
     * - OpenAI：4o 及之后的主线模型都支持，`*-audio`、`*-realtime` 这些除外。
     * - 自定义端点：无从判断，一律放行——用户自己填的中转，他比我们清楚。
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

    private fun dir(context: Context): File =
        File(context.filesDir, "$DIR_NAME${com.xjtu.toolbox.account.AccountContext.safeSuffix()}")
            .apply { mkdirs() }

    /**
     * 把用户选中的图片压好存进私有目录，返回文件绝对路径；失败返回 null。
     *
     * 必须在 IO 线程调用。
     */
    fun attach(context: Context, uri: Uri): String? = try {
        val source = decodeScaled(context, uri)
        if (source == null) {
            null
        } else {
            val out = File(dir(context), "${UUID.randomUUID()}.jpg")
            out.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            source.recycle()
            out.absolutePath
        }
    } catch (e: Exception) {
        Log.w(TAG, "attach failed: $uri", e)
        null
    }

    /**
     * 按 [MAX_EDGE] 解码。
     *
     * 用 [ImageDecoder] 而不是 BitmapFactory：
     * - `setTargetSize` 在**解码阶段**就采样，不会先把 5000 万像素整张读进内存再缩
     *   （那一步就能 OOM）；
     * - 它会自己按 EXIF 摆正方向。竖着拍的照片在文件里常常是横着存的，不转的话
     *   模型看到的是躺倒的课表——它不会提醒你图歪了，只会答错。
     *
     * `ALLOCATOR_SOFTWARE` 是必须的：默认可能给出 HARDWARE bitmap，那种读不到像素，
     * [Bitmap.compress] 会失败。
     */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val longEdge = maxOf(info.size.width, info.size.height)
            if (longEdge > MAX_EDGE) {
                val scale = MAX_EDGE.toFloat() / longEdge
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    }.onFailure { Log.w(TAG, "decode failed: $uri", it) }.getOrNull()

    /** 读成 `data:image/jpeg;base64,…`。文件不存在返回 null。 */
    fun dataUri(path: String): String? = runCatching {
        val f = File(path)
        if (!f.isFile) return null
        "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    /** 删掉这些附件文件。会话被删时调用，别让压缩件在私有目录里越堆越多。 */
    fun deleteAll(paths: List<String>) {
        paths.forEach { runCatching { File(it).delete() } }
    }

    /**
     * 组装一条带图的 user 消息内容。
     *
     * 没有可用图片时返回**纯字符串**而不是单元素数组：纯文本对话的历史结构不该因为
     * "这个版本支持图片了"而整体改变形状，那会让所有旧会话的前缀缓存一次性失效。
     */
    fun userContent(text: String, imagePaths: List<String>): com.google.gson.JsonElement {
        val uris = imagePaths.mapNotNull { dataUri(it) }
        if (uris.isEmpty()) return com.google.gson.JsonPrimitive(text)
        return JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
            uris.forEach { uri ->
                add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply { addProperty("url", uri) })
                })
            }
        }
    }

    /**
     * 把过老的图片从历史里摘掉，只留最近 [KEEP_IMAGE_TURNS] 轮。
     *
     * 就地改写传入的数组。被摘掉的那条 user 消息退回纯文本，并在末尾补一句说明，
     * 免得模型对着"用户明明发过图"的空气找图。
     */
    fun pruneOldImages(messages: JsonArray) {
        val imageTurns = (0 until messages.size())
            .filter { i ->
                val m = messages[i] as? JsonObject ?: return@filter false
                m.get("role")?.asString == "user" && m.get("content")?.isJsonArray == true
            }
        if (imageTurns.size <= KEEP_IMAGE_TURNS) return

        imageTurns.dropLast(KEEP_IMAGE_TURNS).forEach { i ->
            val m = messages[i].asJsonObject
            val parts = m.getAsJsonArray("content")
            val text = (0 until parts.size())
                .mapNotNull { (parts[it] as? JsonObject) }
                .filter { it.get("type")?.asString == "text" }
                .joinToString("\n") { it.get("text")?.asString.orEmpty() }
            val count = (0 until parts.size())
                .count { (parts[it] as? JsonObject)?.get("type")?.asString == "image_url" }
            m.remove("content")
            m.addProperty("content", "$text\n（此前随这条消息发送的 $count 张图片已从上下文中移除，如需再看请重新发送。）")
        }
    }
}
