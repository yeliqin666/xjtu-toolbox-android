package com.xjtu.toolbox.plugin.api

import android.content.Context
import android.view.View

/**
 * 岱宗盒子插件 API（v1）。
 *
 * 边界只有这一组接口。插件 APK 自带 Compose / Kotlin / 协程等依赖，宿主只提供
 * `plugin.api` 这几个类；两边通过 `android.view.View` 和 JSON 字符串交换数据。
 *
 * 兼容性约定：
 * - 这里的方法签名只用 Android 框架类型（`Context` / `View` / `String` / `Int` / `Boolean` / `ByteArray`），
 *   不要加 `suspend`、inline class、默认参数或 Kotlin 专属类型；
 * - 改任何签名都要同步把 [PluginApi.VERSION] 加一，旧插件会被宿主拒绝加载。
 */
object PluginApi {
    /** 宿主能接受的插件 API 版本；插件 `plugin.json` 的 `apiVersion` 必须完全相等。 */
    const val VERSION: Int = 2

    /** 插件 APK 里放清单的 asset 路径。 */
    const val MANIFEST_ASSET: String = "plugin.json"
}

/** 插件入口：APK 里 `plugin.json` 的 `entryClass` 指向这个接口的实现，且要有无参构造。 */
interface PluginEntry {
    fun create(context: PluginContext): Plugin
}

/** 一个插件的运行实例。宿主每个插件只建一份，页面和工具都从它取。 */
interface Plugin {
    /** 页面工厂；`id()` 必须与 `plugin.json` 的 `pages[].id` 对得上。 */
    fun pages(): List<PluginPage>

    /** 小岱工具；没有工具返回空列表。工具名/定义以 `plugin.json` 为准。 */
    fun tools(): List<PluginTool>
}

/**
 * 一个可打开的插件页。宿主给标准页面壳（顶栏 + 返回），插件返回**界面树**，宿主负责渲染。
 *
 * 用 abstract class 而不是 interface，是为了让这些 open 方法在字节码里是普通虚方法，
 * 不依赖 Kotlin 的 `$DefaultImpls`，跨 classloader 更稳。
 *
 * 生命周期：宿主在主线程依次调 [onStart]、[onResume]；关页面时 [onPause]、[onStop]、[onDestroy]。
 * 插件在 [render] 里**不要**做耗时操作——它在主线程、每次 invalidate 都会被调一次。
 */
abstract class PluginPage {
    abstract fun id(): String

    /** 顶栏标题；空串表示用 `plugin.json` 里这个页面的 title。 */
    open fun title(): String = ""

    /**
     * 返回这一帧的界面树。宿主在主线程调用，必须快；数据要提前取好放在字段里。
     * 树里不要放可变状态，插件改完状态调 [invalidate] 让宿主重新要一次。
     */
    abstract fun render(): UiNode

    /**
     * 用户操作的唯一回传口：[actionId] 是节点上声明的那个 id。
     * 输入框在 `value` 里给整段文本，其它节点为 null。
     */
    open fun onAction(actionId: String, value: String?) {}

    open fun onStart() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onStop() {}
    open fun onDestroy() {}

    /** 宿主注入的「该重画了」。插件不要调这个，调 [invalidate]。 */
    private var invalidator: (() -> Unit)? = null

    fun attachInvalidator(invalidate: (() -> Unit)?) {
        invalidator = invalidate
    }

    /**
     * 告诉宿主界面该重画了。**任意线程都可以调**：宿主会切回主线程再要 [render]。
     * 插件改完字段记得调一次，否则用户看不到变化。
     */
    protected fun invalidate() {
        invalidator?.invoke()
    }
}

/**
 * 一个交给小岱的工具。
 *
 * 定义（名称、描述、参数 schema）写在 `plugin.json` 里，不在这里重复；这里只按名提供执行体。
 * [execute] 在宿主的 IO 线程调用，必须同步返回；耗时操作自己阻塞，不要假设主线程。
 */
interface PluginTool {
    fun name(): String

    /** 参数是模型给的 JSON（可能为空串或 `{}`）；返回值是给模型看的纯文本。 */
    fun execute(argsJson: String): String
}

/**
 * 插件能看到的宿主能力。所有方法都在宿主进程里执行，插件本身没有额外权限。
 *
 * 安全边界：插件一旦被加载，就和 App 同 uid、同进程，能读 App 的全部私有数据与登录会话。
 * 这些接口只是"方便"，不是"权限控制"。
 */
interface PluginContext {
    val pluginId: String

    /** 当前 App 的 `BuildConfig.VERSION_CODE`。 */
    val appVersionCode: Int

    /** 宿主当前是否深色主题；插件画自己的界面时用。 */
    val isDarkTheme: Boolean

    /** 宿主主题主色（ARGB）；插件画自己的界面时用。 */
    val primaryColor: Int

    /** 宿主 Application Context。插件拿它做工具/通知等非 UI 的事；页面另外会收到 Activity Context。 */
    val applicationContext: Context

    /**
     * 只读学校取数桥，复用 App 已有的登录、MFA、WebVPN、缓存与限流，只放行只读工具。
     *
     * 返回 JSON：`{"ok":true,"tool":"get_grades","text":"..."}`；
     * 不允许 / 主线程 / 失败时返回 `{"ok":false,"error":"..."}`。
     *
     * `text` 是给小岱看的取数文本（不是稳定结构化契约）。必须在非主线程调用。
     */
    fun schoolTool(name: String, argsJson: String): String

    /** 读插件 APK 里的 assets 文件；不存在返回 null。 */
    fun openAsset(name: String): ByteArray?

    /** 插件自己的可写目录（`filesDir/plugins/data/<id>`），已创建。 */
    fun dataDir(): String

    /** 主线程 Toast。 */
    fun toast(message: String)

    /** 打到 logcat，tag 为 `Plugin/<id>`。 */
    fun log(message: String)
}
