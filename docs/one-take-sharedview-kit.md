# 首页卡片「一镜到底」：金标联盟 SharedViewKit + Compose 转场回落

本文记录 `feat/one-take-seamless` 分支上「首页卡片 → 功能页」的无缝转场实现与真机验证方法。

覆盖的入口：

- 首页公告堆叠条「查看详情」→ 站内公告详情
- 首页「常用功能」图标 → 对应功能页
- 首页功能小组件卡片（含经典模式下的分类入口行）→ 对应功能页

## 1. 运行时怎么选（点一下之后的路径）

| 条件 | 走哪条路 | 效果 |
|---|---|---|
| ROM 声明支持 SharedViewKit（`isViewAnimationSupported() > 0`）且目标页可独立托管、登录会话已就绪、需要联网时有网 | `KitOneTake` → `SeamlessPageActivity`（独立 Activity，`ActivityOptions`） | ROM 系统级一镜到底：卡片/图标快照长成页面，返回缩回原卡片 |
| 需要登录但当前站点还没登录 | 原 Compose 路由（`AppRouter.open` 自动登录 / 提示） | 现有流程；登录成功后再点一次才走 KIT |
| 底栏语义路由（日程 / 屁岱 / 学辅）、付款码覆盖层、`Main` | 原路径 | 保持切 tab / 覆盖层语义 |
| 不支持 ROM / 截图失败 / `makeViewAnimationOptions()` 返回 null / 已有启动在进行 | 原 Compose 路由 | 仓库现有放大转场：窗口从卡片矩形长到全屏，返回叠回源快照 |

判定在点击处完成；KIT 失败路径**恰好回落一次**（主线程），不会出现「点不动」或两套动画叠加。

## 2. 关键文件

| 层 | 文件 |
|---|---|
| KIT 桥（只 `:app`） | `app/src/main/java/com/xjtu/toolbox/sharedview/KitOneTake.kt` |
| 页面宿主 Activity | `app/src/main/java/com/xjtu/toolbox/sharedview/SeamlessPageActivity.kt` |
| 进程级会话 | `app/src/main/java/com/xjtu/toolbox/auth/ProcessLoginState.kt` |
| 路由 / 可托管判断 | `shared/navigation/.../AppRoute.kt`、`app/.../nav/AppNavHost.kt`（`isStandaloneHostable`） |
| 起点几何与通用入口 | `shared/navigation/.../SeamlessCardSource.kt`（`LocalSeamlessOpener`、`rememberSeamlessOrigin`） |
| 准入判断（全应用一份） | `app/.../sharedview/SeamlessOpener.kt`，`AppRoot` 注入 `LocalSeamlessOpener` |
| 公告详情屏 | `feature/home/.../bulletin/NoticeDetailScreen.kt` |
| 首页入口 | `feature/home/.../bulletin/BulletinUi.kt`、`home/HomeScreen.kt`、`home/HomeHost.kt`、`home/HomeQuickActions.kt`、`home/HomeWidgetGrid.kt`、`app/.../home/AppHomeHost.kt` |
| 其他入口 | 「我的」菜单行（设置、账号管理、下载管理、电子成绩单）、游戏合集卡片、首页头卡的消息摘要、通知列表 |

新入口的写法：`val origin = rememberSeamlessOrigin(圆角)`，把 `origin.modifier` 挂在转场要长出来的那一块上，
点击时 `origin.open(route) { 原来的打开方式 }`。它先登记 Compose 放大起点，再交给 `LocalSeamlessOpener`；
桌面、Web 和一镜到底的独立宿主里没有提供它，直接走原来的打开方式。
| 构建 | `settings.gradle.kts`、`gradle/libs.versions.toml`、`app/build.gradle.kts` |

## 3. 宿主为什么能复用页面

`SeamlessPageActivity` 不是一页一页复制的：它把 `AppNavHost` 用的那套 `appPageEntries` 搬进第二个 Activity，自己建一份 `NavBackStack` / `AppNavigator` / `AppRouter`，并补齐页面依赖的 CompositionLocal 和弹窗宿主：

- 会话 / 账号表来自进程级 `ProcessLoginState`（**不能**再 `viewModel()` 出第二份 `AppLoginStateViewModel`，否则会复制 SessionManager、覆盖全局 `SessionManager.active`、抢 cookie 文件）
- `LocalAppLoginState` / `LocalAuthExpiry` / `LocalIsWideLayout` / `LocalGlassStyle` 与主界面一致
- MFA、自动登录弹窗、付款码覆盖层、排行榜弹窗都在宿主里重放
- 页面内的二级跳转在宿主自己的返回栈里；退到栈底 `finish()` 触发 ROM 关闭动画

## 4. KIT 侧的硬约束（实现时已满足）

- 目标必须是**独立 Activity**、同一 task；关闭动画绑在该 Activity 的 `finish()` 上。
- 目标页首帧必须不透明全屏，不能有自定义 window enter/exit 转场。
- 源是 Compose 页面，没有单独 View，所以用 **Bitmap 源**：`PixelCopy` 截取卡片/图标窗口矩形（保留玻璃、底色等真实像素）。
- 单位：`radius/bounds` 都是 **px**（卡片 20dp / 行 15dp / 图标 `iconSize*0.31`，宿主按 density 换算）。
- 坐标系：读 `getViewAnimationExtras()[EXTRA_BOUNDS]` 的 `rect|WINDOW|...` 元数据决定 SCREEN/WINDOW，不按机型硬编码。
- extras：只写 ROM 声明支持的 key（当前只用 `EXTRA_BLUR`）。
- 宿主 Activity 关掉预测式返回（manifest `android:enableOnBackInvokedCallback="false"`），避免系统跨 Activity 预测返回与 ROM 关闭动画打架；系统返回交给 Compose 返回栈处理，退到底 `finish()`。

## 5. 真机验证清单

```bash
./gradlew :app:assembleDebug          # 有 release 签名时可覆盖安装正式版；没有则用 -PdebugSuffix 共存安装
adb install -r app/build/outputs/apk/debug/app-debug.apk

adb logcat -c && adb logcat -s KitOneTake SharedViewKit

# 首页点「常用功能」图标 / 功能小组件卡片 / 公告「查看详情」
# 期望（支持 ROM 且条件满足）：
#   SharedViewKit supported, protocol=100, extras=...
#   start KIT: route=<route.id>, protocol=100, windowRect=..., radiusPx=...
#   KIT animation start, mode=0
#   topResumedActivity = .../SeamlessPageActivity
# 返回：
#   KIT animation start/end, mode=1，回到 MainActivity
# 需登录但未登录时点卡片：不应出现 start KIT，走原 Compose 自动登录/提示
```

回归关注点：起点位置/圆角是否对齐卡片、返回落点、快速连点、页面内二级跳转、深色/动态取色、旋转/分屏、登录过期后的重登、release 包（preview 包不混淆，问题只会在 release 暴露）。

## 6. 已知限制 / 风险

1. **覆盖率**：仅声明支持且 `isFeatureEnabled()` 打开的 ROM 走 KIT；模拟器、旧机型全部走 Compose 回落。
2. **登录门禁**：KIT 只在会话已就绪时走；未登录时保留原来的自动登录流程（登录完成后需再点一次才是 KIT 动画）。
3. **半遮挡卡片**：卡片被列表视口裁切时 `PixelCopy` 可能失败 → 自动回落；尚未做 demo 里「返回前滚动入视」的兜底。
4. **通用宿主仍是第二套 Compose 根**：页面里「回到首页」这类路由（`AppRoute.Main`）不会关闭宿主（极少见）；页面内二级跳转都在宿主自己的返回栈里，不回到主界面栈。
5. **依赖与许可**：`gsai.sdk:sharedview-kit` 来自联盟内测 Nexus（HTTP、`allowInsecureProtocol`），版本已钉死 `1.0.0.5`；闭源 AAR + GPL-3.0 发行需维护者/法务确认，CI 需能访问该仓库。
6. **回滚**：整个能力集中在 `KitOneTake` / `SeamlessPageActivity` / `ProcessLoginState` / 首页 `onRouteSeamless` 钩子，`git revert` 对应提交即可退回；不支持 ROM 的设备本来就走原路径。

## 7. 顺带改掉的进程级状态

`AppLoginStateViewModel` 从 Activity 级 `viewModel()` 改成进程级 `ProcessLoginState.get(context)`，主界面与一镜到底宿主共用同一份会话。它继承 `AndroidViewModel`、没有 `onCleared` 逻辑，进程存活期内始终有效；进程被杀后按现有登录恢复流程重建。
