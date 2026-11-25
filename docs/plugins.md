# 插件（Android）

Android 端支持**进程内 dex 插件**：插件是带签名的 APK，但不会被系统安装，只由本 App 用
`DexClassLoader` 加载。插件可以加页面、给小岱加工具，并通过宿主提供的只读接口取学校数据。

> [!CAUTION]
> **当前是 WIP：安装/管理/工具已通，打开示例页的 Compose 渲染仍会崩。**
> 进度、已修崩溃和下一步见 [`docs/plugins-status.md`](plugins-status.md)。不要把这个分支当稳定功能合并。

> [!WARNING]
> 插件和 App 同 uid、同进程运行，拥有完全相同的权限，能读取登录会话、账号、本机缓存并联网。
> 宿主**没有沙箱**，`plugin.json` 里的声明也不是权限控制。只安装你信任来源的插件。

桌面端和 Web 端不支持插件（dex 只属于 Android）。

## 包格式

插件是一个普通 Android APK（`applicationId` 随意，不会被安装），必须满足：

- 有签名；
- `assets/plugin.json` 存在；
- `assets/plugin.json` 的 `entryClass` 是无参构造、实现了
  `com.xjtu.toolbox.plugin.api.PluginEntry` 的类；
- 不超过 30 MB，`classes*.dex` 不超过 8 个；
- `apiVersion` 与当前 App 的 `PluginApi.VERSION` 完全相等；
- `minAppVersionCode` 不高于当前 App 的 `BuildConfig.VERSION_CODE`。

`plugin.json`：

```json
{
  "apiVersion": 1,
  "id": "com.example.demo",
  "name": "示例插件",
  "version": "1.0.0",
  "versionCode": 1,
  "minAppVersionCode": 101,
  "entryClass": "com.example.demo.DemoPlugin",
  "pages": [
    { "id": "home", "title": "示例页" }
  ],
  "tools": [
    {
      "name": "demo_course_count",
      "definition": {
        "type": "function",
        "function": {
          "name": "demo_course_count",
          "description": "统计本周课程条数。",
          "parameters": { "type": "object", "properties": {} }
        }
      }
    }
  ]
}
```

- `id`：小写字母开头，2–32 位，只允许 `a-z0-9_.`。
- 页面最多 8 个，页面 id 1–32 位；工具最多 16 个，工具名 1–20 位。
- **页面/工具的定义以 `plugin.json` 为准**，这样列页面和给小岱工具清单时不需要加载 dex。
- `tools[].definition` 是 OpenAI function calling 的单条定义；宿主会统一把工具名改写成
  `plugin_<id>_<tool>`，并按 `plugin.json` 里的定义把它交给模型。

## 插件 API

插件作者只依赖 `:plugin:api`（AAR），不要依赖 App 的任何内部模块：

```kotlin
class DemoPlugin : PluginEntry {
    override fun create(context: PluginContext): Plugin = Demo(context)
}

class Demo(private val context: PluginContext) : Plugin {
    override fun pages() = listOf(DemoPage(context))
    override fun tools() = listOf(DemoTool(context))
}

class DemoPage(private val context: PluginContext) : PluginPage() {
    override fun id() = "home"
    override fun createView(context: Context, argsJson: String): View =
        ComposeView(context).apply {
            setContent { /* 插件自己的 Compose */ }
        }
}

class DemoTool(private val context: PluginContext) : PluginTool {
    override fun name() = "demo_course_count"
    override fun execute(argsJson: String): String =
        context.schoolTool("get_schedule", argsJson)
}
```

### 页面

- 宿主给标准页面壳（顶栏、返回、全屏覆盖层），插件只返回内容区的 `View`。
- **插件要自带 Compose / lifecycle / 协程等依赖**。App 的 release 包开了 R8，宿主的
  `androidx.compose.*` / `kotlin.*` 类会被改名，插件不能直接复用。
- 插件自带 Compose 时，`ComposeView` 需要插件自己的 `LifecycleOwner` /
  `SavedStateRegistryOwner` / `ViewModelStoreOwner`，不能依赖宿主的 `ViewTreeLifecycleOwner`
  （两边是不同的类）。`plugin/sample` 有一份可直接抄的 40 行 shim。
- 插件页不在导航返回栈里：打开是覆盖层，返回关掉；不能放底栏、首页宫格或深链。
- 不支持 Android 资源（`R` / `painterResource`）和 `.so`；数据文件放 assets，用
  `PluginContext.openAsset` 读。

### 工具

- 宿主编排（`CompositeAgentTools`）把插件工具和内置工具并在一起，模型看到的名字是
  `plugin_<id>_<tool>`。
- `execute` 在 IO 线程调用，必须同步返回；返回给模型看的纯文本，过长会被截断。
- 插件工具默认关闭，安装后在「设置 → 插件」里单独打开「允许小岱调用」。
- 工具抛异常只影响这一轮，宿主回 `error: plugin_failed`，不会让 Agent 循环崩掉。

### 学校数据

`PluginContext.schoolTool(name, argsJson)` 复用 App 现有的登录、MFA、WebVPN、缓存和限流，
只放行这些只读工具：

```
get_calendar, get_schedule, get_exam_schedule, search_school_courses,
get_empty_rooms, get_attendance, get_grades, get_card_info, get_notifications,
search_yellow_page, find_faculty, get_library, list_zyxf, read_zyxf_file,
get_textbooks, get_coupons, get_lms, get_lms_activity, get_fitness_score
```

返回 JSON：

```json
{"ok":true,"tool":"get_grades","text":"..."}
{"ok":false,"tool":"get_grades","error":"not_allowed"}
```

- 必须在非主线程调用；主线程调用直接回 `main_thread`。
- `text` 是给小岱看的取数文本，不是稳定的结构化契约。
- 写入类（`add_schedule_event`、`set_alarm`、`preference`、`app_setting`、`save_file`、
  `activity`）不在白名单里，插件硬调会回 `not_allowed`。

## 构建与安装

1. 在 App 仓库里：`./gradlew :plugin:sample:assembleDebug`。
2. 产物：`plugin/sample/build/outputs/apk/debug/sample-debug.apk`。
3. App：「设置 → 插件 → 安装插件 APK」，选这个文件，确认签名/版本/权限提示。
4. 打开示例页；小岱问「这周几门课」应能调到 `plugin_..._demo_course_count`。

正式插件建议：

- 用独立的插件签名密钥（不要复用 App 的 release key），并在 CI 保住；
- 开 R8，`proguard-rules.pro` 保留入口类和 `com.xjtu.toolbox.plugin.api.**`；
- 每次 App 的 `PluginApi.VERSION` 加一，插件都要重编并升 `apiVersion`。

## 安装、更新、卸载

- 首次安装记录 APK 签名证书 SHA-256；更新必须同证书、`versionCode` 必须更大。
- 更新/卸载会关闭正在显示的该插件页面。
- 卸载删除 `filesDir/plugins/<id>/` 和 `filesDir/plugins/data/<id>/`。
- 已加载的 `DexClassLoader` 在进程内不能真正卸载；卸载后建议冷启动一次。
- 插件文件在 `filesDir` 下，不参与系统云备份（见 `res/xml/data_extraction_rules.xml`）。

## 限制（刻意不做）

- 不做沙箱、不做权限清单、不做逐次工具确认（插件代码本来就在进程里）；
- 不做插件市场、远程下载安装、自动更新；
- 不做插件间依赖、插件调用插件、插件改首页/底栏；
- 不做桌面/Web 插件。
