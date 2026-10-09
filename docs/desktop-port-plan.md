# 桌面端（Linux / Windows）移植方案 · 决策记录

> 状态：**设计已完毕**（2026-10-09，用户逐条确认）。基线 HEAD = `4a73495`。
> 本文只写"目标 + 决策 + 分阶段 + 验收判据"。每一步的动机若写进 commit message，本文不再复述。

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

### Stage B · 写路径

图书馆约座/换座/签到/取消、场馆预订/取消、付款码。
**验收**：与 Android 的写能力等价（按钮出现、动作成功）。

### Stage C · 宿主机能族 → 真 40/40

设置 / 多账号 / 下载管理 / 首页 / 通知。（小组件用通知替代。）
**验收**：40 条路由在桌面都有真实实现（`NotPortedScreen` 归零）。

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
| 其余路由 | ✓ 不再借 campus-api 画画面，而是 `NotPortedScreen` 如实列出「屏在了、取数还在 `:app`」（12 条） |
| Android 行为不变 | ✓ `:app` / `:web` **diff 为空**（本轮改动只落在 `:core` 新增文件、`:data` 新增文件、`:desktop`、新模块 `:testkit`） |
| 证据 | ✓ `:desktop:renderScreens` 出 5 张图：`login.png` / `library-after-login.png`（**真登录后的座位图**，含当前预约 A01 与平面图）/ `shell-after-login.png` / `routes.png` / `library-demo.png` |
| 装配验收测试 | ✓ `:desktop:test` 的 `DesktopAuthLibraryJvmTest`（8 例）：真登录、错密码不落盘、读路径、冷启动免密、会话失效重登重放、退出即清除 |

**这一轮落下的手法（下一步照抄）：**

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

**这一轮没做的**：`Sites.kt` 里其余 18 个站点仍留在 `:app`（它们各自的 `*Login` + 功能包里的解析类要一起搬）；
校园网判定（WebVPN 网关）在桌面端仍缺席 —— 故**校外直连不上**，属 §5.4；serve 模式、网络栈迁移、
40/40 之外的清洁（理由同 §4/§8）。
