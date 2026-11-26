package top.dzhz.plugin.sample

import com.xjtu.toolbox.plugin.api.PluginContext
import com.xjtu.toolbox.plugin.api.PluginPage
import com.xjtu.toolbox.plugin.api.UiButtonNode
import com.xjtu.toolbox.plugin.api.UiButtonStyle
import com.xjtu.toolbox.plugin.api.UiCardNode
import com.xjtu.toolbox.plugin.api.UiColumnNode
import com.xjtu.toolbox.plugin.api.UiDividerNode
import com.xjtu.toolbox.plugin.api.UiFieldNode
import com.xjtu.toolbox.plugin.api.UiListItemNode
import com.xjtu.toolbox.plugin.api.UiLoadingNode
import com.xjtu.toolbox.plugin.api.UiNode
import com.xjtu.toolbox.plugin.api.UiRowNode
import com.xjtu.toolbox.plugin.api.UiTextNode
import com.xjtu.toolbox.plugin.api.UiTextStyle

/**
 * 示例页：**一行 Compose 都没有**。
 *
 * 插件只把界面描述成 [UiNode] 树，宿主用盒子自己的组件渲染——所以深色模式、莫奈取色、
 * 字阶、卡片材质都自动跟盒子一致，插件不用管，也不会出现跨 classloader 的类身份问题。
 */
internal class SamplePage(private val ctx: PluginContext) : PluginPage() {

    @Volatile
    private var count = 3

    @Volatile
    private var busy = false

    @Volatile
    private var status = "点下面的按钮查一次本周课表"

    private var query = ""

    override fun id(): String = "home"

    override fun render(): UiNode = UiColumnNode(
        spacingDp = 12,
        children = listOf(
            UiTextNode("宿主渲染的插件页", style = UiTextStyle.Display),
            UiTextNode(
                "这个页面里没有任何 Compose 类。插件交回数据，宿主用 miuix 画——" +
                    "所以主题、深浅色、圆角、动效跟盒子自己的页面是一套。",
                secondary = true,
            ),
            UiCardNode(
                UiColumnNode(
                    spacingDp = 8,
                    children = listOf(
                        UiTextNode("计数器", style = UiTextStyle.Section),
                        UiTextNode("现在是 $count", secondary = true),
                        UiRowNode(
                            children = listOf(
                                UiButtonNode("加一", "inc"),
                                UiButtonNode("清零", "reset", style = UiButtonStyle.Tonal),
                            ),
                        ),
                    ),
                ),
            ),
            UiCardNode(
                UiColumnNode(
                    spacingDp = 8,
                    children = listOf(
                        UiTextNode("学校数据", style = UiTextStyle.Section),
                        if (busy) {
                            UiLoadingNode("正在查课表…")
                        } else {
                            UiTextNode(status, secondary = true, maxLines = 6)
                        },
                        UiButtonNode(
                            label = if (busy) "查询中…" else "查本周课表",
                            actionId = "schedule",
                            enabled = !busy,
                        ),
                    ),
                ),
            ),
            UiFieldNode(label = "随便写点什么", value = query, actionId = "query"),
            UiDividerNode(),
            UiListItemNode(
                title = "顶栏来自宿主",
                subtitle = "标题、返回、毛玻璃都是盒子画的",
                trailing = "↑",
            ),
            UiTextNode("v1.1.0 · 全部由宿主渲染", style = UiTextStyle.Caption, secondary = true),
        ),
    )

    override fun onAction(actionId: String, value: String?) {
        when (actionId) {
            "inc" -> {
                count++
                invalidate()
            }
            "reset" -> {
                count = 0
                invalidate()
            }
            "query" -> {
                query = value.orEmpty()
                invalidate()
            }
            "schedule" -> refreshSchedule()
        }
    }

    /** 取数是阻塞的（[PluginContext.schoolTool] 必须在非主线程），查完从后台线程 invalidate。 */
    private fun refreshSchedule() {
        if (busy) return
        busy = true
        status = "查询中…"
        invalidate()
        Thread({
            val text = runCatching { ctx.schoolTool("get_schedule", "{}") }
                .getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
            status = text.take(600)
            busy = false
            invalidate()
        }, "sample-schedule").apply { isDaemon = true }.start()
    }
}
