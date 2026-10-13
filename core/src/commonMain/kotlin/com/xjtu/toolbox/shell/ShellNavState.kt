package com.xjtu.toolbox.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Text

/**
 * 底栏 / 侧栏共用的状态：**当前在哪一页、哪一格该高亮、有没有账号角标、点了怎么办**。
 *
 * 这四样是壳的语义，不是端的能力：端上只决定「当前页是哪个」（写自己的设置 / 切自己的页），
 * 以及「点这一格要顺带干什么」（Android 点屁岱还要冒一句闲话，见那一段的 `onSelect`）。
 *
 * ## 为什么「当前页」是 [ShellTarget] 而不是「选中了哪一格」
 *
 * 一个端的导航模型是**页**，不是「选中了某一格」：桌面 43 条路由里有 37 条不属于底栏任何一格
 *（体测 / 成绩 / 屁岱……），`/web` 也是同一套。把「当前位置」只写成「选中了某一个 tab」，
 * 那种页就只能被画成某一格（往往是首页）——**而且 42 张证据图与 85 例测试一张都不会红**。
 * 所以这里存 [target]（当前页），高亮的格子由它推出来（[selected]，可能是 null）。
 *
 * @param tabs 这一端的 tab 集合（[ShellTabs]，裁剪是各端的权利）。
 * @param target 当前页。它不一定落在某一格上（见 [selected]）。
 * @param onSelect 点了某一格。**程序性切页不要走这里**（那是端上自己的状态写入）——
 *   这一条回调只从底栏/侧栏来，所以「点 tab 才震一下」「点屁岱冒气泡」这类副作用也挂在这里。
 * @param accountBadge 「我的」那一格上的角标内容；null = 不画。见 [ShellAccountBadge.of]。
 */
@Stable
class ShellNavState(
    val tabs: ShellTabs,
    val target: ShellTarget,
    val onSelect: (ShellTab) -> Unit,
    val accountBadge: ShellAccountBadge? = null,
) {

    /**
     * [target] 落在的那一格；**null = 不属于任何一格** ⇒ 底栏 / 侧栏一格都不高亮。
     *
     * 这不是异常态：底栏画的是「去哪儿」，不是「你从哪儿来」（搬迁前 `DesktopBottomBar`
     * 里那句注释），桌面上除那六格之外的路由全都走这里。
     * 判据只此一份（按 [ShellTab.target] 相等认），端上不许再自己写一份「当前页是哪一格」——
     * 那正是这一步要终止的事。
     */
    val selected: ShellTab? get() = tabs.tabs.firstOrNull { it.target == target }

    /**
     * [tab] 这一格上的角标；null = 不画。
     *
     * 「哪一格有角标」由 [ShellTab.showsAccountBadge] 说，「画什么」由 [accountBadge] 说 ——
     * 这一条判据（只有那一格有、其余格一个都不画）是共享的，端上不许自己再写一份。
     */
    fun badge(tab: ShellTab): (@Composable () -> Unit)? {
        val badge = accountBadge ?: return null
        if (!tab.showsAccountBadge) return null
        val count = badge.count
        return if (count != null) {
            { Badge { Text(count.coerceAtMost(99).toString()) } }
        } else {
            { Badge() }
        }
    }
}

/**
 * 账号角标的内容：数字（多账号，最多画到 99）或一个红点（未登录）。
 *
 * 判据本身也共享，因为三端都要回答同一个问题：这台设备上有几个账号。
 * `:app` 传的是 `accountManager.accountList().size` 与 `loginState.isLoggedIn`。
 */
class ShellAccountBadge private constructor(val count: Int?) {

    companion object {

        /** 多账号 → 数字；未登录 → 红点；其余（单账号且已登录）→ null（**不画角标**）。 */
        fun of(accountCount: Int, isLoggedIn: Boolean): ShellAccountBadge? = when {
            accountCount > 1 -> ShellAccountBadge(accountCount)
            !isLoggedIn -> ShellAccountBadge(null)
            else -> null
        }
    }
}
