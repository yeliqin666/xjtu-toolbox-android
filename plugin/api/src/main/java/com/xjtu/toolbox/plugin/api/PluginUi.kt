package com.xjtu.toolbox.plugin.api

import kotlin.jvm.JvmOverloads

/**
 * 插件页的界面树（v2）。
 *
 * 插件**不画界面**，只描述界面：返回一棵由这些节点组成的树，宿主用盒子自己的组件渲染。
 * 这样插件一个 `androidx.compose` 的符号都不需要——Compose 对象永远不跨 classloader 边界，
 * 也就不会出现「两边的 `ProvidedValue` 是两个类」这类崩溃。
 *
 * 约定：
 * - 节点都是普通类，字段只增不改；**加节点不升** [PluginApi.VERSION]（旧插件用不到新节点，
 *   老宿主也看不懂新节点）。但插件用了新节点，要自己把清单里的 `minAppVersionCode` 提上去。
 * - 树的深度不要超过 24 层，单个节点子树不超过 2000 个节点（宿主会拦，超了显示错误）。
 * - 文字是纯文本，v0 不解析 Markdown。
 */
sealed class UiNode

/** 字阶。宿主映射到自己的字号表，插件不要假设具体像素。 */
enum class UiTextStyle { Display, Title, Section, Body, Caption }

/**
 * 文字颜色。`Default` 跟主题走（再看 `secondary`），其余取主题色板；
 * 精确颜色用节点的 `argb`，插件自己负责适配深色。
 */
enum class UiTextColor { Default, Secondary, Primary, Success, Warning, Error }

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
 * 一段文字。
 *
 * [secondary] 用次要色（说明、辅助信息）；[maxLines] 为 0 表示不限行数；
 * [color] / [argb] 覆盖颜色（`argb` 优先，插件要精确颜色时用）；
 * [actionId] 非空时这段文字可点，点击回传 `onAction(actionId, null)`——
 * 比按钮轻，适合「查看全部 ›」这种。
 */
class UiTextNode @JvmOverloads constructor(
    val text: String,
    val style: UiTextStyle = UiTextStyle.Body,
    val secondary: Boolean = false,
    val maxLines: Int = 0,
    val color: UiTextColor? = null,
    val argb: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeThrough: Boolean = false,
    val actionId: String = "",
) : UiNode()

/** 富文本里的一段：样式含义同 [UiTextNode] 的对应字段。 */
class UiTextSpan(
    val text: String,
    val color: UiTextColor? = null,
    val argb: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeThrough: Boolean = false,
    /** 非空时这一段可点。 */
    val actionId: String = "",
)

/**
 * 混排富文本：同一行里不同样式、不同链接的文字贴在一起，读起来是一句话。
 * 用 [UiRowNode] 拼多个 [UiTextNode] 也可以，但段与段之间会带间距。
 */
class UiRichTextNode(
    val spans: List<UiTextSpan>,
    val style: UiTextStyle = UiTextStyle.Body,
) : UiNode()

/** 一张卡片，里面放任意节点。[actionId] 非空时整张卡可点（按下有反馈）。 */
class UiCardNode @JvmOverloads constructor(
    val child: UiNode,
    val actionId: String = "",
) : UiNode()

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

/** 开关行：标题 + 可选说明 + 右侧开关。点击回传目标值（`"true"` / `"false"`）。 */
class UiSwitchNode(
    val label: String,
    val checked: Boolean,
    val actionId: String,
    val summary: String = "",
    val enabled: Boolean = true,
) : UiNode()

/** 一行「标签 —— 值」，值靠右。成绩、卡余额这类一行一条最合适。 */
class UiKeyValueNode(
    val label: String,
    val value: String,
    /** 值用次要色（说明性的数值）。 */
    val secondary: Boolean = false,
) : UiNode()

/** 进度条。[value] 会被夹到 0..1；[label] 非空时显示在左边。 */
class UiProgressNode(
    val value: Float,
    val label: String = "",
) : UiNode()

/** 网格：每行 [columns] 个（1..4），子节点等宽。 */
class UiGridNode(
    val columns: Int,
    val children: List<UiNode>,
) : UiNode()

/** 徽章的色调。 */
enum class UiBadgeTone { Neutral, Accent, Warning }

/** 小徽章：状态标签（已满、可预约、欠费）。 */
class UiBadgeNode(
    val text: String,
    val tone: UiBadgeTone = UiBadgeTone.Neutral,
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

/**
 * 一个图标。名字取自宿主的白名单——语义名（`calendar`、`card`、`library`…），不是图标库的类名，
 * 这样宿主换图标集时插件不用改。不认识的名字会画成占位图标，不会崩也不会空白。
 *
 * 颜色跟随所在行的文字色，插件不要指定。
 */
class UiIconNode(
    val name: String,
    val sizeDp: Int = 24,
) : UiNode()

/** 下拉里的一个选项。[key] 会原样回传给插件，别用下标——排序一变下标就错位了。 */
class UiOption(val key: String, val label: String)

/**
 * 下拉单选，画成设置页那种「标题 + 当前值 + 箭头」的行，点开是宿主的菜单。
 *
 * [options] 最多 8 个：再多就该插件自己开一页列表了（浮层里滚动很难用）。
 * 选中后回传 `onAction(actionId, key)`；[value] 不在 [options] 里时照原样显示，不报错。
 */
class UiSelectNode(
    val label: String,
    val value: String,
    val options: List<UiOption>,
    val actionId: String,
    val summary: String = "",
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
