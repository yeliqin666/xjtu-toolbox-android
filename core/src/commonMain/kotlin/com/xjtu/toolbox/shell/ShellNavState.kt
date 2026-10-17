package com.xjtu.toolbox.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Text

/** 底栏 / 侧栏共用的状态：当前在哪一页、哪一格该高亮、有没有账号角标、点了怎么办。 */
@Stable
class ShellNavState(
    val tabs: ShellTabs,
    val target: ShellTarget,
    val onSelect: (ShellTab) -> Unit,
    val accountBadge: ShellAccountBadge? = null,
) {

    /** [target] 落在的那一格；null = 不属于任何一格 ⇒ 底栏 / 侧栏一格都不高亮。 */
    val selected: ShellTab? get() = tabs.tabs.firstOrNull { it.target == target }

    /** [tab] 这一格上的角标；null = 不画。 */
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

/** 账号角标的内容：数字（多账号，最多画到 99）或一个红点（未登录）。 */
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
