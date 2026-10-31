# 三端能力台账（Android 基准 → 桌面 / web 差集）

> 生成：2026-10-19 ｜ 扫描对象：`c02011d1`（= `dev` + PR #142 的 12 个文件）

> 口径：**以 Android 现有行为为基准**，逐功能点判定桌面与 web 有没有真实装。

> ⚠️ 与 `docs/desktop-port-plan.md` §16.2 的「差集」不是一回事：那份只算**路由**（43 条），本台账算**功能点**（设置项 / 首页块 / 能力槽位 / 平台缝 / 存档族 ……）。两套数字不能互相替换。


## 0. 怎么读


| 记法 | 含义 |
|---|---|
| ✅ | 该端有真实现 |
| 🔧 | **没做** —— 共享槽位（seam）已在，但该端没实现、或 honored 清单里不列 |
| ❌ | 物理不可能（附一句为什么），或该端完全没有这一档 |
| ➖ | 不适用（该端本来就不该有） |
| ❓ | 未核实（宁可标这个，不猜） |


**判据来源**：每条都带「证据（文件:行）」。本节数字由脚本按表格列复算（不数 emoji 文本），但仍属**逐格判定**，可靠性以各表证据为准。


## 1. 汇总（复算）

| 台账 | 覆盖 | 数据行 | Android | 桌面 | web |
|---|---|---|---|---|---|

| 1 | 设置页 / 首页 / 外壳 / 主题 / 触感 | 47 | ✅45 🔧0 ❌0 ❓0 ➖2 | ✅19 🔧21 ❌1 ❓0 ➖6 | ✅15 🔧26 ❌0 ❓1 ➖5 |

| 2 | 日程 / 考勤 / 成绩 / 教学空间 | 32 | ✅31 🔧0 ❌1 ❓0 ➖0 | ✅22 🔧3 ❌7 ❓0 ➖0 | ✅6 🔧11 ❌15 ❓0 ➖0 |

| 3 | 屁岱 / 通知与提醒 / 更新 / 账号 / 小组件 | 31 | ✅24 🔧1 ❌2 ❓0 ➖0 | ✅6 🔧0 ❌23 ❓0 ➖0 | ✅1 🔧2 ❌27 ❓0 ➖0 |

| 4 | 媒体 / 下载 / 二维码 / 相机 / 剪贴板 / PWA / 浏览器 / 落盘 | 32 | ✅26 🔧0 ❌0 ❓0 ➖6 | ✅13 🔧12 ❌2 ❓0 ➖5 | ✅11 🔧15 ❌3 ❓0 ➖2 |

| 5 | 路由×三端 / 统一存档层 / serve 端点缺口 | 54 | ✅5 🔧0 ❌1 ❓0 ➖0 | ✅4 🔧0 ❌1 ❓0 ➖0 | ✅2 🔧0 ❌3 ❓0 ➖0 |

| **合计** | | **196** | ✅131 🔧1 ❌4 ❓0 ➖8 | ✅64 🔧36 ❌34 ❓0 ➖11 | ✅35 🔧54 ❌48 ❓1 ➖7 |

> ⚠️ **台账 5 的 emoji 列不适用**：它的 A 节用**文字判定**（`真屏` / `serve-only` / `无分支 ⇒ NotPortedScreen`），所以上面那行的 ✅/❌ 计数不代表路由数。该节的路由计数以原文为准（Android 43 真屏；桌面 41 真屏 + 2 兜底；web 26 真屏 + 13 serve-only + 3 无分支 + 1 非屏）。主代理只复算了 `WebSupport.openableRouteIds` = **39** 条（= 26 + 13；按注释剔除后 `sort -u` 复算），与原文自洽。


## 2. 真·功能缺失集中在四块

1. **web 的 13 条 serve-only 屏**（`WebServeOnlyScreen`）—— 含付款码 / 成绩单 / 考勤 / 加餐券 / 宿舍电费等高频繁项。卡的是 serve 宿主与端点，不是前端工时。
2. **统一存档层 7 处不对称**（详见台账 5 · B 节）：`files/schedule` 只有 Android、`files/agent_sessions` web 无、Room 三表三端三种形态、web `manifest.accounts` 恒空、Android 无 `#written_at`、目录形态只有桌面双向、导入时机三端不同。**"统一存档"实际只在 stores 层统一，跨端丢的恰是用户自造数据。**
3. **设置/首页 honored 差集**（台账 1）：桌面 3/11 行、web 2/11 行；首页块桌面 8/9、web 6/9。
4. **平台缝**（台账 4）：web 整套下载 5 格、桌面内嵌浏览器 + cookie 注入、桌面无 `SystemTray` 通知、桌面 `DownloadedFilesHost` / `SHARE_FILE` 缺口。

## 3. 已复核的新发现（含对文档的更正）

| # | 发现 | 复核方式 |
|---|---|---|
| 1 | **`AgentHost.onAccountChanged` 在代码里不存在**（只在 4 处 docs 出现） | `grep -rn onAccountChanged --include=*.kt` 零命中；等价物是 `AgentScreen.kt:96-99` + `AgentViewModel.kt:171-183` 的账号后缀守卫 |
| 2 | **web `SecureRandom` 退化为 `Random.Default`** | `shared/model/src/wasmJsMain/.../SecureRandom.wasmJs.kt` 全文 11 行；被匿名 ID / 考勤请求号 / 一次性对局令牌 / 反馈 nonce 使用 |
| 3 | **桌面 `DesktopUpdate` 无生产调用方** | `grep -rln DesktopUpdate desktop/src/main/kotlin` 只命中它自己（135 行骨架 + 测试全绿） |
| 4 | `SettingsRow` 成员数 = **11**（子代理自报 12） | `grep -c` 复算 |
| 5 | serve `AccountRoutes` 写死 `readOnly = true` | `AccountRoutes.kt:59` |

## 4. 本台账的盲区（如实）

- **未覆盖**：契约例外与产品文案（`api-contract.md` §5.5 末尾的成绩单 PDF、`agent-web-spec.md` §9 未决 #7/#8、桌面共享屏里那句 Android 口径支付文案）。
- **未逐条复核**：子代理给的「证据(文件:行)」只抽核了 5 条（见 §3），其余按原样保留。
- **判定的边界**：「🔧 没做」不区分"计划内待做"与"决定不做"，那要会议拍板（见下节映射）。

## 5. 与会议议程的映射

| 议程 | 台账里的对应 |
|---|---|
| #1 存档冲突语义 / #3 / #5 / #6 | 台账 5 · B 节 |
| #9 web 三屏 | 台账 5 · A 节 |
| #10 账号写侧 | 台账 3 · 账号节 + 台账 5 · C 节 |
| #11 桌面浏览器 | 台账 4 · Browser / cookie 注入 |
| #13 设置行 / #14 首页块 | 台账 1 |
| #15 屁岱 | 台账 3 |
| #16 小组件 / 通知 | 台账 3 + 台账 4 |
| #12 路由口径 | 台账 5 · A 节（43 条逐条） |

---



---

# 台账 1 · 设置页 / 首页 / 外壳 / 主题 / 触感

> 子代理原始产物（`/tmp/ledger-1-shell-settings.md`），表格内容原样保留；汇总数字以上表复算为准。


### 台账①：设置页 / 首页 / 外壳 / 主题 / 触感 —— 三端能力对照

仓库：`/mnt/nas/data/works/xjtu-toolbox-fixes`（HEAD `c02011d1` = dev + PR#142 的 12 个文件）。**只读扫描，未改仓库任何文件、未跑 gradle、未联网。**

## 档位说明

- ✅ 实装：这一端**在 `honoredRows` / `honoredCards` 里列了它**，且该端有真接线（给 `文件:行`）。
- 🔧 没做：缺的是可写的接线或实现（seam 已有，写一行/写一份即可）。
- ❌ 物理不可能：这一端物理上没有该能力（例：桌面窗口没有振动器）。
- ➖ 不适用：这一端模型里没有这一档。
- ❓ 未核实：没拿到能定档的证据。

列固定为：功能点 | Android | 桌面 | web | 证据(文件:行) | 补它要动什么(一句话)。
所有路径相对仓库根；`A`=Android，`D`=桌面(jvm)，`W`=web(wasmJs)。

---

## ① `enum SettingsRow`（11 项）× 三端 `honoredRows`

三端声明本体：A = `app/src/main/java/com/xjtu/toolbox/settings/AppSettings.kt:124`（`SettingsRow.entries.toSet()`，11/11）；
D = `desktop/src/main/kotlin/com/xjtu/toolbox/desktop/DesktopSettings.kt:60-61`（3 项）；
W = `web/src/wasmJsMain/kotlin/com/xjtu/toolbox/web/WebSettingsHost.kt:45-46`（2 项）。

| 功能点 | Android | 桌面 | web | 证据(文件:行) | 补它要动什么(一句话) |
| --- | --- | --- | --- | --- | --- |
| 1. `DARK_MODE` 深色模式 | ✅ | ✅ | ✅ | `AppSettings.kt:124` · `MainActivity.kt:82-83`（`appearance.darkMode` → 主题） · `DesktopSettings.kt:61` · `ToolboxDesktopApp.kt:167-171`（`appearanceTick` 重读） · `WebSettingsHost.kt:46` · `web/src/wasmJsMain/kotlin/Main.kt:17-21`（`appearanceTick` 重读） · `theme/Theme.kt:29-33` | 无 |
| 2. `DYNAMIC_COLOR` 取色开关 | ✅ | ✅ | 🔧 | `AppSettings.kt:124` · `MainActivity.kt:83` · `DesktopSettings.kt:61` · `ToolboxDesktopApp.kt:169,171` · `theme/Theme.kt:22-27,45-52` · `WebSettingsHost.kt:46`（未列） · `Main.kt:19-21`（`XJTUToolBoxTheme` 只传 `darkModeOverride`） | web：`Main.kt` 多传一个 `dynamicColor = settings.dynamicColor`，再把 `DYNAMIC_COLOR` 加进 `WebSettingsHost.honoredRows` |
| 3. `DYNAMIC_COLOR` 是否真跟随系统壁纸（Monet 取色来源） | ✅（Android Monet） | 🔧（调色板来自固定种子） | ❓（未在浏览器里验证） | `miuix-ui-desktop-0.9.4.jar` 字节码：`javap -c ThemeControllerKt` → `monetSystemColors` 首条即 `ldc2_w 4284960932l`（= `0xFF6750A4` 固定种子），`DynamicColors_skikoKt.platformDynamicColors` → `monetSystemColors(dark)` · `docs/desktop-port-plan.md:943,993`（"Monet 在 wasm 上未验证"） | 桌面：自己读壁纸取色再喂 `colorsFromSeed`（miuix 的 skiko 实现恒用固定种子）；web：先接线再看运行时 |
| 4. `HOME_THEME` 首页主题 | ✅ | ✅ | ✅ | `AppSettings.kt:124` · `MainScreen.kt:146`（collect） · `AppHomeHost.kt:230` · `DesktopSettings.kt:61` · `DesktopHome.kt:143` · `WebSettingsHost.kt:46` · `WebHomeHost.kt:85,90,144` · 版式分流在共享屏 `feature/home/src/commonMain/kotlin/com/xjtu/toolbox/home/HomeScreen.kt:127`（`isIconTheme`） | 无（三端共用同一份 `HomeScreen`） |
| 5. `QUICK_ACTIONS` 常用入口开关 | ✅ | 🔧 | 🔧 | `AppSettings.kt:124` · `MainScreen.kt:147` · `AppHomeHost.kt:172-183` · `HomeScreen.kt:370` · `DesktopSettings.kt:61`（未列） · `WebSettingsHost.kt:46`（未列） · 共享槽位 `feature/home/.../HomeHost.kt:106`（`quickRoutes` 默认空） | 两端：把 `ServiceUsageTracker`（现在只用 Android `Context`，`app/.../home/ServiceUsageTracker.kt:12-35`）搬到 `:data` + `KeyValueStore`，宿主再给 `quickRoutes` |
| 6. `NAV_STYLE` 界面风格（玻璃/经典） | ✅ | 🔧 | 🔧 | `AppSettings.kt:124` · `MainScreen.kt:145,173` · `AppRoot.kt:203`（`LocalGlassStyle`） · `DesktopSettings.kt:61`（未列） · `WebSettingsHost.kt:46`（未列） · 两端 `glassStyle = false` 硬编码：`ToolboxDesktopApp.kt:209`、`ToolboxWebApp.kt:166` | 两端：把 `glassStyle` 从常量改成读 `store.navBarStyle`（键已存在于共享 `SettingsStore`），再把该行加进 `honoredRows` |
| 7. `DEFAULT_TAB` 默认启动 tab | ✅ | 🔧 | 🔧 | `AppSettings.kt:124` · `AppRoot.kt:106`（`credentialStore.defaultTab` → `BottomTab`） · `SettingsScreen.kt:262-267` · `AndroidAgentHost.kt:143,194` · 桌面/web 无读取者（`grep defaultTab` 只命中 `shared/model` 与其测试） · 落点硬编码：`ToolboxDesktopApp.kt:151`、`ToolboxWebApp.kt:236-237` | 两端：读同一个键并把值映射到各自的 tab id —— 桌面的 tab id 是 `AppRoute.id`（`DesktopShell.kt:26,33,40,47,54`），web 是 `BottomTab` 那五个名字，**两套 id 不同** |
| 8. `HAPTICS` 触感开关 | ✅ | 🔧 | 🔧 | `AppSettings.kt:124` · `MainScreen.kt:232` · 开关本体 `shared/designsystem/.../ui/Haptics.kt:63-75` · Android actual `Haptics.android.kt:26,51` · `Haptics.jvm.kt:7` · `Haptics.wasmJs.kt:7` | 桌面：先得有能震的东西（没有就只留一个失效开关）；web：写一个 wasm actual 接 `navigator.vibrate`（Android Chrome 才有） |
| 9. `NETWORK_MODE` 连接模式 | ✅ | 🔧 | 🔧 | `AppSettings.kt:124,145`（`onNetworkModeChanged`） · `SettingsScreen.kt:95`（双条件：honored **且** 回调非 null） · `AppLoginState.kt:311-315`（读 `networkMode` 短路探测） · `DesktopSettings.kt:73`（null） · `WebSettingsHost.kt:63`（null） | 桌面：campus-api 直连/反代两种形态与"直连/WebVPN"不是一套，要先定义桌面这一端的两种模式；web：本端只有服务端给的那条路 |
| 10. `SCHEDULE_SOURCE` 课表来源 | ✅ | 🔧 | 🔧 | `AppSettings.kt:124,134` · `ScheduleSourceRouter.kt:32-43`（`of()` 读设置）、`:65-66`（`manager == null` 时短路成教务） · `SettingsScreen.kt:291-293` · `DesktopSettings.kt:65`（空表 → 不画） · `WebSettingsHost.kt:52`（空表） · 两端无 `ScheduleSourceRouter` 调用（`grep` 只命中 `:app`/`:server`） | 两端：先得有第二个来源 —— 考勤系统那条路要校园会话（`manager`），现在两头都传不进 |
| 11. `SCHEDULE_ATTENDANCE` 考勤角标 | ✅ | 🔧 | 🔧 | `AppSettings.kt:124` · `AppScheduleHost.kt:62` · `feature/schedule/.../ScheduleScreen.kt:908` 与 `CourseLinkSections.kt:100`（`ATTENDANCE in honored && attendanceBadgeEnabled`） · 共享默认 `shared/model/.../schedule/ScheduleHost.kt:79`（false） · 桌面 `DesktopScheduleHost.kt:24-32`（无 `ATTENDANCE`） · web `WebScheduleHost.kt:33-38`（无 `ATTENDANCE`） | 两端：先要有考勤取数口，再给宿主 `honored` 加 `ATTENDANCE`（设置行才有意义） |
| 12. `VENUE_CAPTCHA` 场馆验证码自动识别 | ✅ | 🔧 | 🔧 | `AppSettings.kt:124` · `AppNavHost.kt:310`（读 `venueAutoSolveCaptchaEnabled` 喂识别器） · `SettingsScreen.kt:110,327-331` · `DesktopSettings.kt:61`（未列） · `WebSettingsHost.kt:46`（未列） · web 已有滑块识别器 `web/src/wasmJsMain/kotlin/com/xjtu/toolbox/web/WebSlideCaptchaHost.kt`（无设置读取者） | web：把恒开的识别器改成受设置控制（读完即一行 honored）；桌面：要先有场馆预约那条路 |

## ② `SettingsHost` 其它槽位

| 功能点 | Android | 桌面 | web | 证据(文件:行) | 补它要动什么(一句话) |
| --- | --- | --- | --- | --- | --- |
| 13. `versionText` | ✅ | ✅ | ✅ | `AppSettings.kt:120`（`BuildConfig.VERSION_NAME (VERSION_CODE)` + 预览标记） · `DesktopSettings.kt:57` · `WebSettingsHost.kt:43` · 画在 `SettingsScreen.kt:342-345` | 无 |
| 14. `changelog`（空 = 不画那一行） | ✅ | 🔧 | 🔧 | `AppSettings.kt:126`（`AppChangelog.ENTRIES`） · `SettingsScreen.kt:347-353` · `DesktopSettings.kt:63`（`emptyList`） · `WebSettingsHost.kt:49`（`emptyList`） | 两端：要么搬 `AppChangelog`，要么从 GitHub release 生成一份（同一份 `ChangelogEntry` 结构） |
| 15. `scheduleSourceOptions`（空 = 不画） | ✅ | 🔧 | 🔧 | `AppSettings.kt:134`（`ScheduleSource.entries.map`） · `SettingsScreen.kt:291-293` · `DesktopSettings.kt:65` · `WebSettingsHost.kt:52` | 与 #10 同一件事，改完这一行自动出现 |
| 16. `noticeSection`（null = 无通知与后台排程） | ✅ | 🔧 | 🔧 | `AppSettings.kt:137`（`NoticeAndReminderSection`） · `DesktopSettings.kt:67`（null） · `WebSettingsHost.kt:55`（null） | 两端：各写一套排程实现（桌面系统通知 / 浏览器通知），再挂进这一槽 |
| 17. `dataPrivacySection`（null = 无可清缓存与崩溃上报） | ✅ | ✅ | ✅ | `AppSettings.kt:139` · `DesktopSettings.kt:69` 与实现 `:83-160`（导出/导入存档） · `WebSettingsHost.kt:58` 与实现 `:73-94` | 无（三端各有一份，动作不同） |
| 18. `updateSection`（null = 整组不画） | ✅ | 🔧 | ➖ | `AppSettings.kt:141` · `DesktopSettings.kt:71`（null） · `WebSettingsHost.kt:61`（null，注释：本端更新 = 服务端换 dist） | 桌面：接 jpackage/自更新渠道；web 的"更新"就是刷新页面，这一组没必要存在 |
| 19. `onNetworkModeChanged` | ✅ | 🔧 | 🔧 | `AppSettings.kt:145`（→ `loginState` 重挑 backend） · `SettingsScreen.kt:95`（null 就不画那一行） · `DesktopSettings.kt:73` · `WebSettingsHost.kt:63` | 与 #9 同生共死：回调非 null 才有那一行 |
| 20. `onAppearanceChanged` | ➖ | ✅ | ✅ | 接口默认空实现 `feature/settings/.../SettingsHost.kt:75` · Android 不实现（走 `AppearanceSettings` 的 StateFlow，`MainScreen.kt:145-147`） · `ToolboxDesktopApp.kt:167,728`（`appearanceTick++`） · `WebSettingsHost.kt:65` + `Main.kt:17,21` | 无（Android 用响应式流，不需要回调） |
| 21. `onOpenUrl`（附带槽位） | ✅ | ✅ | ✅ | `AppSettings.kt:148` · `DesktopSettings.kt:78` · `WebSettingsHost.kt:68` · 消费点 `SettingsScreen.kt:356-360` | 无 |

## ③ `HomeCard` 全枚举（9 项）× 三端 `honoredCards`

枚举本体 `feature/home/src/commonMain/kotlin/com/xjtu/toolbox/home/HomeHost.kt:12-39`。
A = `app/.../home/AppHomeHost.kt:216-226`（8 项 + `INBOX` 条件式）；D = `desktop/.../DesktopHome.kt:129-138`（8 项）；W = `web/.../WebHomeHost.kt:131-138`（6 项）。

| 功能点 | Android | 桌面 | web | 证据(文件:行) | 补它要动什么(一句话) |
| --- | --- | --- | --- | --- | --- |
| 22. `BULLETIN_BAR` 公告堆叠条 | ✅ | ✅ | 🔧 | `AppHomeHost.kt:217` · `DesktopHome.kt:130` · `WebHomeHost.kt:131-138`（未列） · 共享槽位 `HomeHost.kt:97` · 消费点 `HomeScreen.kt:166` | web：接一个公告源（campus-api 有 bulletin 路由就照桌面的 `DesktopBulletins` 写一份） |
| 23. `GREETING` 问候与日期 | ✅ | ✅ | ✅ | `AppHomeHost.kt:218,231-233`（昵称→姓名→账号三级回退） · `DesktopHome.kt:145`（`""`，无名字） · `WebHomeHost.kt:147`（`""`） · `HomeScreen.kt:214,367,399` | 无（两端只差"问候里没有名字"） |
| 24. `WEEK_NUMBER` 第 N 周 | ✅ | ✅ | ✅ | `AppHomeHost.kt:219,235` · `DesktopHome.kt:149` · `WebHomeHost.kt:133,151` · `HomeScreen.kt:178,239,364` | 无 |
| 25. `NEXT_UP` 下一项安排 | ✅ | ✅ | ✅ | `AppHomeHost.kt:220` · `DesktopHome.kt:153` · `WebHomeHost.kt:134,154-172`（三种"算不出"的说法与桌面逐条一致） · `HomeScreen.kt:369,424` | 无 |
| 26. `GLANCE` 速览小格 | ✅ | ✅ | 🔧 | `AppHomeHost.kt:221` · `DesktopHome.kt:173` · `WebHomeHost.kt:131-138`（未列） · 槽位默认空 `HomeHost.kt:103` · 消费点 `HomeScreen.kt:369` | web：要有可算的速览项（校园卡余额/考试倒计时那几格的取数口） |
| 27. `QUICK_ACTIONS` 常用入口 | ✅ | 🔧 | 🔧 | 同 #5：`AppHomeHost.kt:222,172-183` · `DesktopHome.kt:129-138`（未列，`:129` KDoc 写明"九块里不列 QUICK_ACTIONS"） · `WebHomeHost.kt:130`（同） | 同 #5（两侧都要一份点击习惯来源） |
| 28. `CATEGORY_STATS` 分类卡状态 | ✅ | ✅ | ✅ | `AppHomeHost.kt:223` · `DesktopHome.kt:199` · `WebHomeHost.kt:136,174` · `HomeScreen.kt:148` | 无 |
| 29. `SERVICE_GRID` 功能宫格 | ✅ | ✅ | ✅ | `AppHomeHost.kt:224` · `DesktopHome.kt:138,204`（`openableRouteIds`） · `WebHomeHost.kt:137,179`（`WebSupport.openableRouteIds`） · `HomeScreen.kt:189` | 无 |
| 30. `INBOX` 待办与消息 | ✅（图标主题下不列） | ✅ | ✅ | `AppHomeHost.kt:225`（`if (theme != THEME_ICON)`） · `DesktopHome.kt:135,201` · `WebHomeHost.kt:135,176` · `HomeScreen.kt:157,251` | 无 |

## ④ 外壳：五格 tab / 宽窗侧栏 / 落点

| 功能点 | Android | 桌面 | web | 证据(文件:行) | 补它要动什么(一句话) |
| --- | --- | --- | --- | --- | --- |
| 31. 五格 tab 集合本体 | ✅（`BottomTab`） | ➖（另一套：六格路由格） | ✅（与 `BottomTab` 逐项同 id/名/图标/iconSize） | A `app/.../main/BottomTab.kt:26-31,49-60` · web `WebShell.kt:84-124`（`HOME/COURSES/PIDAI/TOOLS/PROFILE`，label 与 `iconSize` 24/23/27/22/27 与 A 一致） · D `DesktopShell.kt:19-63`（首页/校历/图书馆/黄页/游戏/全部） | 无（桌面这一端按路由表导航，本来就不是五格） |
| 32. 屁岱格自绘（`ShellTabArt`） | ✅ | ➖（无那一格） | 🔧 | A `MainScreen.kt:257,338`（`ANDROID_SHELL_TABS` + `tabArt`） · `BottomTab.kt:43,66-67`（`PIDAI_TAB`） · 默认空实现 `core/.../shell/AppShell.kt:49` · 消费点 `core/.../shell/ShellBars.kt:59,113,166,219` · web 未传 `tabArt`（`ToolboxWebApp.kt:161-168`） | web：写一个 wasm 版 `ShellTabArt`（接口已在 `:core`），再在 `ToolboxWebApp` 传进去 |
| 33. 账号角标（`showsAccountBadge`） | ✅ | ➖（无该格） | 🔧 | A `BottomTab.kt:59` + `MainScreen.kt:270`（`ShellAccountBadge.of(账号数, 登录态)`） · 画法 `core/.../shell/ShellNavState.kt:14,22` · web `WebShell.kt:121`（`showsAccountBadge = true`）但 `ToolboxWebApp.kt:155` `accountBadge = null`（注释：本端没有账号这回事） | web：要有账号概念才谈得上角标（现在凭据在 campus-api / serve 进程） |
| 34. 宽窗侧栏（`calculateIsWideLayout` → `RAIL`） | ✅ | ✅ | ✅ | `shared/designsystem/.../ui/WindowSize.kt:50-59`（宽≥840 或 宽≥600 且 高宽比<1.2，且 `smallestScreenWidthDp()≥600`） · `AppShell.kt:64-66` · 提供点 A `AppRoot.kt:202` / D `ToolboxDesktopApp.kt:203` / W `ToolboxWebApp.kt:159` · jvm 与 wasm 的 actual 都是"窗口短边"近似：`SmallestScreenWidth.jvm.kt:9-16`、`SmallestScreenWidth.wasmJs.kt:9-16` | 无（三端同一判据；桌面/web 的"设备短边"就是窗口短边） |
| 35. 底栏四形态（`ShellForm`） | ✅（四种都可达） | 🔧（只有经典/侧栏两种） | 🔧（同） | `core/.../shell/ShellLayout.kt:9-36`（RAIL / GLASS_BAR / FLOATING_PILL / CLASSIC_BAR） · 实参 `glassStyle`：A 真值 `AppRoot.kt:203` · D `ToolboxDesktopApp.kt:209 = false` · W `ToolboxWebApp.kt:166 = false` | 同 #6：玻璃那两态的闸门就是 `glassStyle` |
| 36. 页面常驻策略（`ShellPageHost`） | ✅ `VISITED_TABS` | ➖ `CURRENT_ONLY` | ➖ `CURRENT_ONLY` | 默认值 `AppShell.kt:50`；A 未传（`MainScreen.kt:319-320`）⇒ 取默认 · D `ToolboxDesktopApp.kt:208` · W `ToolboxWebApp.kt:165`（两处都写明"路由表导航，不做 tab 常驻"） | 无（两端的导航模型是路由表，常驻没有意义） |
| 37. 系统返回（`AppShell.onBack`） | ✅ | 🔧 | 🔧 | 接线 A `MainScreen.kt:440-455`（关浮层 / 回首页 / 双击退出） · 共享实现 `shared/designsystem/.../platform/BackHandler.kt:8`（包 CMP 的 `androidx.compose.ui.backhandler`） · 注册点 `AppShell.kt:69` · D/W 的 `AppShell(...)` 都没传 `onBack`：`ToolboxDesktopApp.kt:204-211`、`ToolboxWebApp.kt:161-168` | 两端：给 `AppShell` 传一个 `onBack`（CMP 的 `BackHandler` 是 common API，接口不用动） |
| 38. 默认落点 | ✅（读 `defaultTab`，默认 `COURSES`） | 🔧（硬编码 `AppRoute.Main`） | 🔧（硬编码 `AppRoute.Schedule`） | 共享默认 `shared/model/.../settings/SettingsStore.kt:25`（`?: TAB_COURSES`） · A `AppRoot.kt:106` · D `ToolboxDesktopApp.kt:151`（`initialTarget` 默认值，`grep` 无调用方覆盖） · W `ToolboxWebApp.kt:112,236-237`（`initialWebTarget()` 无 `?route=` 时给 `Schedule`） | 两端：先读 `defaultTab` 再映射到各自 tab id（同 #7 的两套 id 问题） |
| 39. `ShellTarget.AllPages`（「全部/更多」那一页） | ➖（无入口） | ✅（第六格 + `RoutesPage`） | ➖（`toWeb` 返回 null） | `core/.../shell/ShellTarget.kt:12` · D `DesktopShell.kt:53-60,73,79` + `ToolboxDesktopApp.kt:226` · W `WebShell.kt:69`（`ShellTarget.AllPages -> null`）+ `ToolboxWebApp.kt:190`（有分支但没有任何 tab 指向它） | web：要它就加一格 tab 指向 `AllPages`（分支已存在） |

## ⑤ 主题

| 功能点 | Android | 桌面 | web | 证据(文件:行) | 补它要动什么(一句话) |
| --- | --- | --- | --- | --- | --- |
| 40. `nav_bar_style` 键的读取 | ✅（唯一读取者） | 🔧（零读取者） | 🔧（零读取者） | 键与默认值 `shared/model/.../settings/SettingsStore.kt:10-12,74-75`（默认 `floating`） · A `AppRoot.kt:203`、`MainScreen.kt:173`、别名 `app/.../data/CredentialStore.kt:251-252` · D/W 无读取者（`grep NAV_STYLE` 只命中 `shared/model` 与其测试） | 两端：读同一个键（键已共享），别再硬编码 `glassStyle` |
| 41. `glassStyle` 实参 | ✅ 真值 | 🔧 恒 false | 🔧 恒 false | A `AppRoot.kt:203`（`navStyle == NAV_STYLE_FLOATING`） · D `ToolboxDesktopApp.kt:209` · W `ToolboxWebApp.kt:166` | 同 #6 |
| 42. 玻璃顶栏 / 首页 hero 玻璃（`LocalGlassStyle`） | ✅ | 🔧（恒经典） | 🔧（恒经典） | 默认值 `shared/designsystem/.../ui/glass/GlassTopBar.kt:55`（false）+ `:58-62`（经典时 `rememberPageGlass()` 返回 null） · 唯一提供点 `AppRoot.kt:203` · 消费点 `HomeScreen.kt:223`、`app/.../nav/AppNavHost.kt:247` | 同 #6（提供点一开，全端玻璃顶栏一起生效） |
| 43. `dynamic_color` 透传进主题 | ✅ | ✅ | 🔧 | A `MainActivity.kt:83` · D `ToolboxDesktopApp.kt:169,171` · W `Main.kt:19-21`（无该参数） · 主题出口 `theme/Theme.kt:17-34` | web：`Main.kt` 多传一个参数（同 #2） |

## ⑥ 触感（Haptics）

| 功能点 | Android | 桌面 | web | 证据(文件:行) | 补它要动什么(一句话) |
| --- | --- | --- | --- | --- | --- |
| 44. 触感实现（5 档语义） | ✅ 真 `Vibrator` | ❌（桌面窗口没有振动器） | 🔧（浏览器有 `navigator.vibrate`，仅 Android Chrome 有效） | 语义与入口 `shared/designsystem/.../ui/Haptics.kt:42,51-60` · A `Haptics.android.kt:26-135`（`actual fun rememberHaptics`，`:51` 读开关） · D `Haptics.jvm.kt:5-13`（`NoopHapticsController`，注释写明"桌面窗口没有振动器"） · W `Haptics.wasmJs.kt:5-13`（`NoopWebHapticsController`） | web：写一个 wasm actual 走 `navigator.vibrate`；桌面：把开关与调用点一起显示为不可用（守住 ✅/➖ 的说法） |
| 45. 触感偏好存储 `HapticsPrefs` | ✅ | ✅ | ✅ | `Haptics.kt:24-39`（`haptics_prefs` / `enabled`，默认 true，独立于 `CredentialStore`） · `keyValueStore` 三端都有 actual（`SettingsStore` 同一条路） | 无（存储本身三端都能读；缺的是"谁去读"） |
| 46. 静音时不震（`shouldPlayHaptic`） | ✅ | ➖ | ➖ | `Haptics.kt:27-28,44-48`（读 `AudioManager.RINGER_MODE_SILENT` 字面值） · 唯一调用点 `Haptics.android.kt:51` | 无（jvm/wasm 没有"铃声模式"这一档） |
| 47. 共享屏里的触感调用点 | ✅ | ➖（调用存在但落到 Noop） | ➖（同） | `feature/score/.../ScoreReportScreen.kt:76` · `feature/game/.../Gpa2048Screen.kt:101`、`hop/HopScreen.kt:120`、`blocks/BlocksScreen.kt:223` · `feature/account/.../AccountManagerScreen.kt:100,749` · `feature/schedule/.../ScheduleScreen.kt:111,896` | 无（共享屏调的是 `rememberHaptics()`，两端接上 actual 就自动生效） |

---

## 统计（按"行×端"的格计）

- 行数：47（① 12 + ② 9 + ③ 9 + ④ 9 + ⑤ 4 + ⑥ 4）；每行 3 个端列 = 141 格
- ✅ 格：79　🔧 格：47（涉及 28 行）　❌ 格：1　➖ 格：13（含"该端无这一格"的 4 格）　❓ 格：1
- 最贵三项：#3（Monet 真取色）、#7+#38（`defaultTab` 的两套 tab id）、#16（通知与后台排程）


---

# 台账 2 · 日程 / 考勤 / 成绩 / 教学空间

> 子代理原始产物（`/tmp/ledger-2-schedule.md`），表格内容原样保留；汇总数字以上表复算为准。


### 三端能力清单 · 日程 / 考勤 / 成绩 / 教学空间

仓库：`/mnt/nas/data/works/xjtu-toolbox-fixes`（只读扫描，未改任何文件）
判定口径（每格＝一端对这一功能点的兑现，不是「代码里有没有枚举值」）：

- ✅ honored 且该端真能兑现
- 🔧 honored 或部分兑现，但功能打折（只在某个后端形态下真通 / 只读 / 缺子项 / 入口半途）
- ❌ 该端不画、不可达，或缺后端端点
- ❓ 证据不足（本轮 0 处）

三端 honored 一览（本表全部行都以此为底）：`ScheduleHost.kt:12-43`（10 项）· Android 全集 `AppScheduleHost.kt:32` · 桌面 3 项 `DesktopScheduleHost.kt:24-32` · Web 2 项 + serve 时 1 项 `WebScheduleHost.kt:33-38`。

---

## A. `ScheduleCapability` 全枚举逐项（10 行）

| 功能点 | Android | 桌面 | web | 证据 | 补它要动什么 |
|---|---|---|---|---|---|
| A1 自建日程编辑（工具栏「+」/改/删，`CUSTOM_COURSE_EDIT`） | ✅ | ✅ | ✅ | 枚举 `shared/model/…/schedule/ScheduleHost.kt:15`；门控 `feature/schedule/…/ScheduleScreen.kt:232`；`AppScheduleHost.kt:32`；`DesktopScheduleHost.kt:25,43-44`；`WebScheduleHost.kt:34,48-49`（`KeyValueCustomCourseStore`） | 无 |
| A2 ICS 导出（`SHARE_FILE`） | ✅ | ❌ | ❌ | 默认实现 false `ScheduleHost.kt:18,88`；门控 `ScheduleScreen.kt:273`；桌面未覆写，测试锁死 `desktop/src/test/…/DesktopScheduleJvmTest.kt:196`；web 不列 `WebScheduleHost.kt:33-38` + 自述 `web/…/WebFallbackScreens.kt:222` | 桌面：覆写 `shareFile` 走 AWT 保存对话框，`ScheduleExport.generateIcs` 已是纯 Kotlin（`feature/schedule/…/ScheduleExport.kt:20`），约 30 行。web：Blob + `<a download>` 锚点（「没有分享面板」≠「不能落文件」），约 40 行 + honored 加一项 |
| A3 课表考勤角标 + 详情「本次考勤」（`ATTENDANCE`） | ✅ | ❌ | ❌ | `ScheduleScreen.kt:908`、`CourseLinkSections.kt:100`；Android `AppScheduleHost.kt:62`（`CredentialStore.scheduleAttendanceBadge`）；桌面 honored 不含 `DesktopScheduleHost.kt:24-32`，`attendanceBadgeEnabled` 默认 false `ScheduleHost.kt:79`，连设置行都不画 `feature/settings/…/SettingsScreen.kt:98` + `DesktopSettings.kt:60-61`；web `WebScheduleHost.kt:33-38` 且 `courseLinks = null` `:64` | 桌面：`DataCourseLinkAccess` 已接好（`DesktopScheduleHost.kt:60-63`）⇒ 只需 honored 加 `ATTENDANCE` + `attendanceBadgeEnabled` 读 `SettingsStore` + honoredRows 加 `SCHEDULE_ATTENDANCE`。web：要一份 **wasm 版 `CourseLinkAccess`**（现实现 `shared/data/…/ScheduleDataPorts.kt:42` 绑 `java.time` + `SessionManager`）+ 角标设置项 + 用已有 serve 端点（`AttendanceRoutes.kt:289` `records`） |
| A4 详情「N座」座位数（`SEAT_COUNT`） | ✅ | ❌ | 🔧 | 门控 `ScheduleScreen.kt:1142`；Android `AppScheduleHost.kt:59`；桌面未覆写 ⇒ null（`ScheduleHost.kt:76`，测试 `DesktopScheduleJvmTest.kt:197`）；web serve ✅ `WebScheduleHost.kt:37,69`，campus-api ❌ —— 但 `CampusEmptyRoomApi.kt:135-137`（campus-api 分支）其实能从已拉到的教室行答，能力判定比实现更保守 | 桌面：一行 —— 转 `AppEmptyRoomSource(auth.sessionManager).seatCount(...)`（`AppEmptyRoomSource.kt:98` 已实现，桌面空闲教室屏已在用 `ToolboxDesktopApp.kt:518`）。web：去掉 `mode == SERVE` 那个门，先查 `cachedRooms` 再 null |
| A5 思源学堂下钻（`LMS_LINK`） | ✅ | ✅ | ❌ | 门控 `CourseLinkSections.kt:120`；桌面 `DesktopScheduleHost.kt:26,60`；web `courseLinks = null` `WebScheduleHost.kt:64` + 自述 `WebFallbackScreens.kt:223` | web：serve 已有 LMS 端点（`server/…/LmsRoutes.kt:283-288`），但 `CourseLinks.lmsCourseFor`（`shared/data/…/CourseLinks.kt:44`）要会话 + `java.time` ⇒ 把匹配器下沉 `:core` + 写 wasm 取数 |
| A6 今日栏「接下来」的作业截止（`DUE_HOMEWORK`） | ✅ | ❌ | ❌ | 门控 `ScheduleViewModel.kt:164`；Android `AppScheduleHost.kt:57` → `app/…/LmsDueStore.kt:37`（写方 `LmsDueCollector.kt:51`）；桌面未覆写 ⇒ `emptyList()` `ScheduleHost.kt:70`，测试 `DesktopScheduleJvmTest.kt:198`；web 不列 + 自述 `WebFallbackScreens.kt:224` | `LmsDueStore` 现在在 `:app`（`DataCache`/`Context`）⇒ 下沉 `:data` + `KeyValueStore` 版（桌面即得）；web 还要 **serve 新端点**（`LmsRoutes.kt` 只有 courses/activities，无 due） |
| A7 桌面小组件刷新（`WIDGET_REFRESH`） | ✅ | ❌ | ❌ | Android `AppScheduleHost.kt:71-74`（`ScheduleWidgetUpdater`）；桌面注释明说不列 `DesktopScheduleHost.kt:29-30`（设计 §6 Stage C「系统通知替代」未做，`DesktopHomeScreen` TODO #1），测试 `DesktopScheduleJvmTest.kt:193`；浏览器无小组件 | 桌面：要么做系统通知/托盘替代（中），要么永久 ❌；web：不该补 |
| A8 课表落地 → 首页信号（`HOME_SIGNALS`） | ✅ | ✅ | ❌ | Android `AppScheduleHost.kt:71-73`；桌面 `DesktopScheduleHost.kt:31,36`（`DesktopHomeSignals`）；web 不列 ⇒ `onScheduleSynced()` 空默认 `ScheduleHost.kt:85` | web：一行 honored + 把版本号接到 `WebHomeScreen`（若首页要跟着重读缓存），最便宜的一档 |
| A9 联网判定（`ONLINE_CHECK`） | ✅ | ✅ | ✅ | Android `AppScheduleHost.kt:64-69`（`ConnectivityManager`）；桌面/web 用默认 `ScheduleHost.kt:82` `= true`，KDoc 明说默认就是「真发请求，失败即失败」⇒ 不算缺口 | 无（想省流量可加 `navigator.onLine`，收益低） |
| A10 调课/停课/换教室气泡（`SCHEDULE_CHANGE_BUBBLE`） | ✅ | ❌ | ✅ | 门控 `ScheduleViewModel.kt:448`；web `WebScheduleHost.kt:36,41-43`（`InboxStore.post(OwnInbox.scheduleChange(...))`）；桌面不列 ⇒ 不冒泡 | 桌面：`InboxStore` 在 `:model`，桌面收纳屏已读它（`DesktopHome.kt:80-82`、`ToolboxDesktopApp.kt:545`）⇒ 抄 `WebScheduleHost.kt:41-43` 两行 + honored 加一项 |

## B. `ScheduleSource`（教务 / 考勤）与 serve 投影（4 行）

| 功能点 | Android | 桌面 | web | 证据 | 补它要动什么 |
|---|---|---|---|---|---|
| B1 来源可选（教务 / 考勤）+ 三端 `scheduleSourceOptions` | ✅ | 🔧 | ❌ | 枚举与标签 `ScheduleSourceRouter.kt:32-43`；Android `app/…/AppSettings.kt:134-135` ⇒ 两档；桌面 `DesktopSettings.kt:65` 空表 + honoredRows `:60-61` 无 `SCHEDULE_SOURCE`（测试锁死 `DesktopSettingsJvmTest.kt:84`）；web `WebSettingsHost.kt:52` 空表 + honoredRows `:45-46`；屏门控 `SettingsScreen.kt:96-97` | 桌面：两行（`ScheduleSource.entries` 映射 + honored 加 `SCHEDULE_SOURCE`）——路由器已就绪且桌面已把 manager 传进去了（`DesktopScheduleHost.kt:53-56`） |
| B2 选考勤来源后真能出整学期课表（本研合并） | ✅ | 🔧 | ❌ | 转换在 `ScheduleSourceRouter.kt:187-200`（`KqPortal.currentSemester` + `KqTimetableRow.toCourses`），回退规则 `:66,79-93`；桌面共用同一份（`shared/data/…/ScheduleDataPorts.kt:14-27`）但 UI 不可达；web 固定 `SettingsStore.SCHEDULE_SOURCE_JWXT`（`WebScheduleHost.kt:97`），`WebScheduleSource` 里没有 router 分支 | 桌面同 B1；web 需新写考勤取数分支（`WebScheduleSource.kt:43-58` 只有 jwxt 一条） |
| B3 serve 上有「考勤 → 课表」端点 | ❌ | ❌ | ❌ | `server/…/AttendanceRoutes.kt:287-292` 子段只有 `terms` / `records` / `leave*`（`:338-…`），没有课表投影；`JwxtRoutes.kt:65-81,300,512-513` 的 `schedule` 只有 `?merged=1` | serve 加 `GET /api/attendance/schedule`，照 `JwxtRoutes` 的先例投 `KqTimetableRow.toCourses`；这是 web 要考勤来源的前置 |
| B4 历史学期（开学日 / 周次 / 整学期回看） | ✅ | ✅ | ❌ | web `WebScheduleSource.kt:72-85`：非当前学期直接抛 `UnsupportedOperationException`（`/api/jwxt/term-start` 只答当前学期）⇒ 历史学期在浏览器里拿不到开学日，两形态都一样 | 上游/serve 侧让 `term-start` 认 `?term=`（现在会静默拿当前学期错答，才被前端拦成抛错）；不改上游就只能保持 ❌ 并把屏上话说明白 |

## C. 日程附加（考试 / 教材 / 调停补课；「N座」「自建日程」「思源」「ICS」「今日栏」「小组件」「气泡」见 A 节，不重复计行）（3 行）

| 功能点 | Android | 桌面 | web | 证据 | 补它要动什么 |
|---|---|---|---|---|---|
| C1 考试栏（倒计时条 + 考试列表） | ✅ | ✅ | 🔧 | `ScheduleScreen.kt:165-166,573-590`（`ExamCountdown` / `ExamListSheet`）；取数 `ScheduleViewModel.kt:406-415,479-481,590-604`；web serve ✅ `WebScheduleSource.kt:69-70` → `jwxt.examSchedule`，campus-api 返回空表（静默空）但屏上有说明 `WebFallbackScreens.kt:217-219` | web campus-api：campus-api 没有这条端点 ⇒ 要么补端点，要么在 `WebScheduleGapNote` 之外再给「这两栏空着」的原地提示，别只靠横幅 |
| C2 教材栏（学期栏课程-书名/ISBN/出版社） | ✅ | ✅ | 🔧 | 同上门控；web `WebScheduleSource.kt:61`（`textbooksWithoutStudentId = serve`）、`:93-94`（非 serve 空表）；serve 端点 `JwxtRoutes.kt` 子段 `textbooks` | 同 C1（同一处形态差异，一次修两栏） |
| C3 调停补课合并进格子（`/api/jwxt/schedule?merged=1`） | ✅ | ✅ | 🔧 | 合并逻辑 `shared/data/…/JwxtScheduleApi.kt:116-138`（`JwxtChanges.apply`）；serve `JwxtRoutes.kt:65-81,540-541`（非 `1` 就 400）；web serve ✅ `WebScheduleSource.kt:45-47`，campus-api `:48-57` 只拿排课原样 + `changes = emptyList()`，自述 `WebFallbackScreens.kt:219` | web campus-api：没有合并端点 ⇒ 要么让 campus-api 认 `merged`，要么屏上明写「调课/停课不体现在格子」 |

## D. 考勤族（6 行）

| 功能点 | Android | 桌面 | web | 证据 | 补它要动什么 |
|---|---|---|---|---|---|
| D1 课堂考勤记录（`records` 那一栏） | ✅ | ✅ | ✅ | `AttendanceScreen.kt:234`（tab0 无能力门控）；`CampusAttendanceApi.kt:82`（`getWaterRecords` requireServe）；serve `AttendanceRoutes.kt:289` | 无 |
| D2 打卡原始流水（`streams` 那一栏） | ✅ | ✅ | ❌ | 门控 `AttendanceScreen.kt:235`；`CampusAttendanceApi.kt:41-45`（`streams = false`）；自述 `ToolboxWebApp.kt:446` | web：serve 加打卡流水端点（上游 `getStreams` 在 `:data` 已有）+ 打开 `streams` |
| D3 考勤统计（按课程统计那一栏） | ✅ | ✅ | ❌ | 门控 `AttendanceScreen.kt:236`；`CampusAttendanceApi.kt:41-45`（`stats = false`）、`:131`（`computeCourseStatsFromRecords` 返回空）；自述 `ToolboxWebApp.kt:445` | web：统计可由 `records` 本地算 ⇒ 比 D2 便宜（只差一个 `:core` 侧口径下沉），端点可省 |
| D4 请假主链（学期窗口 / 审批人预览 / 列表 / 提交 / 撤回 / 销假） | ✅ | ✅ | ✅ | 门控 `AttendanceScreen.kt:151,239`；web 六条端点 `CampusAttendanceApi.kt:141,154,179,212,240,251`；serve `AttendanceRoutes.kt` 的 `leave/…`；缺口自述 `ToolboxWebApp.kt:448-450` | 无。附注：**「作为审批人去批准/驳回」三端都没有**（`LeaveApi.kt:35-47` 只有 `nextApprovers`，全仓无 approve 动作），属产品未做，不是端间差异 |
| D5 请假附件上传 | ✅ | 🔧 | ❌ | 槽位 `AttendanceScreen.kt:106`（默认空 = 不发附件）；Android `AppNavHost.kt:177`（`rememberPickEvidenceFiles`）；桌面未传（`ToolboxDesktopApp.kt:615-619`）但直连 `AppAttendanceSource` 有 `uploadEvidence`；web：`CampusAttendanceApi.kt:206` 无端点 + **serve 侧也没有上传端点**（`AttendanceRoutes.kt:164-165`「附件这一格固定空表」） | 桌面：接一个文件选择器（JFileChooser 读字节）即可，端点已有；web：先给 serve 加 evidence 上传端点，再接 `<input type=file>` |
| D6 快速考勤流水屏（`AppRoute.Iclassface`） | ✅ | ✅ | 🔧 | Android `AppNavHost.kt:434`；桌面 `ToolboxDesktopApp.kt:648`；web `ToolboxWebApp.kt:606` 仅 serve（campus-api 整屏 `WebServeOnlyScreen`），且考勤屏内那个跳转入口不画（`ToolboxWebApp.kt:456` `onOpenIclassface = null`） | web：补 serve 形态下考勤屏内的入口（一行）；campus-api 形态要新端点 |

## E. 成绩族（5 行）

| 功能点 | Android | 桌面 | web | 证据 | 补它要动什么 |
|---|---|---|---|---|---|
| E1 成绩报表屏（GPA 概览 / 学期 GPA / 绩点） | ✅ | ✅ | ✅ | 屏与模型 `feature/score/…/ScoreReportScreen.kt:89-90,197-205,247-271`；Android `AppNavHost.kt:275`；桌面 `ToolboxDesktopApp.kt:452`；web `ToolboxWebApp.kt:322-326`（`CampusGradesApi` 两形态；上游字段对齐见其 KDoc） | 无 |
| E2 分项成绩（点开一门课） | ✅ | ✅ | 🔧 | `JwappScoreScreen.kt:107-110` + `JwappScoreSource.kt:9`（`canFetchScoreDetail`）；web serve ✅ `CampusJwappScoreApi.kt:32`（`mode == SERVE`），campus-api ❌ 整屏 serve-only `ToolboxWebApp.kt:571`；serve 端点 `JwappRoutes.kt:47-63,123-140` | web campus-api：无端点 ⇒ 只能保持；serve 形态已真通 |
| E3 未评教名单（成绩屏「未评教」标记那一路） | ✅ | ✅ | ❌ | 三路并行 `JwappScoreViewModel.kt:119`、失败容忍 `:176`、标记 `JwappScoreScreen.kt:732`；web serve 传 `studentId = ""`（`ToolboxWebApp.kt:590`）⇒ 那一路不调，自述 `:579-586`；campus-api 无可查 | web：要学号（红线）⇒ 需服务端从会话里取学号再答，否则永久 ❌ |
| E4 评教问卷屏（未评 / 已评两栏 + 详情） | ✅ | ✅ | 🔧 | Android `AppNavHost.kt:199`（可提交）；桌面 `ToolboxDesktopApp.kt:473-498`（研究生走 `GraduateJudgeSource`，本科生走教务）；web `ToolboxWebApp.kt:371` + `CampusJudgeApi.kt:34,61` 两形态，但 `canSubmit = false` `:40`、`judge()` 直接 error `:58` ⇒ **只读** | web：提交链没端点（写路径），保持只读即可；要能评只能在 App/桌面 |
| E5 电子成绩单 / 学籍档案（`dzpz`） | ✅ | ✅ | ❌ | Android `AppNavHost.kt:288`（`TranscriptScreen` + 本地落盘）；桌面 `ToolboxDesktopApp.kt:597`；web campus-api 整屏 serve-only `ToolboxWebApp.kt:411-416`，serve 只画「选类型」一步、六步链刻意不投影 `:419-424`；六步清单 `feature/score/…/TranscriptScreen.kt:229` | web：要出 PDF 得投影后面六步（含写路径与学号）⇒ 保持 ❌ 更诚实；至少让 serve 形态别看起来「能用」 |

## F. 教学空间（4 行）

| 功能点 | Android | 桌面 | web | 证据 | 补它要动什么 |
|---|---|---|---|---|---|
| F1 空闲教室（含「数据源」档位 LIVE / CDN / DIRECT） | ✅ | ✅ | 🔧 | 屏 `feature/campus/…/EmptyRoomScreen.kt`；Android `AppNavHost.kt:155`（`AppEmptyRoomSource` + 缓存）；桌面 `ToolboxDesktopApp.kt:516-524`（同源、`store=null`）；web `ToolboxWebApp.kt:383-392`（`CampusEmptyRoomApi`）；档位 `CampusEmptyRoomApi.kt:37-38`：serve 才有 LIVE/DIRECT，campus-api **只有 CDN** | web campus-api：要实时/直查档得加端点；不然屏上就只该有 CDN 一档（现在是这么做的） |
| F2 全校课表检索（含院系 / 选修筛选） | ✅ | ✅ | 🔧 | 屏 `feature/schedule/…/SchoolCourseScreen.kt`；Android `AppNavHost.kt:396-406`；桌面 `ToolboxDesktopApp.kt:434-438`；web `ToolboxWebApp.kt:367-370`；筛选只在 serve `CampusSchoolCourseApi.kt:41,45`（`isFilterSupported`），检索两形态都通 `:47-71` | web campus-api：缺院系/选修筛选 ⇒ 补端点或让筛选行不画（至少别点了没反应） |
| F3 教师检索 | ✅ | ✅ | 🔧 | 屏 `feature/campus/…/faculty/FacultyScreen.kt`（联系方式段 `:533-539`）；Android `AppNavHost.kt:516-523`；桌面 `ToolboxDesktopApp.kt:404-408`；web `ToolboxWebApp.kt:362-366`；联系方式只在 serve 解析出（`CampusFacultyApi.kt:127-138` `contactsAvailable`），campus-api 那一半空；教师主页：App 站内抓取，web 一律外开新标签（`CampusFacultyApi.kt:61-62` `HomepageResult.External`） | web campus-api：联系方式缺端点；主页外开可接受（浏览器本就该外开） |
| F4 教材查询（教学空间视角） | ✅ | ✅ | 🔧 | 与 C2 是**同一处实现**（日程学期栏，无独立屏；`AppRoute` 里没有 Textbook 路由，`shared/navigation/…/AppRoute.kt`） | 同 C2 |

---

## 汇总

- 总行数：**32**（96 格）
- ✅ **59** 格 · 🔧 **14** 格 · ❌ **23** 格 · ❓ **0** 格

### 最贵的 3 项

1. **web 考勤三栏（打卡流水 / 统计 / 上课教师名）+ serve 的「考勤 → 课表」端点**（D2/D3/B3）
   一条线要动 serve 契约、上游 `:data` 取数、以及 §5.2 的姓名红线（上课教师名被现存缺口注明文列为「红线」）。补完后 web 才可能支持 `ScheduleSource.ATTENDANCE`——否则 B1/B2 的 web 列永远是 ❌。
2. **web 版 `CourseLinkAccess`（A3 角标 + A5 思源 + 详情「本次考勤」一次拿三样）**
   现实现 `ScheduleDataPorts.kt:42` 绑 `java.time` + `SessionManager`，wasm 端用不了；要把 `lmsCourseFor` / 考勤 index 的**匹配器下沉 `:core`**，再各自写 wasm 取数（serve 的 LMS / 考勤端点已有）。一次动三格，收益最高、体量也最大。
3. **`DUE_HOMEWORK` 跨端下沉（A6 桌面 + web）**
   `LmsDueStore` 现在长在 `:app`（`DataCache`/`Context`）⇒ 迁移到 `:data` + `KeyValueStore` 版才能给桌面；web 还要 serve 新开一条 due 端点（`LmsRoutes.kt` 目前只有 courses/activities）。

### 反方向的「最便宜 5 格」（都是一两行，建议先做）

1. 桌面 `SEAT_COUNT`：`AppEmptyRoomSource(...).seatCount(...)` 已经在用（`ToolboxDesktopApp.kt:518`），转一下即可（A4 桌面）。
2. 桌面 `SCHEDULE_CHANGE_BUBBLE`：抄 `WebScheduleHost.kt:41-43` 两行（A10 桌面）。
3. 桌面 `SCHEDULE_SOURCE`：`ScheduleSource.entries` 映射 + honoredRows 加一项，路由器早就就绪（B1/B2 桌面）。
4. web `HOME_SIGNALS`：honored 加一项 + 信号转给 `WebHomeScreen`（A8 web）。
5. web `SEAT_COUNT`：去掉 `mode == SERVE` 门，走 `CampusEmptyRoomApi` 的 `cachedRooms` 兜底（A4 web）。


---

# 台账 3 · 屁岱 / 通知与提醒 / 更新 / 账号 / 小组件

> 子代理原始产物（`/tmp/ledger-3-agent-notice-update-account.md`），表格内容原样保留；汇总数字以上表复算为准。


### 三端能力台账 · 屁岱 / 通知与提醒 / 更新 / 账号 / 小组件

- 扫描对象：`/mnt/nas/data/works/xjtu-toolbox-fixes`，分支 HEAD `c02011d1`，工作区干净（`git status --short` 空）。
- 扫描方式：**只读**（未改仓库、未跑 gradle、未联网）。所有证据均为「文件:行」。
- 三端定义：Android `:app` / 桌面 `desktop`（JVM，Compose Desktop）/ Web `:web`（wasmJs）+ `:server`（serve，浏览器与桌面 serve 形态共用）。
- 判定档：
  - ✅ 该端真的做完了（代码在，有消费方）
  - 🔧 部分 / 降级 / 只读 / 只剩骨架 / 只在计划里写了做法
  - ❌ 该端没有（代码里搜不到，或有显式 `null`）
  - ❓ 未核实或契约与实做不一致、无法只读判定

---

## 1. `AgentCapability` 全枚举 × 三端 honored（逐枚举值核）

枚举定义：`feature/agent/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentCapability.kt:5`（5 值，`:7/:10/:13/:16/:19`）。
honored 的默认值是 `emptySet()`：`feature/agent/.../AgentHost.kt:21`。

| # | 枚举值（工具名） | Android `:app` | 桌面 `desktop` | Web `:web` | serve `:server` |
|---|---|---|---|---|---|
| 1 | `ALARM`（`set_alarm`） | ✅ honored 全量 `AndroidAgentHost.kt:47`；真实现 `AlarmClock.ACTION_SET_ALARM` `AndroidAgentHost.kt:94`；工具门 `AgentToolCatalog.kt:88` | ❌ `honored = emptySet()` `DesktopAgentHost.kt:51` ⇒ 走 `AgentHost.kt:46` 默认 `notFound`；工具表也裁掉 `DesktopAgentTools.kt:44-56` | ❌ `WebAgentHost` 不 override `honored`（`WebAgentScreen.kt:112-190`）⇒ `AgentHost.kt:21` 的 `emptySet` | ❌ `honored = AGENT_HONORED` `AgentRoutes.kt:46`，而 `AGENT_HONORED = emptyList()` `AgentRoutes.kt:426` |
| 2 | `APP_SETTINGS`（`app_setting`） | ✅ `AndroidAgentHost.kt:47`；`readAppSettings` `:134` / `writeAppSetting` `:152`（白名单 `:131`）；工具门 `AgentToolCatalog.kt:172` | ❌ 同 #1；`AgentHost.kt:50`/`:54` 默认 `notFound` | ❌ 同 #1；且 Key 与设置都在 serve 那台机器上，浏览器只画只读徽标 `WebAgentScreen.kt:173-183` | ❌ 工具表自己列 9 件、不含它 `ServeAgentTools.kt:65-76` |
| 3 | `SCHEDULE_EVENT_WRITE`（`add_schedule_event`） | ✅ `AndroidAgentHost.kt:47`；工具门 `AgentToolCatalog.kt:99`；实现 Room `custom_courses` `AgentTool.kt:424`/`:1820-1887` | ❌ `DesktopAgentHost.kt:51` + 白名单不含 `DesktopAgentTools.kt:44-56` | ❌ | ❌ |
| 4 | `IMAGE_INPUT`（`tool = null`） | ✅ 槽位在：`imagePicker = rememberAndroidAgentImagePicker()` `MainScreen.kt:490`，适配器 `AndroidAgentHost.kt:306-330`（`PickMultipleVisualMedia`） | ❌ `DesktopAgentScreen` 不传 `imagePicker`（`ToolboxDesktopApp.kt:843-850`）⇒ 默认 `AgentImagePicker.None` `AgentScreen.kt:55` | ❌ `WebAgentScreen.kt:95-110` 同样不传 | ❌ 没有相册这一档 |
| 5 | `FILE_SAVE`（`tool = null`） | ✅ 槽位在：`saveZyxfFile` `AndroidAgentHost.kt:296`；`save_file` 工具 `AgentTool.kt:441`/`:1376`/`:1387` | ❌ `AgentHost.kt:96` 默认 `null`；`save_file` 被白名单裁掉 | ❌ | ❌ |

**这一节最要紧的一条（不是判定，是判据本身有问题）**：`IMAGE_INPUT` 与 `FILE_SAVE` 的 `tool` 是 `null`，于是

- `AgentCapability.ofTool()`（`AgentCapability.kt:25`）对它们永不命中，工具执行门 `AgentCapability.ofTool(name)?.let { it in host.honored } ?: true`（`AgentTool.kt:353`）对 `save_file` 一律放行；
- 全仓**非测试代码**里 `IMAGE_INPUT` / `FILE_SAVE` 一个字都没出现过（只有 `AgentCapability.kt:16,19` 的定义与 `AgentToolCatalogTest.kt:38` 的断言）；
- 结论：这两档是「**声明档**」，`honored` 里写不写都不改变行为——真能力由槽位（`imagePicker` / `saveZyxfFile`）和工具的 whitelist 裁剪（`DESKTOP_AGENT_TOOLS` / `ServeAgentTools.NAMES`）决定。台账里给 Android 打 ✅ 是按「槽位在」判的，不是按 honored 判的。

---

## 2. 屁岱工具白名单：serve 九件 vs Android 全量（差集）

`agentToolDefinitions(honored)` 的目录本体（`:core` 唯一一份顺序定义）：`feature/agent/.../AgentToolCatalog.kt:5-188`，**核心 26 件**，其中 3 件按 honored 现生成（`set_alarm` `:88`、`add_schedule_event` `:99`、`app_setting` `:172`）。

| 端 | 核心件数 | 定义处 / 真源 | 额外并进来的工具（`CompositeAgentTools.extras`） | 相对 Android 全量 |
|---|---|---|---|---|
| Android `:app` | 26 | `AgentTool.kt:349` `agentToolDefinitions(host.honored)`，honored = 全量 `AndroidAgentHost.kt:47` | `SelfTodoAgentTools`、`CampusMapAgentTools(locate=…)`、`PidaiTaskAgentTools`、`WeChatSearchTools`、`DeepSeekWebTools`（配 Key 时）：`AndroidAgentHost.kt:68-74` | 基准（含 `set_alarm`/`add_schedule_event`/`app_setting`/`save_file`） |
| serve `:server` | 9 | `ServeAgentTools.kt:65-76`（`NAMES`），下发名从真定义读：`ServeAgent.kt:114-119`；端点投影见 `docs/api-contract.md` §5.11「S-d 落地实况」 | `CampusMapAgentTools()`（无 locate）、`DeepSeekWebTools`（配 Key 时）：`ServeAgent.kt:508-524` | **少 17 件** |
| 桌面 `desktop` | 9 | `DesktopAgentTools.kt:44-56`（`DESKTOP_AGENT_TOOLS`，与 serve `NAMES` **同档同顺序**），按它裁目录：`:59-63` | `CampusMapAgentTools()`（无 locate）、`DeepSeekWebTools`（配 Key 时）：`DesktopAgentHost.kt:80-95` | **少 17 件**（与 serve 同档；B-8 补上的四件：`get_calendar` 读 `SchoolCalendarApi`、`get_schedule`/`get_exam_schedule` 读本机 `ScheduleCache`（桌面不直连教务，缓存空时直说“先去日程页刷一次”）、`get_empty_rooms` 读 `EmptyRoomApi` 的 CDN 逐节空闲表、无智慧教室实时状态） |
| Web `:web` | 0（不下发） | 浏览器不自带工具层：`WebAgentHost` 无 `tools()` override（`WebAgentScreen.kt:112-190`），工具在 serve 上跑 | 同 serve | 同 serve（**少 17 件**） |

**差集明细**

- serve ⊂ Android（少 17）：`add_schedule_event`、`app_setting`、`get_attendance`、`get_card_info`、`get_coupons`、`get_fitness_score`、`get_grades`、`get_library`、`get_lms`、`get_lms_activity`、`get_notifications`、`get_textbooks`、`list_zyxf`、`read_zyxf_file`、`save_file`、`search_school_courses`、`set_alarm`。
- 桌面 ⊂ serve：**已收平**（B-8 补上 `get_calendar`/`get_schedule`/`get_exam_schedule`/`get_empty_rooms`，两边的口径差异——serve 现拉、桌面读缓存——写在 `DesktopAgentHost.gapNote` 里）。
- 三端都并进来的「外挂」：`campus_map`（全部）、`my_location`（**只有 Android**，因为只有它给了 `locate`：`MainScreen.kt` 侧的 `AgentLocation.current`，`CampusMapAgentTools.kt:62-75`）、`web_search`/`web_fetch`（三端都只在配了 `webSearchKey` 时并 `DeepSeekWebTools`）、`add_todo`/`get_todos`/`complete_todo`（`SelfTodoAgentTools.NAMES` `SelfTodoAgentTools.kt:25`，仅 Android）、`schedule_task`/`list_tasks`/`cancel_task`（`PidaiTasks.kt:87-98`，仅 Android）、`wechat_search`（`WeChatSearchTools.kt:23`，仅 Android）。
- 合计真下发：Android 35（配 Key 37）/ serve 10（配 Key 12）/ 桌面 10（配 Key 12）/ Web = serve。

---

## 3. `AgentHost` 各槽位（账号变化 / 形象角色皮肤 / 主动任务）

| 槽位 | Android `:app` | 桌面 `desktop` | Web `:web` | 备注 |
|---|---|---|---|---|
| `onAccountChanged()` | ❌ | ❌ | ❌ | **这个槽位在代码里根本不存在**：全仓只有 docs 提到（`docs/desktop-port-plan.md:1105`、`docs/storage-layout.md:423`/`:494`、`docs/audit-verify-1-correctness.md:184`）。等价物是 `:core` 自己那套：`AgentScreen.kt:96-99` `LaunchedEffect(host.accountId)` + `AgentViewModel.bind()` 的账号后缀守卫（`AgentViewModel.kt:171-183`，注释明写「换了账号要重来」）⇒ 三端共用同一条，不算缺 |
| 形象 / 角色 / 皮肤（`hero`、`pokeWhisper`、`appearanceSection`） | ✅ `MainScreen.kt:493-495` 传 `AndroidPidaiHero` / `AndroidPokeWhisper` / `PidaiAppearancePanel`；存储 `PidaiAppearance.kt`（prefs `pidai_appearance`，见 `ArchiveRegistry.kt:54`） | ❌ 三槽全用默认：`AgentScreen.kt:73-79`（`agentWelcomePlainHero` / `{}` / `null`），`ToolboxDesktopApp.kt:843-850` 一个都没传 | ❌ 同桌面：`WebAgentScreen.kt:95-110` 只传了 `apiKeySection` / `memorySection` | web v1 无形象的记载：`docs/agent-web-spec.md:187`/`:371`/`:394` |
| 主动任务（早晚简报 / 预告 / 定时任务 / 主动气泡） | ✅ `PidaiJobs.kt:31-40`（`PidaiJobWorker` + `PeriodicWorkRequestBuilder`）、`ProactiveBubble.kt`（`pidai_proactive` / `level` 三档，`:132-133`）、`PidaiTasks.kt` 的 `schedule_task` 工具 | ❌ 无 Worker、无对应物（`desktop/` 下 grep `WorkManager`/`Proactive`/`PidaiJobs` 零命中） | ❌ 同桌面 | 「主动提醒」设置项只在 Android 的设置面板里 |

---

## 4. 通知与提醒整组

`SettingsHost` 侧接口：`feature/settings/.../SettingsHost.kt:63`（`noticeSection`）。

| 项 | Android `:app` | 桌面 `desktop` | Web `:web` | 证据 / 备注 |
|---|---|---|---|---|
| `SettingsHost.noticeSection` | ✅ `AppSettings.kt:137` = `NoticeAndReminderSection(context, scope)`（本体 `:152-330`） | ❌ 显式 `null` `DesktopSettings.kt:67`；测试钉死「桌面没有系统通知权限与 WorkManager 排程 ⇒ 整组不画」`DesktopSettingsJvmTest.kt:77` | ❌ 显式 `null` `WebSettingsHost.kt:55` | — |
| `notice_watch`（新通知提醒 + 来源多选 + 关键词 + 已读基线） | ✅ `NoticeWatchStore.kt`、`NoticeWatchSync.kt`、`NoticeWatchWorker.kt:28`/`:62`（周期）、UI `AppSettings.kt:246-275` + `NoticeSourceSheet` `:531+`；权限申请 `:227-244` | ❌ 无 | ❌ 无 | 桌面/Web 连「来源多选」的表都没有 |
| `reminder_watch`（图书馆 / 课表 / LMS 三类） | ✅ `ReminderStore.kt:29`（键 `schedule`/`lms`/`library`）、`ReminderNotifier.kt`、UI `AppSettings.kt:281-311`（`applyReminder` 先要通知权限 `:208-235`，还有「提醒不准时？去关省电限制」`:312-330`） | ❌ 无 | ❌ 无 | — |
| `session_keepalive`（登录态保活） | 🔧 只有「前台每 10 分钟预热一次」：`SessionKeepAlive.kt:19-24`（`INTERVAL_MS = 10 * 60_000L`），启停挂 `MainActivity.kt:97`/`:102`；**没有设置项、没有任何落盘** | ❌ 无 | ❌ 无 | ⚠️ 文档与实做不一致：`docs/storage-layout.md:214` 写它在「`SessionKeepAlive.kt:68,75,76`、键 `enabled` `interval_min`」，但那个文件**总共只有 55 行**，`:68` 越界，`interval_min` 全仓只出现在这份文档里 ⇒ 「档案里登记了一个空壳」。档案侧登记在 `ArchiveRegistry.kt:58` |
| 后台排程（`NoticeWatchWorker` / `ScheduleWatchWorker` / `LmsDeadlineWorker` / `LibraryReminderWorker` / `PidaiJobWorker` / `SelfTodoReminderWorker` / `WidgetRefreshReceiver.Delayed`） | ✅ 全在 `:app`：`notification/` 整包 + `agent/PidaiJobs.kt` + `inbox/SelfTodoReminder.kt:68` + `widget/WidgetRefreshReceiver.kt:39`，全部 `CoroutineWorker`/`PeriodicWorkRequestBuilder` | ❌ 无 WorkManager、无任何定时器对应物 | ❌ 明说不给：`WebPwa.kt:41`「后台定时（periodicSync）同理……基本不给」；`WebPwa.kt:21` 只探 API 表面 | 桌面「对应物 = serve 进程」只在计划里：`docs/desktop-port-plan.md:35`（D6）；`docs/desktop-port-plan.md:123` 同段 |
| **桌面「小组件用通知替代」是否做了** | — | ❌ **没做**：`desktop/` 下 grep `SystemTray` / `TrayIcon` / `DesktopNotification` / `showNotification` **零命中**；唯一带 `Notification` 的是 `NotificationScreen`（共享的「通知公告」页面，`ToolboxDesktopApp.kt:130`/`:581`），与系统通知无关 | — | 计划侧要求写在 `docs/desktop-port-plan.md:123`（「小组件无桌面等价物 ⇒ 用系统通知替代」）与 `:153`/`:437` |
| （附）Web PWA 通知 | — | — | 🔧 有权限 + 测试通知 + `notificationclick`：`WebPwa.kt:20`/`:65-72`、`web/src/wasmJsMain/resources/sw.js:65`；但**没接进**「新通知 / 提醒」业务（业务侧 `noticeSection` 仍是 null） | — |

---

## 5. 更新

`SettingsHost` 侧接口：`SettingsHost.kt:57`（`changelog`）、`:69`（`updateSection`）。

| 项 | Android `:app` | 桌面 `desktop` | Web `:web` | 证据 / 备注 |
|---|---|---|---|---|
| `updateSection`（更新组） | ✅ `AppSettings.kt:141` = `UpdateSection(...)`，本体 `:442`（渠道下拉 `:446`、预览开关 `:453`、检查更新 `:487-508`、`AutoUpdateDialog` `:546-554`） | ❌ 显式 `null` `DesktopSettings.kt:71`；测试钉死「桌面是 .deb/tar.gz，没有下载并安装 APK 那条路 ⇒ 整组不画」`DesktopSettingsJvmTest.kt:82` | ❌ 显式 `null` `WebSettingsHost.kt:61`（理由：更新 = 服务端换一份 dist） | — |
| changelog | ✅ 唯一数据源 `AppChangelog.kt:36`（`ENTRIES`，629 行 / 约 67 个版本条目，`:629`/`:641` 取用），三处展示：设置页 `AppSettings.kt:126`、更新弹窗 `UpdateDialogs.kt:75-103`、启动 What's New `LaunchNotices.kt:254` | ❌ `emptyList()` `DesktopSettings.kt:63` | ❌ `emptyList()` `WebSettingsHost.kt:49` | 桌面/Web 的「关于」里连历史都没有 |
| 更新渠道（`update_channel` / `receive_preview_updates`） | ✅ 两个键都在 `:app`：`CredentialStore.kt`；预览版全量推送、不做灰度分桶；预览版选择 `AppUpdater.kt`；渠道 UI `AppSettings.kt:446`；屁岱可读写 `AndroidAgentHost.kt:131`/`:147-148`/`:220-232` | ❌ 无渠道/灰度概念；`DesktopUpdate.kt` 只读 GitHub releases 的 `browser_download_url`，`canInstallAutomatically=false`，且**全仓没有任何生产调用方**（只有 `DesktopUpdateJvmTest.kt` 引用）⇒ 现在是死骨架 | ❌ 无 | 渠道文档：`docs/preview-channel.md`；这两个键的归属：`docs/storage-layout.md` 记成 `:app` 键 |
| 安装包 SHA-256 校验（PR#145） | ✅ `AppUpdater.download()` 在 `sha256` 存在时校验，缺失时跳过 | ❌ 没做（连下载都没有） | ❌ 没做 | 「PR#145」在本仓库**没有对应提交**（`git log --all --grep=145` 只匹配到 147/CI 字体等无关提交）；⚠️ 灰度分桶已删除；`MessageDigest` 现在用于 `AppUpdater.download()` 的摘要校验与 GitHub digest 读取 |
| （附）版本号那一行 | ✅ `AppSettings.kt:120`（`BuildConfig.VERSION_NAME`） | ✅ `DesktopSettings.kt:56`「v… (desktop)」 | 🔧 `WebSettingsHost.kt:43`「Web 构建（wasm）」（本端没有版本概念） | 三端都有 | 

---

## 6. 账号（账号表 + 切换 / 新增 / 删除 / 改密）

契约：`docs/api-contract.md` §5.10（`api-contract.md:1321-1373`），只有 `GET /api/accounts`（`readOnly:true`），并明写「切换/新增/删除/改密四个动作一个端点都没有」。

| 项 | Android `:app` | 桌面 `desktop` | Web `:web` | serve `:server` |
|---|---|---|---|---|
| 账号表（列出 / 当前账号） | ✅ `AccountManager.kt:64` `accountList()` | ✅ `DesktopAccounts.kt:29` | ✅ 只读 `GET /api/accounts`：`WebAccountsHost.kt:46-70` | ✅ `AccountRoutes.kt:13-19`（`get` 一条），`readOnly = true` 写死 `:59`，学号打码 `:71-86` |
| 切换 | ✅ `AccountManager.kt:71` | ✅ `DesktopAccounts.kt:31` | ❌ `WebAccountsHost.kt:79` `notWritable()` | ❌ 无端点 |
| 新增 | ✅ `AccountManager.kt:115` | ✅ `DesktopAccounts.kt:42` | ❌ `WebAccountsHost.kt:81-85`（`Result.failure`） | ❌ 无端点 |
| 删除 | ✅ `AccountManager.kt:238`（含清缓存/`deleteCache`） | ✅ `DesktopAccounts.kt:96`（含 `wipeDesktopCaches` `:135`） | ❌ `WebAccountsHost.kt:89` | ❌ 无端点 |
| 改密 | ✅ `AccountManager.kt:188` | ✅ `DesktopAccounts.kt:89` | ❌ `WebAccountsHost.kt:87` | ❌ 无端点 |
| 契约 §5.10 写侧 | — | — | ❌（页顶如实写「这一端只读」`WebAccountsHost.kt:92-106`） | ❌ 四个动作一个端点都没有（`AccountRoutes.kt` 全文只有一个 `get`，93 行） |

---

## 7. 桌面小组件的等价物

Android 的 AppWidget：`app/src/main/java/com/xjtu/toolbox/widget/`（`ScheduleWidgetProvider.kt`、`ScheduleWidgetRemoteViewsService.kt`、`CampusCardWidgetProvider.kt`、`NoticeWidgetProvider.kt`、`TodoWidgetProvider.kt`、`WidgetRefreshReceiver.kt`），清单注册 `AndroidManifest.xml:76-129`（调度 2×2 + 4×2 两个尺寸、校园卡、通知、待办共 5 个 provider）+ `:131`/`:142`（刷新广播、RemoteViewsService）；布局参数 `res/xml/{schedule_widget_2x2,schedule_widget_4x2,campus_card,notice,todo}_widget_info.xml`。

| 小组件 | Android `:app` | 桌面 `desktop` | Web `:web` |
|---|---|---|---|
| `ScheduleWidget`（2×2 / 4×2） | ✅ `ScheduleWidgetProvider.kt` + `AndroidManifest.xml:76-96`，刷新动作用户 `WidgetRefreshReceiver` / `PidaiJobs` | ❌ 无任何对应物（`desktop/` 无 `widget` 包、无托盘/贴桌面窗口） | ❌ 无 |
| `NoticeWidget` | ✅ `NoticeWidgetProvider.kt` + `NoticeWidgetStore.kt`（缓存 `notice_widget_cache`，`ArchiveRegistry.kt:59`）+ `AndroidManifest.xml:109-118` | ❌ | ❌ |
| `CampusCardWidget` | ✅ `CampusCardWidgetProvider.kt` + `AndroidManifest.xml:98-107` | ❌ | ❌ |
| `TodoWidget` | ✅ `TodoWidgetProvider.kt` + `AndroidManifest.xml:120-129` | ❌ | ❌ |
| 桌面等价物（计划：系统通知替代 / 贴桌面小窗） | — | ❌ 未做（见 §4 末行：桌面无任何系统通知/托盘代码；计划 `docs/desktop-port-plan.md:123`） | ❌ 不做（明写：`WebPwa.kt:41`、`docs/desktop-port-plan.md:1293`「小组件那一档不做」） |

---

## 统计

| 指标 | 数值 |
|---|---|
| 表格数据行（不含表头/分隔行） | **40**（其中判定行 35，`统计` 表自身 5 行） |
| ✅ 格 | **33** |
| 🔧 格 | **4** |
| ❌ 格 | **63** |
| ❓ 格 | **0** |

统计口径：对本文件里所有表格的数据行（跳过表头行与 `|---|` 分隔行）逐个符号计数；`判定档` 说明段与本节表头不计入。

## 最贵的 3 项

1. **账号写侧整条缺（serve 只有只读端点）** —— `AccountRoutes.kt:13-19`/`:59`。四端里 Android 与桌面各自维护了一套完整 CRUD，浏览器却一个写动作都没有：`WebAccountsHost.kt:79-89` 四个方法全是 `notWritable()`。这不是补 UI，是要在 serve 上真加 4 条写端点（切换要搬 live session、新增要搬密码、删除要清缓存、改密要重登），契约 §5.10 已经把「这一批没有写端点」写成事实了。
2. **桌面更新整条链只有死骨架** —— `DesktopSettings.kt:63`/`:71` 两处 `emptyList()`/`null`，而 `DesktopUpdate.kt` 有 135 行的可用骨架（挑发布、比版本、pre-release 识别，测试 `DesktopUpdateJvmTest.kt` 全绿）却**没有任何生产调用方**；渠道/预览两个键是 `:app` 独有（`CredentialStore.kt:238-240`）。桌面用户拿不到「有新版本」这件事，而仓库里现成的零件足够接线。
3. **通知与提醒整组在桌面/Web 是空的，且「用通知替代」这条计划没落地** —— 桌面 `noticeSection`/`updateSection` 全 null、`desktop/` 下没有任何系统通知/托盘代码（grep 零命中），Web 同样 null 且后台定时被浏览器限死（`WebPwa.kt:41` 如实写）。桌面要真做，前置是 D6 那条「后台能力只放在 serve 模式」（`docs/desktop-port-plan.md:35`）——这不是一个页面的活，是一个常驻进程 + 通知载体 + 权限的活。

### 顺手可修的小账（成本极低，先记着）

- `session_keepalive` 的档案条目是空壳：`ArchiveRegistry.kt:58` 登记 + `docs/storage-layout.md:214` 声称两个键，实际 `SessionKeepAlive.kt` 只有 55 行、无任何 prefs 读写，被引用的 `:68/:75/:76` 越界。要么把设置项做出来，要么把这行文档与档案项删掉。
- `IMAGE_INPUT` / `FILE_SAVE` 两档 honored 无判定点（见 §1 末段）：如果哪天要让桌面「存进下载」可用，靠改 honored 是**没有用**的，得加 `tool` 名或改执行门 `AgentTool.kt:353` 的 `?: true`。
- 桌面的 `my_location` 之所以没有，只是因为 `DesktopAgentHost.kt:47` 调 `CampusMapAgentTools()` 时没给 `locate`（Android 在 `AndroidAgentHost.kt:70` 给了）——这是三端里最便宜的一处「能力差 1 件」。


---

# 台账 4 · 媒体 / 下载 / 二维码 / 相机 / 剪贴板 / PWA / 浏览器 / 落盘

> 子代理原始产物（`/tmp/ledger-4-platform-seams.md`），表格内容原样保留；汇总数字以上表复算为准。


### 平台能力台账 · 媒体 / 下载 / 二维码 / 相机 / 剪贴板 / PWA / 浏览器 / 文件落盘

仓库：`/mnt/nas/data/works/xjtu-toolbox-fixes`（只读核对；未跑 gradle、未联网）
图例：✅ 实装 ｜ 🔧 没做（缝在、该端没实现）｜ ❌ 物理不可能 ｜ ➖ 不适用 ｜ ❓ 未核实
“web 标注 ⚠️/❌ 的那几格另有一句为什么”，写在证据列。

| 功能点 | Android | 桌面 | web | 证据(文件:行) | 补它要动什么 |
|---|---|---|---|---|---|
| 视频播放（直播/回放，两路流） | ✅ | 🔧 | 🔧 | `feature/lms/.../media/VideoPlayerHost.kt:16`；`app/.../media/AndroidVideoPlayerHost.kt:6`(Media3)；`desktop/.../media/DesktopVideoPlayerHost.kt:26`；`web/.../ToolboxWebApp.kt:531`(null) | 桌面接 VLCJ/JCEF；web 加 `<video>`/hls.js 并传 host |
| 播放要带的鉴权请求头 | ✅ | 🔧 | 🔧 | `VideoPlayerHost.kt:11`；`AndroidVideoPlayerHost.kt:14`；`DesktopVideoPlayerHost.kt:35`（只交 URL，头丢了） | 桌面改成自己拉流带 cookie；web 同 |
| 没有播放器时的如实降级页 | ➖ | ✅ | ✅ | `DesktopVideoPlayerHost.kt:44-45,62-72`；`LmsScreen.kt:273-277,448-467` | — |
| DownloadEngine（字节写入） | ✅ | ✅ | 🔧 | `shared/model/.../media/DownloadEngine.kt:19`；`app/.../media/AppDownloadEngine.kt:16`；`shared/data/src/jvmMain/.../JvmDownloadEngine.kt:17`；`web/.../WebSupport.kt:15` | web 加 fetch+Range 引擎（OPFS/Blob 落盘） |
| DownloadFileSystem（目录/大小/删） | ✅ | ✅ | 🔧 | `DownloadEngine.kt:29`；`AppDownloadEngine.kt:88`；`JvmDownloadEngine.kt:105`(根=~/Downloads/ClassReplay :131-135) | web 无文件系统宿主，需 OPFS 或纯内存 |
| DownloadTaskStore（任务持久化） | ✅ | ✅ | 🔧 | `feature/lms/.../media/DownloadTaskStore.kt:4`；`app/.../media/AppDownloadTaskStore.kt`(Room)；`desktop/.../media/DesktopDownloadTaskStore.kt:23` | web 落到已有 `KeyValueStore`/IndexedDB |
| DownloadedFilesHost（文件登记/打开/删除） | ✅ | 🔧 | 🔧 | `DownloadedFilesHost.kt:4`；`app/.../AppDownloadedFilesHost.kt:15`；`DesktopDownloads.kt:32`(自认没登记表)、`ToolboxDesktopApp.kt:717`(`files = null`) | 桌面建登记表 + `Desktop.open`；web 无载体（可只列不打开） |
| 下载管理屏本身 | ✅ | ✅ | 🔧 | `DownloadManagerScreen.kt:104`；`DesktopDownloads.kt:13-22`；`WebSupport.kt:15`(NotPortedScreen) | web 加 AppPage 分支 + 上述 4 件 |
| 公共下载目录（MediaStore） | ✅ | ➖ | ➖ | `app/.../lms/LmsDownloadStore.kt:87-97`（`Download/<dir>`, `IS_PENDING`） | — |
| 桌面 `~/Downloads` 落盘 | ➖ | ✅ | ➖ | `desktop/.../lms/DesktopLmsSaver.kt:12,53`；`desktop/dzpz/DesktopTranscriptSaver.kt:19` | — |
| 浏览器 Blob 下载机制 | ➖ | ➖ | ✅ | `web/.../WebArchiveIo.kt:130-143`；调用方 `WebArchiveHost.kt:51-56` | 复用给 ICS/成绩单/海报 |
| QrDecode（ARGB→文本） | ✅ | ✅ | ✅ | 缝 `shared/model/.../platform/QrDecode.kt:4`；`androidMain/QrDecode.android.kt:11`；`jvmMain/QrDecode.jvm.kt:11`(zxing)；`wasmJsMain/QrDecode.wasmJs.kt:6`(纯 Kotlin `platform/qr`) | — |
| 相机取景 | ✅ | 🔧 | ✅ | `app/.../qrlogin/QrScannerView.kt:4-11,30`(CameraX)；`ToolboxDesktopApp.kt:805-811`(`scanner = null`)；`web/.../WebQrCamera.kt:28-50`、接线 `ToolboxWebApp.kt:689` | 桌面接 webcam 或截图取码 |
| 相机前置条件（安全上下文/getUserMedia） | ➖ | ➖ | ⚠️ | `WebQrCamera.kt:17-21`；`WebBrowserCapabilities.kt:26-30`（`--host 0.0.0.0` 不带 `--https` 不算安全上下文 ⇒ 不给相机） | 部署强制 HTTPS/localhost |
| 相册/文件选图解码 | ✅ | 🔧 | ✅ | `app/.../social/AppMatchScreen.kt:51-75`(PickVisualMedia)；`WebQrCamera.kt:53-59`(`<input type=file>`)；`ToolboxDesktopApp.kt:811` | 桌面 FileDialog + `QrDecode` |
| 剪贴板写（复制文字/验证码） | ✅ | ✅ | ✅ | `feature/community/.../CommunityScreen.kt:186`（共享 Compose，一套代码三端）；`desktop/.../DesktopBrowser.kt:36`；`WebBrowserCapabilities.kt:31-36` | web：`navigator.clipboard` 不在时会**静默失败**，需降级提示 |
| WebCrypto（WebVPN 需 AES-128-CFB/MD5） | ➖ | ➖ | ❌ | `WebBrowserCapabilities.kt:49-54`（`crypto.subtle` 有，但 CFB 与 MD5 都没有，本端也没消费方） | 只能用纯 JS 密码库；WebCrypto 做不了 |
| SecureRandom | ✅ | ✅ | 🔧 | `platform/SecureRandom.kt:4`；`androidMain/...android.kt:5`；`jvmMain/...jvm.kt:6`；`wasmJsMain/...wasmJs.kt:6-9`(退化成 `Random.Default`) | web 换 `crypto.getRandomValues`（消费方：`AnonymousId.kt:28`、`CampusAttendanceApi.kt:276`、`OneTimeToken.kt:31`） |
| 可安装包 | ✅ | ✅ | ✅ | APK；`desktop/build.gradle.kts:97-149`(jpackage deb/msi/app-image)；`manifest.webmanifest:1-31` | — |
| Service Worker 离线壳 | ➖ | ➖ | ✅ | `web/src/wasmJsMain/resources/sw.js:18-40`；注册 `pwa.js:12-30`（非安全上下文直接报错返回） | — |
| 系统通知 | ✅ | 🔧 | ✅ | `app/.../notification/AppNotificationChannels.kt:7-19`、`NoticeNotifier.kt`；`WebPwa.kt:23-36`+`@JsFun:59-80`；桌面全库无 Tray/Notification 调用 | 桌面接 `SystemTray`（`DesktopScheduleHost.kt:29-30` 已标 TODO） |
| 后台定时（拉新通知/上课提醒） | ✅ | 🔧 | 🔧 | `app/.../notification/{ScheduleWatchWorker,NoticeWatchWorker,LmsDeadlineWorker,LibraryReminderWorker}.kt`；`WebPwa.kt:20-21,41`(periodicSync 只探测，Chrome 桌面基本 denied) | web 只能在页面打开时跑；桌面加调度器 |
| 主屏小组件 | ✅ | ❌ | ❌ | `app/.../widget/{Schedule,Notice,CampusCard,Todo}WidgetProvider.kt`；`WebPwa.kt:40`（浏览器无对应物） | 桌面无“主屏”这一载体，不做 |
| 内置浏览器 | ✅ | 🔧 | 🔧 | `app/.../browser/BrowserScreen.kt:150`(WebView)；`desktop/.../DesktopBrowser.kt:46-53`(无 JCEF/JavaFX)；`ToolboxWebApp.kt:305-310`(`navigateSameTab`) | 桌面接 JCEF；web 只有整页跳（不可能内嵌） |
| 会话 cookie 注入 | ✅ | ❌ | ❌ | `BrowserScreen.kt:88-135`(`syncCookiesToWebView`→`android.webkit.CookieManager`+flush)、注入时机 `:208-209,419-420,539-540`；桌面/ web 无容器 | 桌面若有 JCEF 才谈得上；web 跨站 cookie JS 写不了 |
| `then` 跳转语义 | ✅ | 🔧 | 🔧 | `BrowserScreen.kt:403-404,441-444`（离开登录页后 `loadUrl(then)`）；`DesktopBrowser.kt:99-106` 如实说跳不过去、但 `:117-122` 直接开 then；`ToolboxWebApp.kt:305-310` **忽略 then** | web 用 then 当跳转目标 |
| 系统分享（ACTION_SEND） | ✅ | 🔧 | 🔧 | `app/.../schedule/AppScheduleHost.kt:76-95`(FileProvider+chooser)；缝默认 `ScheduleHost.kt:88`=false、能力位 `:18`；`DesktopScheduleHost.kt:24-32`、`WebScheduleHost.kt:33-38` 都不给 SHARE_FILE | 桌面=写文件+`Desktop.open`；web=Blob 下载 |
| ICS 导出 | ✅ | 🔧 | 🔧 | 生成 `feature/schedule/.../ScheduleExport.kt:20`；入口 `ScheduleScreen.kt:273,290-294`；桌面 `DesktopScheduleJvmTest.kt:194-195` 断言“没有分享面板” | 同上一行（只是接线） |
| 成绩单 PDF 保存 | ✅ | ✅ | 🔧 | `app/.../dzpz/AppTranscriptSaver.kt:22-35`(MediaStore)；`desktop/dzpz/DesktopTranscriptSaver.kt:19-27`；`ToolboxWebApp.kt:422-431`（不传 saveSink，且 serve 契约没投影出 PDF 链） | web 先让 serve 端出 PDF，再接 Blob 下载 |
| 存档导出/导入（zip） | ✅ | ✅ | ✅ | `app/.../settings/AppSettings.kt:333,347`(SAF Create/OpenDocument)；`desktop/archive/DesktopArchiveHost.kt:96-123`；`WebArchiveHost.kt:51-56`+`WebArchiveImport.kt` | — |
| 海报存图（干饭人账单） | ✅ | ✅ | 🔧 | `app/.../card/MealReportShare.kt:14-33`(FileProvider)；`desktop/.../DesktopMealReportExport.kt:15-25`(AWT FileDialog)；web 无实现 | web canvas→PNG→Blob |
| 系统“选/存文件”对话框 | ✅ | 🔧 | ✅ | Android SAF 见上；桌面仅海报有 `FileDialog`（`DesktopMealReportExport.kt:16`），存档导入导出走 dataRoot；web `<input type=file>`(`WebQrCamera.kt:53`) | 桌面存档导入也接 FileDialog |

## 计数

- 总行数：**32**（三端格子共 96 格）
- 🔧 单元格：**27**（桌面 12 / web 15）
- ❌ 单元格：**5**（WebCrypto-web、小组件-桌面、小组件-web、cookie注入-桌面、cookie注入-web）
- ❓ 单元格：**0**（另有 1 处 ⚠️：web 相机的前置条件；web 剪贴板是 ✅ 但附静默失败告警）
- 其余：✅ 50、➖ 13

## 最贵的 3 项（按“补它的成本 ÷ 收益”）

1. **web 那一整套下载（Engine/FileSystem/TaskStore/DownloadedFilesHost/下载屏 5 格全 🔧）**
   `WebSupport.kt:15` 现在直接落 `NotPortedScreen`。但 web 已有 Blob 下载（`WebArchiveIo.kt:130-143`）与 `KeyValueStore`，
   最小可行是 fetch→OPFS/Blob + IndexedDB 任务表，收益是「浏览器也能存回放/课件」。
2. **桌面内嵌浏览器（连带 cookie 注入、then 语义、桌面视频 ⚠️ 同一族）**
   `DesktopBrowser.kt:46-53` 明说没有 JCEF/JavaFX。接一整套 JCEF 是唯一能同时救“内置浏览器 + 会话注入 + 内嵌播放器 + 抄屏小游戏”的改动，
   但它是重依赖（打包体积/许可证），成本最高、杠杆最大。
3. **桌面 宿主能力缺口那一批“低垂果实”**：`DownloadedFilesHost`（`files=null`）、`SHARE_FILE`（`DesktopScheduleHost.kt:24-32`）、
   系统通知（`SystemTray`）。三件都只是“接一个 AWT/JDK API”，却各让一整屏降级；加起来最便宜。


---

# 台账 5 · 路由×三端 / 统一存档层 / serve 端点缺口

> 子代理原始产物（`/tmp/ledger-5-routes-archive-endpoints.md`），表格内容原样保留；汇总数字以上表复算为准。


### Ledger 5 — 路由 × 三端 / 统一存档层 / serve 端点缺口

- 仓库：`/mnt/nas/data/works/xjtu-toolbox-fixes`，HEAD `c02011d1`，`git status` 干净
- 本轮**只读**：未改仓库任何文件，未跑 gradle，未联网
- 判定四级：**真屏**（有实现、有数据源）· **诚实兜底**（`NotPortedScreen` / `HostUnavailableScreen` / `WebServeOnlyScreen` / `WebSupportMismatchScreen` 这类如实说明页）· **非屏**（`Browser`：交给浏览器/同标签导航）· **❓**（证据不足，不猜）
- 证据格式 `文件:行`；短名约定见每节开头

---

## A. 路由 × 三端（43 条）

**总数 43** = `AppRoute.kt` 里 43 个 `@Serializable data object/class`：`simpleRoutes` 40 条（`AppRoute.kt:180-197`）+ 带参加的三条 `Lms` / `Jiaocai1Reader` / `Browser`（`AppRoute.kt:135-139`、`144-147`、`168-171`）。

平台文件短名：

- **A** = `app/src/main/java/com/xjtu/toolbox/nav/AppNavHost.kt`（`entry<AppRoute.X>`，40 条：153…560）
- **A′** = Android 的非 `entry` 通路：`main/BottomTab.kt:52-53`（底栏 tab）、`main/AppRouter.kt:115-117`（tab 转发 + 付款码覆盖层）
- **D** = `desktop/src/main/kotlin/com/xjtu/toolbox/desktop/ToolboxDesktopApp.kt`（`DesktopPage` 的 `when(route)`，43 条顶层分支：394…813；`else -> NotPortedScreen` 在 `:816`，**当前 0 条路由落进去**）
- **W** = `web/src/wasmJsMain/kotlin/com/xjtu/toolbox/web/ToolboxWebApp.kt`（`AppPage` 的 `when(route)`，**40 条**顶层分支：264…740；兜底 `:744-745` = `WebSupportMismatchScreen` / `NotPortedScreen`）
- **W′** = `WebSupport.openableRouteIds`（`WebSupport.kt:20-79`，39 条 id —— web「本端真能打开」的唯一清单）

### A.1 逐条表

| # | 路由 | id | Android | Desktop | Web | Web 判定 |
|---|---|---|---|---|---|---|
| 1 | `Main` | `main` | 真屏 `A:153` | 真屏 `D:394` | 真屏 `W:282` | — |
| 2 | `Schedule` | `schedule` | 真屏（tab）`BottomTab.kt:52` / `AppRouter.kt:115` | 真屏（SiteGate 教务）`D:440` | 真屏 `W:264` | — |
| 3 | `Agent` | `agent` | 真屏（tab）`BottomTab.kt:53` / `AppRouter.kt:116` | 真屏 `D:813` | **serve-only** `W:289`（`mode != SERVE` ⇒ `WebServeOnlyScreen`） | 兜底 |
| 4 | `PaymentCode` | `payment_code` | 真屏（覆盖层）`AppRouter.kt:117` | 真屏 `D:508` | **serve-only** `W:628` | 兜底 |
| 5 | `EmptyRoom` | `empty_room` | 真屏 `A:155` | 真屏 `D:516` | 真屏 `W:383` | — |
| 6 | `Notification` | `notification` | 真屏 `A:166` | 真屏 `D:581` | 真屏 `W:356` | — |
| 7 | `Attendance` | `new_attendance` | 真屏 `A:169` | 真屏 `D:614` | **serve-only** `W:435` | 兜底 |
| 8 | `Judge` | `judge` | 真屏 `A:199` | 真屏 `D:473` | 真屏 `W:371` | — |
| 9 | `JwappScore` | `jwapp_score` | 真屏 `A:181` | 真屏 `D:633` | **serve-only** `W:571` | 兜底 |
| 10 | `Library` | `library` | 真屏 `A:207` | 真屏 `D:414` | 真屏 `W:400` | — |
| 11 | `CampusCard` | `campus_card` | 真屏 `A:240` | 真屏 `D:500` | 真屏 `W:393` | — |
| 12 | `Coupon` | `coupon` | 真屏 `A:265` | 真屏 `D:591` | **serve-only** `W:462` | 兜底 |
| 13 | `ScoreReport` | `score_report` | 真屏 `A:275` | 真屏 `D:452` | 真屏 `W:322` | — |
| 14 | `Transcript` | `transcript` | 真屏 `A:288` | 真屏 `D:597` | **serve-only** `W:411` | 兜底 |
| 15 | `Venue` | `venue` | 真屏 `A:298` | 真屏 `D:528` | 真屏 `W:398` | — |
| 16 | `DormPower` | `dorm_power` | 真屏 `A:318` | 真屏 `D:605` | **serve-only** `W:486` | 兜底 |
| 17 | `DownloadManager` | `download_manager` | 真屏 `A:338` | 真屏 `D:714` | **无分支** ⇒ `NotPortedScreen` `W:745` | 诚实兜底 |
| 18 | `Lms` | `lms[?courseId=]` | 真屏 `A:341` | 真屏 `D:621` | **serve-only** `W:500` | 兜底 |
| 19 | `Jiaocai` | `jiaocai` | 真屏 `A:357` | 真屏 `D:688` | **serve-only** `W:537` | 兜底 |
| 20 | `Jiaocai1` | `jiaocai1` | 真屏 `A:368` | 真屏 `D:672` | **无分支** ⇒ `NotPortedScreen` | 诚实兜底 |
| 21 | `Jiaocai1Reader` | `jiaocai1_reader/<ssno>` | 真屏 `A:380` | 真屏 `D:705` | **无分支** ⇒ `NotPortedScreen` | 诚实兜底 |
| 22 | `SchoolCourse` | `school_course` | 真屏 `A:396` | 真屏 `D:434` | 真屏 `W:367` | — |
| 23 | `SchoolCalendar` | `school_calendar` | 真屏 `A:408` | 真屏 `D:400` | 真屏 `W:312` | — |
| 24 | `YellowPage` | `yellow_page` | 真屏 `A:415` | 真屏 `D:409` | 真屏 `W:327` | — |
| 25 | `Fitness` | `fitness` | 真屏 `A:426` | 真屏 `D:428` | 真屏 `W:316` | — |
| 26 | `Iclassface` | `iclassface` | 真屏 `A:434` | 真屏 `D:648` | **serve-only** `W:606` | 兜底 |
| 27 | `Browser` | `browser?url=&then=` | 真屏（内置 WebView 浏览器）`A:443` | **外链中转页**（「桌面端没有内嵌浏览器」，交系统浏览器）`D:753` + `DesktopBrowser.kt:26-54` | **非屏**：同标签导航 `W:305-310`（`navigateSameTab`，在 `openableRouteIds` 里被显式排除 `WebSupport.kt:13`） | 非屏 |
| 28 | `Settings` | `settings` | 真屏 `A:463` | 真屏 `D:725` | 真屏 `W:740` | — |
| 29 | `Inbox` | `inbox` | 真屏 `A:473` | 真屏 `D:545` | 真屏 `W:372` | — |
| 30 | `Feedback` | `feedback` | 真屏 `A:501` | 真屏 `D:666` | **serve-only** `W:657` | 兜底 |
| 31 | `Community` | `community` | 真屏 `A:488` | 真屏 `D:656` | 真屏 `W:720` | — |
| 32 | `Faculty` | `faculty` | 真屏 `A:516` | 真屏 `D:404` | 真屏 `W:362` | — |
| 33 | `Accounts` | `accounts` | 真屏 `A:552` | 真屏 `D:730` | **serve-only** `W:729` | 兜底 |
| 34 | `WebVpnConverter` | `webvpn_converter` | 真屏 `A:560` | 真屏 `D:746` | **serve-only** `W:692` | 兜底 |
| 35 | `Games` | `games` | 真屏 `A:525` | 真屏 `D:735` | 真屏 `W:332` | — |
| 36 | `Game2048` | `game_2048` | 真屏 `A:528` | 真屏 `D:741` | 真屏 `W:338` | — |
| 37 | `GameMerge` | `game_merge` | 真屏 `A:531`（Android 有 WebView ⇒ 能跑） | **`HostUnavailableScreen`** `D:778`（无 WebView / 无 JS 引擎，`D:761-788` 逐条说明） | 真屏 `W:352` | — |
| 38 | `GameBlocks` | `game_blocks` | 真屏 `A:534` | 真屏 `D:742` | 真屏 `W:339` | — |
| 39 | `GameHop` | `game_hop` | 真屏 `A:537` | 真屏 `D:744` | 真屏 `W:348` | — |
| 40 | `GameGomoku` | `game_gomoku` | 真屏 `A:540` | 真屏 `D:743` | 真屏 `W:342` | — |
| 41 | `GameGo` | `game_go` | 真屏 `A:543` | 真屏 `D:745` | 真屏 `W:344` | — |
| 42 | `GameXiangqi` | `game_xiangqi` | 真屏 `A:546` | 真屏 `D:759` | 真屏 `W:345` | — |
| 43 | `Match` | `schedule_match` | 真屏 `A:549` | 真屏 `D:808` | 真屏 `W:686` | — |

### A.2 计数

| 平台 | 真屏 | 诚实兜底 | 非屏 | 合计 |
|---|---|---|---|---|
| **Android** | **43**（40 个 nav `entry` + `Schedule`/`Agent` 底栏 tab + `PaymentCode` 覆盖层） | **0** | **0** | 43 |
| **Desktop** | **41** | **2**（`GameMerge` `HostUnavailableScreen`；`Browser` 外链中转页） | **0**（按「有分支」计；`Browser` 已计入兜底） | 43 |
| **Web** | **26** | **16** = 13 条 serve-only（`Agent`/`PaymentCode`/`Attendance`/`JwappScore`/`Coupon`/`Transcript`/`DormPower`/`Lms`/`Jiaocai`/`Iclassface`/`Feedback`/`Accounts`/`WebVpnConverter`）+ 3 条 `NotPortedScreen`（`DownloadManager`/`Jiaocai1`/`Jiaocai1Reader`） | **1**（`Browser`） | 43 |

Web 侧自洽核对：`openableRouteIds` 39 条 = 26 真屏 + 13 serve-only；39 + `Browser` + 3 无屏 = 43 ✓（与 `WebSupport.kt:13-16` 的文件头自白一致）。

### A.3 一致性缺口（只列差异，不评价）

1. **web 有 13/43 屏与后端形态强耦合**：campus-api 形态下画 `WebServeOnlyScreen`（`WebFallbackScreens.kt:177`），serve 形态下才是真屏；Android / Desktop 无此概念（同一屏两形态都在）。
2. **web 3 条路由无屏**（`DownloadManager` / `Jiaocai1` / `Jiaocai1Reader`），且**不在** `openableRouteIds` ⇒ 首页宫格/学辅目录里也不出现（`WebSupport.kt:81-85`）。
3. **`Browser` 三端语义三分**：Android 内置 WebView 浏览器（真屏）· Desktop 外链中转页（诚实降级）· Web 同标签导航（非屏，入口即离开 SPA）。
4. **`GameMerge` 只有 Android / Web 能跑**，Desktop 是 `HostUnavailableScreen`（无 WebView，且**不在** `DESKTOP_SUPPORTED_ROUTES`）。
5. ❓ Desktop 的 `else -> NotPortedScreen`（`D:816`）是一道**没有路由落进去的活门**：今天 43/43 有分支 ⇒ 兜底页在桌面实际不可达。若将来 `AppRoute` 加一条，桌面会静默落到「还没搬到桌面」而不是编译期报错（Kotlin `when` 对 sealed 的穷尽性被 `else` 吃掉）。

---

## B. 统一存档层（export / import × 三端）

共享层（`:core`/`feature/settings` 的 `commonMain`，三端唯一实现）：

- 格式与版本：`ArchiveModel.kt`（`xjtu-toolbox-archive` / `v1`）
- 组装与导入：`StoreArchive.kt` — `export` `:9-63`、`inspect`(`validate`) `:68-150`、`import` `:78-116`、`archiveWins` `:119-125`
- 家族分类表：`ArchiveRegistry.kt:23-33`（`credentialPrefixes`）/ `:26`（`excludedNames`）/ `:97-107`（`entryFor` / `isCredential` / `isExcluded`，未登记名 ⇒ 默认全局 plain KV）
- 缝：`ArchiveSource`（读）· `ArchiveImportTarget`（写）· `ArchiveFileBackend` / `ArchiveStoreBackend`（平台投影）

### B.1 三端能力矩阵

| 项目 | Android（`:app`） | Desktop（jvm） | Web（wasmJs） |
|---|---|---|---|
| **导出通道** | SAF `CreateDocument("application/zip")` ⇒ 用户手选位置写 zip（`AppSettings.kt:333-346`、`AppArchiveUx.kt:69-73`）；`filesDir/archive/` 目录形态**只能读不能写**（`AppArchiveHost.kt:80` `writeToDirectory` **无任何调用方**） | 直接写数据根：目录 `<数据根>/archive/` + `<数据根>/archive.zip`（`DesktopArchiveHost.kt:118-127`、`DesktopArchiveIo.kt:62-92` / `:29-43`）；设置页一行触发（`DesktopSettings.kt:91-110`） | 浏览器下载 zip（自建 zip 写入器 `WebArchiveIo.kt:19-21` + `@JsFun` `:57-143`；点名 `xjtu-toolbox-archive.zip`）；设置页一行触发（`WebSettingsHost.kt:58,73-90`） |
| **导入触发** | SAF `OpenDocument` 复制进 `filesDir/archive_staging/`（`AppArchiveUx.kt:75-85`），**不在当场合并**；真正的导入在下一次进程启动：`XjtuApp.kt:58` → `maybeImportPendingArchive`（`AppArchiveUx.kt:87-97`），候选根 = `archive/` + `archive_staging/`（`:113-140`） | `archive/` 目录与数据根下 `*.zip`（`DesktopArchiveHost.kt:130-140`），启动时一次（`Main.kt:30`）+ 设置页「导入数据存档」行手动再跑（`DesktopSettings.kt:112-133,137-148`） | `<input type=file>` 选 zip ⇒ 内存解 ⇒ 当场 `StoreArchive.import`（`WebArchiveIo.kt:24-28,146-207`；`WebArchiveHost.kt:78-83`；`WebArchiveActions.import` 在 `WebArchiveHost.kt:133-136`） |
| **目录形态** | **只读**（`AppArchiveHost.kt:89-100`；候选识别 `AppArchiveUx.kt:123-140` 支持「根自身有 manifest」「根下 zip」「根下带 manifest 的子目录」三种）；写侧无出口 ⇒ 用户拿不到目录形态的**导出** | **双向**（写 `DesktopArchiveIo.kt:62-77` 逐条 `.tmp`→`ATOMIC_MOVE`；读 `:79-91` 跳过点文件与 `.tmp`） | **无**（`archiveFileBackend()` 恒 `null` — `ArchiveFileBackend.wasmJs.kt:4`；`archiveDataRoot()` 恒 `null` — `ArchiveStoreBackend.wasmJs.kt:13`） |
| **凭据是否导出** | **否**（默认） | **否**（默认） | **否**（默认） |
| **镜像/覆盖语义** | **未实现** | **未实现** | **未实现** |

**凭据口径的细节（三端同一条共享代码，故一致）**：`StoreArchive.export` 对 `isCredential(base)` 的族只把路径记进 `manifest.redacted`，不写内容（`StoreArchive.kt:25-31`）；`ExportOptions.includeCredentials` 默认 `false`（`StoreArchive.kt:148-151`）。⚠️ 这个开关**三端都没有 UI**（全仓只有单元测试用到：`ArchiveCodecTest.kt:152`）⇒ 实机永远走 `redacted` 路径。凭据族清单：`xjtu_credentials` / `xjtu_accounts` / `xjtu_cookies` / `github_community` / `serve`（`:31-37`）+ 按前缀 `cookies_` / `sites_` / `agent_config_secure`（`:23`）+ `xjtu_accounts` 的账号列表改走 `manifest.accounts`（不含密码）。

**镜像/覆盖**：全仓 `grep -rn "mirror\|镜像"` 在存档层**零命中**；写入侧只有 §6.4 合并两条规则 —— 键级「本端没有⇒写 / 双方都有 `at` 时新者胜 / 缺一方⇒本端胜」（`StoreArchive.kt:119-125`）与 files 级「**本端有这一份 ⇒ 跳过**」（`StoreArchive.kt:104-106`）。所以 UI 文案「默认不覆盖本机数据」（`AppSettings.kt:395`、`DesktopSettings.kt:113`）是**如实**的，反过来说「把本机整份替换成档案」这件事三端都没有。

### B.2 逐族覆盖差（这一节就是 B 的 diff 清单）

| 族 | Android | Desktop | Web |
|---|---|---|---|
| **stores**（prefs / `.properties` / `localStorage`） | ✅ 导出 `AppArchiveHost.kt:55` + 导入 `AppArchiveImportTarget.kt:22-37` | ✅ `DesktopArchiveHost.kt:96-104` + `DesktopArchiveImport.kt:57-67` | ✅ `WebArchiveHost.kt:29-30` + `WebArchiveImport.kt:24-35` |
| **`files/agent_sessions<suffix>/…`** | ✅ 双向（`AppArchiveHost.kt:67-69`；写 `AppArchiveImportTarget.kt:114,118`） | ✅ 双向（`DesktopArchiveHost.kt:77-83`；写 `DesktopArchiveImport.kt:68-76`） | ❌ **不落地**：导入前整体滤掉并只报条数（`WebArchiveImport.kt:51-58`、`WebArchiveHost.kt:100-105`） |
| **`files/schedule/<key>.json`** | ✅ 双向（`AppArchiveHost.kt:70-72`；写 `AppArchiveImportTarget.kt:101,114`；读侧投影 `ArchiveFileBackend.android.kt:20-33`） | ❌ **不导出**（`readFiles` 只列 `agent_sessions`，`DesktopArchiveHost.kt:77-83`）；导入时也不写（`putFile` 只认 `agent_sessions/`，`DesktopArchiveImport.kt:68-76`） | ❌ 同 agent_sessions（无文件系统） |
| **Room 三表**（`custom_courses` / `download_tasks` / `jiaocai1_shelf`） | ✅ 真表双向（导出 `AppArchiveHost.kt:59-64`；导入 `AppRoomRows`，`AppArchiveImportTarget.kt:54-58`；行适配 `RoomArchiveFamilies.kt:31-53,73-…`） | ⚠️ **等价物是键值店**（`DesktopArchiveRows.kt:37-66`；店名 `CUSTOM_COURSE_STORE` / `DownloadTaskStore` / `Jiaocai1ShelfStore`，含 legacy 无后缀兜底） | ⚠️ **只有 1/3**：`LANDED_STORES` 只有 `CUSTOM_COURSES`（`WebArchiveRows.kt:15-19`）⇒ `download_tasks` / `jiaocai1_shelf` 条目导入时滤掉并计数（`WebArchiveImport.kt:51-58`） |
| **`manifest.accounts` / 账号后缀** | ✅ `AppArchiveHost.kt:22-29` | ✅ `DesktopArchiveHost.kt:63-74` | ❌ 恒 `emptyList()`（`WebArchiveHost.kt:34`），导出档 `noAccount=true` |
| **`#written_at` 旁挂键** | ❌ **不写也不读**（`AppArchiveImportTarget.kt:36-37` 明说「`at` 不落地」；`ArchiveStoreBackend.android.kt:67-83` 无 `normalizeWrittenAt`） | ✅ 写 `DesktopArchiveImport.kt:52`、读 `ArchiveStoreBackend.jvm.kt:31` | ✅ 写 `WebArchiveImport.kt:33`、读 `ArchiveStoreBackend.wasmJs.kt:37` |

### B.3 B 的 diff 计数：**7 处不一致**

| # | 不一致 | 后果 |
|---|---|---|
| B-1 | `files/schedule` 族：Android 有，Desktop/Web 无 | Android→Desktop/Web 的档里这一族**静默丢弃**（Desktop 连计数都不报，只有 Web 报「N 个 files 条目未落地」） |
| B-2 | `files/agent_sessions`：Android/Desktop 有，Web 无 | 屁岱会话历史迁到 Web 一律丢（报数，不静默） |
| B-3 | Room 三表：Android 真表 · Desktop 键值店近似 · Web 只有 `custom_courses` | 自建日程/下载队列/教材书架三族里，Web 丢 2 族 |
| B-4 | 账号维度：Android/Desktop 带 `manifest.accounts` + 账号后缀目录，Web 恒空 | Android→Web→Android 往返**丢账号后缀与账号表**；Web 导出的档在另一端只会落 `global/` |
| B-5 | `#written_at`：Desktop/Web 双向，**Android 全缺** | Android 侧的逐键裁决退化成「本端有 ⇒ 本端胜」（`StoreArchive.kt:121-124` 的 `localAt == null ⇒ false`），即档案永远改不动 Android 已有的键 |
| B-6 | 目录形态：Desktop 双向 · Android 只读（且只在 app 私有目录，用户够不着写）· Web 无 | 「整拷一个目录就是档案」这条只在桌面成立；`AppArchiveHost.writeToDirectory`（`:80`）是死代码 |
| B-7 | 导入时机：Android 下次启动 · Desktop 启动+设置页可重跑 · Web 当场 | Android 用户点完「导入」看不到任何即时反馈（只有「已放入待导入」Toast，`AppSettings.kt:352`） |

**幂等**：三端都有，「身份 = `createdAt|createdBy|version`」+ 标记文件 `.imported`（Android `AppArchiveImport.kt`（`identityOf` / `.imported`）· Desktop `DesktopArchiveImport.kt:75-96,135-146` · Web 无标记 ⇒ **Web 靠 `StoreArchive.import` 的合并语义本身幂等**，重复导入同一份档 keysWritten=0）。

---

## C. serve 端点缺口（`docs/api-contract.md`）

### C.1 仍标 TODO / 未实现的条目：**10 条**（其中 **8 条是端点级**）

| # | 条目 | 端点级? | 证据 |
|---|---|---|---|
| C-1 | `/api/v1/personal/me/user`（P2，来源待确认） | ✅ | `:118`（表行）、`:161`（未实现清单） |
| C-2 | `/api/match/profile`（P2，学工档案在 `:data` 无源） | ✅ | `:116`（表行 ⚠️）、`:162`、`:1066` |
| C-3 | `GET /api/library/seats?time`（`time` 收了但上游无时间维） | ✅（维度缺口） | `:278`、`:291` |
| C-4 | 校园卡**挂失**（P1 写） | ✅ | `:291-292`、`:294`（核账：`CardRoutes` 只有 `balance`/`transactions`） |
| C-5 | 评教**提交 / 撤销**（P1 写，`canSubmit` 恒 `false`） | ✅ | `:275`、`:284-285`、`:292`、`:294` |
| C-6 | 研究生评教 `gste` / `gmis` 路（P1） | ✅ | `:275` |
| C-7 | `GET /api/emptyroom/rooms` 的 `from/to`（节次过滤；`:data` 只给全天） | ✅（参数缺口） | `:260` |
| C-8 | `GET /api/jwxt/school-courses` 补字段（人数/学时、`YPSJDD`、开课单位、公选筛选） | ✅（字段缺口） | `:92`、`:273` |
| C-9 | 成绩单 **PDF 落盘**（D7 ⑤：第 2~7 步是真向学校提交申请，且信封形状没有位置） | ❌ 能力缺口 | `:154-157`、`:589`、`:1699`、`:1906` |
| C-10 | MFA 绑定手机号投影（§5.1 TODO ①；弹窗里的「已发送到 138****0000」） | ❌ 投影缺口 | `:212`、`:502` |

另有两处**形态/未决**不算端点 TODO，但影响「屏真能用」：campus-api 形态下 `/api/agent/*` **整族没有端点**（`:1497` 附近 §5.11 的实况说明）；web 侧「改上一条重发」「Key 配在 serve 那台机器上」两条文案未决（`:1506-1510`）。

### C.2 「只在 serve 模式才是真屏」的屏：**13 条**

判据：`AppPage` 里写成 `if (mode != ApiMode.SERVE) WebServeOnlyScreen(...) else <真屏>` 的路由：

| 屏 | 判据行 | 契约依据 |
|---|---|---|
| `Agent` | `W:289` | §5.11（campus-api 里没有 Key 与循环，`:1497`） |
| `Transcript` | `W:411` | §5.5（`dzpz/transcript`） |
| `Attendance` | `W:435` | §5.5 / §5.5.1 |
| `Coupon` | `W:462` | §5.5 |
| `DormPower` | `W:486` | §5.5 |
| `Lms` | `W:500` | §5.6 |
| `Jiaocai` | `W:537` | §5.6 |
| `JwappScore` | `W:571` | §5.6 / §5.8（`score-detail`） |
| `Iclassface` | `W:606` | §5.6 |
| `PaymentCode` | `W:628` | §5.7 |
| `Feedback` | `W:657` | §5.7 |
| `WebVpnConverter` | `W:692` | §5.7 |
| `Accounts` | `W:729` | §5.10 |

注：这 13 条**在** `openableRouteIds` 里（门开着，`WebSupport.kt:20-79`），所以宫格能点进去、进去后看到的是「切到 serve 模式就能用」。另外 `Match`（`W:686`）读本机 `localStorage`，**不**受形态限制（`WebMatchHost`）。

---

## 汇总（本轮要求的四个数）

| 指标 | 值 |
|---|---|
| **A. 路由总数** | **43**（`simpleRoutes` 40 + 带参 3） |
| A. Android | 真屏 **43** / 诚实兜底 **0** / 非屏 **0**（其中 40 条走 nav `entry`，2 条底栏 tab，1 条覆盖层） |
| A. Desktop | 真屏 **41** / 诚实兜底 **2**（`GameMerge` `HostUnavailableScreen` + `Browser` 外链中转页） / 非屏 **0** |
| A. Web | 真屏 **26** / 诚实兜底 **16**（13 serve-only + 3 `NotPortedScreen`）/ 非屏 **1**（`Browser`） |
| **B. 存档层 diff 数** | **7 处**（B-1…B-7；其中 B-1/B-3/B-4 会**静默或半静默丢数据**） |
| **C. TODO 端点数** | **10 条**（8 条端点级 + 2 条能力/投影级） |
| **C. serve-only 屏** | **13 条** |

### 最贵的三件事

1. **Web 有 13/43 屏与后端形态强绑定**（`WebServeOnlyScreen`）：等于 web 端有 30% 的路由在 campus-api 形态下只是「说明页」，而这 13 条里包含 `PaymentCode` / `Transcript` / `Attendance` / `Coupon` / `DormPower` 这类**日常高频**项。修它需要的是 serve 宿主机，不是前端工作量 —— 这是「同一份路由，三种可用性」里最贵的一处。
2. **档案层的 files 家族三端不对称（B-1 / B-2 / B-3）**：`files/schedule` 只有 Android 有、`agent_sessions` Web 没有、Room 三表 Web 只有 1/3。结果是「统一存档」实际只在 stores 一层真统一：跨端迁移时最容易被丢的恰是**用户自己造的数据**（自建日程、下载队列、教材书架、屁岱会话），而且 Android→Desktop 这条路上 `schedule` 是**完全静默**丢的（Desktop 不报未落地数）。
3. **Android 缺席 `#written_at`（B-5）**：三端共用的逐键裁决「新者胜」在 Android 上退化为「本端有则本端胜」⇒ 用档案更新一台已有数据的 Android 机**基本改不动**任何键。这是隐蔽的语义漂移：导入会报 `Imported`、计数也好看（keysKept 大），但实质是「档案被本机覆盖」。

（次贵但**季节性强**：C-5 评教提交 / C-4 卡挂失 —— 都是写端点缺口，评教季一到就没有替代路径。）

---

## 6. 2026-10-19 会议与 A 批落地记录（后续接手从这里看）

### 6.1 会议定案（#1–#13）

| # | 议题 | 定案 |
|---|---|---|
| 1 | 存档冲突语义 | **C**：不动 Android 落盘形状（**否掉**了 B「引入 `#written_at`」）⇒ 导入时给显式选择，**三端同一套**；选完文件弹一次，**默认「只补上本机没有的键」**；选「以档案为准」= **真镜像、不做备份**；镜像**只覆盖档案声明过的范围**、**红线清单永不删** |
| 2 | 「旧形状只读」不成立 | **乙**：端内键名与值语义逐字不变；档案是投影；**端内形状不追求统一** |
| 3 | web 成绩缓存 | **甲**：补 `at` + 7d TTL（旧裸数组仍可读、按无年龄当过期） |
| 4 | `rsa_key_time` 同键两类型 | **甲**：导入端按目标端读法容错，**绝不抛** |
| 5 | `venue_favorites` 两种编码 | **甲**：web 换共用 `VenueFavorites` + 档案按声明类型 |
| 6 | 成绩报表三种形状 | **甲**：只在**档案层**统一（`files/schedule/score_report.json` + `{at,grades}`，不含学号） |
| 7 | 桌面 store 只活在内存 | **已闭环**（`JvmFileKeyValueStore`，`MemoryKeyValueStore` 全仓消失） |
| 8 | `DesktopDownloads` 单例串号 | **甲**：随账号重建；在跑的任务跑完落回原账号 |
| 9 | web 缺三屏 | **丙**：全做（`DownloadManager` 按「serve 装真引擎」做，**不做影子版**） |
| 10 | web 账号页写侧 | **丙**：全做，**HTTPS 变硬前提**（未启用 HTTPS 时写端点拒绝服务） |
| 11 | 桌面内嵌浏览器 | **甲**：本轮不引入；调研结论写在 `desktop-port-plan.md` §13.3 第 2 条，**下次 PR 里以「留给 owner 的决定」列出**（推荐方案 = jcefmaven + 首次运行下载 natives） |
| 12 | 40/43 口径 | **甲**：三个数分开写（43 声明 / 42 真实现 / 40 宫格口径） |
| 13 | 设置页缺行 | **丁**：全补（含玻璃风、默认启动 Tab、考勤角标、场馆验证码、考勤当课表源）；其中 **3 格物理不可能**（web 取色、桌面/web 触感）**豁免** |
| 附 | 缺口说明 | 走**共享层 `gapNotes` 槽位**（挂 Host 接口 + 默认空实现 + 逐条）；文案 18 条、豁免 6 条、等做 10 条 |

### 6.2 A 批已落地（分支 `chore/parity-a-batch`，stacked 在 PR #142 头上）

- `7e854fe2`：小修 8 条 + 视频鉴权头本地回环代理（`LoopbackStreamProxy`，Range/206 透传，jlink 加 `jdk.httpserver`）
- 之后又落了：批 3（`GapNote` 槽位 + 6 条文案）、批 5（成绩族档案形状统一 + 键名归一 + 导入侧类型归一「绝不抛」）、批 1（文档 21 处）
- **门禁**（独占、`--max-workers=1`）：全量 `BUILD SUCCESSFUL 56m11s`；`:app` 另单独 `--rerun` 复算

### 6.3 基线更正：`:app` 453 → **450**

本批**零改动 `app/`**。差异全部可解释（详见 `storage-layout.md` 判据 A12）：dev 线「联网改用 DeepSeek」删掉旧搜索解析代码 ⇒ `AgentWebTest` 少 5 条；新增公式测试 ⇒ `TexLiteTest` 多 2 条。**交接里的 453 是对着 kmp-tail 工作区测的过时基线。**

### 6.4 本轮新增的“未验证 / 已知没做”（别当已验证用）

| 项 | 状态 |
|---|---|
| 视频鉴权头代理 | **未验证**：真播放器 seek、打包后 `jdk.httpserver` 真在运行时里、真 LMS 回放地址；**已知没做**：HLS `.m3u8` 里的相对分片不走代理 |
| `SecureRandom`（wasmJs） | `:shared:model` **没有 wasmJsTest 源集** ⇒ 无可跑测试（只有 node 复刻算法的旁证） |
| 桌面 `DesktopDownloads` | 每账号留一份实例（会长留 N 份 scope）；下载页开着切账号列表不自动刷新 |
| 桌面 `my_location` | **没补**：桌面无定位能力，硬塞 = 假实现（差集继续记在本台账） |
| 批次 5 的不确定点 | ① 桌面 `at` 用店 `.properties` mtime 近似；② 桌面行族店名按当前账号解析这个旧问题同样影响成绩族；③ web 只有一个 `all` 键，多账号档案后落覆盖先落；④ 未给 `ArchiveFixture` 加成绩假数据；⑤ 导入侧未拒收旧档案里多出来的 `stores/score_report*.json` |
| 缺口说明 | D21（桌面无主屏小组件）**无落点**（日程屏没有那一格）；**web 也不 honor `SHARE_FILE`**（导出菜单现在是空的）—— 后者已归入批 3b |

### 6.5 还没做完

- 批 3b + 4：剩余家族的缺口说明 + 4 条能力归位（共享文件登记/ICS/请假附件/屁岱存文件与挑图 + 工具门一致性）
- **C 组**（已拍板、待排期）：C-1 = #13 丁（两端玻璃风 + 首页两块 + 8 行设置）；C-2 = #9 丙 三屏 + #10 丙 账号写侧（HTTPS 硬前提）；C-3 = 存档镜像档
- 议程 **B 组**第一簇已提出（两进程互写 / `then` 口径 / 目录形态），**尚未收到答复**

---

## 7. 2026-10-20：B 组全部定案 + C 组排期 + 本轮进度

### 7.1 B 组定案（全部）

| 条 | 定案 |
|---|---|
| B-1 web Monet | **❌ 物理不可能**（已验：web 的 `Main.kt:19` 根本没读/传 `dynamicColor`；miuix 在非 Android 平台是按固定种子取色 ⇒ 开了就是假开关） |
| B-2 默认启动 Tab | (a) 存规范化值 + 三端各一张映射表（已落地） |
| B-3/B-11 端点 | **(c) 一次性全做**（13 屏 + 10 端点 + 考勤三栏） |
| B-4 `CourseLinkAccess` | (a) 下沉 `:core` + 各端取数 |
| B-5 `DUE_HOMEWORK` | (a) 下沉 `:data` + serve 端点 |
| B-7 桌面通知/更新 | (a) 只做 `SystemTray`（**已落地**）；更新链保持如实说明 |
| B-8 屁岱白名单 | (a) 桌面补到与 serve 同档（**已落地 5→9**）；做不到的如实写明 |
| B-9 存档 files 家族 | **(a) 全补齐**（web 部分依赖 C-2） |
| B-10 web 账号维度 | (a) 取 serve 当前账号 + 顺带收口未决 #8 |
| B-12 写端点缺口 | (b) 卡挂失 + 评教提交都排进 C-2 |
| B-13 两进程互写 | (a) 单实例闸门（**已落地**；含只读进程不回写的 guard） |
| B-14 `then` | (a) 统一「不保证」+ 修桌面矛盾（**已落地**） |
| B-15 目录形态 | (a) 不拉平 + Android 目录导出真接上（**已落地**） |
| B-16 ① 成绩单 | **① = (b) 显式两段式提交**（开契约第三条例外 + 幂等；契约里那两句过期描述已更正） |
| B-16 ②③ 文案 | 抽槽位（**已落地**；默认文案不动 ⇒ `:app` 零变化） |
| B-16 ④ 场馆支付口径 | **无需修改**（文档已是最保守写法，代码一致） |

### 7.2 C 组排期（已定）

| 批 | 内容 | 状态 |
|---|---|---|
| C-1a | 接线类（B-2 / 考勤角标 / 场馆开关 / 白名单 / `SystemTray`） | **已完成** |
| C-1b | 玻璃风（**一步到位 = 真模糊**） | 进行中（先技术验证再铺开） |
| C-1c | 首页两块（`BULLETIN_BAR` + `GLANCE`，**对齐 Android**、不新增端点） | **等 #147 合**（那个第三方 PR 在重排首页） |
| C-2 | 端点批（账号写侧 HTTPS 硬前提 → 高频屏 → 考勤三栏 → ①/B-4/B-5/B-12/B-9-web） | 进行中（第①步：账号写侧） |
| C-3 | 存档镜像档（三端同框 + 先算差异 + 失败回滚） | 排最后（唯一会删用户数据） |

### 7.3 本轮已落地（提交）

- `9a0dde7c` A 批剩余（缺口说明槽位 / 档案成绩族 / 能力归位 / 文档 21 处）
- `4ab162be` 批 1（跨端机制）+ C-1a（设置与能力接线）
- `fe9f756e` **CI 修复**：`InstanceLock` 把「自己 pid 永远算活着」写进代码
- 分支 `chore/parity-a-batch`，PR **#150**（draft / WIP，stacked 在 #142 上）

### 7.4 门禁（重要）

本机门禁**必须与 `.github/workflows/core-gate.yml` 一致**（清单与当前基线见 `docs/desktop-port-plan.md` §14.8）。
2026-10-20 曾出现「本机绿、CI 红」—— 实为**本机没跑** `:shared:*:jvmTest` 与 `:feature:*:jvmTest`。
当前基线（2026-10-21 复算，见 `docs/desktop-port-plan.md` §14.8）：**25 个测试任务 / 1764 条用例 / 0 失败**；
`:app` 基线 **450**（453 是过时数）。本节原先记的 1721 是更早一次测量，§14.8 已就地更正。

---

## 8. 2026-10-21：W-E1 剩余两条里的 jiaocai1 + 下载契约（本节只记口径与结论）

### 8.1 本次拍的两条口径（用户 2026-10-21 定）

| 题 | 定案 |
|---|---|
| jiaocai1 的**页图 / 封面**怎么过 `/api` | **二进制端点直出 `image/jpeg`** —— 即 §4 的**第四条例外**（与成绩单那条 `application/pdf` 同一种东西）。理由：扫描页一张几百 KB，data URI 要乘 1.33 且整张进内存；而屏上就是一张位图，`serve_token` 是 `Path=/` 的 cookie ⇒ 直接当图片取即可。**不**用 `venue/captcha` 那条 data URI 先例。 |
| **教材书架**落在哪一端 | **serve 侧、按账号**（店名 `serve_jiaocai1_shelf<账号后缀>`）—— 照会议 **#9**（下载队列）的先例「serve 装真的那一层、浏览器只当界面」，与桌面 `DesktopJiaocai1ShelfStore` 同性质：跟着账号、换浏览器还在。**代价如实记着**：web 的档案导出是同步的、读不到 serve 侧的东西 ⇒ `files/jiaocai/jiaocai1_shelf.json` 这一族在 web 的档案里仍不落地。 |

### 8.2 落地（细节读契约，本节不复述）

| 想知道的 | 读哪里 |
|---|---|
| jiaocai1 那一族九个端点、逐字段、不端什么 | `docs/api-contract.md` **§5.14** |
| 下载那一族四条、逐字段、本批修的三处 | `docs/api-contract.md` **§5.13** |
| §4 的例外清单（三条 → **四条**）与那句过时的「只有两条」 | `docs/api-contract.md` **§4** |
| 用例与夹具那一处的核 | 两节末尾的「夹具与用例」段；`server/src/test/.../Serve{Jiaocai1,DownloadRoutes}Test.kt` |

### 8.3 ⚠️ 本批的**重头**：补契约测试补出来的三处线上缺陷

下载那一族上一轮**只落了实现、没写测试也没写契约**（交接文件里自己记着的「如实清单」）。本批补测试时
逐条撞出来（均已修、均已在 `ServeDownloadRoutesTest` 里钉住）：

1. **整族 404**：`DownloadRoutes` 把绝对路径 `/api/downloads` 又挂在 `route("/api")` 之下 ⇒ 实际注册成
   `/api/api/downloads`。**在线上也是 404**，不是测试环境的差别。
2. **回裸对象**：四条都 `respond(DTO)` ⇒ 没有 §4 的信封。客户端按 `code`/`data` 读会直接解不开。
3. **删「正在下载」那一条是静默空操作**（`:data` 的 `deleteTask` 只在非 `downloading` 时真删），
   旧代码照旧回 `200` + 成功形状 ⇒ 屏上点删除看起来成功、那一行还在。

另补一条**进门条件**：这四条原本只挂令牌闸门、**不要求登录**（拿到令牌就能让那台机器去取任意 URL、
往操作者的下载目录写文件；未登录时登记表还会落到 `_default` 命名空间）⇒ 本批与其余二十多族拉齐。

### 8.4 仍未做完（下一轮的入口，别当已完成）

1. **W-E1 剩下一条**：**账号 suffix 字段**（给 web 一个「serve 当前账号的 suffix」来源，B-10 要用）。
   本批**有意不动** —— 「后缀要不要原样端给浏览器」牵到红线（`AccountContext.suffixFor` 就是 `_<学号>`）
   与档案 `accounts/<后缀>/` 的跨端对得上对不上，要先把档案导入那一侧的匹配读清楚再拍，**不猜**。
2. **W-E2（最大的一块）**：13 条 serve-only 屏真屏化 + `DownloadManager` / `Jiaocai1` / `Jiaocai1Reader` 三屏
   + B-4/B-5 的 web 取数 + B-10。⚠️ 下载那一族**没有**暂停 / 恢复 / 取消三条端点 —— web 的下载管理屏
   要么只画这四件，要么在 W-E2 里拍要不要补（已记在契约 §5.13）。
3. **C-1c**：首页两块（等 **#147** 合）。
4. 其余仍见 §6.4 与 §7.4。

### 8.5 门禁（2026-10-21，独占 + `--max-workers=1`）

清单照 `.github/workflows/core-gate.yml` 那两条 `run:`（§14.8 那张表），本机另加 `:app:testDebugUnitTest`
与 `:server:test`：**`BUILD SUCCESSFUL in 15m 14s`**，635 个任务（130 executed / 505 up-to-date）。

⚠️ 如实记：本批只动了 `:server`（main + test）与文档 ⇒ 其余 24 个测试任务 Gradle 判 `UP-TO-DATE`
（输入哈希未变，不是「跳过」）；`:server:test` 是同一天真跑过的 **169 例 / 0 失败**（本批新增 10 例在内）。
复算的逐模块数见 §14.8（总数 1721 → **1764**）。

### 8.6 批 2（2026-10-21 晚）：下载族补齐三个动作动词

**背景**：上一节那批把下载族修到「能用了」，但只有 列 / 建 / 进度 / 删 四件 —— 桌面那一屏本来就直调
`DownloadManager` 的 `pauseDownload` / `resumeDownload` / `cancelDownload`，web 的下载管理屏要用就得有端点。

**落地**：`POST /api/downloads/{id}/{pause,resume,cancel}`（契约 §5.13 已同步）。两条口径：

1. **`{ok,id}` 不回状态** —— `:data` 的 `resumeDownload` 只丢一个协程（那一刻状态还没变）⇒ 报状态就是编事实；
2. **与 `DELETE` 分工**：`DELETE` 是「删掉就没了」（幂等 200）；三个动词是「对这一条做一件事」⇒
   目标不在表里、或状态不允许时一律 `409` + 中文短句。**两处状态闸门是必要的**：`:data` 的 `pauseDownload`
   **不看当前状态**（拿一个已完成的任务调它会把那一行改回「已暂停」，之后 `resumeAll` 还会把它重新下一遍）；
   同理对已完成的任务调 `resume` 会把那一集重下一遍。可暂停 = `downloading`/`pending`；可恢复 = `paused`/`pending`；
   取消任意状态都行。

**门禁**：`:server:test` **BUILD SUCCESSFUL in 7m 9s**，**170 例 / 0 失败**（`ServeDownloadRoutesTest` 4 → 5 例）。

### 8.7 本次拍定的三条口径（2026-10-21，用户）

| 题 | 定案 | 备注 |
|---|---|---|
| B-10：web 的账号命名空间 | **丙：端内数据搬到 serve 侧** | ⚠️ **有硬障碍**（见下） |
| B-9：web 的档案带上 serve 侧那几族 | **丙：浏览器异步多走几跳**（导出前先 fetch） | 依赖上一条的形状 |
| C-10：MFA 弹窗的手机号 | **端打码手机号**（`138****0000`） | 首次让手机号进响应 ⇒ §5.2 要加一条 |

⚠️ **B-10 = 丙 的硬障碍（必须记着，别当成“还没做”）**：web 的端内数据全都压在 `keyValueStore(name)` 上，
而 **`KeyValueStore` 是同步接口**（`getString` / `putString` / `getBoolean` …）—— 浏览器里只有 `localStorage`
能同步实现它。搬到 serve 意味着每次读写都是 HTTP 往返 ⇒ **接口必须改异步** ⇒ 动的是 `:shared:model` 的
公开面（三端 + 全部测试）。所以**可行的丙 = 只搬那些本来就是 `suspend` 接口的族**（例如 `CustomCourseStore`
全家都是 suspend），同步接口支撑的那些（`ScheduleCacheStore` 等）留在浏览器并如实记账；
要“连缓存也搬”，得单独拍一次 `KeyValueStore` 改异步（三端公开面）的代价表。
本轮的施工顺序：先做与它无关的批 2–5（下载族 / 三屏 / C-2 web 半），B-10 与 B-9 的形状单独出代价表。

### 8.8 批 3（2026-10-21 晚）：web 的下载管理屏真屏化（W-E2 的第一屏）

**背景**：`DownloadManager` 是 web 那三条「门在、屏不在」里的一条（`NotPortedScreen`）。端点齐了（§8.6 补完三个动词），
缺的是**屏**。

**关键设计（会议 #9「不做影子版」的落地方式）**：屏原来直接收一个具体的 `DownloadManager` —— 那是「本机引擎 +
本机登记表」的装配，浏览器里造不出来；在浏览器里造一个假引擎**恰恰就是影子版**。所以抽了一层窄接口
**`DownloadsHost`**（`:feature:lms`）：

- Android / 桌面：`DownloadManager` 自己实现它（语义一字未改；只有 `resumeDownload` / `resumeAll` 声明成 `suspend`，
  它们本来仍是「丢一个协程就返回」）；
- web：`WebDownloadsHost`（`:web`）走 §5.13 的 `/api/downloads`，取数用 `ServeDownloadsApi`
  （住在 `:feature:lms`，因为这一族的模型就在那里，而 `:shared:network` 看不见 `:feature:*`）。

**三处如实差异**（屏上按宿主能力画，不画假按钮）：没有推送 ⇒ 轮询；目录那一格要等第一次列表；**不画播放按钮**
（文件在运行 serve 那台机器上）。另加一条**错误通道** `DownloadsHost.notices`（默认空流）：serve 那一份用它报
`409` 与网络故障，屏弹一张一次性提示；**本机那一份永远不发**，所以那张弹窗在 App / 桌面上永不出现。

**顺带修的两处**（都不改语义）：① 屏的 `loadTasks()` 原来分两发（任务 / 计数）—— 合成一次 `snapshot()`，
任务与计数不会一前一后对不上，serve 那一侧也少一半请求；② 屏原来**没有任何错误面**（本机那份的取数几乎不会失败），
web 上「点了没反应」不可接受 ⇒ 加了 `notices` + `DownloadNoticeDialog`。

**门禁**：`:feature:lms:jvmTest` **22 → 26**（新增 `ServeDownloadsApiTest` 4 例，`MockEngine`）·
`:web:wasmJsBrowserTest` **69 / 0**（`WebRouteCoverageTest` 的差集 4 → **3** 条）· `:shared:network:jvmTest` 109 / 0。

### 8.9 批 4（2026-10-22）：web 的教材全文库两屏真屏化（W-E2 第二、三屏）

**背景**：`Jiaocai1` / `Jiaocai1Reader` 是 web 那三条「门在、屏不在」里的另两条（`NotPortedScreen`）。
端点上一批（§8.2）已经齐了九个（含两条二进制），缺的是**屏**：`:feature:jiaocai` 那两份屏与 Android /
桌面是同一份，但它们要的 `Jiaocai1Source` / `Jiaocai1ShelfStore` 在浏览器里没有实现。

**落地**（细节读 `docs/api-contract.md` §5.14 末尾那段「消费方」）：

1. `ServeJiaocai1Api`（`:feature:jiaocai`，与它的模型同模块 —— 同 `ServeDownloadsApi` 那条）
   包住七条 JSON 端点；`page` / `cover` 只给 `pagePath` / `coverPath`（路径构造），字节由 `:web` 取。
   两处**刻意**：`nodes` 不是数组就报错（空分类树与形状漂了不是一件事）；页表不是 8 组也报错
   （`Jiaocai1Paging` 会整体错位，宁可当场炸）。
2. `WebJiaocai1Source` / `WebJiaocai1ShelfStore`（`:web`）：页图 / 封面走**同源 `fetch`**
   （`ArrayBuffer` → URL-safe Base64 → `ByteArray`，复用 `:shared:model` 的 base64 缝，不写第二份）；
   书架是**冷流**（收一次先拉一份），写端点回整份 ⇒ 直接覆盖。`prefetchWindow` / `evictBook` 空操作
   （预取是「一台设备上本地磁盘 + 内存 LRU」的优化，本层不为它多开端点）。
3. **顺带给 `Jiaocai1ShelfStore` 补了两个带默认实现的成员**（`refresh()` / `notices`）：
   书架取数失败时屏上画 `ErrorState` + 重试，而不是「书架还是空的」（那四个字在 serve 形态下是**编事实** ——
   库里有几本书只是这一发没读到）。Android / 桌面那两份**一个字节没改**（默认空实现 / 空流）。
4. `AppRoute.Jiaocai` 那一屏也把全文库那一半真接上了（它原来那句「书架与全文库两栏不画」的说明随之改写）。

**⚠️ 一处对交接文件预期的更正（如实记）**：交接文件 §3.1 写「`WebRouteCoverageTest` 的差集 3 → 1（只剩 Browser）」，
**实际只到 2**：`AppRoute.Jiaocai1Reader` 的 `id` 恒带 ssno（`jiaocai1_reader/<ssno>?title=…`），没有定值形态，
而 `openableRouteIds` 是一份**定值 id** 的清单（`AppRoute.Lms()` 能进清单正是因为它有「无参数」那一形态）
⇒ 它的门在 `Jiaocai1` 那一屏内部（书架与检索结果的「阅读全文」），**不进清单**。所以：

- 差集 = `[Browser, Jiaocai1Reader]`，两条都在 `WebSupport` 文件头里写了理由（前者「不是一屏」，
  后者「清单只认定值 id」）；
- 第 3 条断言顺带**换成了更紧的形态**：`AppPage` 里多出清单的分支必须**正好等于差集**
  （原来写死 `listOf("Browser")`）—— 「每个分支要么有门、要么在差集里且写了理由」，比原来更不容易漏。

**门禁（2026-10-22，独占 + `--max-workers=1`）**：照 `.github/workflows/core-gate.yml` 那张表 + `:app:testDebugUnitTest`
与 `:server:test` 全量跑一遍 —— **`BUILD SUCCESSFUL in 10m 17s`**，635 个任务（101 executed / 534 up-to-date）；
复算的逐模块数见 `docs/desktop-port-plan.md` §14.8：**25 个测试任务 / 1783 条用例 / 0 失败**（1769 → 1783，
+14 全在 `:feature:jiaocai`）。本批相关的那几项：`:feature:jiaocai:jvmTest` 16 → **30**（新增 14 例）·
`:web:wasmJsBrowserTest` **69 / 0**（差集 3 → 2）· 三个编译面 `:core:compileKotlinWasmJs` / `:desktop:compileKotlin` /
`:app:compileDebugKotlin` 全过。

**仍未做完**（本批之后的入口，顺序不变）：C-2 的 web 半 + B-4/B-5（**下一批**，端点齐了而客户端一个引用都没有）、
13 条 serve-only 屏的**真接线**（`WebScheduleHost` 那几格全是空的）、B-10/B-9 代价表、C-10（端打码手机号）。

### 8.10 批 5（2026-10-22）：C-2 的 web 半 + B-4/B-5（W-E2 主体）

**背景**：契约 §5.12（2026-10-20 落地的 C-2 端点批）那几条端点以前**一个客户端引用都没有**（grep 过：
`attendance/{stats,streams}`、`POST jwxt/evaluations/submit`、`lms/{due,course-match}`），
而 `WebScheduleHost` 那几格全是空的（`courseLinks = null`、没有 `dueHomeworks` / `attendanceMarks` /
`lmsCourseFor` 覆写）⇒ 思源下钻 / 考勤角标 / 本次考勤 / 作业截止四格在 web 上一个都不画。

**落地**（细节读 `docs/api-contract.md` §5.12 末尾那段「消费方」）：

1. **考勤统计与打卡流水**（`CampusAttendanceApi`）：两条端点接上 ⇒ `AttendanceCapabilities` 三项全 `true`，
   考勤屏的「统计」与「打卡流水」两栏**真画**。⚠️ 一处接口改动：`AttendanceSource.computeCourseStatsFromRecords`
   **改成 `suspend`** —— 算路在 `:data`（只有 JVM/Android 变体，浏览器里拿不到），serve 那一份必须打端点；
   本机那两份实现只是加一个关键字（调用方 `AttendanceViewModel` 本来就在协程里），语义一字未改。
   两条端点只认学期号（不认一对日期）⇒ 用 `/records` 回显并缓存的那个 `term`。
2. **评教提交**（`CampusJudgeApi`）：serve 形态下 `canSubmit = true` + `judge()` 走
   `POST /api/jwxt/evaluations/submit` ⇒ 评教屏那个「一键评教」块真能用（标题从「学生评教（只读）」改回
   「本科评教」）；`submitted:false`（已被评过）也是成功（契约写明那一档幂等）；`409` 被屏按「这一门失败」计数。
3. **日程页四格**（`WebScheduleHost` + 新文件 `WebCourseLinkAccess`）：`courseLinks`（思源下钻走
   `/api/lms/course-match`；考勤角标与「本次考勤」走 `/api/attendance/records` + `:model` 的 `attendanceMarksOf`）、
   `dueHomeworks`（`/api/lms/due`）、`attendanceBadgeEnabled`（与 App/桌面同一个设置键，默认开）。
   `honored` 里的考勤 / 思源下钻 / 作业截止三档**只在 serve 形态下给**（campus-api 没有这三族端点）。
   学期代码→学期号要换一次（`/api/attendance/terms`）；下钻取数失败 = 那一格不画（屏上那两处在
   `LaunchedEffect` 里裸调，不能抛）。

**⚠️ 两处顺带的如实记账**：

- 日程页顶那条缺口说明（`WebScheduleGapNote`）与考勤屏那条（`ToolboxWebApp` 里的 `WebGapNote`）都改掉了：
  它们原来写着「这几栏本端拿不到」，现在按形态分了两段（serve = 已接上；campus-api = 那一形态没有端点）。
- `:data` 的算路与 serve 的算路是同一份（服务端调的就是它）⇒ 统计那一栏不存在「第二份口径」。

**仍未做完**（本批之后的入口）：成绩单两段式（`dzpz/transcript/{submit,preview,download}` —— **下一批**）·
13 条 serve-only 屏里剩下的零碎接线 · B-10/B-9 代价表 · C-10（端打码手机号）· C-1c（等 #147）。

**门禁（2026-10-22，独占 + `--max-workers=1`）**：照 `.github/workflows/core-gate.yml` 那张表 + `:app:testDebugUnitTest`
与 `:server:test` 全量跑一遍 —— **`BUILD SUCCESSFUL in 15m 3s`**，631 个任务（136 executed / 495 up-to-date）；
复算：**25 个测试任务 / 1789 条用例 / 0 失败**（1783 → 1789，+6 全在 `:shared:network`）·
`:web:wasmJsBrowserTest` **69 / 0** · 三个编译面（`:core` wasmJs / `:desktop` / `:app`）全过。

### 8.11 批 6（2026-10-22）：成绩单两段式（`dzpz/transcript/{submit,preview,download}`）

**背景**：serve 契约 §5.12 的最后三条端点也是「端了没接」—— `CampusTranscriptApi` 只填得了第一步
（`canRunWorkflow = false`），屏上那块于是画一张缺口卡，写「六步刻意不投影」。本批把它接完。

**落地**（细节读 `docs/api-contract.md` §5.12 末尾那段「消费方」）：把三发 HTTP 映射到屏上那七步，
**屏与 `TranscriptViewModel` 一行未改**：

| 屏上的步骤 | serve 里对应什么 |
|---|---|
| Step 1 加载表单 | `GET /api/dzpz/transcript`（未变） |
| Step 2 学籍联动 | **空壳**（服务端在建单那一发里自己跑，返回值里有学号 ⇒ 不投影；后续几步也不读它） |
| Step 3 保存申请 | `POST …/submit`（第一段；`created:false` 也成功） |
| Step 4 生成文件 | `POST …/preview`（第二段：生成 + 签章 + 取下载信息一口气做完，回一次性票据） |
| Step 5 签章 / 第二次提交 | **没有单独的端点** ⇒ 原样交回上一步的结果，**不发请求** |
| Step 6 取下载链接 | 交回 preview 存下的三格（上游 host / link 永不出现在响应里） |
| Step 7 下载 PDF 字节 | `GET …/download?ticket=`（`application/pdf` 那条例外） |

两处刻意：① Step 7 **先看状态码与内容类型** —— 失败那一半回的是信封（JSON），不把一段 JSON 当 PDF
交上去（否则屏上会「成功」地存出一个假的 .pdf）；② 保存 PDF 交给**浏览器自己下**
（新文件 `WebTranscriptSaveSink`：`Blob` + 下载锚点），目标位置是浏览器的下载目录 ⇒ 屏上那行
「将保存到 X」不画（与 `WebArchiveActions` 同一条）。

⚠️ 契约要求「消费方按钮文案必须写清这一步真的会在教务留一条待审批记录」—— `:web` 那一支的缺口说明
与 `TranscriptScreen` 的 KDoc 都改了（原来那句「serve 只端了第一步」已不成立）。

**⚠️ 一处「以前只编过、没跑过」的缝，本批真跑了**：Step 7 走的是 Ktor 的 `bodyAsBytes()` ——
全仓此前没用过它，`:web` 那条线只编得过。本批给 `:web` 的 wasmJsTest 加上 `ktor-client-mock`
（wasmJs 上可用），新写 `WebServeBytesTest` 在**真 Chrome** 里读回四格字节 ⇒ 这条缝现在有运行时证据
（而不只是「编译过了」）。

**用例**：`:shared:network` 新增 `ServeTranscriptWorkflowApiTest` **5** 例（两段式逐发、幂等、票据过期、
409、campus-api 闸门）；`ServeReadonlyFourDomainsApiTest` 里那一条改成钉相反的事实；
`:web` 新增 `WebServeBytesTest`（浏览器里真读字节）。

**仍未做完**（本批之后的入口）：13 条 serve-only 屏里剩下的零碎接线 · B-10/B-9 代价表 ·
C-10（端打码手机号）· C-1c（等 #147）。

**门禁（2026-10-22，独占 + `--max-workers=1`）**：照 `.github/workflows/core-gate.yml` 那张表 + `:app:testDebugUnitTest`
与 `:server:test` 全量跑一遍 —— **`BUILD SUCCESSFUL in 12m 37s`**，635 个任务（128 executed / 507 up-to-date）；
复算：**25 个测试任务 / 1795 条用例 / 0 失败**（1789 → 1795：`:shared:network` +5、`:web` +1）·
`:web:wasmJsBrowserTest` **70 / 0**（新增那条就是 `WebServeBytesTest`）· 三个编译面全过。

### 8.12 #150 rebase 到 dev（2026-10-22，#142 合了之后的例行动作，但这次不是例行）

**为什么做**：交接文件 §3.4 的发布链是「`#142` 合 → `#150` rebase 到 `dev` 并改 base → `dev→main` 出 5.2.0
（顺序不能反）」。`#142` 已在 2026-10-21T09:42Z 合并（`7042ed41`），`#147` 也早就合了（09:23Z）⇒ 该做这一步了。

**⚠️ 发现：分支被另一条线重写过（如实记）**。准备推送时 `ls-remote` 核对发现远端 `chore/parity-a-batch`
的 tip 是 `f85c272a`，而它**与本地没有任何共同提交**（merge-base 就是 `dev` 的 `acd79744`）——
即在我最后一次成功推送（`5c77dd97`）之后，有另一条会话/机器把这条分支重写成了 6 个新提交
（`139fe96f` … `f85c272a`，committer 时间全是 2026-10-21 17:32），**我的推送被抹掉了**。

核过两边的内容：那 6 个提交里，**与 A 批相关的文件与我那一版逐字节相同**（我四个提交涉及的文件逐个比过），
另多出 21 个文件是**别的线的工作**（首页杂志版式、App 更新器、Windows 打包 workflow、lms 时间格式、
`artwork/previews/home`）—— 也就是说：重写把 A 批的改动带过去了，另外拼了别的分支的活。

**做法（没有 force-push 掉别人的东西）**：先把本地四个提交原样 rebase 到那一条的 tip 上（快进），
再整体 rebase 到新的 `dev`。结果：`#150` 现在 = `dev` + 6 个 A 批提交 + 我的批 6（共 7 个）。

**rebase 到 dev 时解了 3 处冲突**（dev 那 43 个提交里有两个跟 A 批改了同一块地方）：

| 文件 | 两侧要的是什么 | 怎么解的 |
|---|---|---|
| `feature/schedule/.../ScheduleScreen.kt` | dev 把 `campusNow` 带进了 `buildUpcoming` / `ExamCountdown.next`；A 批新加了「作业截止没接就带一句缺口说明」 | 取 dev 的新签名 + 保留 A 批那三行（注释写清了） |
| `shared/model/.../SettingsStore.kt` | dev 新加了自定义主题色三格（`customColorHex` / `customColorEnabled` / `themeSeedHex`）；A 批新加了 `defaultTab` 规范化的 KDoc 与实现 | 两边都留（主题色在前、`defaultTab` 那段 KDoc 在后） |
| `web/.../WebSettingsHost.kt` | dev 多列了 `DYNAMIC_COLOR`；A 批多列了 `DEFAULT_TAB` / `NAV_STYLE` | 五条合并 |

⚠️ **用了一个非默认的 merge 策略**：`git rebase -Xignore-all-space`。原因：A 批那个提交把 desktop 的
`when(target)` 整块包进了 `Column { }`（整体多缩进一级），默认策略下 `ToolboxDesktopApp.kt` 报了一个
「一侧看起来删了 95 行」的巨型冲突，而两边其实只是**缩进不同**。带 `-Xignore-all-space` 之后那处自动合上了，
只留下上面那 3 处真冲突。风险已知：Kotlin 里空白不参与语义（原始字符串除外，而那几处逐字比过）。

**门禁（基底已是 dev，独占 + `--max-workers=1`）**：`BUILD SUCCESSFUL in 35m 49s`，635 个任务
（248 executed / 387 up-to-date）；复算：**25 个测试任务 / 1813 条用例 / 0 失败**（1795 → 1813，+18 全来自
dev 新合进来的那些 PR）· `:web:wasmJsBrowserTest` **70 / 0** · 三个编译面全过。

⚠️ **跑完上面那一遍之后 `dev` 又前进了一条**（`3e576195 feat(home): 常用功能可自定义（#161）`，改的是
`:feature:home` 与 `SettingsStore`/`SettingsScreen`）⇒ 又 rebase 了一次（这次零冲突：两边改的
是不同区域），并**再跑一遍全量门禁**：`BUILD SUCCESSFUL in 30m 32s`，631 个任务（218 executed /
413 up-to-date）—— 基线一字不变（**1813 条**，dev 那一条新提交没带测试）。`#150` 的 tip 与 base
（`dev`）至此都是最新。

**给下一个人的两句话**：① 这条分支上**可能还有另一个会话在写**（这次的重写就是证据）：每次推之前先
`git fetch` + `ls-remote` 比对，别拿本地旧 tip 直接推；② 如果要动这条分支的历史，用
`--force-with-lease`，别用 `--force`。

#### 8.12.1 谁是谁（仓库治理的事实，别把角色搞反）

- **仓库主人是 `yeliqin666`**（`xjtu-toolbox-android` 的 owner），本工作区的协作者是 **`Triangle-GitHub`**
  （本机 `git config user.name` 就是它，SSH 推上去也是它：`ssh -T git@github.com` → `Hi Triangle-GitHub!`）。
- ⚠️ **`chore/parity-a-batch` 是 owner 仓库里的分支**（`gh pr view 150` 的 `headLabel` =
  `yeliqin666:chore/parity-a-batch`），不是 fork 里的 —— 也就是说：它的历史归 **owner** 说了算；
  协作者只有写权限，没有「这条分支归我」的默认位分。`#150` 的 **author 是 Triangle-GitHub**（本人），
  base 原本是 `#142` 的分支，2026-10-22 已按交接文件 §3.4 改成 `dev`。
- 两边 agent 的**提交身份都是 Triangle-GitHub**（重写那条线的 author 也是它）⇒ 光看 `%an` 分不出是谁推的，
  只能靠 `ls-remote` 的 SHA 与 committer 时间。
- 推论（写给下一个会话）：**不要与这条分支的另一个会话争** —— 新增工作默认放自己的分支；
  要动 `chore/parity-a-batch` 就先 `ls-remote` 比对、用 `--force-with-lease`，并把
「为什么动」写进提交信息。`#150` 何时合、`dev → main` 何时发版，属于 owner 的决定。
