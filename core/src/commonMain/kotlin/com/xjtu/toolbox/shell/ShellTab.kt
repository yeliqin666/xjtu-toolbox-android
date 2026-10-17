package com.xjtu.toolbox.shell

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 底栏 / 侧栏里的一格：tab 集合语义的载体（三端同壳说的第二件事）。 */
data class ShellTab(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val target: ShellTarget,
    val iconSize: Dp = 24.dp,
    val showsAccountBadge: Boolean = false,
)

/** 一个端的那份 tab 集合。 */
@Stable
class ShellTabs(val tabs: List<ShellTab>) {

    init {
        require(tabs.isNotEmpty()) { "tab 集合不能为空：底栏至少要有能去的一格" }
        require(tabs.map { it.id }.toSet().size == tabs.size) { "tab 的 id 不能重复" }
    }

    val ids: List<String> get() = tabs.map { it.id }

    val size: Int get() = tabs.size

    /** 第一格（集合非空，构造时已经要求过）。 */
    val first: ShellTab get() = tabs.first()

    operator fun get(index: Int): ShellTab = tabs[index]

    /** [id] 的序号；没有这一格、或 [id] 为 null，返回 -1（与 `List.indexOfFirst` 同）。 */
    fun indexOf(id: String?): Int = if (id == null) -1 else tabs.indexOfFirst { it.id == id }

    /** 按 id 找那一格。 */
    fun byId(id: String?): ShellTab? = if (id == null) null else tabs.firstOrNull { it.id == id }
}
