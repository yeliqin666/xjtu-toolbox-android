# 首页卡片「一镜到底」：金标联盟 SharedViewKit + Compose 转场回落

「首页卡片 → 功能页」的无缝转场：支持的 ROM 走系统级 SharedViewKit，其余回落到 Compose 放大转场。

覆盖的入口：

- 首页公告堆叠条「查看详情」→ 站内公告详情
- 首页「常用功能」图标 → 对应功能页
- 首页功能卡片（卡片组 / 原小组件，含经典模式下的分类入口行）→ 对应功能页
- 首页经典布局与卡片组的「待办」行 → 消息收纳
- 顶部铃铛（消息收纳）、「我的」页的扫一扫 → 扫码登录 / 图书馆座位码
- 「我的」菜单行（设置、账号管理、下载管理、学业进度、电子成绩单）、游戏合集卡片、通知列表

## 1. 运行时怎么选（点一下之后的路径）

| 条件 | 走哪条路 | 效果 |
|---|---|---|
| ROM 声明支持 SharedViewKit（`isViewAnimationSupported() > 0`）、目标页可独立托管、有凭据、非离线页有网 | `KitOneTake` → `SeamlessPageActivity`（独立 Activity，`ActivityOptions`） | ROM 系统级一镜到底：卡片/图标快照长成页面，返回缩回原卡片 |
| 站点会话还没连上（有凭据） | 同上，宿主先画骨架再补登录 | **转场立刻播**；骨架后面 `ensureSite`（超时 60s / 多跳 180s），连上后才压真实页面 |
| 一个凭据都没有 / 页面要联网但没网且没有缓存 / 页面维护中 | 原 Compose 路由（`AppRouter.open` 提示 / 自动登录） | 保留原来的提示，不把人送进一页空白 |
| 底栏语义路由（日程 / 小岱 / 学辅）、付款码覆盖层、`Main` | 原路径 | 保持切 tab / 覆盖层语义 |
| 不支持 ROM / 截图失败 / `makeViewAnimationOptions()` 返回 null / 已有启动在进行 | 原 Compose 路由 | 仓库现有放大转场：窗口从卡片矩形长到全屏，返回叠回源快照 |

判定在点击处完成；KIT 失败路径**恰好回落一次**（主线程），不会出现「点不动」或两套动画叠加。
点击处的门只判「注定开不了」的静态条件（[SeamlessOpener](../app/src/main/java/com/xjtu/toolbox/sharedview/SeamlessOpener.kt) 的 `canHostWithKit`），
「会话准备好没有」交给宿主——见 `SeamlessPageActivity` 的 `Preparation`。

## 2. 关键文件

| 层 | 文件 |
|---|---|
| KIT 桥（只 `:app`） | `app/src/main/java/com/xjtu/toolbox/sharedview/KitOneTake.kt` |
| 页面宿主 Activity / 会话准备状态机 | `app/src/main/java/com/xjtu/toolbox/sharedview/SeamlessPageActivity.kt` |
| 骨架屏 / 失败态 | `app/src/main/java/com/xjtu/toolbox/sharedview/SeamlessPageSkeleton.kt` |
| 进程级会话 | `app/src/main/java/com/xjtu/toolbox/auth/ProcessLoginState.kt` |
| 路由 / 可托管判断 | `shared/navigation/.../AppRoute.kt`、`app/.../nav/AppNavHost.kt`（`isStandaloneHostable`） |
| 起点几何与通用入口 | `shared/navigation/.../SeamlessCardSource.kt`（`LocalSeamlessOpener`、`rememberSeamlessOrigin`） |
| 准入判断（全应用一份） | `app/.../sharedview/SeamlessOpener.kt`，`AppRoot` 注入 `LocalSeamlessOpener` |
| 公告详情屏 | `feature/home/.../bulletin/NoticeDetailScreen.kt` |
| 首页入口 | `feature/home/.../bulletin/BulletinUi.kt`、`home/HomeHost.kt`、`home/HomeQuickActions.kt`、`home/HomeTileBoard.kt`、`home/HomeClassicScreen.kt`、`home/HomeCardGroupScreen.kt`、`app/.../home/AppHomeHost.kt` |
| 顶栏铃铛 / 扫一扫 | `app/.../main/MainScreen.kt`；扫一扫另有路由 `AppRoute.QrScan`（`app/.../nav/AppNavHost.kt`） |
| 其他入口 | 「我的」菜单行（`app/.../profile/ProfileTab.kt`）、游戏合集卡片（`feature/game/.../GamesScreen.kt`）、通知列表（`feature/notice/.../NotificationScreen.kt`） |

新入口的写法：`val origin = rememberSeamlessOrigin(圆角)`，把 `origin.modifier` 挂在转场要长出来的那一块上，
点击时 `origin.open(route) { 原来的打开方式 }`。它先登记 Compose 放大起点，再交给 `LocalSeamlessOpener`；
桌面、Web 和一镜到底的独立宿主里没有提供它，直接走原来的打开方式。

## 3. 宿主为什么能复用页面

`SeamlessPageActivity` 不是一页一页复制的：它把 `AppNavHost` 用的那套 `appPageEntries` 搬进第二个 Activity，自己建一份 `NavBackStack` / `AppNavigator` / `AppRouter`，并补齐页面依赖的 CompositionLocal 和弹窗宿主：

- 会话 / 账号表来自进程级 `ProcessLoginState`（**不能**再 `viewModel()` 出第二份 `AppLoginStateViewModel`，否则会复制 SessionManager、覆盖全局 `SessionManager.active`、抢 cookie 文件）
- `LocalAppLoginState` / `LocalAuthExpiry` / `LocalIsWideLayout` / `LocalGlassStyle` 与主界面一致
- MFA、自动登录弹窗、付款码覆盖层、排行榜弹窗都在宿主里重放
- 页面内的二级跳转在宿主自己的返回栈里；退到栈底 `finish()` 触发 ROM 关闭动画

**会话准备（`Preparation`）**：宿主拿到路由后先判一次——已登录 / 不需要登录就直达；否则先画 `SeamlessPageSkeleton`
（主题底色铺满 + 顶栏/卡片/列表占位 + 真返回键），在骨架后面跑 `ensureSite`。成功后才创建 `NavDisplay` 去组合
`appPageEntries`；那些 `WithSite(...)` 的 entry 在拿不到会话时会 `back()`，所以必须等连接完成再组合。失败时显示
`SeamlessPageFailure`（原因 + 重试 + 返回），返回即 `finish()`，接 ROM 的缩回动画。

## 4. KIT 侧的硬约束（实现时已满足）

- 目标必须是**独立 Activity**、同一 task；关闭动画绑在该 Activity 的 `finish()` 上。
- 目标页首帧必须不透明全屏，不能有自定义 window enter/exit 转场——补登录时的骨架屏就是首帧（见上一节）。
- 源是 Compose 页面，没有单独 View，所以用 **Bitmap 源**：`PixelCopy` 截取卡片/图标窗口矩形（保留玻璃、底色等真实像素）。
- 单位：`radius/bounds` 都是 **px**（卡片 20dp / 行 15dp / 图标 `iconSize*0.31`，宿主按 density 换算）。
- 坐标系：读 `getViewAnimationExtras()[EXTRA_BOUNDS]` 的 `rect|WINDOW|...` 元数据决定 SCREEN/WINDOW，不按机型硬编码。
- extras：只写 ROM 声明支持的 key（当前只用 `EXTRA_BLUR`）。
- **底板填充色（`sharedView.color`）必须自己传，且要取源元素自己的底色**：底板是比源元素大一圈的圆角矩形，动画打开 / 返回时会从卡片四周露出来。不传时由 ROM 自动取色，它挑的是源截图里最显眼的颜色——卡片里的图标（比如公告的红色铃铛）会被当成底色，返回动画收尾回弹段就在卡片底下露出一圈红；取页面底色又会在渐变背景上露出一块与卡片无关的板子。现在的做法：`KitMaskColor` 在源元素截图里贴着圆角矩形内边取一圈像素（避开中间内容，圆角外的页面底色不参与），去极值后逐通道求均值——卡片得到自己的背景色（当前浅色配置约 `#F5F6F7`），常用功能图标得到图标那块浅色底（红铃铛约 `#FBE1DE`，每个图标不同），经 `ViewAnimationConfig.setColor` 写进 Bundle；取不到色时不传，交给 ROM 兜底。
- **截图圆角外那圈页面像素刷成底板同色**：源截图是矩形的，圆角外还带着点击那一刻的页面像素；动画里底板和截图错开时，这些像素会在底板与卡片之间露出一圈白边。拿到底板色后把圆角外区域填成同色，接缝就看不出来了。
- 宿主 Activity 关掉预测式返回（manifest `android:enableOnBackInvokedCallback="false"`），避免系统跨 Activity 预测返回与 ROM 关闭动画打架；系统返回交给 Compose 返回栈处理，退到底 `finish()`。

## 5. 真机验证清单

```bash
./gradlew :app:assembleDebug          # 有 release 签名时可覆盖安装正式版；没有则用 -PdebugSuffix 共存安装
adb install -r app/build/outputs/apk/debug/app-debug.apk

adb logcat -c && adb logcat -s KitOneTake SharedViewKit

# 首页点「常用功能」图标 / 功能小组件卡片 / 公告「查看详情」
# 期望（支持 ROM 且条件满足）：
#   SharedViewKit supported, protocol=100, extras=...
#   start KIT: route=<route.id>, protocol=100, windowRect=..., radiusPx=..., maskColor=#AARRGGBB
#   KIT animation start, mode=0
#   topResumedActivity = .../SeamlessPageActivity
# 返回：
#   KIT animation start/end, mode=1，回到 MainActivity
# 需登录但站点还没连上时点卡片：应当出现 start KIT，宿主先是骨架，随后自动登录并显示页面
# （只有一个凭据都没有 / 页面要联网但没网 / 维护中 才走原提示）
```

回归关注点：起点位置/圆角是否对齐卡片、返回落点、快速连点、页面内二级跳转、深色/动态取色、旋转/分屏、登录过期后的重登、混淆后的 preview / release 包。

## 6. 已知限制 / 风险

1. **覆盖率**：仅声明支持且 `isFeatureEnabled()` 打开的 ROM 走 KIT；模拟器、旧机型全部走 Compose 回落。
2. **补登录期间**：骨架最长等到 `autoLoginTimeoutMs`（60s / 多跳 180s）；失败留错误页 + 重试，不再回到「先等 Snackbar 再点一次」。MFA 弹窗由宿主自己的 `MfaDialogHost` 接；主界面此时处于 stopped，不会重复收 `activeMfaRequest`。
3. **半遮挡卡片**：卡片被列表视口裁切时 `PixelCopy` 可能失败 → 自动回落；尚未做 demo 里「返回前滚动入视」的兜底。
4. **通用宿主仍是第二套 Compose 根**：页面里「回到首页」这类路由（`AppRoute.Main`）不会关闭宿主（极少见）；页面内二级跳转都在宿主自己的返回栈里，不回到主界面栈。
5. **依赖与许可**：`gsai.sdk:sharedview-kit` 来自联盟内测 Nexus（HTTP、`allowInsecureProtocol`），版本已钉死 `1.0.0.5`；闭源 AAR + GPL-3.0 发行需维护者/法务确认，CI 需能访问该仓库。
6. **进程级会话**：主界面与宿主共用 `ProcessLoginState.get(context)`（`AndroidViewModel`，进程存活期内有效，进程被杀后按登录恢复流程重建），不要再用 Activity 级 `viewModel()` 建第二份。
