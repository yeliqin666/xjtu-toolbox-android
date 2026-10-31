package com.xjtu.toolbox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.xjtu.toolbox.account.AccountContext
import com.xjtu.toolbox.agent.AgentImagePicker
import com.xjtu.toolbox.agent.AgentVision
import com.xjtu.toolbox.platform.showBriefMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

/**
 * 桌面这一端的「附件图」那一半（`:core` 的 [AgentImagePicker] 与 `AgentHost` 那四个图片槽位）。
 *
 * 与 `:app` 的 `attachAgentImage` 同一个套路：挑到的图**拷进这一端的私有目录**（不引用原路径 ——
 * 用户随时会挪走/删掉那张图，而会话历史要能再打开），之后缩略图、`data:` URI、删除都对着这份拷贝做。
 *
 * ### 与 Android 那两处不同，都是有意为之
 * 1. **不缩图**。`:app` 用 `BitmapFactory` 缩到长边 1300（对齐服务端重采样）再编 JPEG 88；
 *    桌面这一端的图来自文件对话框 —— 截图、网页存的图，本来就小，而 JVM 侧要重编码就得走
 *    skia 的表面/画布那一套。于是改成**按字节数设上限**（[MAX_BYTES]），超了就说一句、不收，
 *    而不是悄悄把几十兆塞进请求里。
 * 2. **目录在 `~/.cache/xjtu-toolbox/agent_images`**（而不是 App 的 `filesDir`）：桌面没有
 *    "应用私有目录"这一说，缓存目录是这一端放可再生数据的地方（同 `jiaocai1CacheRoot`）。
 *    附件图跟着会话历史走，但真丢了也只是气泡里的缩略图变成"读不出来"，那条消息的**文字还在**。
 */
internal object DesktopAgentImages {

    /** 单张图的字节上限。桌面不缩图 ⇒ 这是"多大还肯往请求里塞"的唯一一道闸。 */
    const val MAX_BYTES = 4L * 1024 * 1024

    /** 认得出来的图片后缀（与 `AgentVision` 那边收的几种一致）。 */
    val EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    /** 附件图的落地目录（按账号分：换账号就换目录，和 `:app` 的 `<filesDir>/agent_images<suffix>` 同义）。 */
    fun dir(): File {
        val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home") ?: "/tmp", ".cache")
        return File(File(base, "xjtu-toolbox"), "agent_images" + AccountContext.safeSuffix())
    }

    /**
     * 挑图并落盘：返回拷进来的那些路径（失败/超限的不在表里）。
     *
     * @param room 这一条消息还能再带几张（`:core` 按 [AgentVision.MAX_IMAGES_PER_MESSAGE] 减出来的）
     */
    fun attach(room: Int): List<String> {
        if (room <= 0) return emptyList()
        val picked = DesktopFileDialogs.pickFiles("挑几张图", multi = true, extensions = EXTENSIONS)
        if (picked.isEmpty()) return emptyList()
        val target = dir().apply { mkdirs() }
        var tooBig = 0
        val saved = picked.take(room).mapNotNull { f ->
            runCatching {
                if (f.length() > MAX_BYTES) {
                    tooBig++
                    null
                } else {
                    val name = "${System.currentTimeMillis()}_${f.name}"
                    File(target, name).also { it.writeBytes(f.readBytes()) }.absolutePath
                }
            }.getOrNull()
        }
        if (tooBig > 0) {
            showBriefMessage("有 $tooBig 张图超过 ${MAX_BYTES / 1024 / 1024}MB 没带上（桌面这一端不缩图）")
        }
        return saved
    }

    /** 这张图的 `data:` 头（浏览器/服务端都按它认格式）。 */
    fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    /** 读成 `data:image/…;base64,…`（带图消息要的就是这个形状，见 `AgentVision.userContent`）。 */
    fun dataUri(path: String): String? = runCatching {
        val bytes = File(path).readBytes()
        "data:${mimeOf(path)};base64," + Base64.getEncoder().encodeToString(bytes)
    }.getOrNull()
}

/**
 * 桌面这一端的挑图入口（`:app` 是 `rememberAndroidAgentImagePicker`）。
 *
 * [DesktopFileDialogs.pickFiles] 是**阻塞**的：对话框弹在 UI 线程上会把人卡住，所以照 Android
 * 那侧一样先挂住这次回调，等 IO 线程上挑完再回填。
 */
@Composable
internal fun rememberDesktopAgentImagePicker(): AgentImagePicker {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        AgentImagePicker { room, onPicked ->
            scope.launch {
                val added = withContext(Dispatchers.IO) { DesktopAgentImages.attach(room) }
                // 用户按了取消 = 空表：不回调（回调是"多出了这几张"，不是"没有了"）
                if (added.isNotEmpty()) onPicked(added)
            }
        }
    }
}
