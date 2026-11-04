# KMP 模块拆分地图

拆分基线是 `refactor/kmp-multiplatform` 上的 `6f16e363`。这次只做 `git mv` 和必要的 build 改动，包名 `com.xjtu.toolbox.*` 一个都没改，行为也不变。

## 依赖方向

```
:shared:model ← :shared:navigation ← :shared:designsystem
      ↑                ↑
:shared:network ───────┘          （network api 依赖 model、navigation）
      ↑
:shared:data（只有 jvm + android：okhttp / jsoup）

:feature:*  →  只依赖 :shared:{model,navigation,designsystem,network}，功能之间互不依赖
:core       →  残留壳：shell/**，并用 api 转发全部 :shared:* 与 :feature:*
:data       →  残留：场馆滑块验证码，api 转发 :shared:data 与 :core
:app / :desktop / :web / :server  →  平台壳，依赖不变（仍然经 :core / :data 拿到全部模块）
```

- 功能模块要的数据接口（`*Source`、`AgentTransport` 之类）都放在 `:shared:model`，不放在 `:shared:data`。原因是 `:shared:data` 没有 wasmJs 目标。真正的实现由各平台壳注入：Android 和桌面端用 `:shared:data`，Web 用 `web/`。
- 各平台壳本来就负责收集 agent 工具、注册路由：`:app` 里的 `AppNavHost`、`AndroidAgentHost`，桌面端的路由表，`web/`、`server/`。这次没有新造一套注册契约，`:app` 也基本没动，方便 `port/android-only` 变基。
- 约定插件放在 `build-logic/`，共三个：
  - `toolbox.kmp.library`：android、jvm、wasmJs 三个目标；
  - `toolbox.kmp.jvm-android`：只有 android 和 jvm；
  - `toolbox.kmp.compose`：Compose 和 MIUIX 依赖。

  每个模块的 Kotlin moduleName 和 jar 名都是 `toolbox-<路径>`，避免 `:data` 和 `:shared:data` 的 `META-INF/*.kotlin_module`、jar 撞名。

## 模块清单（.kt 文件数，main / test）

| 模块 | main | test | 内容 |
|---|---:|---:|---|
| `:shared:model` | 171 | 27 | 纯 Kotlin 领域模型、`*Source` 接口、agent 工具接口、WebVpn 编解码、Inbox |
| `:shared:navigation` | 3 | 1 | `AppRoute`、`AuthExpiry`、`ExpandTransition` |
| `:shared:designsystem` | 75 | 4 | MIUIX 主题、玻璃效果、通用组件、`CourseColors` |
| `:shared:network` | 37 | 22 | Ktor 客户端（`createToolboxClient`）、`YellowPageApi`、`CampusJudgeApi` |
| `:shared:data` | 135 | 57 | 原 `:data` 去掉 Compose 后的部分：会话、登录、WebVpn、各站 Api、平台存储，再加上从 `:app` 移来的 `LibrarySeatQr` |
| `:feature:account` | 3 | 0 | account、auth 界面 |
| `:feature:agent` | 49 | 3 | agent 对话界面 |
| `:feature:attendance` | 2 | 1 | 考勤 |
| `:feature:campus` | 20 | 1 | card、venue、pay、coupon、dormpower、emptyroom、yellowpage、fitness、iclassface、faculty |
| `:feature:community` | 16 | 2 | community、social、feedback |
| `:feature:game` | 52 | 3 | 小游戏 |
| `:feature:home` | 11 | 3 | home、bulletin |
| `:feature:jiaocai` | 13 | 2 | jiaocai、jiaocai1 |
| `:feature:library` | 4 | 1 | 图书馆座位 |
| `:feature:lms` | 11 | 2 | lms、media |
| `:feature:notice` | 4 | 1 | notification、inbox（待办也在这里，仓库里没有单独的 todo 包） |
| `:feature:schedule` | 17 | 7 | schedule、calendar |
| `:feature:score` | 7 | 2 | score、jwapp、dzpz、judge |
| `:feature:settings` | 21 | 7 | settings、legal、archive |
| `:feature:webvpn` | 2 | 1 | webvpn 换算器、内置浏览器 |
| `:core`（残留） | 10 | 2 | shell/** |
| `:data`（残留） | 3 | 3 | 场馆滑块验证码 |

## 重点文件

| 文件 | 原位置 | 现位置 | 说明 |
|---|---|---|---|
| `LibrarySeatQr.kt`（模型） | `core/src/commonMain/…/library/` | `:shared:model` | `LibrarySeatQr`、`LibraryQrArea`、`ScanSeatPrompt`、`LibrarySeatStatus` |
| `LibrarySeatQr.kt`（解析） | `app/src/main/java/…/library/` | `:shared:data` `commonMain/…/library/` | `parseLibrarySeatQr`、`LibrarySeatAvailability`。用到 okhttp `HttpUrl`，所以放在 data，不放 model。测试 `LibrarySeatQrTest` 移到 `shared/data/src/jvmTest`。两个文件都没有顶层函数撞同名 facade |
| `InboxModels.kt`（`InboxStore` / `InboxItem` / `InboxRules`） | `core/src/commonMain/…/inbox/` | `:shared:model` | Web 端也要用，所以不能放进只有 jvm/android 的 `:shared:data`。`InboxStore` 用到 compose-runtime 的 `mutableStateListOf`，所以 `:shared:model` 单独多依赖一个 `org.jetbrains.compose.runtime:runtime`，没有 UI 和 foundation。这是 model「不带 Compose」规则的唯一例外 |
| `AppRoute.kt` | `core/src/commonMain/…/nav/` | `:shared:navigation` | `web/build.gradle.kts` 的路由快照任务和 `DesktopRouteCoverageJvmTest` 都已改指新路径 |
| WebVpn 会话 | `data/src/commonMain/…/auth/*Session.kt`、`…/webvpn/WebVpnUrl.kt`、`WebVpnUtil.kt` | `:shared:data` 的同名目录 | 403、未授权的判定都在 `SiteSession`、`LmsSession`，原样搬过来 |
| `WebVpnCodec.kt` | `core/src/commonMain/…/webvpn/` | `:shared:model` | 纯算法，三端共用 |
| `ToolboxHttp.kt`（`createToolboxClient`） | `core/src/commonMain/…/core/net/` | `:shared:network` | 见下方「备注」 |
| `ScheduleSourceRouter.kt` | `data` | `:shared:data` `…/schedule/` | 原样 |
| `AttendanceApi.kt`（`getKqtjCurrentWeek`、`parseCourseStats`） | `data` | `:shared:data` `…/attendance/` | 原样 |
| `MainActivity`、`AppUpdater`、`HomeTab` | `:app` | `:app`（没动） | |

## 残留模块，以及为什么残留

1. **`:core` 只剩 shell/**，共 10 个文件，外加测试 `ShellModelTest`、`StoreArchiveImportJvmTest`。
   - 应用外壳要把所有功能模块拼起来，本来就该依赖全部 feature，所以留在原地当壳。
   - `StoreArchiveImportJvmTest` 同时用到 settings、library、schedule 三个功能，放进哪个 feature 都会造成 feature 之间的依赖，所以跟壳一起留下。
   - `:core` 用 `api` 转发全部 shared 和 feature 模块，四个平台壳的依赖写法因此一行都不用改。
2. **`:data` 只剩 venue 的 `SliderCaptcha.kt`、`VenueSlideCaptchaHost.kt`、`VenueCaptchaSolver.kt`，外加 3 个测试**（`VenueCaptchaSolverJvmTest`、`VenueApiJvmTest`、`auth/VenueLoginTestHarness`）。
   - 这几个文件是 `@Composable` 的。它们实现的是 `:feature:campus` 里的 Compose 接口 `SlideCaptchaHost`，还用到 designsystem 里带 Compose 类型的 `ImageDecode`。
   - 放进 `:shared:data` 会把 Compose 带进 data，或者让 data 反过来依赖 feature。
   - 想拆开得先把验证码的图像接口改成纯数据类型，那是行为相关的改动，不在这次范围内。

## 为了拆分做的内容改动（全部在单独的 commit 里）

- `4f6d7e45`：把混着 Compose 的模型拆出来，拆出 `agent/AgentWidgetModels.kt`、`library/LibraryFocus.kt`、`judge/JudgeSource.kt`。
- 同一个 commit 里，有 8 处跨模块用到的 `internal` 改成 public：`countWorkDays`、`SOURCE_PREF_KEY`、`ScanSeatPrompt`、`androidPlatformContext`、`keyValueStoreRoot`、wasm `KeyValueStore.PREFIX`、`CodeMatrixView`、`beijingEpochMs`。
- `64931a3d`：跨模块后，别的模块里声明的 public 属性不能再智能转换，所以补了两处 `!!`，都在原来判过 null 的分支里：`ScoreReportScreen` 的 `grade.gpa`、`VenueScreen` 的 `result.orderId`。

## 备注

- `createToolboxClient()` 实际建了 4 次：
  - `app/…/yellowpage/YellowPageApp.kt`；
  - `desktop/…/Main.kt`；
  - `server/…/ServeAgent.kt`；
  - `server/…/ServeEndpointRoutes.kt`（默认参数）。

  其中 server 进程里有 2 个。另外 `web/…/Data.kt` 也会 `config` 一份。这次按要求不合并，只记在这里。
- `CourseColors`、`CourseColorMap` 是课表配色，严格说不算「通用」UI。但 home 和 schedule 都要用，为了不让 feature 互相依赖，放进了 designsystem。
- `AuthExpiry` 和 `ExpandTransition` 放在 navigation，因为它们和路由契约绑在一起。
- 下面这些放在 model，因为它们是纯类型、多个 feature 共用：
  - `core/net/JwxtSchedule.kt`；
  - `MatchProfile`；
  - `DzpzDocuments`。
- 测试跟着源码走。CI `core-gate` 已经改成逐个模块跑 `jvmTest`；`desktop-linux` 的路径过滤覆盖 `:desktop` 的全部依赖（`shared/**`、`feature/**`、`build-logic/**`），`desktop-windows` 只看 Windows 专属面（`desktop/**`、`data/**`、`shared/data/**`）。
- 编译警告，不影响构建：
  - 各个 KMP 库有 `commonTest`，但没开 Android host test，AGP 会提示；
  - 约定插件里用了已弃用的 `compose.runtime` 等访问器；
  - `build-logic` 加了 `-Xskip-metadata-version-check`。
- 任务清单里的 `SeatComfort` 在这个仓库的任何分支、任何历史里都搜不到，所以本次拆分没有涉及它。
- **serve 模式联调**：`./gradlew :server:run` 后浏览器打开它打印的地址，页面上「会话」一格里先贴启动横幅里打印的访问令牌、再登录即可；数据源由 `?backend=serve` 钉死、否则自动探测（探 `/api/status` 有没有 `username` 字段区分 serve 与 campus-api；本地联调 campus-api 可用 `web/tools/serve-same-origin.py` 反代）。
- **wasmJs 便携性判据**：`String.format`、`toSortedMap`、`java.time`、`Dispatchers.IO` 在 JVM 上是默认导入/标准库，光看 import 查不出来；只有把共享层真编到 wasmJs（如 `./gradlew :shared:model:compileKotlinWasmJs :feature:home:compileKotlinWasmJs`）才能全部拦下。

## 旧路径 → 新模块（目录级，按 `git diff -M 6f16e363 HEAD` 生成，只统计 .kt）

`…` 代表 `kotlin/com/xjtu/toolbox`（或 `java/com/xjtu/toolbox`）。

| 旧目录 | 新模块 | 新目录 | 文件数 |
|---|---|---|---:|
| `app/src/main/…/library/` | `:shared:data` | `shared/data/src/commonMain/…/library/` | 1 |
| `app/src/test/…/library/` | `:shared:data` | `shared/data/src/jvmTest/…/library/` | 1 |
| `core/src/androidMain/…/archive/` | `:feature:settings` | `feature/settings/src/androidMain/…/archive/` | 2 |
| `core/src/androidMain/…/core/net/` | `:shared:network` | `shared/network/src/androidMain/…/core/net/` | 1 |
| `core/src/androidMain/…/game/` | `:feature:game` | `feature/game/src/androidMain/…/game/` | 1 |
| `core/src/androidMain/…/platform/` | `:shared:designsystem` | `shared/designsystem/src/androidMain/…/platform/` | 5 |
| `core/src/androidMain/…/platform/` | `:shared:model` | `shared/model/src/androidMain/…/platform/` | 11 |
| `core/src/androidMain/…/ui/` | `:shared:designsystem` | `shared/designsystem/src/androidMain/…/ui/` | 1 |
| `core/src/androidMain/…/ui/components/` | `:shared:designsystem` | `shared/designsystem/src/androidMain/…/ui/components/` | 3 |
| `core/src/androidMain/…/ui/theme/` | `:shared:designsystem` | `shared/designsystem/src/androidMain/…/ui/theme/` | 1 |
| `core/src/commonMain/…/account/` | `:feature:account` | `feature/account/src/commonMain/…/account/` | 2 |
| `core/src/commonMain/…/account/` | `:shared:model` | `shared/model/src/commonMain/…/account/` | 3 |
| `core/src/commonMain/…/agent/` | `:feature:agent` | `feature/agent/src/commonMain/…/agent/` | 31 |
| `core/src/commonMain/…/agent/` | `:shared:model` | `shared/model/src/commonMain/…/agent/` | 4 |
| `core/src/commonMain/…/agent/bot/` | `:feature:agent` | `feature/agent/src/commonMain/…/agent/bot/` | 8 |
| `core/src/commonMain/…/agent/bot/cast/` | `:feature:agent` | `feature/agent/src/commonMain/…/agent/bot/cast/` | 10 |
| `core/src/commonMain/…/archive/` | `:feature:settings` | `feature/settings/src/commonMain/…/archive/` | 11 |
| `core/src/commonMain/…/attendance/` | `:feature:attendance` | `feature/attendance/src/commonMain/…/attendance/` | 2 |
| `core/src/commonMain/…/attendance/` | `:shared:model` | `shared/model/src/commonMain/…/attendance/` | 5 |
| `core/src/commonMain/…/auth/` | `:feature:account` | `feature/account/src/commonMain/…/auth/` | 1 |
| `core/src/commonMain/…/auth/` | `:shared:model` | `shared/model/src/commonMain/…/auth/` | 4 |
| `core/src/commonMain/…/auth/` | `:shared:navigation` | `shared/navigation/src/commonMain/…/auth/` | 1 |
| `core/src/commonMain/…/browser/` | `:feature:webvpn` | `feature/webvpn/src/commonMain/…/browser/` | 1 |
| `core/src/commonMain/…/bulletin/` | `:feature:home` | `feature/home/src/commonMain/…/bulletin/` | 3 |
| `core/src/commonMain/…/bulletin/` | `:shared:model` | `shared/model/src/commonMain/…/bulletin/` | 1 |
| `core/src/commonMain/…/calendar/` | `:feature:schedule` | `feature/schedule/src/commonMain/…/calendar/` | 1 |
| `core/src/commonMain/…/calendar/` | `:shared:model` | `shared/model/src/commonMain/…/calendar/` | 2 |
| `core/src/commonMain/…/card/` | `:feature:campus` | `feature/campus/src/commonMain/…/card/` | 4 |
| `core/src/commonMain/…/card/` | `:shared:model` | `shared/model/src/commonMain/…/card/` | 4 |
| `core/src/commonMain/…/community/` | `:feature:community` | `feature/community/src/commonMain/…/community/` | 12 |
| `core/src/commonMain/…/community/` | `:shared:model` | `shared/model/src/commonMain/…/community/` | 5 |
| `core/src/commonMain/…/core/net/` | `:shared:model` | `shared/model/src/commonMain/…/core/net/` | 1 |
| `core/src/commonMain/…/core/net/` | `:shared:network` | `shared/network/src/commonMain/…/core/net/` | 32 |
| `core/src/commonMain/…/coupon/` | `:feature:campus` | `feature/campus/src/commonMain/…/coupon/` | 2 |
| `core/src/commonMain/…/coupon/` | `:shared:model` | `shared/model/src/commonMain/…/coupon/` | 2 |
| `core/src/commonMain/…/dormpower/` | `:feature:campus` | `feature/campus/src/commonMain/…/dormpower/` | 1 |
| `core/src/commonMain/…/dormpower/` | `:shared:model` | `shared/model/src/commonMain/…/dormpower/` | 2 |
| `core/src/commonMain/…/dzpz/` | `:feature:score` | `feature/score/src/commonMain/…/dzpz/` | 2 |
| `core/src/commonMain/…/dzpz/` | `:shared:model` | `shared/model/src/commonMain/…/dzpz/` | 2 |
| `core/src/commonMain/…/emptyroom/` | `:feature:campus` | `feature/campus/src/commonMain/…/emptyroom/` | 2 |
| `core/src/commonMain/…/emptyroom/` | `:shared:model` | `shared/model/src/commonMain/…/emptyroom/` | 3 |
| `core/src/commonMain/…/error/` | `:shared:model` | `shared/model/src/commonMain/…/error/` | 1 |
| `core/src/commonMain/…/faculty/` | `:feature:campus` | `feature/campus/src/commonMain/…/faculty/` | 3 |
| `core/src/commonMain/…/faculty/` | `:shared:model` | `shared/model/src/commonMain/…/faculty/` | 2 |
| `core/src/commonMain/…/feedback/` | `:feature:community` | `feature/community/src/commonMain/…/feedback/` | 3 |
| `core/src/commonMain/…/feedback/` | `:shared:model` | `shared/model/src/commonMain/…/feedback/` | 2 |
| `core/src/commonMain/…/fitness/` | `:feature:campus` | `feature/campus/src/commonMain/…/fitness/` | 1 |
| `core/src/commonMain/…/fitness/` | `:shared:model` | `shared/model/src/commonMain/…/fitness/` | 1 |
| `core/src/commonMain/…/game/` | `:feature:game` | `feature/game/src/commonMain/…/game/` | 5 |
| `core/src/commonMain/…/game/blocks/` | `:feature:game` | `feature/game/src/commonMain/…/game/blocks/` | 3 |
| `core/src/commonMain/…/game/g2048/` | `:feature:game` | `feature/game/src/commonMain/…/game/g2048/` | 5 |
| `core/src/commonMain/…/game/go/` | `:feature:game` | `feature/game/src/commonMain/…/game/go/` | 5 |
| `core/src/commonMain/…/game/gomoku/` | `:feature:game` | `feature/game/src/commonMain/…/game/gomoku/` | 5 |
| `core/src/commonMain/…/game/hop/` | `:feature:game` | `feature/game/src/commonMain/…/game/hop/` | 4 |
| `core/src/commonMain/…/game/net/` | `:feature:game` | `feature/game/src/commonMain/…/game/net/` | 11 |
| `core/src/commonMain/…/game/ui/` | `:feature:game` | `feature/game/src/commonMain/…/game/ui/` | 2 |
| `core/src/commonMain/…/game/xiangqi/` | `:feature:game` | `feature/game/src/commonMain/…/game/xiangqi/` | 2 |
| `core/src/commonMain/…/game/xiangqi/engine/` | `:feature:game` | `feature/game/src/commonMain/…/game/xiangqi/engine/` | 1 |
| `core/src/commonMain/…/game/xiangqi/rules/` | `:feature:game` | `feature/game/src/commonMain/…/game/xiangqi/rules/` | 6 |
| `core/src/commonMain/…/home/` | `:feature:home` | `feature/home/src/commonMain/…/home/` | 8 |
| `core/src/commonMain/…/home/` | `:shared:model` | `shared/model/src/commonMain/…/home/` | 1 |
| `core/src/commonMain/…/iclassface/` | `:feature:campus` | `feature/campus/src/commonMain/…/iclassface/` | 1 |
| `core/src/commonMain/…/iclassface/` | `:shared:model` | `shared/model/src/commonMain/…/iclassface/` | 1 |
| `core/src/commonMain/…/inbox/` | `:feature:notice` | `feature/notice/src/commonMain/…/inbox/` | 2 |
| `core/src/commonMain/…/inbox/` | `:shared:model` | `shared/model/src/commonMain/…/inbox/` | 2 |
| `core/src/commonMain/…/jiaocai/` | `:feature:jiaocai` | `feature/jiaocai/src/commonMain/…/jiaocai/` | 4 |
| `core/src/commonMain/…/jiaocai/` | `:shared:model` | `shared/model/src/commonMain/…/jiaocai/` | 2 |
| `core/src/commonMain/…/jiaocai1/` | `:feature:jiaocai` | `feature/jiaocai/src/commonMain/…/jiaocai1/` | 9 |
| `core/src/commonMain/…/jiaocai1/` | `:shared:model` | `shared/model/src/commonMain/…/jiaocai1/` | 3 |
| `core/src/commonMain/…/judge/` | `:feature:score` | `feature/score/src/commonMain/…/judge/` | 2 |
| `core/src/commonMain/…/judge/` | `:shared:network` | `shared/network/src/commonMain/…/judge/` | 1 |
| `core/src/commonMain/…/jwapp/` | `:feature:score` | `feature/score/src/commonMain/…/jwapp/` | 2 |
| `core/src/commonMain/…/jwapp/` | `:shared:model` | `shared/model/src/commonMain/…/jwapp/` | 2 |
| `core/src/commonMain/…/legal/` | `:feature:settings` | `feature/settings/src/commonMain/…/legal/` | 2 |
| `core/src/commonMain/…/library/` | `:feature:library` | `feature/library/src/commonMain/…/library/` | 4 |
| `core/src/commonMain/…/library/` | `:shared:model` | `shared/model/src/commonMain/…/library/` | 4 |
| `core/src/commonMain/…/lms/` | `:feature:lms` | `feature/lms/src/commonMain/…/lms/` | 5 |
| `core/src/commonMain/…/lms/` | `:shared:model` | `shared/model/src/commonMain/…/lms/` | 4 |
| `core/src/commonMain/…/media/` | `:feature:lms` | `feature/lms/src/commonMain/…/media/` | 6 |
| `core/src/commonMain/…/media/` | `:shared:model` | `shared/model/src/commonMain/…/media/` | 1 |
| `core/src/commonMain/…/nav/` | `:shared:navigation` | `shared/navigation/src/commonMain/…/nav/` | 2 |
| `core/src/commonMain/…/notification/` | `:feature:notice` | `feature/notice/src/commonMain/…/notification/` | 2 |
| `core/src/commonMain/…/notification/` | `:shared:model` | `shared/model/src/commonMain/…/notification/` | 1 |
| `core/src/commonMain/…/pay/` | `:feature:campus` | `feature/campus/src/commonMain/…/pay/` | 1 |
| `core/src/commonMain/…/pay/` | `:shared:model` | `shared/model/src/commonMain/…/pay/` | 1 |
| `core/src/commonMain/…/platform/` | `:shared:designsystem` | `shared/designsystem/src/commonMain/…/platform/` | 6 |
| `core/src/commonMain/…/platform/` | `:shared:model` | `shared/model/src/commonMain/…/platform/` | 19 |
| `core/src/commonMain/…/platform/qr/` | `:shared:model` | `shared/model/src/commonMain/…/platform/qr/` | 12 |
| `core/src/commonMain/…/schedule/` | `:feature:schedule` | `feature/schedule/src/commonMain/…/schedule/` | 16 |
| `core/src/commonMain/…/schedule/` | `:shared:designsystem` | `shared/designsystem/src/commonMain/…/schedule/` | 2 |
| `core/src/commonMain/…/schedule/` | `:shared:model` | `shared/model/src/commonMain/…/schedule/` | 19 |
| `core/src/commonMain/…/score/` | `:feature:score` | `feature/score/src/commonMain/…/score/` | 1 |
| `core/src/commonMain/…/score/` | `:shared:model` | `shared/model/src/commonMain/…/score/` | 2 |
| `core/src/commonMain/…/settings/` | `:feature:settings` | `feature/settings/src/commonMain/…/settings/` | 2 |
| `core/src/commonMain/…/settings/` | `:shared:model` | `shared/model/src/commonMain/…/settings/` | 1 |
| `core/src/commonMain/…/social/` | `:feature:community` | `feature/community/src/commonMain/…/social/` | 1 |
| `core/src/commonMain/…/social/` | `:shared:model` | `shared/model/src/commonMain/…/social/` | 4 |
| `core/src/commonMain/…/ui/` | `:shared:designsystem` | `shared/designsystem/src/commonMain/…/ui/` | 2 |
| `core/src/commonMain/…/ui/adaptive/` | `:shared:designsystem` | `shared/designsystem/src/commonMain/…/ui/adaptive/` | 1 |
| `core/src/commonMain/…/ui/components/` | `:shared:designsystem` | `shared/designsystem/src/commonMain/…/ui/components/` | 20 |
| `core/src/commonMain/…/ui/glass/` | `:shared:designsystem` | `shared/designsystem/src/commonMain/…/ui/glass/` | 10 |
| `core/src/commonMain/…/ui/theme/` | `:shared:designsystem` | `shared/designsystem/src/commonMain/…/ui/theme/` | 4 |
| `core/src/commonMain/…/util/` | `:shared:model` | `shared/model/src/commonMain/…/util/` | 7 |
| `core/src/commonMain/…/venue/` | `:feature:campus` | `feature/campus/src/commonMain/…/venue/` | 4 |
| `core/src/commonMain/…/venue/` | `:shared:model` | `shared/model/src/commonMain/…/venue/` | 3 |
| `core/src/commonMain/…/webvpn/` | `:feature:webvpn` | `feature/webvpn/src/commonMain/…/webvpn/` | 1 |
| `core/src/commonMain/…/webvpn/` | `:shared:model` | `shared/model/src/commonMain/…/webvpn/` | 1 |
| `core/src/commonMain/…/yellowpage/` | `:feature:campus` | `feature/campus/src/commonMain/…/yellowpage/` | 1 |
| `core/src/commonMain/…/yellowpage/` | `:shared:model` | `shared/model/src/commonMain/…/yellowpage/` | 1 |
| `core/src/commonMain/…/yellowpage/` | `:shared:network` | `shared/network/src/commonMain/…/yellowpage/` | 1 |
| `core/src/commonTest/…/agent/` | `:feature:agent` | `feature/agent/src/commonTest/…/agent/` | 1 |
| `core/src/commonTest/…/agent/` | `:shared:model` | `shared/model/src/commonTest/…/agent/` | 1 |
| `core/src/commonTest/…/archive/` | `:feature:settings` | `feature/settings/src/commonTest/…/archive/` | 6 |
| `core/src/commonTest/…/attendance/` | `:shared:model` | `shared/model/src/commonTest/…/attendance/` | 1 |
| `core/src/commonTest/…/browser/` | `:feature:webvpn` | `feature/webvpn/src/commonTest/…/browser/` | 1 |
| `core/src/commonTest/…/card/` | `:shared:network` | `shared/network/src/commonTest/…/card/` | 1 |
| `core/src/commonTest/…/community/` | `:feature:community` | `feature/community/src/commonTest/…/community/` | 1 |
| `core/src/commonTest/…/community/` | `:shared:model` | `shared/model/src/commonTest/…/community/` | 1 |
| `core/src/commonTest/…/core/net/` | `:shared:network` | `shared/network/src/commonTest/…/core/net/` | 12 |
| `core/src/commonTest/…/game/xiangqi/` | `:feature:game` | `feature/game/src/commonTest/…/game/xiangqi/` | 3 |
| `core/src/commonTest/…/home/` | `:feature:home` | `feature/home/src/commonTest/…/home/` | 2 |
| `core/src/commonTest/…/home/` | `:shared:model` | `shared/model/src/commonTest/…/home/` | 1 |
| `core/src/commonTest/…/library/` | `:shared:network` | `shared/network/src/commonTest/…/library/` | 2 |
| `core/src/commonTest/…/lms/` | `:feature:lms` | `feature/lms/src/commonTest/…/lms/` | 1 |
| `core/src/commonTest/…/lms/` | `:shared:model` | `shared/model/src/commonTest/…/lms/` | 1 |
| `core/src/commonTest/…/media/` | `:feature:lms` | `feature/lms/src/commonTest/…/media/` | 1 |
| `core/src/commonTest/…/nav/` | `:shared:navigation` | `shared/navigation/src/commonTest/…/nav/` | 1 |
| `core/src/commonTest/…/schedule/` | `:feature:schedule` | `feature/schedule/src/commonTest/…/schedule/` | 2 |
| `core/src/commonTest/…/schedule/` | `:shared:designsystem` | `shared/designsystem/src/commonTest/…/schedule/` | 2 |
| `core/src/commonTest/…/schedule/` | `:shared:model` | `shared/model/src/commonTest/…/schedule/` | 4 |
| `core/src/commonTest/…/score/` | `:shared:model` | `shared/model/src/commonTest/…/score/` | 1 |
| `core/src/commonTest/…/settings/` | `:shared:model` | `shared/model/src/commonTest/…/settings/` | 1 |
| `core/src/commonTest/…/ui/` | `:shared:designsystem` | `shared/designsystem/src/commonTest/…/ui/` | 1 |
| `core/src/commonTest/…/ui/components/` | `:shared:designsystem` | `shared/designsystem/src/commonTest/…/ui/components/` | 1 |
| `core/src/commonTest/…/util/` | `:shared:model` | `shared/model/src/commonTest/…/util/` | 2 |
| `core/src/commonTest/…/venue/` | `:shared:network` | `shared/network/src/commonTest/…/venue/` | 2 |
| `core/src/commonTest/…/yellowpage/` | `:shared:network` | `shared/network/src/commonTest/…/yellowpage/` | 1 |
| `core/src/jvmMain/…/archive/` | `:feature:settings` | `feature/settings/src/jvmMain/…/archive/` | 2 |
| `core/src/jvmMain/…/core/net/` | `:shared:network` | `shared/network/src/jvmMain/…/core/net/` | 1 |
| `core/src/jvmMain/…/game/` | `:feature:game` | `feature/game/src/jvmMain/…/game/` | 1 |
| `core/src/jvmMain/…/platform/` | `:shared:designsystem` | `shared/designsystem/src/jvmMain/…/platform/` | 5 |
| `core/src/jvmMain/…/platform/` | `:shared:model` | `shared/model/src/jvmMain/…/platform/` | 11 |
| `core/src/jvmMain/…/ui/` | `:shared:designsystem` | `shared/designsystem/src/jvmMain/…/ui/` | 1 |
| `core/src/jvmMain/…/ui/components/` | `:shared:designsystem` | `shared/designsystem/src/jvmMain/…/ui/components/` | 3 |
| `core/src/jvmMain/…/ui/theme/` | `:shared:designsystem` | `shared/designsystem/src/jvmMain/…/ui/theme/` | 1 |
| `core/src/jvmTest/…/agent/` | `:feature:agent` | `feature/agent/src/jvmTest/…/agent/` | 2 |
| `core/src/jvmTest/…/archive/` | `:feature:settings` | `feature/settings/src/jvmTest/…/archive/` | 1 |
| `core/src/jvmTest/…/attendance/` | `:feature:attendance` | `feature/attendance/src/jvmTest/…/attendance/` | 1 |
| `core/src/jvmTest/…/bulletin/` | `:feature:home` | `feature/home/src/jvmTest/…/bulletin/` | 1 |
| `core/src/jvmTest/…/bulletin/` | `:shared:model` | `shared/model/src/jvmTest/…/bulletin/` | 1 |
| `core/src/jvmTest/…/core/net/` | `:shared:network` | `shared/network/src/jvmTest/…/core/net/` | 4 |
| `core/src/jvmTest/…/coupon/` | `:feature:campus` | `feature/campus/src/jvmTest/…/coupon/` | 1 |
| `core/src/jvmTest/…/dzpz/` | `:feature:score` | `feature/score/src/jvmTest/…/dzpz/` | 1 |
| `core/src/jvmTest/…/error/` | `:shared:model` | `shared/model/src/jvmTest/…/error/` | 1 |
| `core/src/jvmTest/…/feedback/` | `:feature:community` | `feature/community/src/jvmTest/…/feedback/` | 1 |
| `core/src/jvmTest/…/inbox/` | `:feature:notice` | `feature/notice/src/jvmTest/…/inbox/` | 1 |
| `core/src/jvmTest/…/jiaocai/` | `:feature:jiaocai` | `feature/jiaocai/src/jvmTest/…/jiaocai/` | 1 |
| `core/src/jvmTest/…/jiaocai/` | `:shared:model` | `shared/model/src/jvmTest/…/jiaocai/` | 1 |
| `core/src/jvmTest/…/jiaocai1/` | `:feature:jiaocai` | `feature/jiaocai/src/jvmTest/…/jiaocai1/` | 1 |
| `core/src/jvmTest/…/jiaocai1/` | `:shared:model` | `shared/model/src/jvmTest/…/jiaocai1/` | 1 |
| `core/src/jvmTest/…/jwapp/` | `:feature:score` | `feature/score/src/jvmTest/…/jwapp/` | 1 |
| `core/src/jvmTest/…/library/` | `:feature:library` | `feature/library/src/jvmTest/…/library/` | 1 |
| `core/src/jvmTest/…/platform/` | `:shared:model` | `shared/model/src/jvmTest/…/platform/` | 5 |
| `core/src/jvmTest/…/schedule/` | `:feature:schedule` | `feature/schedule/src/jvmTest/…/schedule/` | 5 |
| `core/src/jvmTest/…/schedule/` | `:shared:model` | `shared/model/src/jvmTest/…/schedule/` | 3 |
| `core/src/jvmTest/…/social/` | `:shared:model` | `shared/model/src/jvmTest/…/social/` | 2 |
| `core/src/wasmJsMain/…/archive/` | `:feature:settings` | `feature/settings/src/wasmJsMain/…/archive/` | 2 |
| `core/src/wasmJsMain/…/core/net/` | `:shared:network` | `shared/network/src/wasmJsMain/…/core/net/` | 1 |
| `core/src/wasmJsMain/…/game/` | `:feature:game` | `feature/game/src/wasmJsMain/…/game/` | 1 |
| `core/src/wasmJsMain/…/platform/` | `:shared:designsystem` | `shared/designsystem/src/wasmJsMain/…/platform/` | 5 |
| `core/src/wasmJsMain/…/platform/` | `:shared:model` | `shared/model/src/wasmJsMain/…/platform/` | 11 |
| `core/src/wasmJsMain/…/ui/` | `:shared:designsystem` | `shared/designsystem/src/wasmJsMain/…/ui/` | 1 |
| `core/src/wasmJsMain/…/ui/components/` | `:shared:designsystem` | `shared/designsystem/src/wasmJsMain/…/ui/components/` | 3 |
| `core/src/wasmJsMain/…/ui/theme/` | `:shared:designsystem` | `shared/designsystem/src/wasmJsMain/…/ui/theme/` | 1 |
| `data/src/androidMain/…/data/` | `:shared:data` | `shared/data/src/androidMain/…/data/` | 1 |
| `data/src/androidMain/…/platform/` | `:shared:data` | `shared/data/src/androidMain/…/platform/` | 2 |
| `data/src/commonMain/…/account/` | `:shared:data` | `shared/data/src/commonMain/…/account/` | 1 |
| `data/src/commonMain/…/agent/` | `:shared:data` | `shared/data/src/commonMain/…/agent/` | 5 |
| `data/src/commonMain/…/attendance/` | `:shared:data` | `shared/data/src/commonMain/…/attendance/` | 6 |
| `data/src/commonMain/…/auth/` | `:shared:data` | `shared/data/src/commonMain/…/auth/` | 42 |
| `data/src/commonMain/…/bulletin/` | `:shared:data` | `shared/data/src/commonMain/…/bulletin/` | 1 |
| `data/src/commonMain/…/calendar/` | `:shared:data` | `shared/data/src/commonMain/…/calendar/` | 1 |
| `data/src/commonMain/…/card/` | `:shared:data` | `shared/data/src/commonMain/…/card/` | 2 |
| `data/src/commonMain/…/community/` | `:shared:data` | `shared/data/src/commonMain/…/community/` | 4 |
| `data/src/commonMain/…/coupon/` | `:shared:data` | `shared/data/src/commonMain/…/coupon/` | 2 |
| `data/src/commonMain/…/dormpower/` | `:shared:data` | `shared/data/src/commonMain/…/dormpower/` | 2 |
| `data/src/commonMain/…/dzpz/` | `:shared:data` | `shared/data/src/commonMain/…/dzpz/` | 2 |
| `data/src/commonMain/…/emptyroom/` | `:shared:data` | `shared/data/src/commonMain/…/emptyroom/` | 3 |
| `data/src/commonMain/…/faculty/` | `:shared:data` | `shared/data/src/commonMain/…/faculty/` | 2 |
| `data/src/commonMain/…/feedback/` | `:shared:data` | `shared/data/src/commonMain/…/feedback/` | 2 |
| `data/src/commonMain/…/fitness/` | `:shared:data` | `shared/data/src/commonMain/…/fitness/` | 3 |
| `data/src/commonMain/…/iclassface/` | `:shared:data` | `shared/data/src/commonMain/…/iclassface/` | 3 |
| `data/src/commonMain/…/inbox/` | `:shared:data` | `shared/data/src/commonMain/…/inbox/` | 2 |
| `data/src/commonMain/…/jiaocai/` | `:shared:data` | `shared/data/src/commonMain/…/jiaocai/` | 3 |
| `data/src/commonMain/…/jiaocai1/` | `:shared:data` | `shared/data/src/commonMain/…/jiaocai1/` | 3 |
| `data/src/commonMain/…/judge/` | `:shared:data` | `shared/data/src/commonMain/…/judge/` | 3 |
| `data/src/commonMain/…/jwapp/` | `:shared:data` | `shared/data/src/commonMain/…/jwapp/` | 3 |
| `data/src/commonMain/…/library/` | `:shared:data` | `shared/data/src/commonMain/…/library/` | 3 |
| `data/src/commonMain/…/lms/` | `:shared:data` | `shared/data/src/commonMain/…/lms/` | 4 |
| `data/src/commonMain/…/network/` | `:shared:data` | `shared/data/src/commonMain/…/network/` | 3 |
| `data/src/commonMain/…/notification/` | `:shared:data` | `shared/data/src/commonMain/…/notification/` | 2 |
| `data/src/commonMain/…/pay/` | `:shared:data` | `shared/data/src/commonMain/…/pay/` | 1 |
| `data/src/commonMain/…/platform/` | `:shared:data` | `shared/data/src/commonMain/…/platform/` | 2 |
| `data/src/commonMain/…/schedule/` | `:shared:data` | `shared/data/src/commonMain/…/schedule/` | 8 |
| `data/src/commonMain/…/score/` | `:shared:data` | `shared/data/src/commonMain/…/score/` | 2 |
| `data/src/commonMain/…/venue/` | `:shared:data` | `shared/data/src/commonMain/…/venue/` | 2 |
| `data/src/commonMain/…/webvpn/` | `:shared:data` | `shared/data/src/commonMain/…/webvpn/` | 2 |
| `data/src/jvmMain/…/library/` | `:shared:data` | `shared/data/src/jvmMain/…/library/` | 1 |
| `data/src/jvmMain/…/media/` | `:shared:data` | `shared/data/src/jvmMain/…/media/` | 1 |
| `data/src/jvmMain/…/platform/` | `:shared:data` | `shared/data/src/jvmMain/…/platform/` | 5 |
| `data/src/jvmTest/…/account/` | `:shared:data` | `shared/data/src/jvmTest/…/account/` | 1 |
| `data/src/jvmTest/…/agent/` | `:shared:data` | `shared/data/src/jvmTest/…/agent/` | 5 |
| `data/src/jvmTest/…/attendance/` | `:shared:data` | `shared/data/src/jvmTest/…/attendance/` | 1 |
| `data/src/jvmTest/…/auth/` | `:shared:data` | `shared/data/src/jvmTest/…/auth/` | 13 |
| `data/src/jvmTest/…/card/` | `:shared:data` | `shared/data/src/jvmTest/…/card/` | 1 |
| `data/src/jvmTest/…/community/` | `:shared:data` | `shared/data/src/jvmTest/…/community/` | 1 |
| `data/src/jvmTest/…/coupon/` | `:shared:data` | `shared/data/src/jvmTest/…/coupon/` | 1 |
| `data/src/jvmTest/…/dormpower/` | `:shared:data` | `shared/data/src/jvmTest/…/dormpower/` | 1 |
| `data/src/jvmTest/…/dzpz/` | `:shared:data` | `shared/data/src/jvmTest/…/dzpz/` | 1 |
| `data/src/jvmTest/…/emptyroom/` | `:shared:data` | `shared/data/src/jvmTest/…/emptyroom/` | 2 |
| `data/src/jvmTest/…/faculty/` | `:shared:data` | `shared/data/src/jvmTest/…/faculty/` | 3 |
| `data/src/jvmTest/…/feedback/` | `:shared:data` | `shared/data/src/jvmTest/…/feedback/` | 1 |
| `data/src/jvmTest/…/fitness/` | `:shared:data` | `shared/data/src/jvmTest/…/fitness/` | 2 |
| `data/src/jvmTest/…/iclassface/` | `:shared:data` | `shared/data/src/jvmTest/…/iclassface/` | 1 |
| `data/src/jvmTest/…/inbox/` | `:shared:data` | `shared/data/src/jvmTest/…/inbox/` | 1 |
| `data/src/jvmTest/…/jiaocai/` | `:shared:data` | `shared/data/src/jvmTest/…/jiaocai/` | 1 |
| `data/src/jvmTest/…/jiaocai1/` | `:shared:data` | `shared/data/src/jvmTest/…/jiaocai1/` | 1 |
| `data/src/jvmTest/…/judge/` | `:shared:data` | `shared/data/src/jvmTest/…/judge/` | 2 |
| `data/src/jvmTest/…/jwapp/` | `:shared:data` | `shared/data/src/jvmTest/…/jwapp/` | 1 |
| `data/src/jvmTest/…/library/` | `:shared:data` | `shared/data/src/jvmTest/…/library/` | 3 |
| `data/src/jvmTest/…/lms/` | `:shared:data` | `shared/data/src/jvmTest/…/lms/` | 1 |
| `data/src/jvmTest/…/media/` | `:shared:data` | `shared/data/src/jvmTest/…/media/` | 1 |
| `data/src/jvmTest/…/network/` | `:shared:data` | `shared/data/src/jvmTest/…/network/` | 1 |
| `data/src/jvmTest/…/notification/` | `:shared:data` | `shared/data/src/jvmTest/…/notification/` | 2 |
| `data/src/jvmTest/…/platform/` | `:shared:data` | `shared/data/src/jvmTest/…/platform/` | 4 |
| `data/src/jvmTest/…/schedule/` | `:shared:data` | `shared/data/src/jvmTest/…/schedule/` | 2 |
| `data/src/jvmTest/…/score/` | `:shared:data` | `shared/data/src/jvmTest/…/score/` | 2 |
