# 桌面端（Linux / Windows）移植方案 · 决策记录

> 状态：**设计已完毕**（2026-10-09，用户逐条确认）。基线 HEAD = `4a73495`。
> 本文只写"目标 + 决策 + 分阶段 + 验收判据"。每一步的动机若写进 commit message，本文不再复述。

>
> **2026-10-14 追加**：三端「完全一致」的会议决策（口径 = **丙**、写操作全等含真支付、统一存档层、三端多账号、
> serve 持 key + 转发 SSE、serve 内置 HTTPS + 鉴权）见 **§16**。下一批施工顺序也记在那里（§16.3）。
---

## 1. 目标与硬约束

**目标**：在 Android 之外再做 **Linux 与 Windows** 两个平台的客户端，任何人**独立安装后开箱即用**（自带登录、自带数据、不依赖任何我方的服务器）。

| 约束 | 内容 | 怎么守 |
|---|---|---|
| **C1** | **Android 行为完全不变** | 现有硬约束：`:app` 侧只允许出现「搬走声明 + 引用改名」；`749` 个测试（`:core` 225 + `:app` 524）继续全绿 |
| **C2** | 每个平台**独立安装、独立登录**（不是"你自己的机器"） | 数据层随客户端一起分发；**不依赖 campus-api** |
| **C3** | **功能全部等价**（含写操作：约座/抢场/签到/取消/付款码） | 数据层必须包含写路径 |
| **C4** | 跨平台复用同一份 UI 与业务逻辑 | 屏继续进 `:core`；目标 **40/40**（见 §6.4） |

**C2 的必然结果**：campus-api 从"数据源"降级为"我自己的自动化中枢"（定时采样 / 推送 / 归档 / 给 AI 供工具），**与三端交付无关**。

---

## 2. 已拍板的六条决策

| # | 决策 | 备注 |
|---|---|---|
| **D1** | 桌面端的**本地后端复用 App 的 Kotlin 数据层**（把 `:app` 那 33 个 `*Api` + 会话内核摘掉 Android 依赖，抽成 JVM 可编的模块） | 三端一份取数；Android 继续进程内跑同一份 |
| **D2** | 可移植性**先只到 JVM**（okhttp + jsoup 原样使用，与 Android 现在一样） | iOS/HarmonyOS 以后再谈；那时才需要换 Ktor/多平台解析 |
| **D3** | **Linux 交付 = 一个 JVM 包、两种运行模式**：默认开原生窗口（本地用户）；`serve` 模式无窗口、托管 webui + `/api/*`（远程/无头部署，浏览器访问） | 见 §3 |
| **D4** | **Electron 从设计中去掉** | 它的两个作用分别被"窗口模式"和"服务模式"覆盖；省一个运行时 + 签名 + sidecar 管理 |
| **D5** | serve 模式的 `/api/*` **重新设计一份干净契约**：以端口/模型的自然形状为准，逐条与旧形状对照 —— 旧的合理就沿用，不合理就改 | 代价：`:web` 那 13 个 `Campus*Api`（约 2.6k 行）跟着改；好处：契约从此归本仓（文档 + 夹具测试），campus-api 的形状只作参考 |
| **D6** | **后台能力只放在 serve 模式**（常驻进程做定时任务 + 系统通知/推送）；窗口模式按需打开，只在打开时显示临近项 | 安卓 WorkManager 的对应物 = serve 进程 |

> 注：**多账号不是决策点**——“同一份代码管理所有客户端”这条原则一确立，安卓有的桌面就有；存储/凭据本来就按账号后缀分（`AccountContext.safeSuffix()`）。只差「账号管理界面」随 Stage C 一起搬。

---

## 3. 架构

```
        :core   屏 / 逻辑 / 模型 / 13 个取数端口 / 19 个平台缝        ← 唯一一份
          │
   ┌──────┴──────────────────────────────┬───────────────────────────┐
   │                                     │                           │
 :app  Android 宿主                  :desktop 窗口模式            serve 模式（同一 JVM 模块的另一个入口）
   进程内直调数据层                   Compose Desktop 原生窗口      无窗口：Ktor 托管 :web(wasm) + 实现 /api/*
   （行为零变化）                     数据进程内直取                数据同一份；浏览器访问

                    ↑ 三种承载共用同一份 ↓
        :data   Kotlin 数据层（从 :app 摘出）= 33 个 *Api + 会话内核 + 存储/凭据的宿主实现
```

- **UI 源码只有一份**：`:core` 的屏。两种承载 = Compose 渲染（窗口模式）/ wasm 渲染（serve 模式，`:web`）。
- **数据层只有一份**：Android 进程内、窗口模式进程内、serve 模式进程内 —— 三个消费者，零实现重复。
- **`:web` 现状保留**：它的定位是"对着自己那台机器的浏览器界面"；serve 模式下它由 `:server` 托管，把 baseURL 指向 `127.0.0.1` 即可，**UI 一行不用改**。

**关于“契约”：它只存在于 serve 模式，而且是物理必然** ——

- **窗口模式**：Compose 窗口与数据层在**同一个 JVM 进程** ⇒ 直接函数调用 ⇒ **零 HTTP、零端口、零 token、零契约**（砍掉 Electron 的直接收益）。
- **serve 模式**：UI 是浏览器里的 wasm（可能在另一台机器），数据层在服务器进程 ⇒ 函数调用跨不了进程 ⇒ **只能用 HTTP**，于是必然有一份“接口长什么样”的约定 = `/api/*` 契约。

### serve 模式的形态（就是现有反代脚本的"换心"）

`web/tools/serve-same-origin.py`（静态产物 + `/api/*` 转发 campus-api）**就是 serve 模式的雏形**，
把"后端"从 campus-api 换成 `:data` 即可 —— 静态托管与同源要求都不变。


**契约口径（D5）**：不照搬 campus-api 的形状，而是**以端口/模型的自然形状重新设计一份**，逐条与旧形状对照 ——
旧的合理就沿用（这样 `:web` 对应那些实现可以留着），不合理的就改。`/api/*` 从此是 `:server` 的对外接口，
文档 + 夹具契约测试都在本仓；campus-api 只作参考，不再是依赖。
**默认安全口径（这条归我们管）**：
- 默认只监听 `127.0.0.1`；
- 首次启动生成并**打印访问令牌**，浏览器侧必须带；否则同机任何进程扫到端口就能用该用户的校园账号；
- 对外暴露必须显式 `--host 0.0.0.0`，文档写明"自己加反代 + 鉴权，风险自负"。

---

## 4. 为什么不是别的形态

| 形态 | 结论 | 原因 |
|---|---|---|
| **PWA** | ✗ 不能作为交付形态 | PWA = 纯浏览器 = 起不了进程。要么后端在远端（回到多用户/凭据托管），要么用户另装守护进程（"装上就用"变成装两样）。可作为 serve 模式的附带产物（浏览器直接访问即可） |
| **Electron 壳 + JVM sidecar** | ✗ 已砍（D4） | Windows 用窗口模式即可；serve 模式已覆盖"浏览器访问"。保留它 = 两个运行时 + 子进程生命周期 + 端口/token + 额外签名 |
| **复用 campus-api（Node）** | ✗ | 单账号、回环、零鉴权、只读 ⇒ 违反 C2/C3；且等于把黑盒随身分发 |
| **桌面端去连 campus-api** | ✗ | 同上（那是"你自己的机器"的形态，不是可分发客户端） |

---

## 5. 工作量清单（实测数据）

### 5.1 数据层摘 Android 依赖（**大头**）

| 分档 | 数量 | 说明 |
|---|---|---|
| 干净（可直接跑 JVM） | **7** | `Attendance` `Leave` `Coupon` `GraduateJudge` `Judge` `ScoreReport` `Ywtb` |
| 机械活（只需换 `android.util.Log` → `platformLog`、`Context` 取存储 → `keyValueStore`/端口） | **17** | 替身**这次重构已造好** |
| 真硬耦合（需逐个处理） | **9** | `SchoolCalendarImage`(Intent) `EmptyRoom`(Intent) `Faculty`(DataCache) `Feedback`(`android.os`) `Hello`(DataCache) `Lms`(`android.net.Uri`) `Notification`(Base64) `Holiday`(Intent+DataCache) `Zyxf`(Intent) |

**会话内核**：26 文件 / 5,293 行，其中 23 个碰 Android。耦合分布：`Context` 83 · `android.util.Log` 74 · `CredentialStore` 8 · `SharedPreferences` 6 · `SecurePrefs` 6 · `DataCache` 1。

**必须新写的宿主实现**：
- `keyValueStore` 的 JVM **落盘**版（现在 `jvmMain` 是纯内存 `MemoryKeyValueStore`）；
- 凭据存储（Windows：DPAPI / Credential Manager；Linux：Secret Service 或"文件 + 0600 权限"，与 campus-api 现有做法同口径）；
- Room `DataCache` → 文件/SQLite。

### 5.2 认证 UI 进 `:core`

桌面**有窗口**，所以 `MfaDialogHost`（短信二验）、二维码、每站点登录页都能做 ⇒ **不是"服务端无 UI"那种难题**。
要做的只是把 Android 的认证界面从"Android 专属"改成"共享屏 + 平台缝"。

### 5.3 平台缝（19 个）的第三种承载

`jvmMain` **已经 19 个缝全部有 actual**（18 文件 / 269 行），窗口模式几乎白拿；需"真化"的：
`KeyValueStore`（→落盘）、`Toast`（→窗口内提示）、`Share`（→`xdg-open`/剪贴板）、`Dialer`/`KeepScreenOn`/`Haptics`（→no-op 或平台等价）。
serve 模式走的是**浏览器承载**，对应 `wasmJsMain` 那 18 个 actual —— **也已存在**。

### 5.4 宿主机能族（到 40/40 才需要）

`settings` / `accounts` / `download_manager` / `home` / `notification` / `agent` / `payment_code` 等：
每个都要一份桌面实现（文件系统、窗口、系统通知、keyring…）。小组件无桌面等价物 ⇒ 用系统通知替代。

---

## 6. 分阶段与验收判据

### Stage 0 · 先证伪（1~2 天）

| 做什么 | 验收 |
|---|---|
| 起 `:desktop` 模块（窗口模式），接**现有** `Campus*Api` 当数据源（脚手架，只对开发机可用） | `:core` 的屏在 Linux 桌面能真渲染；MIUIX 桌面观感/鼠标/输入法可接受；`jpackage` 能出包 |
| 同时做**图书馆整条**的数据层 pilot（摘 `Context`/`Log`/存储/图片解码，读+写+会话失效都串一遍） | 同一份 Kotlin 数据层在 JVM 上跑通，输出与 Android 端逐字段一致 |

两个不确定性（UI 观感 / 数据层可移植）都要在 Stage 0 干掉，再决定是否投 A。

### Stage A · 第一个可分发版本

数据层摘出（§5.1）+ 认证 UI 进 `:core`（§5.2）+ `:server` 定义并实现新契约（`:web` 的取数实现跟着改）
+ 窗口模式 + `serve` 模式（含默认安全口径与后台定时任务，见 D6）。
**验收**：任何人在 Linux/Windows 上装上 → 登录自己的账号 → 只读主要屏可用（约 23~27 条路由）。
> **验收记录：见 §12.1**（判据的「登录 + 屏」这一半已达成，比判据写的还多；「装上」那一半只兑现了 Linux 的出包）。

### Stage B · 写路径

图书馆约座/换座/签到/取消、场馆预订/取消、付款码。
**验收**：与 Android 的写能力等价（按钮出现、动作成功）。
> **验收记录：见 §12.2**（三分项都已达成）。

### Stage C · 宿主机能族 → 真 40/40

设置 / 多账号 / 下载管理 / 首页 / 通知。（小组件用通知替代。）
**验收**：40 条路由在桌面都有真实实现（`NotPortedScreen` 归零）。
> **验收记录：见 §12.3 / §14.5** —— **已按判据达成**：`AppRoute` 的 **43** 条声明在 `DesktopPage` 的
> `when (route)`（`ToolboxDesktopApp.kt` 第 568 行起）里**全部有顶层分支**（**43/43**），兜底的
> `else -> NotPortedScreen`（同一文件第 1218 行）**不再可命中**。最后两条是 `AppRoute.Match`
>（`ec3407d6`，第 33 条真数据路由）与 `AppRoute.Browser`（`76b0d530`，这一端的等价物 =
> 「外链中转页」——把地址交给系统浏览器）。
> 但「归零」是**分支覆盖**的说法，不等于「43 条都是真实现」：`AppRoute.GameMerge` 是一条
> `HostUnavailableScreen` 如实说明页（卡的是**载体** —— 桌面没有 WebView，见 §12.3 末段），
> 它**不在** `DESKTOP_SUPPORTED_ROUTES` 里（那张表今天 **40** 条）。
> 口径提醒：这里的「40 条」是当初的估计（§6.4 原文用的就是「**≈** 40」），今天的数字以 §12.3 / §14.5 为准。

### 6.4 关于"40/40"

- 40/40 **不是跨平台的前提**，跨平台的前提是"屏在 `:core` + 数据层可移植"。
- 但**桌面端比浏览器端容易得多**：现在搬不动的十几条，卡的是 **campus-api 的能力**（只读/脱敏/没接模块），
  而不是平台 —— 桌面端自己登录、自己直连，**"中枢没数据"那 4 条（成绩单/考勤/加餐券/人脸考勤）重新可做**，
  宿主机能族也都有桌面等价物。所以桌面的可达上限 ≈ 40，而不是浏览器的 ~27。

---

## 7. 待定项（不阻塞开工）

Windows 代码签名（否则 SmartScreen 报警）· 更新机制（没有商店）· EULA/隐私说明的复刻 ·
Windows 代码签名（否则 SmartScreen 报警）· 更新机制（没有商店）· EULA/隐私说明的复刻 ·
WebVPN 在桌面的复用（校外访问必需）· 桌面通知/托盘 · serve 模式启动后要不要自动开浏览器
（用户在 2026-10-09 那一轮明确「留到做 serve 模式那一轮再定」）。

### 7.1 Linux 交付形态（**2026-10-09 已定**）

**三样都出**：`.deb` + app-image / tar.gz。

| 形态 | 任务 | 给谁用 |
|---|---|---|
| `.deb` | `./gradlew :desktop:packageDeb` | Debian / Ubuntu（apt 管依赖与卸载） |
| app-image（免安装目录） | `./gradlew :desktop:createDistributable` | 本机试跑；也是 tar.gz 的输入 |
| `tar.gz` | `./gradlew :desktop:packageTarGz` | 无 dpkg / 不能用 FUSE 的机器：解压后直接跑 `bin/xjtu-toolbox` |

⚠️ **一个口径说明**：Compose Desktop 的 `TargetFormat.AppImage` 是 **jpackage 的 `app-image`**
（一个自带运行时的目录），**不是** AppImage.org 那种单文件 `.AppImage`。
真正的单文件 AppImage 要额外一步 `appimagetool`（而且它依赖 FUSE）—— 实测那台机器上没装，
所以**真·单文件 AppImage 仍待定**（需要它时再加一条任务，与 `packageTarGz` 同形）。

三个产物都带 jlink 运行时，体积约 100～190MB（tar.gz 115MB）。

---

## 8. 风险与守则

1. **C1 怎么守**：Android 走的仍是"进程内直调"；数据层模块化时，`:app` 侧只允许出现"引用改名 + 搬走声明"，其余一行不动 —— 与本次重构同一条验收法（`git diff` 看 `:app`）。
2. **`/api/*` 契约要锁**：serve 模式的 `/api/*` 是新的对外接口（`:web` 消费者）。建议把现有 `PayloadShapeTest` 升级为**夹具契约测试**（同一份上游样本 → 数据层输出逐字段断言），避免"静默漂移"。
3. **凭据是分发的代价**：桌面要自己存用户的校园网密码（与 Android 同一件事），必须做**每平台凭据存储 + 隐私说明 + 退出登录即清除**。
4. **不要为了"更深"而动**：网络栈迁移（okhttp→Ktor）、40/40 之外的架构清洁，都不在这条路上（D2）。

---

## 9. Stage 0 结果（2026-10-09 实做）

两个不确定性都干掉了，结论：**继续投 Stage A**。

| 验收 | 结果 |
|---|---|
| `:core` 的屏在 Linux 桌面真渲染 | ✓ 无显示服务器 ⇒ 用 `:desktop:renderScreens`（`ImageComposeScene`，同一套 skiko 渲染栈）离屏出图；**真数据**那张是 campus-api 的图书馆座位屏（座位格 + 区域 + 统计都画出来了）。截图：dufs 的 `v21-desktop-*` |
| 数据层在 JVM 上跑通 | ✓ 图书馆整条搬进 `:data`（jvm + android 共用一份），`LibraryDataLayerJvmTest` 起本地 HTTP 服务器跑完读+写+会话失效+图片字节（13 例全绿） |
| 「同一份样本 → 两端模型相同」 | ✓ 由**同一份实现**保证（`:app` 只是多一个 `AppLibrarySession` 适配器）；上游原文形状另有夹具单测钉住 |
| `jpackage` 能出包 | ✓ `createDistributable` 与 `packageDeb` 都成功（`xjtu-toolbox_5.1.1_amd64.deb`，含 jlink 运行时约 100MB） |

**顺带定下来的两件事**（都已落进代码注释）：

1. **`KeyValueStore` 补了 `getStringSet`/`putStringSet`**：Android 那份就是 `SharedPreferences` 的同一文件/键/值类型（老收藏不丢），Web 沿用原来那个逗号串。于是「座位收藏」从两个端口实现收回了共享的 `LibraryFavorites`——**存本地偏好不再需要一个取数端口来转发**。
2. **`:data` 的会话缝**（`LibrarySession`）：`fetch` / `client` / `authExpired(siteName)` 三个成员，宽度就是 `LibraryApi` 真正用到的那三处。`:app` 的 `AuthExpiredException` 改为继承 `:data` 的 `SessionExpiredException`（类名/构造/文案一字未变）——数据层于是能按类型认「会话失效」，而 `:app` 里按那个类分支的调用点行为不变。

**环境前置**（新机器/重装后必做）：`apt install libgl1 libglx0 libegl1`。缺它时 skiko 的 `libskiko-linux-x64.so` 连 `dlopen` 都过不去，`renderScreens` 与真窗口都会失败在加载动态库上（报错里出现 `libGL.so.1: cannot open shared object file`）。

**这一轮没做的**（不在 Stage 0 范围内）：网络栈迁移、40/40 之外的清洁、serve 模式、桌面登录（Stage A 的 `:data` 会话内核）。

---

## 10. Stage A 第一步结果（2026-10-09 实做）：会话内核进 `:data`

§6 Stage A 的第一件事 —— **数据层摘出 Android 依赖**里最硬的那一块（会话内核）—— 做完了，
而且桌面端已经能**用自己登录的会话**跑通图书馆那条竖切。

| 验收 | 结果 |
|---|---|
| 会话内核在 JVM 上编过并真登录 | ✓ `SiteSession` / `SessionBackend` / `SessionManager` / `XJTULogin` / `CasGate` / `CasSiteSession` / `SiteSnapshots` / `Jwt` / `LibraryLogin` 等搬进 `:data:commonMain`（jvm + android 共用一份） |
| 「同一批断言、换成真会话」 | ✓ `LibraryLoginSessionJvmTest`（6 例，`:data:jvmTest`）：真 CAS 表单 POST（密码 `__RSA__` 加密，夹具用私钥解开比对）→ 种 TGC → 签 ticket 回跳 → 站点会话；冷启动从落盘快照恢复且不重登；读/写/会话失效自动重登重放，断言与 Stage 0 那条逐条相同 |
| Android 行为不变 | ✓ `:app` 侧 diff 只有「搬走声明 + 引用改名」（`initDataPlatform`、`SessionManager()`、`PersistentCookieJar(name)`、`SessionBackend.wipe(suffix)`、一个 `onAccountSwitched` 钩子）+ 一处已论证的参数补救（见下）；`:app` 测试 521 → 515（6 条测试随被测代码搬进 `:data`） |

**落地手法（下一步照抄）：**

1. **同包搬**：`:data` 用与 `:app` **完全相同的包名与类型名**，于是 `:app` 侧几乎不用改 import；
   `register(com.xjtu.toolbox.auth.LibrarySession())` 这种调用点在站点类搬迁时一行未动。
2. **`android.*` 只换三个东西**：`Log` → `:core` 的跨端 `Log`；`SystemClock.elapsedRealtime` → 新缝
   `elapsedRealtimeMs()`；`Base64` → `java.util.Base64`（`getMimeDecoder`/`getEncoder`，字节结果同）。
   `android.os.Handler(HandlerThread)`（cookie 防抖写盘）→ `ScheduledThreadPoolExecutor`。
3. **存储缝而不是 `Context`**：`secureKeyValueStore(name)` / `wipeSecureStore(name)` 两端各一份 actual
   —— Android 就是原来的 `SecurePrefs` **同名文件**（`cookies_normal_default` / `sites_normal_default` …），
   桌面是 `0600` 权限的 Properties 文件。于是 `SessionBackend.create(mode, suffix)`、
   `SessionManager()`、`PersistentCookieJar(name)` 都不再需要 `Context`。
4. **宿主能力留在宿主**：`CampusProbe`（要 `ConnectivityManager`）没跟内核搬，
   内核需要它的**只有一处**（切账号时清一网通办令牌）⇒ 收成一个 `SessionManager.onAccountSwitched` 钩子，
   由 `:app` 注入。这正是「缝照真正用到的那几处切」。
5. **测试跟着代码走**：`internal` 是模块级的，`SiteSnapshotsTest`(4) 与 `CookieHostMatchTest`(2) 随被测代码进 `:data:jvmTest`；
   `InMemorySharedPreferences` 改 public（Android-only，JVM 跑不了）。

**一处碰生产行为的补救（已论证，待复核）**：`LibrarySession.createLogin` 现在把管家的
`cachedRsaKey` 传给 `LibraryLogin` —— 搬迁前图书馆站点漏传（17 个站点里只有它和 campus_card 漏），
于是每次登录都白取一次静态公钥（`GET https://login.xjtu.edu.cn/cas/jwt/publicKey`）。
效果 = 少一次冗余请求，POST 出去的密文与今天逐字节相同，**解不出来时仍会重新取**（`encryptPassword` 的兜底只看函数入参）。
若判定不能动，回退办法是给测试单开一个站点子类。

**测试在 JVM 上真登录的传输手法**：本地 HTTP **代理**（`ProxySelector`）而不是重写 URL ——
会话内核里有一批按 host 判断的判据（`XJTULogin.casPath` 只认 `login.xjtu.edu.cn`、
`LibrarySession.validateLogin` 认 `rg.lib.xjtu.edu.cn`），重写 URL 会让它们全部落空，
测的就不是真内核了。⚠️ 坑：`HttpClients.base` 是进程级 `by lazy`，会把当时的默认 `ProxySelector`
**抄进自己的配置**，所以 selector 必须常驻、读一个可变端口，否则只有第一条测试生效。

**这一轮没做的**：桌面端自身的登录界面与「把 `:desktop` 的脚手架数据源换成 `:data`」
（窗口模式的下一步）；`Sites.kt` 里其余 18 个站点仍留在 `:app`（逐个搬，图书馆已搬完）；
网络栈迁移、serve 模式、40/40 之外的清洁（理由同 §4/§8）。

---

## 11. Stage A 第二步结果（2026-10-09 实做）：`:desktop` 真的用上了 `:data`

交接文档 §3.1 那一步做完了：**桌面端拆掉 campus-api 脚手架，改成自己登录、自己取数**。

| 验收 | 结果 |
|---|---|
| 桌面端自己登录 | ✓ `:data` 的会话内核（`SessionManager` + `XJTULogin` + `LibrarySession`）在窗口里跑完整 CAS｜密码 RSA 加密送交、TGC 与站点会话落盘、冷启动从凭据文件静默恢复 |
| 登录屏在 `:core` | ✓ `LoginScreen` + `MfaCodeDialog` 两个哑视图进 `:core`（状态由宿主喂）；`:app` 的登录界面一行未动（红线） |
| 凭据存储 | ✓ `JvmCredentialStore`（`:data:jvmMain`）：**文件名与键名与 `:app` 的 `CredentialStore` 逐字相同**，落成 `0600` 的 Properties 文件；退出登录把凭据 + cookie + 站点快照一起删 |
| 图书馆屏真取数 | ✓ `:core` 的 `LibraryScreen` + `DesktopLibrarySource`（`:data` 的 `LibraryApi` + 真 `LibrarySession`）—— 读/写/平面图都在 |
| 其余路由 | ✓ 不再借 campus-api 画画面，而是 `NotPortedScreen` 如实列出「屏在了、取数还在 `:app`」（11 条） |
| Android 行为不变 | ✓ `:app` / `:web` **diff 为空**（本轮改动只落在 `:core` 新增文件、`:data` 新增文件、`:desktop`、新模块 `:testkit`） |
| 证据 | ✓ `:desktop:renderScreens` 出 7 张图：`login.png` / `library-after-login.png`（**真登录后的座位图**，含当前预约 A01 与平面图）/ `calendar.png` / `yellowpage.png` / `shell-after-login.png` / `routes.png` / `library-demo.png` |
| 装配验收测试 | ✓ `:desktop:test` 的 `DesktopAuthLibraryJvmTest`（**10 例**，含免登录的校历与黄页）：真登录、错密码不落盘、读路径、冷启动免密、会话失效重登重放、退出即清除 |

**这一轮落下的手法（下一步照抄）：**

**§10 那个待复核的取舍：留（不回退）。** 上一轮给 `LibrarySession.createLogin` 补上了传
`cachedRsaKey`（图书馆与 campus_card 是 17 个站点里仅有的两个漏传的），当时标注“待复核”。
本轮判定**留下**，理由两条：
  - **桌面端就挂在它上面**：`JvmCredentialStore.rsaPublicKey` 缓存了那把静态公钥，本意就是
    登录时不再发那一枪 `GET https://login.xjtu.edu.cn/cas/jwt/publicKey`——而 `cachedRsaKey`
    要在 `LibraryLogin` 这一层被接收，缓存才有意义。回退它等于把桌面凭据文件的这一项变成死字段；
  - 它**不改变密码学结果**：POST 出去的密文与不传缓存时逐字节相同（同一把学校公钥），
    解不出来时 `encryptPassword` 的兜底仍会重新取（只看函数入参，不看缓存状态）。
  Android 侧的请求序列确实少了一次冗余请求——这是**已论证过的小改动**，不是行为漂移。

1. **假上游要能跨模块复用 ⇒ 它得是一个独立的模块**：`LibraryFakeUpstream` / `TestRsaKey` 从
   `:data:jvmTest` 搬到新的 `:testkit`（纯 JVM、零依赖）。理由三条：页面原文只能有一份（改一处所有消费者
   一起动）；Gradle 跨模块共享不了 test 源集（`java-test-fixtures` 对 KMP 不生效，手搬变体属性更脆）；
   **它绝不能进任何交付物**（一个「任何密码都收」的假统一认证不该随 `.deb` 发出去）。
2. **离屏证据生成器跟着假上游待在 test 源集里**：`RenderScreens` 与 `DemoLibrarySource` 因此从
   `src/main` 搬到 `src/test`，`renderScreens` 任务的类路径也换成 `sourceSets["test"]`。
   jpackage 只会打包 `src/main` ⇒ 假上游连影子都不在包里（可用 `dpkg -c` 核）。
3. **桌面端的登录不需要「匿名罐」那一段**：`:app` 先匿名登录、拿到学号再 `adoptAnonymousSession(suffix)`，
   那是因为它**登录前不知道 accountId**；桌面是用户手输学号 ⇒ **先定命名空间再登**（`reconfigureForAccount`
   → `setCredentials` → `ensureSite`）。失败时退回匿名命名空间，绝不把一个没登上去的账号写进 `AccountContext`。
4. **`WindowDialog` 在桌面是一个独立 AWT 窗口**（`androidx.compose.ui.window.Dialog` → `DialogWindow`）
   ⇒ 离屏场景（`ImageComposeScene`）画不进它，没有显示服务器时连创建都不行。所以 MFA 那一屏的证据
   只能由 `:desktop:run` 在真窗口里给 —— MFA 弹窗与 `:app` 那份逐条对齐（同一个 miuix 组件、同一套文案）。
5. **夹具也要能「被解码」**：假上游原来的平面图只有 8 字节的 JPEG 文件头（`getPlanImage` 只认头、不解码，
   够用）。一旦屏上要真把它画出来（`decodePlanImages` → skiko），就只剩一句「平面图解码失败」。
   所以夹具改成用 `ImageIO` 真画 200×300 的 JPEG（底图 + 状态图同尺寸同比例）。
6. **进程级会话状态的隔离要连命名空间一起清**：桌面登录会把 backends 换到账号命名空间
   （`cookies_normal_<学号>`）⇒ 测试里只清 `_default` 等于什么都没清，上一条测试留下的 TGC
   会把「真登录」变成 SSO 直通（`credentialPosts` 恒为 0）。**第一次跑就踩到了。**

### 11.1 同一轮顺带做完的两条：校历与黄页（免登录上游的报偿）

`:app` 的 `SchoolCalendarApi` **一行 Android 都没碰**（`HttpClients.base` 本来就在 `:data`，
模型与解析早在 `:core`）⇒ 同包同类型名搬进 `:data`，`:app` 三处调用点一个字未改。
而它**免登录**，所以桌面端不需要任何站点会话适配器就有真数据。

| 验收 | 结果 |
|---|---|
| 桌面多一屏真数据 | ✓ `calendar.png`：学期切换、第 5 学习周、进度 26%、18 周 / 95 工作日、日程安排（中秋/国庆/元旦）—— 全部来自真解析 |
| 装配测试 | ✓ `:desktop:test` 多一条（共 9 例）：免登录取到两个学期、结束日取 `exam_end`、`specialEvents` 按标题对上、`defaultTermIndex` 选中「今天所在的那个学期」 |
| Android 行为不变 | ✓ 搬迁 = 换目录（+ 一个带默认值的构造参数，见下） |

**这一步得到的一般结论（值得优先做）：**

1. **免登录的上游是最便宜的一批屏**。校历这类接口（还有黄页 / 教师检索 / 公告正文）不需要站点类、
   不需要 `*Login`、不需要会话缝 —— 只要把 IO 适配器搬进 `:data`，三端立刻各多一屏。
   而需要登录的那批（成绩 / 场馆 / 校园卡…）要先搬站点类 + 它的 `*Login`（`Sites.kt` 里 17 个，
   已侦察：**站点类本身只碰 3 处 Android**（两行 `Log` + 一个嵌套 `withContext`），`*Login` 里
   也只有 `android.util.Log` / `android.util.Base64` 两类要换 —— 那些 `com.xjtu.toolbox.util.*`
   的 JSON 助手**早就在 `:core` 了**）。
2. **一个假上游不够用了 ⇒ 要按 host 分派**：`:testkit` 的 `FakeCampusProxy` 用一个代理端口同时扮
   `rg.lib.xjtu.edu.cn` / `login.xjtu.edu.cn` / `workflow.xjtu.edu.cn`（走代理时请求行是
   absolute-form，`requestURI.host` 就是目标域名）。
3. **https 的免登录接口在本地假上游上跑不通**：真机那条是 `https://workflow.xjtu.edu.cn/...`，
   纯 HTTP 假代理给不了 CONNECT+TLS 隧道（图书馆那条能跑通是因为它从 http 的 `/seat/` 开始、
   一路 302 都在 http 上）。所以 `SchoolCalendarApi` 加了一个**带默认值的 url 参数**
   （与 `:core` 里那批 `Campus*Api(client, base)` 同一种形状）；校历解析不看 URL，
   所以这不影响「测的是不是真解析链路」。需要登录的站点**不能**这么办 —— 它们有一批按 host
   判断的判据，必须走代理、URL 一字不改。

**黄页（第三条真数据路由）—— 连搬都不用搬**：`:core` 的 `YellowPageApi` 在第 1 步（okhttp→Ktor）
就已经在 commonMain 里了，而且它的端口收一个 `HttpClient`（调用方一侧）⇒ 桌面端只要给一条客户端
（`cache = null`：那份缓存缝要按账号隔离的落盘实现，属 §5.4）。

| 验收 | 结果 |
|---|---|
| 桌面多一屏真数据 | ✓ `yellowpage.png`：类别标签（教务处 / 学生工作部）、机构行、拨打电话按钮、「收录 3 个校内机构 · 更新于 2026年08月01日」—— 全部来自真解析 |
| 装配测试 | ✓ `:desktop:test` 多一条（共 10 例）：停用的类别/部门被滤掉、按 (sort, id) 升序、电话按 `/` 拆成两个可拨号码 |
| 假上游不用起服务器 | ✓ `:testkit` 的 `YellowPageFixture` 只提供「两条响应体 + 哪条路径对应哪个体」，消费者用 Ktor 的 `MockEngine` 喂进去（`:testkit` 因此**仍然零依赖**，只有 JDK） |

**这条得到的新结论**：`*Api` 的端口形状决定了假上游的形态 ——
**收 `HttpClient` 的（Ktor 接口）用 `MockEngine`**（不起服务器、不受 https 限制、`:testkit` 保持零依赖）；
**吃会话内核的（cookie/302/CAS）必须起服务器 + 代理**。按这个分，下一批屏的成本可以提前估出来。

**这一轮没做的**：`Sites.kt` 里其余 18 个站点仍留在 `:app`（它们各自的 `*Login` + 功能包里的解析类要一起搬）；
校园网判定（WebVPN 网关）在桌面端仍缺席 —— 故**校外直连不上**，属 §5.4；serve 模式、网络栈迁移、
40/40 之外的清洁（理由同 §4/§8）。

---

## 12. Stage A / B / C 验收记录（2026-10-13 清点）

> 这一节把 §6 的三条验收判据与仓库里的事实逐条对上。**分两轮**，读的时候别混：
> ① 2026-10-13 上午的**文档清点轮**（`383b8cf6` → `eaf7a1c7`）：`docs/` 之外一行未改，**没有跑任何 gradle 门禁**，
>    当时的数字只来自 `ls` 与解析 `build/test-results/**` 的 XML —— 那一轮有几处口径写错了（路由分支数、`DESKTOP_SUPPORTED_ROUTES` 的条数、证据图张数），本轮已按实际更正；
> ② 2026-10-13 **04:23–04:49 的最终验收轮**：真跑了全量门禁 + 两个打包任务，命令、产物与未验证项见 **§14**；
> ② 2026-10-13 **04:23–04:49 的最终验收轮**：真跑了全量门禁 + 两个打包任务，命令、产物与未验证项见 **§14.1–§14.4**；
> ③ 2026-10-13 **07:10 起的收尾验收轮**：干净工作区上**独占**重跑 `:web:wasmJsBrowserDistribution`（上一轮的那一红已消失）
>    与最终全量门禁，见 **§14.5**；
> 本节里与 §14 冲突的数字，一律以 **§14.5**（最后一轮真跑）为准。
> 本文档里出现的每个 hash 都核过是当前 `HEAD` 的祖先（`git merge-base --is-ancestor`，本轮 30 个 hash 全过）。

### 12.1 Stage A · 只读主要屏可用 —— 已达成（「装上」那一半另有缺口，见末段）

判据原文（§6 Stage A）：**任何人在 Linux/Windows 上装上 → 登录自己的账号 → 只读主要屏可用（约 23~27 条路由）**。

| 判据 | 结果 | 怎么核的 |
|---|---|---|
| 登录自己的账号（不经过任何我方服务器） | ✓ | 窗口模式用 `:data` 的会话内核自己登（`DesktopAuth`）；`:desktop:test` 的 `DesktopAuthLibraryJvmTest`（**28** 个 `@Test`）钉住真登录（密码 RSA 加密提交 CAS）、错密码不落盘、冷启动从凭据文件静默恢复、会话失效自动重登重放、退出即清除 |
| 取数不借 campus-api | ✓ | `:desktop` 的取数全是 `:data` 的 `*Api`/`*Source`；脚手架在 §11 那一步已拆，只剩离屏证据运行里那个 `DemoLibrarySource`（`desktop/src/test/…/DemoLibrarySource.kt`，只在 test 源集、不进包） |
| 只读主要屏可用 | ✓ 远超判据 | 索引页那张「真能画的路由」表 **40** 条（Stage A 清点那会儿是 37；`Agent` / `Match` / `Browser` 点亮后各多一格）；Stage A 的中间态进度表 `DESKTOP_PENDING_ROUTES` **是空的**（它的 KDoc 里记着这份历史一共长出过 17 条，第 17 条考勤搬完后归零） |
| 「约 23~27 条」这个数字 | 判据过时，不是没达成 | 那个数来自 §6.4 的推理（浏览器端卡在 campus-api 的能力上）；桌面自己直连，落点见下面那张路由账 |
| Android 行为不变（C1） | 本轮未逐提交核 `:app` 的 diff | 各批搬迁提交自己核的（每批都写明 `:app` 侧只搬走 + 引用改名，且 `:app` 用例数只平移不减少）；本轮只核到 `app/build/test-results/testDebugUnitTest/` 的 XML：**429 通过 / 0 失败** |

**路由账（三个数字口径不同，别混；每条都能用一条命令核出来）**：

| 口径 | 数字 | 怎么核 |
|---|---|---|
| `AppRoute` 声明总数 | **43** | `core/src/commonMain/kotlin/com/xjtu/toolbox/nav/AppRoute.kt` —— 40 个 `data object` + 3 个带参 `data class`（`Lms` / `Jiaocai1Reader` / `Browser`） |
| 桌面主壳里出现过的 | **43** | `grep -o 'AppRoute\.[A-Za-z0-9_]*' desktop/src/main/kotlin/com/xjtu/toolbox/desktop/ToolboxDesktopApp.kt \| sort -u` —— 声明集与引用集两边求差都为空（`Agent` 已于 `997929be` 点亮） |
| `DesktopPage` 的顶层分支 | **43** + 一条兜底的 `else -> NotPortedScreen` | 同一文件 `when (route)`（第 568 行起，`else` 在第 1218 行）；把声明集与**顶层分支集**各自提取后求差，**两边都空**（`76b0d530` 点亮 `AppRoute.Browser` 之后）—— 静态脚本与输出见 §12.3 末段。另外三处 `is AppRoute.Browser ->`（第 649 / 834 / 854 行）是**子屏回调**里把外链交给系统浏览器，不是这一层的分支 |
| `DESKTOP_SUPPORTED_ROUTES`（真能画） | **40** | 同一文件那段列表（第 340–443 行，一行一个 `AppRoute to "中文名"`，含 `AppRoute.Lms()` / `AppRoute.Browser()` 这两个带参默认实例）；与声明数差 **3**：`GameMerge`（`HostUnavailableScreen` 如实说明页 —— 43 条里唯一一条）、`Jiaocai1Reader`（二级屏，表里用的是 `Jiaocai1`）与 `Settings`（真分支但不取数）—— 后两条的理由写在 `DESKTOP_SUPPORTED_ROUTES` 与 `:desktop` 的 `DESKTOP_OPENABLE_ROUTE_IDS` 的 KDoc 里 |
| `DESKTOP_PENDING_ROUTES`（屏在了、取数还在 `:app`） | **空** | 同一文件 `emptyList<Pair<AppRoute, String>>()` |
| 提交信息里的「真数据路由」编号 | 到 **33** 为止 | `git log --oneline \| grep 真数据路由`：最大是 `ec3407d6` 的「第 33 条真数据路由」（匹配交友 / `AppRoute.Match`） |

**证据图**：`desktop/build/screenshots/` 共 **41** 个 PNG（`ls \| wc -l`；本轮 `:desktop:renderScreens` 真跑后复核），含 `agent.png` 与 `agent_welcome.png`（`997929be` 新增）。

**门禁数字（本机留下的实测文件）**：`:desktop:test` **80 通过 / 0 失败 / 0 跳过**（18 个 XML，`desktop/build/test-results/test/`，时间戳 2026-10-13 04:10）—— 与 §14 那一轮五个模块的数字出自同一批文件。

⚠️ **「装上」这一半只有 Linux 兑现了出包**：`.deb` / app-image / `tar.gz` 本机真出过（字节数见 §13.1），
但**真装一次、真桌面启动都还没验**（`docs/linux-delivery.md` §7 第 1/2/6/8 条）；
Windows 的 `.msi` / `.exe` **没出过包**（只能在 Windows 上打，见 §13）。
所以这一条判据是「登录 + 屏」这一半达成、「装上」这一半部分达成 —— 分开写，不合起来说成一句「达成」。

### 12.2 Stage B · 写路径 —— 已达成（三分项逐条）

判据原文（§6 Stage B）：图书馆约座/换座/签到/取消、场馆预订/取消、付款码；
**验收：与 Android 的写能力等价（按钮出现、动作成功）**。

| 写路径 | 落地提交 | 桌面这一端怎么通的 | 可核证据 |
|---|---|---|---|
| 图书馆（约座 / 换座 / 签到 / 中途离开 / 中途返回 / 退座 / 切校区） | `4abd1ae`（`:server` 的 `POST /api/library/action`，7 个动作码） | 桌面**不另写一份写实现**：`:core` 的 `LibraryScreen` + `:data` 的 `LibraryApi`（`bookSeat`/`swapSeat`/`executeAction`/`switchCampus`），由 `desktop/…/DesktopLibrarySource.kt` 装配；屏上还多一条只读端没有的能力 —— `reAuthenticate` 真能强制重认证（`ensureSite(..., userInitiated = true)`） | `:data:jvmTest` 的 `LibraryDataLayerJvmTest`（**10** 例，含「写：预约成功落在 my 页」「写：换座后按我的预约的实际座位号判定」「写：取消预约以我的预约消失判定」）与 `LibraryLoginSessionJvmTest`（**6** 例，含「写路径：预约、换座、取消都打在真会话上」）—— 两条都起本地假上游、走真会话；桌面自己那一层钉读路径与重认证（`DesktopAuthLibraryJvmTest`）；证据图 `library-after-login.png`（真登录后的座位图 + 当前预约 A01 + 平面图） |
| 场馆（预订 / 取消，含滑块） | `45a0e50`（`:server` 的 `GET /api/venue/captcha` + `POST /api/venue/book` `/cancel`）；**桌面宿主与 `canBook=true` 是 `184bd527`** | `AppRoute.Venue` 分支：`AppVenueSource(site, canBook = true)` + `captchaHost = DesktopSlideCaptchaHost`（`desktop/src/main/kotlin/…/venue/DesktopSlideCaptchaHost.kt`，拖动编码 `encodeDesktopSlideResult` 在第 229 行）；`/api/venue/status` 的 `canBook:true` 如实 | `DesktopAuthLibraryJvmTest` 里那条「真登录之后：体育场馆走完 OAuth 与 CAS，读场馆列表时段，**并拿到滑块剧本下的成功订单**」（`captchaScript = true` → `captcha()` → `prepareOrder()` → 拖动编码 → `submitBooking()` → `booking.success` + `orderId` + `tobookCalls == 1`），外加单独一条「桌面滑块宿主：拖动编码合成为服务端协议，识别委托共享路径」；证据图 `venue.png` |
| 付款码 | `006e33c` | 借校园卡那个会话（`AppRoute.PaymentCode.loginType = CAMPUS_CARD`）；码阵是新开的跨端缝（`:core` 出纯格子阵列，渲染走 Compose `Canvas`，Android/JVM 的 actual 是同一份 zxing） | `:desktop:test` 的 `DesktopAuthPaymentCodeJvmTest`（**2** 例：真登录取码取券 + 加餐券勾选真落盘）；同一条提交里用 zxing 的**低层解码器**把渲染出的码阵解回同一串数字（这就是「真出码」的判据）；证据图 `payment_code.png` |

⚠️ **一处图注过时（如实记下，本轮不改代码）**：`RenderScreens.kt` 第 98 行与第 415 行的表注仍写着
「这一端 `canBook = false`（滑块控件搬不到桌面）」—— 那是 `184bd527` **之前**的状态；
`184bd527` 加了 `DesktopSlideCaptchaHost` 并切到 `canBook = true`，这两处注释没跟着改。
**这是注释过时，不是行为不一致**（写路径由上面那两条用例钉住）。本轮只允许动 `docs/`，
所以它记在这里，等下次动那个文件时一并改准。

### 12.3 Stage C · 宿主机能族 → 40/40 —— **已达成**（43/43 顶层分支，`NotPortedScreen` 归零）

判据原文（§6 Stage C）：设置 / 多账号 / 下载管理 / 首页 / 通知（小组件用通知替代）；
**验收：40 条路由在桌面都有真实实现（`NotPortedScreen` 归零）**。

**主体已落地**，逐条给提交与证据：

| 宿主能力族 | 提交 | 证据 |
|---|---|---|
| 设置 | `d62a476` | `:core` 的 `SettingsScreen`/`SettingsStore`/`SettingsHost`；桌面偏好落 `secureKeyValueStore("app_settings")`（与 `:app` 同名文件）；`:desktop:test` 的 `DesktopSettingsJvmTest`；证据图 `settings.png`（含「深色模式当场生效」） |
| 多账号 + WebVPN 转换器 | `e605bfc` | `:core` 的 `AccountManagerScreen` + `AccountsController` 缝；`AccountStore` 进 `:data`（同一个 `xjtu_accounts` 文件名与键）；桌面 `DesktopAccounts` 真切/增/删（增账号先探活再落库、失败回滚）；`:desktop:test` 的 `DesktopAccountsJvmTest`（**7** 例）与 `DesktopWebVpnCodecJvmTest`（**5** 例）；证据图 `accounts.png` / `webvpn_converter.png` |
| 下载管理（媒体播放收成端口） | `c909381` | `:core` 的 `DownloadManager`/`DownloadManagerScreen` + 三个端口；桌面用 `:data:jvmMain` 的 `JvmDownloadEngine` 真下载真落盘，队列落 `DesktopDownloadTaskStore`（0600 JSON）；`:desktop:test` 的 `DesktopDownloadsJvmTest`；证据图 `download_manager.png`（先真下载再截图） |
| 首页 + 主壳 | `ced0818`（屏与宿主槽位进 `:core`）+ `70b98c9`（桌面点亮，第 31 条真数据路由） | `:core` 的 `HomeScreen` + 桌面宿主面 `DesktopHomeScreen`/`DesktopHome.kt`；`:desktop:test` 的 `DesktopHomeJvmTest`（**3** 例）；证据图 `home.png`（窄窗）与 `main.png`（900×900 主壳） |
| 付款码 | `006e33c` | 见 §12.2；证据图 `payment_code.png` |
| 我的课表 | `204f290` | `:core` 的 `ScheduleScreen`/VM + 桌面 `DesktopScheduleHost`（课表族缓存与自建日程都按账号落 0600 键值文件）；`:desktop:test` 的 `DesktopScheduleJvmTest`（**3** 例）；证据图 `schedule.png` |
| 教材查询 / 教材全文库 | `7d80438` / `881f5fd` | `:core` 的三栅合并页 `TextbookScreen` 与 `Jiaocai1ReaderScreen`；书架落 `DesktopJiaocai1ShelfStore`（0600 JSON）；证据图 `jiaocai.png` / `jiaocai1.png` / `jiaocai1-reader.png` |
| 电子成绩单 | `cfbc5a7` | `:core` 的 `TranscriptScreen`/VM + `TranscriptSaveSink` 槽位（桌面写用户下载目录）；证据图 `transcript.png` |
| 加餐券 | `ae3c26f` | `:core` 的 `CouponScreen`/VM；证据图 `coupon.png` |
| 宿舍电费 | `99e8df3` | `:core` 的 `DormPowerScreen` + `DormPowerStore`（同名文件同键）；桌面没有内置浏览器 ⇒ `browser` 槽位交给系统浏览器；证据图 `dormpower.png` |
| 考勤 | `d4f78d5` | `:core` 的 `AttendanceScreen`/VM；附件槽位在桌面是空表（没有 SAF）；证据图 `attendance.png` |
| 思源学堂 | `62c709d` | `:core` 的 `LmsScreen` 族 + `LmsSource`/`LmsDownloadSink`/`LmsCacheStore` 三缝；桌面落盘 `DesktopLmsSaver`；证据图 `lms.png` |
| 移动教务成绩 / 人脸考勤流水 / 社区 | `429b650` | 三条路由（前两条走 `DesktopSiteGate`；社区上游是 GitHub，不套 Gate）；证据图 `jwapp.png` / `iclassface.png` / `community.png` |
| 反馈与建议 | `f1e7c3b` | 唯一一条没有校园会话的路由（上游是飞书开放平台）；`:core` 的 `FeedbackScreen` + `FeedbackStore` 本地账本；桌面 `DesktopFeedback` 在窗口起来之前注入凭据；证据图 `feedback.png` |
| 校园公告（只搬数据半，不新增路由） | `0b80244` | `:core` 的 `BulletinRules`/`BulletinStore` + `:data` 的 `BulletinApi`；桌面侧已接上（首页顶部堆叠条 + 冷启动弹框，见 `ced0818` / `70b98c9`） |

| 匹配交友（最后一条「说明页」变真屏） | `51673daa`（算法/模型/屏进 `:core` + 两条平台缝 + `:app` 只留适配器）+ 本提交（桌面点亮） | `:core` 的 `MatchProfile`/`MatchData`/`DiningHabit`/`MatchScreen` + `MatchHost`/`MatchScanner` 两个端口 + `platform/Deflate.kt`/`platform/Base64Url.kt` 两条平台缝（Android/jvm = 原来那份实现，wasmJs 如实没有）；桌面 `DesktopMatchHost`（课表族缓存与「我的课表」同一份）；`:desktop:test` 的 `DesktopMatchJvmTest`（**2** 例）；证据图 `schedule_match.png`（`hasText` 钉住夹具算出来的交集）；`:core:jvmTest` 的 `MatchShareCodeJvmTest`（**4** 例） |
**还有一条不是「没搬」、而是「这一端没有等价载体」**（落 `HostUnavailableScreen`，**不是** `NotPortedScreen`，
也**不在** `DESKTOP_SUPPORTED_ROUTES` 里 ⇒ **不算真实现**）：`AppRoute.GameMerge`（游戏本体是 `:app` 的
`assets/games/merge/` 那套 HTML + JS，跑在 WebView 里 —— 桌面没有 WebView，也没有 JVM 上的 JS 引擎）。
它有一支真分支指向一个**如实说明卡在哪**的页（`HostUnavailableScreen`）—— 那是如实告知，不是「坏了的屏」。

`AppRoute.Match`（匹配交友）与 `AppRoute.Browser`（内置浏览器）这两条「说明页」**都已点亮**，
见下面两节 —— 前者是第 33 条真数据路由（`ec3407d6`），后者在这一端的等价物是**外链中转页**（`76b0d530`）。

**另外七条游戏路由是真实路由**（`playful` 那一批）：合集页 `AppRoute.Games` 与 `Game2048` / `GameBlocks` /
`GameGomoku` / `GameHop` / `GameGo` / `GameXiangqi`。它们是纯 UI、与数据源无关，屏和逻辑三端同一份，
都在 `DESKTOP_SUPPORTED_ROUTES` 里、各有真分支（第 1098 / 1104 / 1105 / 1106 / 1110 / 1111 / 1124 行）。

#### `AppRoute.Agent`（屁岱）—— 已点亮（最后一条）

- **阶段 1** `bed793f6`：配置 / 多会话落盘 / 记忆 / 两条取数搬进 `:core` + `:data`（键值缝 + 数据根目录缝 `dataFilesDir()`）。
- **阶段 2** `97d1d476` + `c6d429d3`：调度循环 `AgentRunner` 搬 `:core`（两处接线收成缝 `AgentTransport` / `AgentTools`）；
  工具层与屏（`AgentScreen` 一族 + `AgentViewModel`）按「纯逻辑进 `:core` / 取数走 `:data` / Android 专属走可空槽位
  `AgentToolHost` + `AgentCapability` honored 清单（没有的档一个字都不画）」搬完。
- **阶段 3** `9f2145ec`：跨端收口 —— `AgentHost.io` 调度器缝（与 `ScheduleHost.io` 同一条口径：`:app` 覆写回
  `Dispatchers.IO`）、`String.format` / `System.arraycopy` / `Math.PI` 三处 JVM 专属用法换跨端等价
  （都是 `:core:compileKotlinWasmJs` 报出来的）。
- **阶段 4** `997929be`：桌面点亮 —— `AppRoute.Agent to "屁岱"` + `DesktopAgentHost`（配置/会话/记忆真落盘：
  `secureKeyValueStore` + `:data` 的 `dataFilesDir()`）+ `DesktopAgentTools`（这一端接得上的五件；没接的档不画、
  调过来回 `not_found`）+ 证据图 `agent.png`（夹具传输，**一个 LLM 请求都不发**）+ `:desktop:test` 的
  `DesktopAgentJvmTest`（**4** 例，含「发一条 → 流式回一条 → 会话与配置落盘」）。

#### `AppRoute.Match`（匹配交友）—— 已点亮（最后一条「说明页」变真屏）

- **阶段 1** `51673daa`：算法与模型（`MatchProfile` 一族 / `MatchData` / `DiningHabit`）与屏按五步法搬进 `:core`；
  本机数据源收成 `MatchHost` 缝（`:app` 那三件原样：`DataCache` / `HelloProfileStore` / `CampusCardCache`），
  deflate 与 URL-safe Base64 收成两条平台缝（Android/jvm = 搬迁前那份实现，wasmJs 如实没有），
  扫码与相册收成 `MatchScanner`（`:app` 那份覆盖层原样）；`:app` 的 `MatchProfileTest` 一条断言未改。
- **阶段 2**（本提交）：桌面点亮 —— `AppRoute.Match to "匹配交友"` + `DesktopMatchHost`
  （课表族缓存与「我的课表」「首页」**同一份** 0600 键值文件 ⇒ 离线算交集在这一端**是真算的**）+ 证据图
  `schedule_match.png`（夹具课表 + 对手那段码，`hasText` 钉住「一起上 1 门课」与夹具课名）+ `:desktop:test` 的
  `DesktopMatchJvmTest`（**2** 例：离线比出交集 / 如实降级）。
- **两处如实降级**（都不是「坏掉」）：① 桌面没有相机也没有相册 ⇒ `scanner` 传 `null`，
  屏上「扫一扫」「从相册选图」**一个字都不画**，只留「粘一段文字码」那一条（它真能用）；
  ② 学工系统档案（`HelloProfileStore`）与校园卡消费（`CampusCardCache`）仍是 `:app` 的实现 ⇒
  `identity()` 为 `null`、`transactions()` 空表，屏上「年级 专业 校区 生源地」与「吃」那两维禁用并写明原因
  （要真点亮它们，见 `DesktopMatchHost` 的 KDoc：学工要接 hello 站点，消费要给校园卡那条路由补一份落盘）。
- **wasmJs（`:web`）**：那两条缝都没有 ⇒ `MatchProfile.codecAvailable` 为 false，屏上如实说「这一端生成不了分享码」
  （TODO 在 `platform/Deflate.kt` 里），既不画假的码也不假装能扫。


#### 「`NotPortedScreen` 归零」的判据（2026-10-13 收尾复核）

- `AppRoute` 表 **43** 条声明；`DesktopPage` 的 `when (route)`（第 568 行）里是 **43** 条顶层分支 + 一条兜底
  `else`（第 1218 行）—— 把声明集与**顶层分支集**各自提取后求差，**两边都是空的**。改动前同一份脚本的输出是
  「43 声明 / 42 分支 / 差集 `{Browser}`」—— 差的那一条就是 `76b0d530` 点亮的那条。
- **静态对比怎么做的**（脚本 `/tmp/notported_check.py`，本轮真跑）：
  - 声明 = `core/src/commonMain/kotlin/com/xjtu/toolbox/nav/AppRoute.kt` 的 `sealed interface AppRoute` 体内
    每一个 `@Serializable data (object|class) X`（共 43 条：40 个 `data object` + 3 个带参 `data class`）；
  - 分支 = 同一文件 `when (route) {` 到 `else -> NotPortedScreen` 之间**恰 8 空格缩进**的
    `is AppRoute.X ->` / `AppRoute.X ->`（共 43 条）；
  - 子屏回调里那些 `is AppRoute.Browser -> …`（第 649 / 834 / 854 行）缩进更深，天然不进这个集合 —— 它们是
    「子屏把外链交给系统浏览器」，不是顶层分支。
  - 本轮输出：`AppRoute 顶层声明：43` / `DesktopPage 顶层 when 分支：43` / 差集 `[]` / 陈旧分支 `[]` /
    `重复分支 []` / `else 是否可能命中：否（覆盖齐全）`。
- `else -> NotPortedScreen` 从此**不可命中** —— 43 条都被显式分支穷举了。这就是「归零」这句话的全部含义。
- 43 条里 **40** 条在 `DESKTOP_SUPPORTED_ROUTES` 里（第 340–443 行；含本轮的 `Match`，也含 `Browser` ——
  索引页上它是「浏览器」，点进去就是那条外链中转页）；另外 3 条有真分支但不进那张表：`GameMerge`
  （`HostUnavailableScreen` 如实说明页）、`Jiaocai1Reader`（从书架/检索结果进去的二级屏，索引页那一格
  用的是 `Jiaocai1`）与 `Settings`（真屏但不取数；它与 `Main` 另外进 `DESKTOP_OPENABLE_ROUTE_IDS`）。
- 「40/40」这个写法来自 §6.4 当初的**估计**（原文用的是「**≈** 40」），今天的口径以「43 条声明 / 43 条顶层分支 /
  `NotPortedScreen` 不可命中」为准。
- 证据图 **41** 张在 `desktop/build/screenshots/`（本轮 `:desktop:renderScreens` 真跑后复核，含新增的 `agent.png`）。

**本轮仍未验证的（不在上面这条验收里，别混为一谈）**：桌面屁岱只跑到夹具传输（真 LLM 没连过，API key 用户自填）；
Windows 的 `.msi`/DPAPI 真行为与 Linux 的真桌面启动都只有本机能验的那一半（见 §13 与两份交付文档的「未验证项」清单）。

---

## 13. 三端交付面索引与未验证项汇总（2026-10-13 清点）

> 三端 = **Android（`:app`）· Linux 桌面 · Windows 桌面**；外加 **serve 模式**（`:server` + `:web` wasm）。
> 这一节只是**索引**：每一行指到它自己那份记录，未验证项不复述细节、更不美化。
> Linux / Windows 两批交付的原文在 `docs/linux-delivery.md` 与 `docs/windows-delivery.md` ——
> 本文档这一轮**一个字都没动那两个文件**（它们是那两批交付的事实记录）。

### 13.1 交付面、产物与当前状态

| 交付面 | 产物 / 入口 | 可核证据 | 状态 |
|---|---|---|---|
| **Android**（`:app`） | 签名 APK → GitHub Release（`main` 出 Latest 并同步 Gitee；`dev` 出 Pre-release、不同步 Gitee）；本机验收出的是调试包 | `.github/workflows/build.yml`（触发 `push: [main, dev]` + `workflow_dispatch`；出包 / Release / 同步 Gitee 的步骤都在该文件里）；`:app:testDebugUnitTest` **429 通过 / 0 失败**（`app/build/test-results/testDebugUnitTest/`）；本轮真跑 `:app:assembleDebug` 出 **`app/build/outputs/apk/debug/app-debug.apk` 42,254,022 B**（2026-10-13 04:49，见 §14） | 与三端重构无关，按 C1 口径行为不变；灰度与预览渠道见 `docs/preview-channel.md`；**签名与 Release 链路仍未在本机跑过** |
| **Linux 桌面** | `.deb` · app-image · `tar.gz` · 可分发目录 | `docs/linux-delivery.md`（`603ece98`）：本机真出过三样 —— `.deb` **111,170,448 B**、app-image **204,922,515 B（207 个文件）**、`tar.gz` **123,750,278 B**；本轮 `ls` 复核：三个文件仍在 `desktop/build/compose/binaries/main/{deb,app,tar}/`，字节数一致；本轮又真跑 `:desktop:createDistributable` 出 **`desktop/build/compose/binaries/main/app/` 205,474,839 B / 207 个文件**（启动器 `xjtu-toolbox/bin/xjtu-toolbox`，见 §14） | 出包已验证；**真装与真启动未验证** |
| **Windows 桌面** | `.msi` / `.exe`（只能在 Windows 上打） | `docs/windows-delivery.md`（`b624008`）：DPAPI 保护器缝 + 假保护器单测、`targetFormats` 含 `Msi`/`Exe`、`.github/workflows/desktop-windows.yml`（`windows-latest`）、更新骨架 `DesktopUpdate`；本轮 `ls` 复核：`desktop/build/compose/binaries/main/` 下**没有** `msi`/`exe` 目录 | 配置与 CI 就绪；**打包与 DPAPI 真调用都没在本机验过** |
| **serve 模式**（`:server` + `:web`） | 一个 JVM 进程：托管 wasm 产物 + `/api/*`（默认只监听 `127.0.0.1`，访问令牌换 `serve_token` cookie） | 契约真源是 `docs/api-contract.md`；`:server` 的端点已落地（§5.1 会话 / §5.2 P0 取数 / §5.3 需登录只读 / §5.3.1 与 §5.3.2 两条写路径）；`:server` 契约测试 **44 通过 / 0 失败**（`server/build/test-results/test/`，10 个 XML）；`:web` 的滑块宿主 `web/src/wasmJsMain/kotlin/com/xjtu/toolbox/web/WebSlideCaptchaHost.kt`；wasm 产物本机在 `web/build/dist/wasmJs/productionExecutable/`（两个 `.wasm` 各 **8,131,435 B** / **8,640,316 B** + `toolbox-web.js` **541,599 B**，时间戳 2026-10-11 06:21） | 覆盖范围**以契约 §5 为准**；**浏览器端到端没有可核记录**，且 §5 表里 P2 那三行仍未实现（见 13.2 第 ④ 组）；本轮重跑 `:web:wasmJsBrowserDistribution` **失败**（失败原文与原因见 §14 第 2 条）——目录里仍是上面那一份旧产物 |

### 13.2 未验证项汇总（按交付面分组，逐条指向原文）

**① Android** —— 本轮核账**没有**覆盖它：

| # | 未验证的事 | 为什么 | 怎么才能验 |
|---|---|---|---|
| 1 | CI 的出包 / 发布链路（本文档只静态读了 `build.yml`，没有真跑） | 它是 `push: [main, dev]` 触发的，本地不可复现 | 推一次 `main` / `dev`，看 Release 与 Gitee 同步；口径见 `docs/preview-channel.md` |

**② Linux 桌面** —— 原文：`docs/linux-delivery.md` §7（**14 条**）+ §8 的三道「本轮没跑」门禁：

| # | 未验证的事（摘要，原文更细） | 怎么才能验 |
|---|---|---|
| 1 | **真桌面环境里启动**（app-image / `.deb`）：本机 `DISPLAY` 为空、也没装 Xvfb | 有桌面的机器（X11 与 Wayland 各一次），或 CI 里 `xvfb-run` 跑一次冷启动 |
| 2 | `.deb` 真装一次（依赖解析、菜单项出现、卸载干净）；`tar.gz` 解压后真跑 | 一次性 Ubuntu 容器 / 虚拟机 |
| 3 | 桌面项图标不是我们自己的（`linux.iconFile` 未设 ⇒ 实测用的是 Compose Desktop 默认图标，md5 与插件里那份一致） | 加 `desktop/icons/*.png` 并指过去，再出一遍 `.deb` |
| 4 | 单文件 `.AppImage`（`TargetFormat.AppImage` 是 jpackage 的 app-image，不是单文件）、RPM、ARM64、非 Debian 系、`Depends` 跨发行版、`copyright` 里的 `License: Unknown` | 见原文逐条；都是「下一个决定」或加一条任务 |

> 该文档 §8 还如实记着：那一轮**没跑** `:data:jvmTest` / `:core:jvmTest` / `:app:testDebugUnitTest`
> （同一工作区里另有 run 在搬 Agent 域，跑出来的红绿指向它的在途代码，不是那一轮的改动）。

**③ Windows 桌面** —— 原文：`docs/windows-delivery.md` §7（**9 条**）：

| # | 未验证的事（摘要，原文更细） | 怎么才能验 |
|---|---|---|
| 1 | **DPAPI 的真实行为**（往返 / 篡改解不开 / 换用户解不开）：本机是 Linux，那条调用路只有 Windows 有 | CI 的 `desktop-windows.yml` → `:data:jvmTest --tests "com.xjtu.toolbox.platform.*"` —— **这条 CI 还没在 GitHub 上跑过** |
| 2 | `:desktop:packageMsi` / `packageExe` 真出得来包（本机这两个任务是 `SKIPPED`，不是失败 —— 「命令绿了」什么都不代表） | 同一条 CI（出包 + artifact 上传，`if-no-files-found: error`）—— **还没跑过** |
| 3 | `desktop/icons/windows.ico` 被 WiX 接受；WiX 自动下载在 CI 上通 | 同一条 CI |
| 4 | per-user 安装 + 开始菜单 + 升级 UUID 的真实观感；未签名产物的 SmartScreen 行为 | 手动：一台真 Windows 装两版看是升级还是并存、双击看提示 |
| 5 | 整套 `:desktop:test` 在 Windows 上是否全绿（**已知会红一条**：`DesktopAuthLibraryJvmTest` 断言凭据文件是 `0600`，Windows 没有这个概念） | 把那条用例改成按平台判，或只跑指定类（现在 workflow 就是这么做的） |
| 6 | Windows 上会话 cookie / 站点快照 / 设置仍是明文文件（这一轮只保护了凭据那一份） | 把 `secureKeyValueStore` 的调用点逐个换成带保护器的版本（信封格式已就绪） |
| 7 | 更新骨架的 UI 接线与发布通道（设置页那一栏现在给的是 `null`；CI 只出 artifact、不上传 Release） | 设置页那一轮 + 发版流程那一轮 |

**④ serve 模式（`:server` + `:web`）**：

| # | 未验证的事 | 为什么 | 怎么才能验 |
|---|---|---|---|
| 1 | **浏览器里的端到端**（真登录 + 真站点 + 真下单）没有可核记录 | 仓库里没有任何一条这样的记录；`:web` **没有测试源集**（`find web/src -name '*Test*.kt'` 为空），它的门禁只有编译（`core-gate.yml` 第 46 行 `:core:compileKotlinWasmJs :web:compileKotlinWasmJs`） | 人工跑一次 serve 模式（真浏览器 + 真账号），把结论记进契约或本文档 |
| 2 | 契约里仍标 TODO 的端点：`/api/coupon/…` 与 `/api/payment-code`（P2，`:server` 里**没有**这两个端点）、`/api/v1/personal/me/user`（P2，来源待确认）、`card` 挂失（P1 写）、`evaluations` 提交/撤销（P1）、`library/seats?time`（上游无时间维）、`emptyroom/rooms` 的 `from/to`、`faculty` 的 `?filters=1`、`fitness/score` 的 `?year=all` | 拍板过但未做 | 各自那一轮（`docs/api-contract.md` §5.2 / §5.3 的 TODO 行） |
| 3 | venue「去支付」那一跳**已经接上**（2026-10-15，批③-a / D11b：`GET /api/venue/pay` 302 → 80 端口那台的 `pay/show.html?id=…`，`CampusVenueApi.paymentUrl` 不再空）⇒ 剩下的是**落点没在线验证过**：那一页要的是浏览器这一侧的场馆站会话，而 serve 的会话带不过去 ⇒ 落成能付款的一页**不担保** | 见契约 §5.3.2 第 6 条（302 一定被跟随；三条依据；并且**不许**写成「已支持真支付」） | 用户在真浏览器里点一次「去支付」，把落点记下来（`docs/serve-visual-checklist.md` ⑨ 那一行；不许替用户打支付站） |
| 4 | 消费方 `school-courses` 的两档筛选不画（服务端报 `supportsDepartmentFilter` / `supportsElectiveFilter` 是 `true`，但没有返回那两张 id 表的端点 ⇒ 消费方报 `false`） | 见契约 §5.4 | 端点落地后把 `Campus*Api` 的 TODO 翻成 true（契约 §7：三处同改） |
| 5 | 契约 §3 的默认安全口径（只听回环 / 令牌换 cookie / `--host 0.0.0.0` 的暴露形态）只做了静态核对 | `:server` 的测试起的是端口 `0` 的临时服务，验的是**形状**不是**部署形态**；本轮没起过真服务进程 | 起一次 `:server`，按契约 §3 逐条走一遍 |

**⑤ 跨端一致性对照**：本轮**没有**做「同一个问题在三端是不是同一个答案」的对照 —— 那是 `:testkit`
夹具契约测试的活，它现在钉的是两层：「同一份上游样本 → 数据层输出」与「同一份数据层 → HTTP 形状」
（`docs/api-contract.md` §6 的原文）。这一节因此不声称任何跨端一致性超出那两层。

### 13.3 仍然存疑 / 待核实的 TODO（本轮**不猜**）

| # | 存疑的事 | 为什么存疑 | 待谁 / 怎么核 |
|---|---|---|---|
| 1 | `RenderScreens.kt` 第 **98** / **415** 行仍写 `canBook = false` | `184bd527` 之后没跟着改（§12.2 已记原委；这两个行号是本轮跑 `grep -n 'canBook = false' desktop/src/test/…/RenderScreens.kt` 测出来的） | 下次动那个文件时改准（本轮只动 `docs/`） |
| 2 | `AppRoute.Browser` 那一支够不够（等价物 = 外链中转页） | `76b0d530` 已单开一支（`DesktopBrowserScreen`）：只做那条路由真交出去的那件事（地址交系统浏览器），地址栏 / 前进后退 / cookie 注入 / 夜间模式 / `then=` 那一跳**一个字都不画**（`DesktopBrowser.kt` 的表列了这三处如实）。**要不要真的内嵌一个浏览器**（JCEF / JavaFX WebView）仍是产品决定 | 用户拍板（本轮只动 `docs/`） |
| 3 | 桌面屁岱只跑到夹具传输：真 LLM 没连过（API key 由用户自填） | `997929be` 的证据图 `agent.png` 用的是夹具传输，`.github` 里也没有它的端到端记录 | 用户真填一次 key 跑一遍 |
| 4 | §6.4 的「可达上限 ≈ 40」这个数目 | 它是**估计**（原文用「≈」），而今天 `AppRoute` 是 43 条声明 —— 口径不同 | 待用户确认要不要把 §6.4 重算一遍（本文档**不改** §6.4） |
| 5 | serve 模式的浏览器端到端 | 无记录、`:web` 无测试源集（§13.2 第 ④ 组第 1 条） | 见那一行 |
| 6 | `docs/api-contract.md` §5.1 的 TODO ①②③④（MFA 绑定手机号不投影、`attemptsLeft` 的第二份写法、轮询间隔） | 契约自己标着，本轮只对齐了「已落地 / 未实现」这一层 | `:web` 那个弹窗真开始画时 / 契约改形状时一起收 |
| 7 | Linux / Windows 两份交付文档里的未验证项 | 它们是那两批交付的事实记录，本文档不改它们 | 各自的「下一个决定」或 CI 真跑（§13.2 第 ② / ③ 组） |
| 8 | `:web:wasmJsBrowserDistribution` 本轮那一红 | 失败输入是**未提交**的在途改动（`core/src/commonMain/kotlin/com/xjtu/toolbox/social/MatchProfile.kt`），不是提交状态；且那一跑跑到中途了另一条构建，**没做到独占**（§14.3 记了失败原文与这两点） | 那批改动落地 / 工作区干净之后，**独占**重跑一次（`:core` 的 wasm 编译要真过） |
| 9 | 本轮五个测试任务全是 `UP-TO-DATE`（**没有真正重跑**） | 04:03–04:16 那一轮同样的输入真跑过、全绿；本轮 04:23 起跑时输入没变，Gradle 直接复用产物（§14.1） | 要「同一刻重跑一遍」的话，那五个任务加 `--rerun` 再跑 |
| 10 | `Match`（匹配交友）「卡在 `:app` 缓存」这条理由还准不准 | **已按实际重核**：那时看到的那批在途改动已落地为 `51673daa`，`Match` 随后于 `ec3407d6` 点亮（第 33 条真数据路由），§12.3 已改写 | 已收口 |

---

## 14. 三端最终验收（2026-10-13 真跑）

> 这一节只记**本轮真跑出来的东西**：命令、输出的原文数字、产物路径与字节数（`stat -c '%s'` / `du -sb`）。
> 跑法：每次一条 gradle 命令、`--max-workers=1 -Pkotlin.incremental=false`（本机 7 GB，增量缓存被中断弄坏过），
> 后台跑 + 轮询；两条命令**起跑前**都核过没有别的 gradle 构建在跑。⚠️ 但第二跑（§14.3）**跑到中途同一目录里又起了一条构建**
> —— 那一次的结论按「非独占」打折看待，原委写在 §14.3 里。

### 14.1 全量门禁 + Android APK + 桌面可分发目录（同一条命令）

```
sh gradlew --console=plain --max-workers=1 -Pkotlin.incremental=false \
  :server:test :testkit:compileKotlin :core:compileKotlinWasmJs :core:compileAndroidMain :core:jvmTest \
  :data:compileAndroidMain :data:jvmTest :desktop:compileKotlin :desktop:test :desktop:renderScreens \
  :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :desktop:createDistributable
```

**结果：`BUILD SUCCESSFUL in 26m 8s`** —— `110 actionable tasks: 35 executed, 11 from cache, 64 up-to-date`（日志 `/tmp/accept.log`）。

| 模块 | 用例 | 失败 | 错误 | 跳过 | XML 个数 | 实测文件时间戳 |
|---|---|---|---|---|---|---|
| `:server:test` | **44** | 0 | 0 | 0 | 10 | 2026-10-13 04:07 |
| `:core:jvmTest` | **415** | 0 | 0 | 0 | 66 | 2026-10-13 02:57 |
| `:data:jvmTest` | **259** | 0 | 0 | 0 | 45 | 2026-10-13 03:00 |
| `:desktop:test` | **80** | 0 | 0 | 0 | 18 | 2026-10-13 04:10 |
| `:app:testDebugUnitTest` | **429** | 0 | 0 | 0 | 76 | 2026-10-13 03:04 |

> 五个模块共 **1227** 条用例，0 失败 0 错误 0 跳过。数字的核法：解析各自 `build/test-results/**/*.xml` 的根节点
> `tests` / `failures` / `errors` / `skipped` 属性求和（不是从提交信息抄的）。
>
> ⚠️ **如实说明：这五个测试任务本轮在 Gradle 里都是 `UP-TO-DATE`（没有真正重跑）** —— 因为 04:03–04:16 那一轮
> 用**同样的输入**（含最新的 `9f2145ec` / `997929be`）真跑过、全绿，本次输入没变，Gradle 直接复用产物。
> 本轮**真正执行**的是：`:desktop:renderScreens`、`:app:assembleDebug`、`:desktop:createDistributable` 与各自的依赖链
> （编译类任务是 `FROM-CACHE` / `UP-TO-DATE`）。想要「同一刻重跑」的话，那五个任务加 `--rerun` 再跑一遍就行（§13.3 第 9 条）。

### 14.2 三端产物（路径与字节数都是本轮真出的）

| 端 | 任务 | 产物路径 | 大小 | 实测时间 |
|---|---|---|---|---|
| **Android** | `:app:assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` | **42,254,022 B**（`stat -c '%s'`） | 2026-10-13 04:49:03 |
| **Linux 桌面** | `:desktop:createDistributable` | `desktop/build/compose/binaries/main/app/` | **205,474,839 B** / **207 个文件**（`du -sb` / `find -type f \| wc -l`）；启动器 `xjtu-toolbox/bin/xjtu-toolbox` | 2026-10-13 04:49 |
| **桌面证据图** | `:desktop:renderScreens` | `desktop/build/screenshots/` | **41 张** PNG（含 `agent.png`） | 2026-10-13 04:25:17–04:30:49 |

> **Windows 的 `.msi` / `.exe` 本轮照旧没出** —— 那两个任务只能在 Windows 上打（本机 Linux）。
> 「本机命令绿了」不代表它们验过：`:desktop:packageMsi` / `packageExe` 在本机是 `SKIPPED`，不是通过（见 `docs/windows-delivery.md` §7）。

### 14.3 `:web` 的 wasm 产物 —— 本轮**失败**（如实记下原文）

```
sh gradlew --console=plain --max-workers=1 :web:wasmJsBrowserDistribution
```

**结果：`BUILD FAILED in 5m 13s`** —— `29 actionable tasks: 27 executed, 2 up-to-date`（日志 `/tmp/webdist.log`）。
失败点在 `:core:compileKotlinWasmJs`：

```
e: .../core/src/commonMain/kotlin/com/xjtu/toolbox/social/MatchProfile.kt:267:64 'fun String(chars: CharArray): String' is deprecated. Use CharArray.concatToString() instead.
e: .../core/src/commonMain/kotlin/com/xjtu/toolbox/social/MatchProfile.kt:497:29 'fun String(chars: CharArray): String' is deprecated. Use CharArray.concatToString() instead.
```

（同一批 `:core/social/` 文件里还有其它同类报错，全文在 `/tmp/webdist.log`。）

**这条红不是提交状态的结论**：`core/src/commonMain/kotlin/com/xjtu/toolbox/social/` 当时是工作区里**未跟踪**的在途文件
（`git status --porcelain` 里显示为 `??`），是「匹配交友」域往 `:core` 搬迁的中间态。提交状态那一侧的证据是：

- 本轮 14.1 那条命令里 `:core:compileKotlinWasmJs` 是 **`UP-TO-DATE`**（工作区还干净时的那一段）；
- 04:03–04:16 那一轮全量门禁里它**编译通过**（含最新的两个提交）。

所以 `web/build/dist/wasmJs/productionExecutable/` 里现在仍只有 **2026-10-11 06:21** 的那一份产物（`ls -l` 复核），
本轮**没有**重新生成 —— 想要一份新的 wasm 产物，得等那批在途改动落地之后**独占重跑一次**（§13.3 第 8 条）。

⚠️ **这条命令没有做到「独占」**：它 ≈04:51:50 起跑、04:57:02 失败（5m 13s），而 04:52:05 同一目录里又起了另一条
`gradle --max-workers=1 :core:compileKotlinJvm :core:compileKotlinWasmJs`（日志 `/tmp/c1.log`；它同样没过 ——
卡在那批 `:core/social/` 文件的 deprecation 上，`BUILD FAILED in 11m 6s`）。
两条构建不在同一个 daemon 里（Gradle 另起了一个，04:52:15），但**共用同一个工作区与缓存目录**，
所以这一次的「失败 / 通过」不能当独占验收结论用。失败本身是编译错误（上面的原文），不是抢占 —— 但按纪律它仍算
**未按规矩跑完**，因此同时记进 §13.3 第 8 条：要的是一份干净工作区上的**独占重跑**。

### 14.4 这一轮**没有**随之验证的

不复述 §13.2 的清单，只列与上面三个产物直接相关的四条：

1. **真站点**：上面的测试全部走本地夹具 / 假上游，没有一条打真站点；真账号、真下单、真座位都没验过。
2. **Windows**：`.msi` / `.exe` 没出包，DPAPI 的真实行为没调用过，`desktop-windows.yml` 那条 CI **在 GitHub 上还没跑过**。
3. **Linux 真装 / 真启动**：`.deb` 装一次、桌面环境里启动一次都没做（本机 `DISPLAY` 为空）。
4. **`:web` 的 serve 覆盖范围**：浏览器里跑 serve 模式没有可核记录；`:server` 的 44 条契约用例验的是**形状**（临时端口），
   不是部署形态。

> 三端之外的第五条：**跨端一致性对照**本轮也没做（`:testkit` 夹具只钉「同一份上游 → 数据层」与「同一份数据层 → HTTP 形状」两层）。

### 14.5 最后一轮：分支覆盖达成 + 干净树上的独占重跑（2026-10-13 07:13–07:57）

这一轮做了两件事，数字都是真跑出来的（日志路径附在后面）：

1. **分支覆盖达成**（最后两条是 `ec3407d6` 的 `AppRoute.Match` 与 `76b0d530` 的 `AppRoute.Browser`）：
   `AppRoute` 表 **43** 条声明，`DesktopPage` 的顶层 `when (route)` 里 **43** 条顶层分支，
   把两边各自提取后求差**两个方向都为空** —— 兜底的 `else -> NotPortedScreen` **不再可命中**。
   口径：这是「分支覆盖」而不是「43 条都是真实现」——`GameMerge` 仍是 `HostUnavailableScreen`
   如实说明页（卡载体），且**不在** `DESKTOP_SUPPORTED_ROUTES` 的口径里。
2. **全量门禁**（`:server:test`、`:testkit:compileKotlin`、`:core` 的 wasm/Android 编译与 jvmTest、
   `:data` 的 Android 编译与 jvmTest、`:desktop` 的编译/test/renderScreens、`:app` 的编译/test）：
   **`BUILD SUCCESSFUL in 21m 14s`**（07:13 起跑，日志 `/tmp/full2.log`），解析各自 `build/test-results/**/*.xml`：
   server **44** / core **424** / data **259** / app **429** / desktop **85**，共 **1241** 例，**0 失败 0 错误**；
   `desktop/build/screenshots/` **42** 张证据图。
3. **`:web:wasmJsBrowserDistribution` 独占重跑**（上一轮那次因不独占而作废的那一条）：
   删掉 `web/build/dist/wasmJs/` 后、在**没有任何其他 gradle 在跑**的窗口里重跑（先 `ps` 确认，
   并把一条遗留的孤立构建 `kill` 掉）—— **`BUILD SUCCESSFUL`**，产物重新生成：
   `productionExecutable/`（2 个 `.wasm`：8,185,926 B 与 8,640,316 B + `index.html` + `canvas-crisp.js`，共 **19 MB**，
   时间戳 `07:57`；日志 `/tmp/wasm3.log`）。这一轮首跑时大多数 wasm 编译产物命中构建缓存（6 executed / 38 up-to-date，8s）。

**这一轮仍没有变的未验证项**：同 §14.4 那四条（真站点、Windows 打包/DPAPI、Linux 真装与真启动、
`:web` serve 的浏览器实跑）一条都没验——本轮只把「产物能出、门禁能绿、分支能覆盖」这三件做成了可核的事实。

### 14.6 serve 模式实跑（2026-10-13 09:37–09:50，本机真跑）

命令：`./gradlew :server:run --args="--host 0.0.0.0 --port 40961"` —— `--host 0.0.0.0` 是**显式对外暴露**
（不写就只监听回环，见服务端启动 banner 的告警），端口用系统挑的空闲端口。下面每一条都是 `curl` 现场跑出来的原文摘录：

| 请求 | 结果 |
|---|---|
| `GET /api/status`（唯一免令牌端点） | `200` `{"authenticated":false,"uptimeSeconds":139}` |
| `GET /`（静态站点） | `200` `text/html` 874 B —— 就是 `:web` 的 wasm dist 那份 `index.html` |
| `GET /api/session`（**不带**令牌） | `401` `{"code":401,…,"message":"访问令牌缺失或无效：请带 Authorization: Bearer <令牌>"}` |
| `GET /api/session`（`Authorization: Bearer <令牌>`） | `200` `{"code":0,"data":{"authenticated":false},"message":null}` |
| `GET /api/session`（`Cookie: serve_token=<令牌>`） | 同上 `200` —— 契约 §3.2 的**两种形态都真通** |
| `GET /api/jwxt/term`（令牌对、**未登录**） | `401` `{"code":401,…,"message":"请先登录：POST /api/session/login"}`（`/api/library/campus`、`/api/venue/status` 同） |
| `GET /api/calendar/school` | `200` 真校历：`2026-09-14 → 2027-01-17`、17 周、`报道注册 2026-09-12` |
| `GET /api/info/yellowpage` | `200` 真黄页：`updateTime 2026年07月02日`、5 个分类 |
| `GET /api/notification/sources` | `200` 29 个源（教务处 / 研究生院 / 钱学森书院 …） |

**这把 §14.4 第 4 条从「没记录」推进到「有记录的一半」**：服务端 + HTTP 契约面（含免登录端点打到真上游、
令牌两种形态、401 的两句不同语义）都是**真跑通**的；**仍然没做**的是「人在浏览器里打开那一页点一遍」——
那需要在能访问 `http://192.168.5.11:40961` 的机器上做（服务当时就开着，令牌在
`~/.local/share/xjtu-toolbox/serve.properties` 的 `access_token`）。

⚠️ 同一次实跑也复现了服务端的告警：**HTTP + 非 localhost 是浏览器的非安全上下文**，
`navigator.clipboard` / `crypto.subtle` 之类会被禁用；要真当产品用，前面得加 HTTPS 反代 + 鉴权。

### 14.7 阶段 B 留下的一处可疑退化：`shell-after-login.png` 底部那 64px —— 查到底了，**不是退化**（2026-10-13 本轮）

`6d94a59c`（桌面换成共享壳）的提交信息里如实留了一条没查到底的疑点：`shell-after-login.png` 的底部一条
与基线对不上。这一轮把它钉死了，结论是**正常差异**（同一条 miuix 契约在换壳前后两种宿主上的必然结果），
`:core` 共享壳**一个字没改**。

#### 现象（只看那一张 PNG；520×900）

| | 旧壳（旧代码重出的那一批） | 新壳（本轮重出） |
|---|---|---|
| 与「上一轮同代码重出」的可见差异 | — | **74,461 px / 15.910%**，包围盒 `520x296+0+604` |
| y=0..603 | 两图**逐字节相同** | |
| 弹窗卡片（亮色连续段 ≥ 200px 的行） | y=**604..822** | y=**669..887** |
| 底栏那一条（y=836..900）的底色 | `247,247,247` | `172,172,172` |
| 底栏图标上那一点的灰度 | `168` | `117` |

172/247 = 0.696、117/168 = 0.697 —— 正好是 miuix 的 `windowDimming`（浅色下 `Color.Black.copy(alpha = 0.3F)`，
`miuix-ui/…/theme/Colors.kt:391`）：**底下那一层没换颜色，是被压暗了**。差的那 64 就是经典底栏的高度
（836..900）：弹窗整体下移 64 之后压在底栏上。

> 旧壳那一批是上一轮在**旧代码**（`6283fad2` 之前那份 `ToolboxDesktopApp`）上重出的 42 张
> （临时目录 `/tmp/base-screens`，日志 `/tmp/baserender.log`）：42 张的字节数与日志**逐条一致**；其中两张
> 不涉壳的图（`login.png` / `library-after-login.png`）与本轮 HEAD 重出的**逐字节相同** ⇒ 那份基线
> 就是「同场景、只差壳」。两样都在临时目录里，不进仓库。

#### 语义树 bounds（本轮新加的两个用例：`desktop/…/DesktopShellOverlayJvmTest.kt`）

```
PROBE[shell] 底栏第一格=Rect.fromLTRB(0.0, 836.0, 86.0, 900.0)  弹窗标题=Rect.fromLTRB(74.0, 733.0, 446.0, 754.0)
PROBE[shell] 弹窗卡片 rows=709..887
PROBE[shell] 语义树文字=[…, 当前预约：A03, …, A01, …, A09, 兴庆图书馆, 首页, 校历, 图书馆, 黄页, 游戏, 全部, 图书馆座位预约, ⏰, 预约成功后，请在 30 分钟内入馆签到…, 知道了]
PROBE[old-shape] 卡片底边=824.0（页框底 = 900 − 64 = 836）/ 壳里=888.0
PROBE[old-shape] 标题底：旧壳那种容器=690.0 / 壳里=754.0
```

- **底栏第一格 `top=836`** ⇒ 页框仍然是 836（= 900 − 64），**没有拿到整窗高度** —— 上一轮那个
  「页拿到了整窗高度 / `bottomBar` 的槽被判空」的假设就此证伪；
- 弹窗底：壳里 **888 = 900 − 12**、旧壳那种容器 **824 = 836 − 12**（`DialogDefaults.outsideMargin.height`
  = 12dp）；两组各自再差 64（像素：卡片 888−824；语义树：标题底 754−690，两次量法互相印证）；
- 两种容器里**页里的内容一模一样**（座位 A01..A09、三个区域、「当前预约：A03」都在）⇒ 「只有内容填到
  底部才出现」那条线索也不成立：差的那 64 与页里画了多少东西无关。

#### 根因（miuix 的契约 + 宿主从「页」变成了「壳」）

`miuix-ui` 的 `overlay/OverlayDialog.kt`：`renderInRootScaffold: Boolean = true`（**默认**）——
KDoc 原文「When true (default), the dialog covers the full screen. When false, it renders within the
current Scaffold's bounds.」；`layout/DialogContentLayout.kt` 里的定位是 `Alignment.BottomCenter` +
`outsideMargin.height`（12dp），变暗那一层 `.fillMaxSize()` 也画在这个宿主里。

- **旧桌面壳**（`6283fad2` 之前的 `ToolboxDesktopApp`）是
  `Column { Box(Modifier.weight(1f)) { 当前页 }; NavigationBar { … } }` —— **一个 Scaffold 都没有**
  ⇒ 「最外层 Scaffold」落在**页自己**身上：宿主 = 836，弹窗被页框裁在底栏上方，底栏也不在变暗范围内
  （所以旧壳那张图里底栏是本色 247）。
- **共享壳**（`AppShell`）自己就是一个 `Scaffold` ⇒ 宿主 = 520×900 整窗：弹窗锚到窗口底、变暗层盖住底栏。
  `:app` 的 `MainScreen.kt` 从第一天起就是这样（`:app` 一直是「弹窗铺满整窗」）—— 也就是说，这一处的
  变化正是「三端同壳」本身，不是桌面这一端的退格。

#### 判定与处置

**不是 bug**：旧壳那 64px 是「桌面这一端恰好没有 Scaffold」这个偶然事实的产物，`shell-after-login.png`
现在的样子才是与 `:app` 一致的形态。所以按纪律**没有**给桌面打补丁、也**没有**改 `:core` 的共享壳
（`AppShell` / `ShellTabHost` / `ShellLayout` 本轮零改动）。钉住它的是两个**新用例**
（`:desktop:test` 98 → **100**，0 失败）：同一份夹具（`canBook = true` 的库页取数）+ 同一个页
（`LibraryScreen` 那条「首次使用提示」），只换外面那层容器，断言弹窗底锚在谁的底上、两者相差正好是
底栏那一条 —— 语义树与像素各量一次。

#### 顺带收敛的另一条 Open risk：深色下外壳那三层的层次

共享壳会铺 Scaffold 的 `containerColor`（= `MiuixTheme.colorScheme.surface`），这是一处**看得见的变化**
（旧桌面壳那些像素是**透明**的）。新加两张图（`home-dark.png` / `main-dark.png`）与两条判据
（`RenderScreens` 的 `bandOf`），判据落在**落盘的 PNG** 上：

```
深浅对照：底栏 最亮=247 亮占比=100% → 最亮=24 亮占比=0%；侧栏 最亮=247 亮占比=100% → 最亮=24 亮占比=0%；页面 最亮=255 亮占比=97% → 最亮=254 亮占比=1%
```

- `home-dark.png`（520×900，与 `home.png` 同一条路由）量的是**底栏**那一层；`main-dark.png`（900×900）
  量的是宽窗下换成**侧栏**的那一层；「页面」那一层用内容区的亮像素占比量（深色下 1%、浅色下 97%）。
  三层都跟着主题换，没有哪一层留在浅色上。
- ⚠️ 深色那两张的课表/考试两格与浅色那两张**不是同一份数据**：这一枪在所有图之后，缓存里已经是
  `schedule_match.png` 那一枪写进去的夹具（图上会看到另一门课）。深浅对照要的是外壳那三层的像素，
  与页里画了什么无关。

**这一轮的证据图 42 → 44 张**（新增 `home-dark.png` / `main-dark.png`）：42 张里 33 张与上一轮 HEAD
重出**逐字节相同**；9 张的差异都是 0.02%~0.85% 的局部小块（时序噪声，上一轮已记），**没有一张**的差异
落在底栏那一条或图像底部。

#### 本轮门禁（`--max-workers=1`；日志 `/tmp/gate-mine.log`）

| 任务 | 用例 / 产物 | 失败 | 跑法 |
|---|---|---|---|
| `:core:jvmTest` | **439**（69 个 XML） | 0 | `--rerun` 独占重跑（XML 20:08:50） |
| `:app:testDebugUnitTest` | **429**（76 个 XML） | 0 | `--rerun` 独占重跑（XML 20:09:48） |
| `:desktop:test` | **98 → 100**（24 个 XML） | 0 | 本轮真跑（新增那两个用例） |
| `:desktop:renderScreens` | **42 → 44 张** | — | 本轮真跑 |
| `:core:compileKotlinWasmJs` / `:core:compileAndroidMain` / `:data:compileAndroidMain` / `:app:compileDebugKotlin` / `:desktop:compileKotlin` | — | — | 全部 `UP-TO-DATE`（`:core` 本轮零改动；`:web:compileKotlinWasmJs` 不在本轮范围） |

⚠️ 这一轮跑门禁时，同一棵工作区里还有另一个子代理在做阶段 C（`:web`）：`core/net/CampusApi.kt` 有一处在途的
**兼容性扩展**（`termStart(term: String? = null)`）与一批未跟踪的 `web/src/wasmJsMain/…` 文件参与了编译
（它们仍在 `git status` 里 —— 本轮提交只 `git add` 了自己那三个路径）。


## 15. 阶段 C：`:web` 换成共享壳（2026-10-13 实做）

这一阶段只解决一件事：**web 的层级与 App 不再是两套**。用户的原话是「web 组织页面的层级逻辑和 app
简直天差地别」——搬迁前 `:web` 的底栏是「课表 / 校历 / 体测 / 成绩 / 黄页 / GPA2048 / 方块 / 游戏 / 社区」
九格（**能做的功能**凑出来的），与 `:app` 的 `BottomTab` 五格（首页 / 日程 / 屁岱 / 学辅 / 我的）
**没有一格对得上**，外壳也是自己写的 `Column + WebBottomBar`。阶段 A/B 已经把壳搬进 `:core`
（`shell/**` 十个文件）并把 `:app` 与桌面换过去；这一阶段把 `:web` 也换过去。

### 15.1 五格与每一格的归属（与 `BottomTab` 逐字一致）

`WebShell.kt` 的 `WEB_SHELL_TABS` 五格，顺序 / 文字 / 图标 / `iconSize` 与
`app/src/main/java/com/xjtu/toolbox/main/BottomTab.kt` **逐字相同**，id 也用那个枚举的名字
（`HOME` / `COURSES` / `PIDAI` / `TOOLS` / `PROFILE`；`SettingsStore.TAB_*` 与 `:app` 的
`defaultTab` / 启动意图认的就是这几个串）：

| 格 | 这一端的归属 | 为什么 |
|---|---|---|
| 首页 | **共享屏** `:core` 的 `HomeScreen` + 本端 `WebHomeHost`（2026-10-13 补：见 §15.8；当时这一格是降级页，于是十几条路由的入口全被关上了） | 这一端所有功能屏的入口：宫格 = `:core` 的服务目录 × 本端真能打开的路由（`WebSupport` 那份唯一真源） |
| 日程 | **共享屏**：`:core` 的 `ScheduleScreen` + 本端 `WebScheduleHost` | 见 §15.3（本端真接上的三格之一，另两格是首页与我的） |
| 屁岱 | **降级页** | `:core` 的 `AgentScreen` 要 `AgentHost`：配置 / 会话 / 记忆三份落盘、一条流式传输（`:data` 的 SSE）、工具层取数口。本端一件都没接，服务端契约里也没有对应端点 |
| 学辅 | 本端自己的**可点目录** `WebServicesScreen`（**不是**降级：点一行进那一屏 + 会话入口；2026-10-13 从「本端有什么」说明页改过来） | `:app` 那一格是学业服务目录（`AppServices` 宫格 + 点击习惯排序 + 每项此刻的状态），本端没有「状态摘要」那一半 ⇒ 换成同一份目录的可点清单，不伪造一个宫格 |
| 我的 | **共享屏**：`:core` 的 `SettingsScreen` + 本端 `WebSettingsHost` | `:app` 那一格是账号页（多账号 / 登录态）；本端没有账号这回事（凭据在 campus-api / serve 进程里）⇒ 落同一份共享设置屏。`honoredRows` 列 `DARK_MODE` 与 `HOME_THEME`（两项真会生效，见 §15.5 与 §15.8），其余九项各自写明为什么不列（列了就是假开关） |

**做不到的格子落降级页、不藏起来**：藏起来就又变成"另一套层级"（这条是这一阶段的验收口径）。
「我的」那一格 `showsAccountBadge = true` 照抄 `:app`，但 `ShellNavState.accountBadge` 传 null ⇒
壳一个角标都不画（本端真没有数字可写，不是藏）。

### 15.2 外壳：`ShellNavState + AppShell`，删掉自己的底栏与伪路由

`ToolboxWebApp` 的 `Column { Box(weight) { when(target) } ; WebBottomBar }` 那一段换成：

- `ShellNavState(tabs = webTabs(), target = target.toShell(), onSelect = { tab.target.toWeb()?.let { target = it } })`
  —— 当前页仍是 `WebTarget`（这一端对「当前在哪一页」的写法，与桌面那份 `DesktopTarget` 同型），
  两个方向的换算在 `WebShell.kt`（`WebTarget.toShell()` 全函数；`ShellTarget.toWeb()` 对
  `AllPages` 与认不出的 `Custom` 如实返回 null ⇒ 不动，不假装能去）；
- `AppShell(pageHost = ShellPageHost.CURRENT_ONLY, glassStyle = false, bottomInset = 0.dp,
  contentWindowInsets = WindowInsets(0))` + `LocalIsWideLayout provides calculateIsWideLayout()`
  —— 判据与 `:app` / 桌面同一套：**宽窗（短边 ≥ 600dp 且窗口够宽）底栏换成侧栏**，窄窗是 miuix 经典底栏。
  `glassStyle = false`：这一端没有「界面风格」这一项（设置页里那一行也不画），搬迁前画的也是经典底栏；
- `page` 里只分派「哪一种页」：`ShellTarget.App(route)` → 各屏（**每屏的宿主面一行未改**），
  `ShellTarget.Custom(id)` → 会话 / 自检 / 协议 / 学辅 / 我的 / 认不出。

删掉：`WebBottomBar`（约 45 行）、`WEB_TABS` / `WebTab`（九格表）、以及 **`:web` 自己那份 439 行的周视图**
`web/src/wasmJsMain/kotlin/com/xjtu/toolbox/web/ScheduleScreen.kt`（共享 `ScheduleScreen` 取代它：
搬迁前那份是 `:app` 课表网格的第二份实现，正是"两套层级"的一部分）。

**「会话」那一屏不进 tab 列表**（它进任何一格都会让"五格与 `:app` 逐字一致"当场失效）：它是
`ShellTarget.Custom("serve")` 这一档，三条入口 —— `?route=serve` 深链（搬迁前就有）、
serve 模式下 `GET /api/session` 失败时自动落过去（搬迁前那一段行为）、「学辅」页上的一个入口行。

**一行未改的那几段**：`?token=<一次性令牌>` 的一次性入口、`?route=` 的语义（`probe` / `eula` / `serve`
三个伪路由 + `:core` 的 `appRouteOf`）、后端探测（3s 超时按 campus-api）、以及「过不了闸门就落到会话屏」
那一段。改的只是"当前页"的表达：从自制的 `WebTarget` 分支换成壳的 `ShellNavState`。

### 15.3 web `ScheduleHost` 的缝落法（哪几条是真的、缺哪三个端点）

`:core` 的 `ScheduleHost` 有四类槽位，本端逐条落成这样（`WebScheduleHost.kt`）：

| 槽位 | 本端 |
|---|---|
| `cache` / `customCourses` | `:core` 的键值缝 `KeyValueScheduleCacheStore` / `KeyValueCustomCourseStore` + `keyValueStore(name)`。**wasm 上的 actual 是 `localStorage`**（`core/src/wasmJsMain/.../KeyValueStore.wasmJs.kt`，键前缀 `xjtu-toolbox.`）—— **真落盘**，刷新页面还在；不是内存实现。没有按账号加后缀：本端没有账号 |
| `login` | `accountId` / `studentId` 空串（本端没有学号，编一个更糟：会去请求别人的数据），`hasCredentials = true`（"能不能取数"由服务端答，答 false 会让屏永远不取数） |
| `session` | `WebScheduleSession` + `WebScheduleSource`：**服务端契约里真有的那四个端点** —— `/api/jwxt/term`（当前学期）、`/api/jwxt/term-start?term=`（开学日 + 总周数）、`/api/jwxt/terms`（学期表，走 `:core` 的 `CampusSchoolCourseApi.terms()`）、`/api/jwxt/schedule?term=`（课表行）。`isLoggedIn = true`、`ensureLogin` / `reAuthenticate` 是空操作：**会话在对面进程里**，这一端没有"页面上登录"这回事 |
| `holidays` | 只读落盘缓存（键 `holiday_dates` 与 `:data` 逐字相同），**不联网**：`:data` 那份挂着 okhttp 打两个公开接口，而浏览器直连它们没有 CORS 头 ⇒ 读不到就是空表（课表里没有一天被当成放假 —— 是"少画"，不是"画错"） |
| `honored` | 只有 `CUSTOM_COURSE_EDIT`（自建日程真落盘）。`SHARE_FILE` / `LMS_LINK` / `ATTENDANCE` / `SEAT_COUNT` / `DUE_HOMEWORK` / `WIDGET_REFRESH` / `HOME_SIGNALS` / `ONLINE_CHECK` / `SCHEDULE_CHANGE_BUBBLE` 一条都不列 ⇒ 屏上那几档**一个字都不画** |

**缺的三个端点（如实写在页面上，不只写在注释里）**：`/api/jwxt/changes`（调停补课）、
`/api/jwxt/exams`（考试）、`/api/jwxt/textbooks`（教材）—— `docs/api-contract.md` §5 的八个端点清单里
**没有它们**（2026-10-13 实测 serve 对这三个答 `404 {"code":404,…,"message":"接口不存在"}`）。
而 `/api/jwxt/schedule` 端出去的是**排课原样**（契约里写明：调停补课没有合进这些行）⇒
本端日程页显示的是排课原样：**调课 / 停课 / 换教室不体现在格子上，考试与教材两栏空着**。
这一条由 `WebScheduleGapNote` 画在日程页顶部（一行 11sp 的说明），不让用户以为教务就长这样。
campus-api 那边虽然有 `/api/jwxt/changes` 与 `/api/jwxt/exams`，但本端没有对应客户端（`:core` 里
没有它们的模型与读法），**不编形状**。

历史学期：`schedule` 认任意学期，但 `term-start` 只答当前学期（非当前学期两个后端都会把当前学期的
开学日当成它答回来 = 静默错答）⇒ 非当前学期一律抛错，屏那边本来就 `try { … } catch { null }`，
于是退化成「没有开学日、周次按学期表/日期推」。

### 15.4 为此动的 `:core` 一行（最小改动，逐条说明）

`CampusApi.termStart()` 补一个可选参数 `term: String? = null`（两个后端都按契约带上 `?term=`）。
理由：`?term=` 是契约里本来就有的参数，而原生端一直直接问 `:data`，只有浏览器这一侧要靠这个端点，
历史学期必须有它才拿得到。**只加默认参数，调用点一行未改**；`:core:jvmTest` 439 例、`:app` 429 例、
`:desktop` 那一套（见 §15.6）与改动前同数、全绿。壳（`shell/**`）**一个字未改**。

### 15.5 我的 = 共享设置屏（顺带把「深色模式」接通）

`WebSettingsHost` 只声明 `DARK_MODE` 一项真会照着办：`Main.kt` 现在按 `SettingsStore().darkMode`
套 `XJTUToolBoxTheme`，设置页改完回调 `onAppearanceChanged` 让外壳重新读一次（键值缝没有监听器，
与 `:desktop` 那份 `appearanceTick` 同一个写法）⇒「把深色模式改成始终深色」当场就变。
其余十项各自写明为什么不列（Monet 在 wasm 上未验证 / 首页还是降级页 / 本端没有玻璃底栏这一档 /
没有"启动"这回事 / 没有触感 / 只有一条网络路 / 只有一个课表来源 / 没有考勤取数 / 图片解码在 wasm 上恒 null）。

### 15.6 验收（这一轮真跑的，逐条可核）

| 项 | 结果 |
|---|---|
| `:web:compileKotlinWasmJs` | **BUILD SUCCESSFUL**（日志 `/tmp/webc2.log`；唯一告警是既有那条 `BrowserUrl.kt` 的 opt-in） |
| `:core:jvmTest` | **439 例 / 0 失败**（`core/build/test-results/jvmTest`，基线 439，只增不减） |
| `:app:testDebugUnitTest` | **429 例 / 0 失败**（基线 429 一条不减） |
| `:desktop:test` | **100 例 / 0 失败**（24 个类，`--rerun` 独占重跑；本轮开始时这一套是 98 例 —— 同时段另一个提交 `07820517` 给桌面加了 2 例） |
| `:web:wasmJsBrowserDistribution` | **BUILD SUCCESSFUL in 24m 22s**（44 tasks：10 executed / 34 up-to-date），产物时间戳 `20:34` |
| serve 冒烟（curl） | 见下面那段：`/`、`toolbox-web.js`、新 `.wasm` 三份的 md5 与盘上逐字节一致；`/api/status` 200 |

### 15.6.1 serve 冒烟的证据（`curl` 现场跑的，逐条是原文）

服务端 `:server:run` 一直跑在本机 `0.0.0.0:40961`（令牌在 `~/.local/share/xjtu-toolbox/serve.properties`
的 `access_token`），**直接从盘上读 `web/build/dist/wasmJs/productionExecutable/`** ⇒ 重建完就生效。
这一轮重跑产出的新 wasm 名字是 `25195b5c10e41a3ae110.wasm`（8,945,354 B），旧的那份是
`0b55fcaa4f325379b37c.wasm`（8,186,584 B）：

| 请求 | 结果 |
|---|---|
| `curl -H 'Accept-Encoding: identity' http://127.0.0.1:40961/toolbox-web.js \| md5sum` | `343d7d6962ae7d5cc53618580ffd7701` —— 与盘上那份 `toolbox-web.js` **同一个 md5**（这份 js 是 20:34 新生成的） |
| 同一份 js 里搜新 / 旧 wasm 名字 | 新名 `25195b5c…` 出现 **1** 次；旧名 `0b55fcaa…` **0** 次 ⇒ 服务端在吐**新产物** |
| `curl …/25195b5c10e41a3ae110.wasm \| md5sum` | `8684fa6c14664568e865e9c3da95d41e` —— 与盘上那份逐字节一致（HTTP 200） |
| `curl -H 'Accept-Encoding: identity' http://127.0.0.1:40961/ \| md5sum` | `a27d0829c8192199b5dae4a4d4d311a6` —— 与盘上 `index.html` 一致（874 B；这份是静态模板，新旧同字节，所以新鲜度看上面那两条） |
| `curl http://127.0.0.1:40961/api/status` | `200` `{"authenticated":true,"uptimeSeconds":39190}` |
| 在服务端吐的 `.wasm` 里搜本阶段新加的文案 | `排课原样` / `首页（Web 这一端）` / `学辅（Web 这一端）` / `屁岱（Web 这一端）` / `认不出这一页` **各 1 次** ⇒ 服务端吐的那份二进制里**确实**是这一阶段的代码 |

⚠️ **`:web` 没有测试源集**：`web/src/` 下只有 `wasmJsMain`（没有 `commonTest` / `wasmJsTest`），
所以这一端的自动化验收**只到「能编译 + 产物能出 + 服务端在吐新产物（且产物里是这份代码）」**这几条；页面上的
「五格与 App 一样、宽窗变侧栏、降级页写清了缺什么」**要靠人看**（本轮没有人看过 —— 见 §15.7 第 1 条）。

⚠️ **`:desktop:test` 的环境插曲（如实记）**：第一次 `--rerun` 时同一台机器上还有几个 Gradle 守护进程与
另一个子代理的构建在跑，内存 / tmpfs 吃紧，那一轮里 59 例以 `java.io.IOException: Disk quota exceeded`
（以及 `keytool -genkeypair` 写不下）报错 —— 那是**环境**，不是代码：等一个空闲窗口重跑，同一棵树
**100 例 / 0 失败**（`desktop/build/test-results/test`，XML 时间 `20:52:54`；副本留在 `/tmp/desktop-results-stageC-full`）。

### 15.7 仍缺的能力（下一步的清单）

1. **人看一眼**：浏览器里打开 serve 那一页，确认五格与 App 一致、宽窗是侧栏、日程能出课表、
   降级页的文案读得懂 —— 这是本轮唯一没人做过的验收（`:web` 没有测试源集，也没有离屏渲染证据运行）。
2. **首页**：接 `HomeHost` 里算得出来的那几块（问候 / 教学周 / 常用入口 / 宫格目录 + `openableRouteIds`），
   把 `HomeCard` 只列那几块；公告条与消息那一格要等本端有对应的 store 宿主。
3. **屁岱**：要服务端有对应的端点（模型配置 / 会话 / 记忆 / 流式）才谈得上，不在本阶段的范围内。
4. **日程缺的三个端点**：`/api/jwxt/changes` / `exams` / `textbooks` 进 serve 契约（或让
   `/api/jwxt/schedule` 端出**已合并调停补课**的那一份）—— 接上之后 web 日程才与 App 同口径；
   在那之前页顶那一行说明必须留着。
5. **`SEAT_COUNT`**：本端已有 `CampusEmptyRoomApi`，补一个「按教室名取座位数」就能点亮详情里那一格。
6. **`DYNAMIC_COLOR`**：先验证 Monet 在 wasm 上真能跑，再把这一项列进 `honoredRows` 并让 `Main.kt` 读它。

---

## 16. 三端完全一致的决策记录（2026-10-14 会议，用户逐条拍板）

> 这一节是**用户视角的诉求**（「web 端要和 Android 完全对齐 —— 显示内容、界面层级、所有持久化与非持久化行为」）
> 被逼成可执行决策之后的记录。§15 是「怎么把 web 换成共享壳」的实做记录，本节是**下一批的判据真源**。
> 本节只记决策与判据；每条决策的实做过程写进 commit message 与对应的新节，不在本节复述。

### 16.0 会议定下的四条总口径

| # | 议题 | 决策 |
|---|---|---|
| **D7-口径** | 「完全一致」的验收口径 | **丙（逐像素 / 逐状态全等）** —— 显示内容、界面层级、持久化与非持久化行为（键名、落盘形状、迁移、TTL、错误文案、加载态、空态、排序）全部逐条对齐。**基准是 Android 现有行为**（与 C1「Android 行为完全不变」合并读：是 web/桌面对齐到 Android，不是反过来） |
| **D8-口径** | 浏览器能力边界（付款码矩阵 / 分享码 / 相机·剪贴板·WebCrypto / PWA / GameMerge） | **能补就补**；补不了的、或补得不漂亮的，**在大实现结束后给用户一张清单**（过程中不逐条请示）。逐条处置见 §16.1 D12 |
| **D9-口径** | 真站点验收 | **实现方列逐屏点位清单（URL + 点哪里 + 期望），用户照单点**；点不通的由用户回报现象、实现方查 |
| **D10-口径** | Windows CI 与施工顺序 | `desktop-windows.yml` **等大实现做完再触发首跑**；施工顺序由实现方定（§16.3） |

### 16.1 逐条决策

| # | 议题 | 决策 | 要点 / 代价 |
|---|---|---|---|
| **D7** | 写操作 | **三端全等，web 由 `:server` 代操作**（会话在服务端） | 图书馆约座/换座/签到/取消、场馆预订/取消、**含真支付**（见 D11b）、反馈提交、考勤请假/销假、领券、成绩单 PDF 落盘 —— 逐条端出写端点 |
| **D11b** | 场馆「去支付」 | **serve 端一条真支付入口，浏览器里真能付款** | 用户明确「要真能付」。这条把 serve 从「只读代理」变成「有支付能力的远端」⇒ HTTPS + 鉴权（D11）从「加分项」变成**前提** |
| **D8** | 持久化 | **键名逐字 + 统一存档层 + 三端互认** | 立一份「统一存档格式」当唯一真源（目录/文件名 + JSON 形状 + 键名 + 账号后缀 `safeSuffix()` + TTL），三端都能**导出/导入**这套档案、目录**可直接拷过去用**。**Android 内部仍走 SharedPreferences/Room**（不动 C1 红线：`:app` 429 条一条不减、行为逐字不变），但对外形状逐字一致 |
| **D9** | 多账号 | **三端都支持** | Android 用现状（keystore）；桌面 DPAPI（`docs/windows-delivery.md` 那条缝）；**web = serve 进程**（DPAPI 保护），浏览器上「切换账号」= 让 serve 换一套凭据。账号页在 web 上要与 App 同一套层级（不再落「本端没有账号这回事」） |
| **D10** | 屁岱（Agent）在浏览器上 | **key 存 serve 侧（DPAPI），浏览器只发消息，serve 转发 SSE** | 工具层按**白名单**开放（先只读口：黄页 / 教师检索 / 课表 / 空教室 / 校历…）；配置 / 会话 / 记忆三份落盘也归 serve，走 D8 的统一存档层 |
| **D11** | HTTPS / 部署 / 鉴权 | **serve 内置 HTTPS + 鉴权，开箱可启用** | 证书目录（含自签选项）+ 令牌鉴权 + 写进交付文档；反代仍可由用户自己接。**这是相机扫码 / 剪贴板 / WebCrypto / PWA 的前置**（HTTP 非 localhost = 非安全上下文，浏览器一律禁） |
| **D12** | 能力边界逐条 | 全部**在 D11 之后**收口 | ① 付款码矩阵：引纯 Kotlin/JS 的 QR + Code128 编码器（不再只画数字）；② 匹配交友分享码：把 deflate + Base64 两条缝搬进 wasm/`commonMain`；③ 相机扫码 / 剪贴板 / WebCrypto：依赖 D11；④ PWA（系统通知 / 小组件 / 后台定时）：依赖 D11 + 用户授权；⑤ `GameMerge`：**浏览器本身就是 JS 引擎 ⇒ web 端最省**（桌面仍卡载体），按此定各端做法 |
| **D13** | 补端点清单 | **一律「端」**（不再逐条请示） | 我课表三端点 `/api/jwxt/changes`（调停补课）+ `exams`（考试）+ `textbooks`（教材）；教材全文库 `jiaocai1` 页图；学堂附件图片代理；成绩分项 `score-detail`；教室座位数（点亮 web 日程/详情里的 `SEAT_COUNT`）；场馆支付（D11b） |
| **D14** | `:web` 测试源集 | **建** | 丙口径下「没人肉眼点过」不能当验收。目标：路由清单覆盖测试（与 `DesktopRouteCoverageJvmTest` 同型，钉住「清单 = 分支」）+ 存档层互认测试 + 关键屏的 wasmJs 测试 |

### 16.2 「全等」的判据怎么落地（工程口径，四条）

1. **路由全等**：`WebSupport.openableRouteIds` 与 `AppRoute`（43 条）的差集，只允许剩「浏览器/这一端永远做不到」的条目，且**每条都在页面上有如实说明**。
   今天的差集是 7 条：`Agent`（D10 之后要变成真屏）/ `DownloadManager` / `Jiaocai1` / `Jiaocai1Reader`（D13 之后要做）/ `GameMerge`（D12-⑤）/ `Accounts`（D9 之后要做）/ `Browser`（它不是一屏，口径上不算差集）。
2. **存档全等**：统一存档格式是唯一真源 —— 文档 + 夹具 + 三端消费方，**遵 `docs/api-contract.md` §7 的「三处同改」**。
3. **屏全等**：每屏的四种状态（加载 / 空 / 错误 / 降级）三端**同一套文案来源**（共享字符串与共享屏，不在各端各写一遍）。
4. **数字回归网**：§5 那张表**只增不减**（`:app:testDebugUnitTest` **429 条一条不许减**）。

### 16.3 下一批施工顺序（2026-10-14 定，与 §16.1 的 D 编号对应）

| 批 | 内容 | 决策来源 |
|---|---|---|
| ① | 补齐端点（`changes`/`exams`/`textbooks`、`jiaocai1` 页图、学堂图片代理、`score-detail`、座位数） | D13 |
| ② | 统一存档层 + 三端多账号（web 账号页与 App 同层级、凭据进 serve） | D8 / D9 |
| ③ | 写路径对齐（含真支付入口） | D7 / D11b |
| ④ | serve 内置 HTTPS + 鉴权 | D11 |
| ⑤ | 能力边界收口（矩阵 / deflate + Base64 / 相机·剪贴板 / PWA / `GameMerge`）+ **给用户的能力缺口清单** | D12 / D8-口径 |
| ⑥ | `:web` 测试源集 + 全量门禁 + **真站点逐屏点位清单** | D14 / D9-口径 |

### 16.4 这一节带出来的新风险（如实记，别藏）

- **丙全等 × C1 红线**：只允许「各端向 Android 现状对齐」；任何要改 Android 可见行为的做法都要先回到用户面前（D8 的「Android 内部仍走 prefs」就是为这条让的路）。
- **serve 从只读变可写、再变可付**：`0.0.0.0` 明文 + 一枚令牌的现状**不能再拖到 D11 之后**；在 D11 落地之前，**写/支付端点不要在公网口上开着**。
- **统一存档层要迁移**：三端各自的旧数据（Android prefs / 桌面 0600 文件 / web localStorage）都要有一次性读入旧形状的路径，且**旧形状只能读、不能写**（避免两套格式长期并存）。

---

### 16.5 外部审计的处置决策（2026-10-14 第二轮，用户拍板）

一份外部第三方对本项目的全量代码审计（36 条，1423 行）交进来后，按用户指示**审查也派活**：三个只读复核子代理逐条复核，产出
`docs/audit-verify-1-correctness.md`（#01–#12）/ `docs/audit-verify-2-security.md`（#13–#20）/ `docs/audit-verify-3-rest.md`（#21–#36），
再由一个汇总子代理合成 `docs/audit-verification.md`（36 行总表 + 不实之处 + 处置分组 + 统计）。

**复核结论（我方重算，不是转述）**：36 条里**成立 19 / 部分成立 14 / 不成立 2 / 无法判定 1**；审计自报「致命 2 / 高 8」，
我方**重判无一条致命**，重判为「高」的只有 5 条：**#02 / #13 / #14 / #30 / #33**。
审计的路径与行号**大量基于 KMP 重构之前的提交**（被点名的 14 个文件里 12 个已搬进 `core`/`data`），
并有 20 条引文在本仓**任何 ref** 都搜不到（用 `git log --all -S` / `git ls-tree` 核过）。

| # | 议题 | 决策 |
|---|---|---|
| **D15** | 审计发现的处置口径 | **审计发现分两类**（用户 2026-10-14 第二轮纠正）：**(a) 安卓原有行为 / 产品设计问题** —— 包括审计认为「该改」的功能改进（如 #29 社区浏览门槛、#32 分享码默认真名、#19 文案宣称口径、#31 无障碍、#28 首屏性能）⇒ **不属于本次重构范围，一行代码不改**，只写进 `docs/audit-response.md` 如实回应「这是安卓侧既有问题，本次是重构不是功能更新」；**(b) 本次重构自己新写的那部分代码里的缺陷**（`:core` / `:data` / `:desktop` / `:server` / `:web` 新写的部分，以及三端行为不一致）⇒ **修**，因为它们是重构的产物。用户原话：「**安卓怎么做就怎么做，这是重构不是功能更新**」 |
| **D16** | `#01` 算分 Bug | **不在本次重构范围**（它是 `:app` 的既有行为）⇒ **一行代码不改**，写进 `docs/audit-response.md`。若将来能拿到一次真实样本（上游真下发带小数/等级制成绩的抓包）并决定做一次**安卓侧修复**，那是单独一次活，不混进重构 |
| **D17** | 统一存档档案是否带凭据 | **不带**（`docs/storage-layout.md` 未决 #5 关闭）。档案只装用户数据；跨端/跨机迁移**需要重新登录**。理由：桌面/Windows 凭据是 DPAPI 信封，跨机本来解不开；导出凭据只会让人误以为能用 |
| **D18** | 对审计的回应 | **出一份正式回应文档** `docs/audit-response.md`（逐条处置 + 无法核实清单 + 请对方补证据的条目），与三份复核报告一起构成完整答复 |

**由复核带出来的、需要跟着修的接口缺口**（审计没提，复核/接线过程中发现）：

1. **课表「已合并调停补课」在 web 上做不到**：`/api/jwxt/changes` 端的是说明面的 `ScheduleChangeEvent`，
   而 `:data` 的合并函数 `JwxtChanges.apply` 吃的是 `JwxtChange`（含 `jxbid` 认原课、`appliedAt` 定先后）。
   接线批**如实不合并**（格子给排课原样）+ 页顶缺口说明。**要做真合并，走「让 `/api/jwxt/schedule?merged=1` 直接端已合并的那一份」**
   （`:data` 的 `JwxtScheduleApi.schedule()` 本来就会合），不要给说明面端点硬加字段。
2. **`SCHEDULE_CHANGE_BUBBLE` 在 web 上点不亮**：`:app` 那一支走 `InboxStore.post(OwnInbox.scheduleChange(...))`，而工厂 `OwnInbox` **住在 `:app`**
   （其 KDoc 明写「Web 端不产生这些提醒」）。要按丙口径对齐，得**把 `OwnInbox` 搬进 `:core`**（id 形状逐字一致）—— 这与 §15.7 第 2 条「首页的消息那一格要等本端有 store 宿主」是同一件事。
3. **教材中心屏（`TextbookScreen`）的「本学期我的教材」仍未接**：数据现在有端点了，但那一屏的宿主端口吃 `JiaocaiTermTextbook`，要再写一次映射（不是「要学号取不到」）。

### 16.6 审计修复的施工顺序（与 §16.3 的一致性线并行，2026-10-14 定）

| 修批 | 内容 | 与一致性线的交叉 |
|---|---|---|
| 修批 | 内容 | 与一致性线的交叉 |
|---|---|---|
| **P0** | **只做分类，不写代码**：把 36 条按 D15 分成 **(a) 安卓原有行为/产品设计 ⇒ 只进回应文档** 与 **(b) 重构新代码的缺陷 ⇒ 修**；并把这项分类写进 `docs/audit-response.md`（表头：`#NN | 归哪类 | 处置 |`） | 这是后续每一批的判据来源，必须先钉 |
| **P1** | (b) 类里**安全面**：只含「重构新引入的暴露面」——例如 serve 在 `0.0.0.0` 明文 + 单令牌（D11 的 HTTPS + 鉴权）、serve 变可写/可付后的幂等与重放。**审计的 `#13`/`#14`/`#20` 等安卓侧旧问题不在此列** | 与 §16.3 批④（HTTPS）合流 |
| **P2** | (b) 类里**多账号隔离**：只修「三端不一致」那部分（如 `:web`/桌面漏了后缀、统一存档层里 `download_tasks` 没有 `accountId`） | **并入 §16.3 批②统一存档层** |
| **P3** | (b) 类里**正确性**：只修新代码里的坑（例如接线批自己发现的两处：课表合并缺 `jxbid`/`appliedAt` 的接口缺口、教材中心模型映射） | 与 §16.3 批①尾合流 |
| **P4** | (b) 类里**工程与合规**：`#33`（`miuix = "latest.release"` 破坏可重现性 —— 这是**构建**问题，不是安卓行为，属于重构现场）、`#34`（workflow 内联 `${{ }}` 与 token 拼 URL） | 与 CI 同批 |
| **—** | **(a) 类全部：不写代码**（城区门槛 / 真名分享码 / 文案口径 / 无障碍 / 首屏性能 / 内存历史 / 工具 schema 全量 / N+1 / 小组件冷启动 / 更新摘要 / 大文件拆分 / 覆盖率 / `latest.release` 之外的库存建议） | 只出现在 `docs/audit-response.md` |

### 16.7 批②（统一存档层 + 三端多账号）的工单与裁定（2026-10-14 定）

**工单真源 = `docs/storage-layout-plan.md`**（602 行，S0→S8，每个工单都能独立提交、独立跑门禁；判据挂在
`docs/storage-layout.md` §7 的 A1–A13 上）。本节只记主代理替用户对设计里那些"待拍板"点的裁定，以及几处与审计分类相关的更正。

| # | 议题 | 裁定 | 理由 |
|---|---|---|---|
| **R1** | 档案容器：JSON 目录 vs `.properties` 目录（规格未决 #1） | **JSON 目录** | wasm 没有 `java.util.Properties`，要在 commonMain 重写它的转义规则（`\uXXXX`、ISO-8859-1、行续接、`= : # !`）并与 JVM 逐字节往返一致 —— 一份新增的易错代码，而值类型标签（long/float/set）在 Properties 里还得另解决 |
| **R2** | 规格未决 #2：「旧形状只读」在 Android 上做不到 | 口径定为 **「键名逐字 + 档案是端内 store 的投影」** | D8 已拍板 Android 内部仍走 SharedPreferences/Room（不动 `:app` 红线）；旧形状仍要能**读**（一次性导入），但端内**写**路径不变 |
| **R3** | Android 档案落地通道（规格未决 #3） | **SAF**（零权限、零 manifest 改动） | 已核 `file_provider_paths.xml` 里没有 `archive/` 条目；若走分享导出则需追加 `archive/` |
| **R4** | 审计 `#02`（切账号屁岱串号并写盘污染） | **Android 侧不改**（D15 (a) 类）；`:core` 只加可选槽位 `AgentHost.onAccountChanged()`（Android 空实现） | `AgentViewModel` 在 `:core`，真修就是三端一起变、直撞 C1 红线（D15 已明确：安卓侧行为一律不动） |
| **R5** | 审计 `#07` 的三端不一致侧 | **不存在** —— 三端今天**都是无后缀**（已复核 `AppPaymentCode.kt:48` / `ToolboxDesktopApp.kt:759` / `ToolboxWebApp.kt:824`）⇒ 整条归 (a) 类，本次只在 registry 里标 `global` | 回应文档 `docs/audit-response.md` 里 #07 的归类要按此更正 |
| **R6** | `data_extraction_rules.xml` | **追加 `archive*` 排除** | 这是 `:app` 侧**唯一**必动的地方（纯安全排除，不改可见行为）：否则含学号的档案会进云备份 |
| **R7** | `CredentialStore.cached_nickname` 不在规格键清单里 | **回写规格**（补进 `docs/storage-layout.md` §3.2） | 规格是唯一真源，漏键就是将来跨端丢东西 |
| **R8** | 施工序 | `S0`（桌面落盘 0 号前置）→ `S1` → {`S2`,`S3`} → {`S4`,`S5`,`S6`} → `S7a` → `S7b` → `S8` | `S0` 是"档案可直接拷过去用"的前提（桌面那一整族 store 现在只在内存）；`S8`（`:web` 测试源集 + 三端互认）放批末最省 |

**参数提醒**：`S8` 之前，A1/A2/A4 的 **web 侧**与 A5 的**三端形态**跑不了 —— 因为 `:web` 没有测试源集、
`:core` 也没声明 wasm 测试环境（`core/build.gradle.kts:45-52`）。夹具放 `:core` 的 commonTest（Kotlin 常量），**不能**放 `:testkit`（它是 `kotlin.jvm`，wasm 消费不了）。

**施工坑（批②连踩 3 次，后续子代理必读）**：Kotlin 的**块注释可嵌套** —— KDoc 里只要出现 `/` + `*` 的序列
（最典型是路径通配 `files/**` / `cache/**` / `accounts/**`），就**开了一层新的块注释**；外层 KDoc 的 `*/`
只关掉内层 ⇒ 外层注释一路吃到 EOF，把它后面那个声明的 `}` 一起吞掉，报成 `Unclosed comment` 或
`Missing '}'`（报错行号落在**该 KDoc 所属声明**那一行，很容易误判成括号问题）。**KDoc 里不要写 `/**`**
（写成 `files/` + 文字说明）。自查一行：`git grep -n 'files/\*\*\|cache/\*\*\|accounts/\*\*' -- '*.kt'`。

### 16.8 批②（统一存档层 + 三端多账号）与批④（serve HTTPS）完工记录（2026-10-15）

| 工单 | 提交 | 一件事实 |
|---|---|---|
| S0 | `47cc75cd` | 桌面键值存储从**内存**改成**真落盘**（0 号前置；隔离根里真出现 0600 的 `.properties`） |
| S1 / S1b | `65567894` / `6ed4adda` | 档案模型 + codec + 纯函数；**S1b 补上导入合并语义**（§6.4）与 `inspect` 收口 |
| S2 / S3 | `73c4a829` / `4abc4bb0` | 三端只读投影（prefs / 文件 / localStorage）+ `files/` 层与 §2.3 行数组排序 |
| S4 | `1261913b` | `:app` 接线点（Room 三表适配器 / 导入触发 / 导出动作 / SAF 通道 / 备份排除）——`:app` 侧 **+635/−0 纯追加** |
| S5 / S6 | `05ee3a9e` / `07720a6d` | 桌面宿主（数据根扫描 + zip/doc 导出 + 设置页）与 web 宿主（下载/上传） |
| S7a / S7b | `2d0a6f7d` / `f3a75616` | `/api/accounts`（打码学号，与 `SessionRoutes` 既有的 `138****0000` 同形）+ web 账号页（**路由差集 7 → 6**）；桌面 store 名补后缀 |
| S8 | `732f8b17` | **`:web` 首个测试源集**：真 ChromeHeadless 跑，A5 三端互认 + 路由差集覆盖 |
| 门禁自足 | `263b5405` | `:web:wasmJsBrowserTest` 的 Chrome 路径自动探测（原来不设 `CHROME_BIN` 直接失败） |
| **批④** | `ded4c65f` | serve 内置 HTTPS + 收紧鉴权（`--https` 开箱可启用，默认行为逐字不变） |

**我逐个强制重跑复算过的门禁（2026-10-15）**：`:core:jvmTest` **515** · `:server:test` **93** ·
`:desktop:test` **117** · `:data:jvmTest` **259** · `:app:testDebugUnitTest` **444**（一条未减）·
`:web:wasmJsBrowserTest` **5 例 / 0 失败**；web 产物重建 `:web:wasmJsBrowserDistribution` 26m36s，
三份流式 md5 与盘上一致、js 只引用新 wasm 名、新 wasm 里能搜到 `accounts`/`archive` 指纹。

**批④的引擎裁定（写在这里免得后人重踩）**：Ktor 的 **CIO 引擎不接受 `sslConnector`**——配上去会在那个端口
继续讲明文（静默降级）。为了不引 `ktor-server-netty`（`:server` 选 CIO 的理由写在 `server/build.gradle.kts` 文件头），
采用 **JDK 终结 TLS（`SSLServerSocket`）+ 回环字节级转发给 CIO**；keep-alive / 分块编码 / 将来的 SSE 在这一层都是
一段字节流。备选方案（换 Netty、只给 HTTPS 用）记在 `ServeHttps.kt` 的文件头，改动是局部的。

**仍未接（如实）**：`StoreArchive.import` 已经在 `:core`（S1b），但**三端的导入写侧**还只到「自校验 → 如实报告」——
真合并要后续工单把 S4/S5/S6 的导入接到它上（`:app` 侧要单独授权）。

### 16.9 批③-a（写路径对齐里的场馆「去支付」/ D11b）完工记录（2026-10-15）

| 项 | 落地内容 |
|---|---|
| 端点 | `GET /api/venue/pay?orderId=…` —— **302 + `Location`**（D11b 要的那个真支付入口的**入口那一跳**）。它是 `/api/…` 家族里**第二条不当信封答**的端点（第一条是 `/api/status` 的裸对象），例外清单写在契约 §4 |
| `:core` | `CampusVenueApi.paymentUrl` 在 serve 形态从「空串」改成 serve 自己的代跳地址（`/api/venue/pay?orderId=…`）；`browserLoginUrl` **仍是空串** |
| `:web` | 新宿主 `WebVenueHost`（判据 `venuePayTarget`：只开一个标签、优先支付页；两个地址都空时在页顶如实写一句）+ `ToolboxWebApp` 那条分支改指它；`WebVenuePayTargetTest` **3 例** |
| 夹具 | `:testkit` 的 `VenueFakeUpstream` 新增 `PAYMENT_PAGE_PATH` / `paymentPageUrl`（这一族第一次动夹具；夹具**不扮演**那一页） |
| 门禁 | `:server:test` **93 → 95** · `:web:wasmJsBrowserTest` **5 → 8 例**；其余四项计数未变（`:core:jvmTest` 515 / `:desktop:test` 117 / `:data:jvmTest` 259 / `:app:testDebugUnitTest` 444） |

**D11b 的原话是「浏览器里真能付款」，本批只做到一半，如实记下**：这一跳**一定被浏览器跟随**（顶层 302），
但**落成能付款的一页不担保** —— 支付页要的是**它自己那个站点在浏览器这一侧的会话**，而 serve 的会话只在
serve 进程的 cookie 罐里（页面替不了别的域名写 cookie）。Android 之所以能，是因为它的内置浏览器是**进程内**的
WebView：`BrowserScreen.syncCookiesToWebView` 把 App 的 cookie 罐灌进 `CookieManager`，再用
`onPageFinished` + `isAuthHop` 串第二跳 —— 浏览器里没有这两手。逐条依据写在契约 §5.3.2 第 6 条
（含 `data/…/auth/VenueLogin.kt` 的 `PAY_BASE_URL` KDoc 那句「要求浏览器自身的会话」。）
⇒ 页面上如实写「**跳过去后若要求登录，请用 App / 桌面端支付**」，**不许**读成「本端已支持真支付」；
这一格的「第一次点到底落在哪一页」**没能在线验证**（不许真发起支付、也没探测那台支付站），
以点位清单 `docs/serve-visual-checklist.md` ⑨ 那一行、用户点出来的现象为准。要把它做成「浏览器里真能付」，
下一步是让 serve **代理**支付页（同源发出去、连表单与 JS 一起转）—— 那是新的写/支付暴露面，需单独授权。
施工坑照旧：KDoc 里不要写 `/**`（§16.7）。

