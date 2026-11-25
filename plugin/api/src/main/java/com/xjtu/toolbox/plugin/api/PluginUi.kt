package com.xjtu.toolbox.plugin.api

/**
 * 插件页的界面树（v2）。
 *
 * 插件**不画界面**，只描述界面：返回一棵由这些节点组成的树，宿主用盒子自己的组件渲染。
 * 这样插件一个 `androidx.compose` 的符号都不需要——Compose 对象永远不跨 classloader 边界，
 * 也就不会出现「两边的 `ProvidedValue` 是两个类」这类崩溃。
 *
 * 约定：
 * - 节点都是普通类，字段只增不改；加节点要升 [PluginApi.VERSION]。
 * - 树的深度不要超过 24 层，单个节点子树不超过 2000 个节点（宿主会拦，超了显示错误）。
 * - 文字是纯文本，v0 不解析 Markdown。
 */
sealed class UiNode

/** 字阶。宿主映射到自己的字号表，插件不要假设具体像素。 */
enum class UiTextStyle { Display, Title, Section, Body, Caption }

/** 按钮外观。 */
enum class UiButtonStyle { Filled, Tonal, Plain }

/** 竖向排列。[spacingDp] 是子节点间距。 */
class UiColumnNode(
    val children: List<UiNode>,
    val spacingDp: Int = 12,
) : UiNode()

/** 横向排列，子节点按顺序排，垂直居中。 */
class UiRowNode(
    val children: List<UiNode>,
    val spacingDp: Int = 8,
) : UiNode()

/**
 * 一段文字。[secondary] 用次要色（说明、辅助信息）；[maxLines] 为 0 表示不限行数。
 */
class UiTextNode(
    val text: String,
    val style: UiTextStyle = UiTextStyle.Body,
    val secondary: Boolean = false,
    val maxLines: Int = 0,
) : UiNode()

/** 一张卡片，里面放任意节点。 */
class UiCardNode(val child: UiNode) : UiNode()

/**
 * 按钮。[actionId] 会在点击时通过 [PluginPage.onAction] 回到插件。
 * 三个按钮放一行时用 [UiRowNode] 包起来。
 */
class UiButtonNode(
    val label: String,
    val actionId: String,
    val style: UiButtonStyle = UiButtonStyle.Filled,
    val enabled: Boolean = true,
) : UiNode()

/**
 * 列表项：标题 + 可选的副标题和右侧文字。[actionId] 非空时整行可点。
 * 比 [UiCardNode] 轻，适合连续的行——宿主会把它画成设置页那种行。
 */
class UiListItemNode(
    val title: String,
    val subtitle: String = "",
    val trailing: String = "",
    val actionId: String = "",
) : UiNode()

/**
 * 输入框。用户每改一个字都会通过 [PluginPage.onAction] 把整段文本回传（`value` 不为 null），
 * 插件自己把它存下来再 `invalidate()`；值以插件为准，宿主不缓存。
 */
class UiFieldNode(
    val label: String,
    val value: String,
    val actionId: String,
    val hint: String = "",
) : UiNode()

/** 网络图片。宿主用 Coil 加载，失败时显示占位。 */
class UiImageNode(
    val url: String,
    val heightDp: Int = 160,
) : UiNode()

/** 分隔线。 */
class UiDividerNode : UiNode()

/** 竖向留白。 */
class UiSpacerNode(val heightDp: Int = 8) : UiNode()

/** 加载中：转圈 + 一行字。 */
class UiLoadingNode(val text: String = "正在加载…") : UiNode()

/** 空状态：标题 + 说明。 */
class UiEmptyNode(
    val title: String,
    val subtitle: String = "",
) : UiNode()
