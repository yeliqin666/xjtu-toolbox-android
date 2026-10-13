package com.xjtu.toolbox.shell

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 底栏 / 侧栏里的一格：**tab 集合语义**的载体（三端同壳说的第二件事）。
 *
 * ## 共享什么
 *
 * 共享的是「一格有哪些字段」与 [ShellTabs] 的操作（按 id 找、取序号）；**这一端有哪几格、
 * 每格叫什么、指向哪里**是各端的权利 —— 但**裁剪的是集合，不是壳**：三端都把这套集合交给同一个
 * [AppShell] 画，端上不许再各写一个底栏。
 *
 * 各端的集合：
 * - `:app`（Android）：五格 —— 首页 / 日程 / 屁岱 / 学辅 / 我的（见 `BottomTab.shellTab()`）；
 * - `:desktop`：六格 —— 首页 / 校历 / 图书馆 / 黄页 / 游戏 / 全部（见 `DESKTOP_TABS`）；
 * - `:web`：与桌面同一套；
 * - 某一端只画两三格也合法（诚实的一条：底栏只放这一端真能画的屏）。
 *
 * @param id 稳定标识：**存进设置与启动意图的就是它**，改一个字就等于让老用户的默认 tab 失效。
 *   `:app` 用的是 [BottomTab][com.xjtu.toolbox.main.BottomTab] 的枚举名（`credentialStore.defaultTab`
 *   与启动意图 `EXTRA_LAUNCH_TAB` 都按它认）。
 * @param label 底栏那一格的文字。
 * @param icon 那一格的图标。选了「自绘」的那一格（见 [ShellTabArt]）用不上它。
 * @param target 这一格指向哪一页（见 [ShellTarget]）。
 * @param iconSize 按墨迹面积拉平的光学尺寸（同为 24dp 的 Material 图标视觉大小差很多），
 *   **只在玻璃底栏生效** —— miuix 自带的几种导航栏把图标尺寸写死了。
 * @param showsAccountBadge 这一格要不要画账号角标（数字 = 多账号，红点 = 未登录）。
 *   角标内容由 [ShellNavState.accountBadge] 给：`:app` 的「我的」那一格是 true，其余端默认 false。
 */
data class ShellTab(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val target: ShellTarget,
    val iconSize: Dp = 24.dp,
    val showsAccountBadge: Boolean = false,
)

/**
 * 一个端的那份 tab 集合。
 *
 * 只是个有顺序的表（顺序就是底栏从左到右的顺序），加上「按 id 反查」这一条 —— 因为
 * **选中的是 id 而不是序号**：序号会在端上加一格/减一格时整体错位，而 id 是存盘的那个东西。
 */
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

    /**
     * [id] 的序号；没有这一格、或 [id] 为 null，返回 -1（与 `List.indexOfFirst` 同）。
     *
     * 收 null 是为了 [ShellNavState.selected]：「当前页不属于任何一格」时它就是 null，
     * 而底栏 / 侧栏到处要算序号（见 `ShellBars`）—— -1 就是「一格都不高亮」。
     */
    fun indexOf(id: String?): Int = if (id == null) -1 else tabs.indexOfFirst { it.id == id }

    /**
     * 按 id 找那一格。找不到（存盘的 id 是上一版的、或者这一端裁掉了那一格）返回 null ——
     * 调用方自己决定退到哪一格（`:app` 退 [first]，见 `AppRoot` 的 `MainTabState` 初值）。
     */
    fun byId(id: String?): ShellTab? = if (id == null) null else tabs.firstOrNull { it.id == id }
}
