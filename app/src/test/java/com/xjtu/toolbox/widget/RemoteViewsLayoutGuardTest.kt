package com.xjtu.toolbox.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 桌面小组件的布局只能用 RemoteViews 白名单里的控件。
 *
 * 道理在 RemoteViews.java 里：`RemoteViews implements Parcelable, LayoutInflater.Filter`，
 * 而且它自己就是那个 filter —— `onLoadClass(clazz)` 只放行带 `@RemoteViews.RemoteView`
 * 注解的类。`android.view.View` **没有**这个注解（`Space` 也没有，它直接 extends View），
 * 所以布局里写一个裸 `<View>` 当占位撑高，`inflate` 时就会抛
 * `InflateException: Class not allowed to be inflated android.view.View`，
 * 桌面表现为「小组件加载失败」，整张卡片变成系统错误占位。
 *
 * 这个错误编译器、lint、单测都发现不了（只有真机把它放到桌面上才炸），
 * 所以在这儿扫源文件拦一道。6.0.1 的智能卡片（#408）就是这么炸的。
 */
class RemoteViewsLayoutGuardTest {

    /** RemoteViews 文档列出的可用容器。 */
    private val allowedLayouts = setOf(
        "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout",
        "AdapterViewFlipper", "GridView", "ListView", "StackView", "ViewFlipper",
    )

    /** RemoteViews 文档列出的可用控件；CheckBox / RadioButton / RadioGroup / Switch 是 API 31 起才放开的。 */
    private val allowedWidgets = setOf(
        "AnalogClock", "Button", "Chronometer", "ImageButton", "ImageView",
        "ProgressBar", "TextClock", "TextView",
        "CheckBox", "RadioButton", "RadioGroup", "Switch",
    )

    private val allowed = allowedLayouts + allowedWidgets

    @Test
    fun `小组件布局不含 RemoteViews 白名单外的控件`() {
        val offenders = widgetLayouts().flatMap { f ->
            disallowedTags(f.readText()).map { "${f.path} 用了 <$it>" }
        }
        assertTrue(
            "这些小组件布局用了 RemoteViews 不许 inflate 的控件，真机上会显示「加载失败」：\n" +
                offenders.joinToString("\n") +
                "\n换成白名单内的控件（撑高留白用空 TextView / 带 weight 的 LinearLayout）。",
            offenders.isEmpty(),
        )
    }

    /** manifest 里挂在 appwidget.provider 上的布局，命名约定都是 widget_ 开头。 */
    private fun widgetLayouts(): List<File> =
        File("src/main/res/layout").listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("widget_") && it.extension == "xml" }
            .sortedBy { it.name }

    /** 返回布局里出现的、不在白名单的标签名。注释先剥掉，免得注释里提一句 <View> 就误报。 */
    private fun disallowedTags(src: String): List<String> {
        val code = src.replace(comment, "")
        return tag.findAll(code)
            .map { it.groupValues[1] }
            // 带命名空间的标签（<androidx.foo.Bar>）一律算自定义控件，不在白名单里
            .map { if ('.' in it) it.substringAfterLast('.') to true else it to false }
            .filter { (name, qualified) -> qualified || name !in allowed }
            .map { (name, _) -> name }
            .toList()
    }

    private val comment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    /** 起始标签名；`<?xml` 因为带 `?` 不会命中。 */
    private val tag = Regex("""<\s*([A-Za-z_][\w.]*)""")
}
