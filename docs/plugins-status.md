# 插件系统进度（WIP）

> ⚠️ **本文档已过时**：写的是早期"插件自带 Compose"的方案，那条路已废弃。
> 现在的插件是 **zip 包 + `dzhz.plugin.json` + ECDSA 签名**，界面由宿主渲染；
> 写法看模板仓库：<https://github.com/Hz6826/dzhz-plugin-template>（dex）、
> <https://github.com/Hz6826/dzhz-plugin-web-template>（web）。本仓库不再自带示例插件。


> **状态：未完成，不要合并。** 安装、管理、工具清单、页面加载已跑通；**打开示例页的 Compose 渲染仍会崩**。
> 本文只记录当前进度、已修问题和下一步；设计说明见 [`docs/plugins.md`](plugins.md)。

## 已完成

- `plugin/api`：稳定的插件 API（`PluginEntry` / `Plugin` / `PluginPage` / `PluginTool` / `PluginContext`），只有 Android 框架类型，没有 suspend / 默认参数。
- `plugin/sample`：可安装的示例插件 APK（1 页 + 1 工具），页面用插件自带 Compose，工具调 `schoolTool("get_schedule")`。
- `app/.../plugin/`：
  - `PluginManager`：安装、更新、启用、允许小岱调用、卸载、dex 加载缓存、工具定义合并。
  - `PluginDexLoader`：child-first/parent-first 混合 classloader。
  - `PluginRegistry` / `PluginManifest`：`filesDir/plugins` 落盘、签名/版本/API 版本/入口类校验。
  - `PluginRuntime`：`PluginContext` 实现；只读学校工具白名单；按账号缓存 `AgentToolRegistry`。
  - `PluginAgentTools`：并入 `CompositeAgentTools`，工具名改写为 `plugin_<id>_<tool>`。
  - `PluginOverlay`：全屏插件页覆盖层。
  - `PluginsPage`：设置 → 插件（SAF 安装、确认框、开关、打开页面、卸载）。
- 接线：
  - `AndroidAgentHost` 加插件工具；
  - `AppRoot` 加覆盖层、切号关页；
  - `SettingsHost` / `SettingsScreen` / `AppSettings` 加「设置 → 插件」整页。
- 设备测试：`PluginSystemInstrumentedTest` 通过（安装 → dex 加载 → 页面对象 → 工具定义 → 卸载）。
- 手工验证：文件选择器、签名/版本确认框、安装/更新/卸载路径已走通。
- Android 14+ `Writable dex file is not allowed` 已修（APK 落盘后 `setReadOnly()`）。

## 当前阻塞：打开示例页仍崩

按时间顺序出现过两类崩溃，均已修，但最新包仍然会崩；**需要装最新 debug 包后再抓一次 logcat**。

### 已修 1：`ViewTreeLifecycleOwner not found`

```
java.lang.IllegalStateException: ViewTreeLifecycleOwner not found from androidx.compose.ui.platform.ComposeView
```

原因：插件自带 `androidx.lifecycle` / `androidx.savedstate`，和宿主 `AndroidView` 不是同一套类，插件给 ComposeView 打的 tag 宿主/插件读不到。

处理：`androidx.lifecycle`、`androidx.savedstate` 改成**父加载器（共用宿主类）**，并在 `app/proguard-rules.pro` 保留。

### 已修 2：`ClassReference cannot be cast to ClassBasedDeclarationContainer`

```
java.lang.ClassCastException: kotlin.jvm.internal.ClassReference cannot be cast to kotlin.jvm.internal.ClassBasedDeclarationContainer
  at androidx.lifecycle.ViewModelProvider$NewInstanceFactory.create
```

原因：lifecycle 共用之后，宿主的 `ViewModelProvider` 用宿主 Kotlin stdlib 处理插件传过去的 `KClass`，两边 stdlib 不是一套。

处理：`kotlin` 也改成**父加载器（共用宿主 stdlib）**，并在 R8 里 `-keep class kotlin.**`。

### 待查的第三次崩溃

最新代码装到设备后，打开示例页仍崩。下一次需要：

```bat
adb logcat -d -b crash | findstr /i "AndroidRuntime"
```

把最后一条 `FATAL EXCEPTION` + `Caused by` 贴出来。优先怀疑：

1. `kotlinx.coroutines` 仍 child-first，宿主 lifecycle / Compose 与插件之间的协程类身份不一致；
2. `androidx.core` 仍 child-first，Compose 内部 `WindowInsetsCompat` / `ViewCompat` 等跨边界；
3. 宿主 `AndroidView` 的 `ComposeViewContext` 与插件 `ComposeView` 的父组合上下文冲突（`findViewTreeComposeViewRoot` 的已知坑）。

## 当前 classloader 策略

- **parent-first（共用宿主）**：Android 框架、`com.xjtu.toolbox.plugin.api`、`androidx.lifecycle`、`androidx.savedstate`、`kotlin`。
- **child-first（插件自带）**：`androidx.compose`、`androidx.core`、`androidx.activity`、`androidx.collection`、`androidx.annotation`、`androidx.tracing`、`androidx.profileinstaller`、`androidx.graphics`、`kotlinx`、`org.jetbrains`。
- **R8 keep**：`plugin.api`、`androidx.lifecycle`、`androidx.savedstate`、`kotlin`。

这个边界是实验性的：每多共享一个库，就要多一份 keep，也会把插件 ABI 绑得更紧。第三条崩溃就是在验证这个边界。

## 文件地图

| 路径 | 内容 |
|---|---|
| `plugin/api/` | 插件 API（AAR，插件作者唯一依赖） |
| `plugin/sample/` | 示例插件 APK（`assembleDebug` 产物） |
| `app/src/main/java/com/xjtu/toolbox/plugin/` | 宿主：manager / loader / runtime / overlay / settings UI |
| `app/src/androidTest/.../PluginSystemInstrumentedTest.kt` | 设备测试 |
| `docs/plugins.md` | 设计与使用说明（WIP 警告已加） |
| `app/proguard-rules.pro` | 插件 API / lifecycle / savedstate / kotlin keep |

## 构建与安装

```bat
gradlew.bat :plugin:sample:assembleDebug
gradlew.bat :app:assembleDebug -PdebugSuffix
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb push plugin\sample\build\outputs\apk\debug\sample-debug.apk /sdcard/Download/
```

> 本机 `gradlew` 是 CRLF，Git Bash 里直接跑会坏；用 `gradlew.bat`，或
> `java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain ...`。

设备测试：

```bat
gradlew.bat :app:connectedDebugAndroidTest -PdebugSuffix
```

## 已知限制（设计层面）

- 只支持 Android；桌面/Web 没有插件。
- 插件在 App 进程内运行，**没有沙箱**，只适合可信插件。
- 不支持插件 Android 资源 / `.so`；页面是覆盖层，不进返回栈、不能放底栏/首页宫格。
- 学校数据只给只读白名单工具，返回文本不是稳定结构化契约。
- R8 正式包还没验证。

## 下一步

1. 抓第三次崩溃的 logcat，按堆栈把对应库（大概率 `kotlinx.coroutines` 或 `androidx.core`）改成共用，或者反过来把整条 Compose/lifecycle 栈都共用。
2. 如果 classloader 仍然打不通，退一步：插件页只返回经典 Android `View`（不用 Compose），或改 WebView/JS 插件方案；先把"安装 + 工具"留下来。
3. 给设备测试补"真正 createView + attach 到 Activity"的用例，防止这类崩溃回归。
4. 在 `preview` / `release` 包上验证 R8 keep 和插件 ABI。
5. 稳定后再更新 `docs/plugins.md`、去掉 WIP 警告、补更新日志。
