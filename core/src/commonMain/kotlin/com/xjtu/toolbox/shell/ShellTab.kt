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
    /**
     * 这一页自己画顶栏（首页，或原本压栈、被放到底栏上的子页）：壳不画主顶栏，
     * 也不按主顶栏的折叠给它做位移。两种风格下都从屏幕顶边画起，顶栏自己盖住状态栏
     *（拿到的 `contentTopPadding` 就是状态栏高度），见 ShellBarPage。
     */
    val drawsOwnTopBar: Boolean = false,
    /**
     * 这一页可以在后台提前落地（建节点、跑副作用、首次测量），切过来时第一帧就是现成的。
     * 只给副作用全是本地读写的页开：会联网、会登录的页提前落地等于用户没点开就发请求。
     * 不开的页后台只组合、不落地，切过来时再落地（见 ShellTabHost）。
     */
    val prewarm: Boolean = false,
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
