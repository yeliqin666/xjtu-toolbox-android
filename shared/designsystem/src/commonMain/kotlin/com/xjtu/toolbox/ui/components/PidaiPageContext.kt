package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * 小岱的「这一页」：页面用 [ReportToPidai] 报上自己叫什么、此刻在显示什么。
 * 用户在这页点小岱球或按住说话时，把这页内容（和一张截图）一并交给小岱；对话里小岱也能用工具随时重读。
 */
object PidaiPageContext {
    class Entry internal constructor(
        val title: String,
        /** 这页第一次出现小岱球时冒的那句提议（点了就带着这页问出去）；null 不冒。 */
        val hint: String?,
        internal val visible: PageSignal,
        private val read: () -> String?,
        private val shootable: () -> Boolean,
    ) {
        /** 这页此刻显示的内容；读的时候现取，页面数据变了就是新的。 */
        fun snapshot(): String? = runCatching { read() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        /** 此刻能不能截图（登录页、有密码框时不能）。 */
        fun screenshotAllowed(): Boolean = runCatching { shootable() }.getOrDefault(false)
    }

    // 只在主线程增删，整份替换；是 Compose 状态，悬浮球跟着它换气泡。工具线程读的是某一刻的完整列表
    private var entries: List<Entry> by androidx.compose.runtime.mutableStateOf(emptyList())

    /** 正显示着的、最后登记的那页。 */
    fun current(): Entry? = entries.lastOrNull { it.visible.value }

    /** 没登记快照的页面至少报个名字（宿主接上：栈顶子页或底栏当前那格的名字）。 */
    var fallbackTitle: () -> String? = { null }

    /** 没登记的页面上看得见的文字（宿主在点球那一刻从界面读一遍存下）。 */
    var fallbackText: () -> String? = { null }

    /** 给小岱的「这一页」：登记过的给快照，没登记的给页名和读到的界面文字；什么都不知道返回 null。 */
    fun describe(): Pair<String, String>? {
        current()?.let { return it.title to (it.snapshot() ?: "（这页此刻没有可读的数据）") }
        return fallbackTitle()?.let { it to (fallbackText()?.let { t -> "页面上看得见的文字：\n$t" } ?: "（这页没有登记数据，只知道页名）") }
    }

    internal fun add(e: Entry) { entries = entries + e }

    internal fun remove(e: Entry) { entries = entries - e }
}

/**
 * 把这页登记给小岱。放在页面算完要显示的列表**之后**，[snapshot] 读的就是用户此刻看到的那几份
 * （筛选条件、各段列表），而不是 ViewModel 里另拼一份——页内现算的筛选只有这里看得到。
 * 格式用 [pidaiSnapshot]。[hint] 是这页第一次出现小岱球时冒的提议；[screenshotAllowed] 为假时不截图。
 * 只在这页真显示着时算数。
 */
@Composable
fun ReportToPidai(
    title: String,
    hint: String? = null,
    screenshotAllowed: () -> Boolean = { true },
    snapshot: () -> String?,
) {
    val visible = LocalPageVisible.current
    val read by rememberUpdatedState(snapshot)
    val shootable by rememberUpdatedState(screenshotAllowed)
    DisposableEffect(title, hint, visible) {
        val e = PidaiPageContext.Entry(title, hint, visible, { read() }, { shootable() })
        PidaiPageContext.add(e)
        onDispose { PidaiPageContext.remove(e) }
    }
}

/** 页面快照的统一写法：先一行筛选条件，再按页面上的分段列内容，每段写明总数和列了几条。 */
class PidaiSnapshotBuilder internal constructor() {
    private val filters = mutableListOf<String>()
    private val body = StringBuilder()

    /** 一个筛选条件或状态（校区、楼、日期、节次、模式…）；[value] 为空不写。 */
    fun filter(name: String, value: Any?) {
        val v = value?.toString()?.takeIf { it.isNotBlank() } ?: return
        filters += "$name=$v"
    }

    /** 一段列表，按页面上的顺序；超过 [max] 条只列前面的并写明总数。 */
    fun <T> section(title: String, items: List<T>, max: Int = 25, line: (T) -> String) {
        body.append("【").append(title).append("】").append(items.size).append(" 条")
        if (items.size > max) body.append("，列前 ").append(max)
        body.append('\n')
        items.take(max).forEach { body.append("- ").append(line(it)).append('\n') }
    }

    /** 一句说明（汇总数字、正在看的那一条…）。 */
    fun note(text: String?) {
        if (!text.isNullOrBlank()) body.append(text.trim()).append('\n')
    }

    internal fun build(): String = buildString {
        if (filters.isNotEmpty()) append("筛选：").append(filters.joinToString("｜")).append('\n')
        append(body)
    }
}

fun pidaiSnapshot(block: PidaiSnapshotBuilder.() -> Unit): String = PidaiSnapshotBuilder().apply(block).build()
