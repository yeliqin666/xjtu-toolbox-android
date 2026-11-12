package com.xjtu.toolbox.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * COUI 菜单项模型（commonMain，Android 端映射成 CouiMenuItem）。
 *
 * 只放平台无关的数据：Android 端把 [Option] / [Submenu] 翻成 `CouiMenuItem`，桌面 / Web 端退回
 * `AppDropdownOption` / `AppDropdownSubMenu`。两级为限——[Submenu] 的 children 里再放 [Submenu]
 * 在 Android 上会超出 COUI 的两级约束，实际映射时深层子菜单会退化成不可点的叶子项。
 */
sealed interface CouiMenuEntry {
    /** 普通菜单项；[selected] 是「当前这一项被选中」的单选态，[enabled] 为 false 时不可点。 */
    data class Option(
        val text: String,
        val selected: Boolean = false,
        val enabled: Boolean = true,
        val icon: ImageVector? = null,
        val onClick: () -> Unit,
    ) : CouiMenuEntry

    /** 二级菜单；[summary] 是标题右侧的补充说明（如当前选中值），COUI 没有 summary 槽，会拼进标题。 */
    data class Submenu(
        val title: String,
        val summary: String?,
        val children: List<CouiMenuEntry>,
        val icon: ImageVector? = null,
    ) : CouiMenuEntry

    /** 分隔线；Android 端落到下一项的 dividerBefore 上，末尾的分隔线不画。 */
    data object Divider : CouiMenuEntry
}

/** DSL 标记：内容块里只能用 [CouiMenuScope] 的成员，不会串到外层的同名函数。 */
@DslMarker
annotation class CouiMenuDsl

/** [CouiIconMenu] 的内容构造器。 */
@CouiMenuDsl
class CouiMenuScope internal constructor() {
    internal val entries = mutableListOf<CouiMenuEntry>()

    /** 普通菜单项。[icon] 只在有图标槽的端显示（Android / 桌面 / Web 都有）。 */
    fun option(
        text: String,
        selected: Boolean = false,
        enabled: Boolean = true,
        icon: ImageVector? = null,
        onClick: () -> Unit,
    ) {
        entries += CouiMenuEntry.Option(
            text = text,
            selected = selected,
            enabled = enabled,
            icon = icon,
            onClick = onClick,
        )
    }

    /** 二级菜单，最多两级。[summary] 非空时和 [title] 一起显示。 */
    fun submenu(
        title: String,
        summary: String? = null,
        icon: ImageVector? = null,
        content: CouiMenuScope.() -> Unit,
    ) {
        val child = CouiMenuScope()
        child.content()
        entries += CouiMenuEntry.Submenu(
            title = title,
            summary = summary,
            children = child.entries.toList(),
            icon = icon,
        )
    }

    /** 分隔线。 */
    fun divider() {
        entries += CouiMenuEntry.Divider
    }
}

/** 二级菜单在单行文字里的写法：有 summary 时拼成「标题 · 说明」。 */
internal val CouiMenuEntry.Submenu.label: String
    get() = if (summary.isNullOrBlank()) title else "$title · $summary"

/**
 * 按当前组合重建 DSL 条目：`content` 每次重组都重新执行，选中态、禁用态跟着状态走。
 * 只在各端 actual 里用。
 */
@Composable
internal fun rememberCouiMenuEntries(content: CouiMenuScope.() -> Unit): List<CouiMenuEntry> {
    val scope = remember { CouiMenuScope() }
    scope.entries.clear()
    scope.content()
    return scope.entries.toList()
}

/**
 * 顶栏右上角图标按钮 + 它弹出的菜单。
 *
 * Android 上是 COUI 原厂菜单（材质、开合动画、两级子菜单）；桌面 / Web 退回仓库原有的
 * `AppDropdownMenu`。触发按钮三端都是 [CouiToolbarActionButton]。
 *
 * @param expanded 菜单是否展开
 * @param onExpandedChange 请求展开 / 收起
 * @param icon 触发按钮里的图标
 * @param content 菜单项，见 [CouiMenuScope]
 */
@Composable
expect fun CouiIconMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    content: CouiMenuScope.() -> Unit,
)
