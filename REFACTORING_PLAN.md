# 登录与会话架构重构计划

> 状态：草案 v1.0 (2026-05-16)
> 范围：`auth/`、`MainActivity` 中的 `AppLoginState` / 启动 warmup / MFA 全套、所有业务 API 类的 Login 类型耦合
> 目标：脱胎换骨，一次到位地解决"混乱不稳、复用失败、功能残废"

## 一、现状病灶（深度诊断）

| # | 现象 | 根因 |
|---|---|---|
| 1 | **13 个 Login 子类**（JwxtLogin / JwappLogin / YwtbLogin / AttendanceLogin / LibraryLogin / CampusCardLogin / DzpzLogin / VenueLogin / CouponLogin / ClassLogin / LmsLogin / JiaocaiLogin / GmisLogin / GsteLogin） | 把"取局部 token / sessionid"误当成 Login 类的责任，实际是 `postLogin` 钩子的活 |
| 2 | **Login 既是认证凭证，又是业务句柄** | 字段里塞了 token、sessionid、userInfo；缓存 Login 对象时 client 引用失效就崩 |
| 3 | **MFA 信号 + 回调地狱**（`pendingMfaLogin / pendingMfaTarget / pendingMfaType / onMfaCodeVerified`） | 11 路并行 autoLogin 各自可能触发 MFA，需要全局可变信号 + 串行 mutex 互斥 |
| 4 | **`mfaSerialMutex` 全局串行锁 + 重入判断** | 补丁——掩盖"为什么会有 11 路并发请求 MFA"的真问题 |
| 5 | **`useWebVpn: Boolean` 参数透传到所有 LoginType** | 只有 `AttendanceLogin` 真用了，其他子系统忽略；语义模糊 |
| 6 | **`vpnClient` + `sharedClient` 双 client 切换** | 网络切换时手动 `clearAllCachedLogins() + clearVpnClient()`，因为 cached Login 持有的旧 client 引用失效 |
| 7 | **`startBackgroundLoginWarmup` 11 路并行 + 错峰 150ms** | 补丁——为了规避 RSA 公钥端拥塞和 MFA 冲突，引入手动调度 |
| 8 | **业务 API 类绑定 Login 类型**（`JwappApi(JwappLogin)` / `CjcxApi(JwxtLogin)` / `ScheduleApi(JwxtLogin)`） | 业务类需要哪个 client、哪个 token 不应是它的关注点 |
| 9 | **"复用失败"** | sharedClient 持有 CAS TGC 是真，但每个子系统还得各自走一遍 OAuth/SSO callback 拿局部 token；Login 对象本身又持有局部 token 字段 → 缓存的"复用"只是表象 |
| 10 | **网络切换 cookie 污染** | sharedClient 和 vpnClient 共享一个 PersistentCookieJar，jwxt.xjtu.edu.cn 和 jwxt-xxx.webvpn 路径混在一起 |

## 二、上游设计哲学（yan-xiaoo / XJTUToolBox）

```
┌──────────────────────────────────┐
│  NewLogin (CAS 状态机)           │  → 唯一登录类，状态机驱动 MFA
│   ├─ login() → LoginState 枚举   │     REQUIRE_MFA / REQUIRE_CAPTCHA / SUCCESS / FAIL / REQUIRE_ACCOUNT_CHOICE
│   ├─ MFAContext (独立对象)        │
│   └─ postLogin() 钩子             │
└──────────────────────────────────┘
        │ 继承
        ▼
┌──────────────────────────────────┐
│  NewWebVPNLogin                  │  → 14 行子类，自动 URL 改写为 webvpn 形式
│   └─ 重写 _get / _post           │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│  SessionBackend                  │  → 每个 AccessMode 一个 backend
│   ├─ access_mode (NORMAL/WEBVPN) │     cookies 物理隔离
│   ├─ session (cookies + UA)      │     login_lock 防并发
│   └─ login_lock                  │
└──────────────────────────────────┘
        │ 包含
        ▼
┌──────────────────────────────────┐
│  CommonLoginSession (站点基类)   │  → 站点只关心如何"取自己的局部 token"
│   ├─ _login(username, password)  │     (postLogin 钩子的等价物)
│   ├─ validate_login()            │     用一个轻请求探活
│   ├─ is_auth_failure_response()  │     识别认证失败
│   └─ ensure_login() (统一入口)   │     先 validate，失败才 _login
└──────────────────────────────────┘

┌──────────────────────────────────┐
│  业务类（Score / Schedule / ...） │  → 只接 session，不关心怎么登录的
│   def __init__(self, session)    │
└──────────────────────────────────┘
```

**核心洞察**：

1. **登录是动作（建立 session），不是身份（业务句柄）。** Login 对象用完就扔。
2. **每个 AccessMode 一个 SessionBackend，cookies 物理隔离。** 网络切换不污染。
3. **业务类只接 session。** 谁登录的、用什么 client 登录的，业务不关心。
4. **MFA 是状态机的状态，不是全局信号。** 谁触发的 login，谁处理 MFA，状态机直接 return。

## 三、目标架构

### 3.1 类层次

```
auth/
├── UnifiedLogin.kt           ← 单一 CAS 状态机类（替代 13 个 Login 子类的核心逻辑）
│   ├── LoginState (enum)     ← REQUIRE_MFA / REQUIRE_CAPTCHA / SUCCESS / FAIL / REQUIRE_ACCOUNT_CHOICE
│   ├── MFAContext            ← 独立对象，封装 phone / sendCode / verifyCode
│   └── login(): suspend (LoginState, info)
│
├── WebVpnLogin.kt            ← 继承 UnifiedLogin，14 行重写 URL → webvpn
│
├── SessionBackend.kt         ← 一个 access mode 一个 backend
│   ├── accessMode (NORMAL/WEBVPN)
│   ├── client: OkHttpClient  ← 持有自己的 PersistentCookieJar
│   ├── loginLock: Mutex
│   └── hasLogin / lastUsed
│
├── SiteSession.kt            ← 业务站点会话基类（替代 13 个 *Login.kt）
│   ├── backend: SessionBackend
│   ├── siteKey (e.g. "jwxt", "jwapp", "library", ...)
│   ├── localToken: Map<String, String>   ← 局部 token 存这里（jwapp JWT、attendance token、library sessionid）
│   ├── ensureLogin(username, password)
│   ├── abstract suspend fun runLogin()    ← 子类只实现"建好 CAS session 后如何取局部 token"
│   ├── validateLogin(): Boolean           ← 子类轻请求探活
│   └── isAuthFailureResponse(resp): Boolean
│
└── SessionManager.kt         ← 顶层管家（替代 AppLoginState 中的会话部分）
    ├── backends: Map<AccessMode, SessionBackend>
    ├── sites: Map<String, SiteSession>
    ├── currentAccessMode: AccessMode  ← 根据 isOnCampus 解析
    ├── ensureWebvpnLogin()             ← 校外时 WebVPN 自身先登录
    └── getSite(siteKey): SiteSession
```

### 3.2 业务 API 改造

```kotlin
// 旧
class JwappApi(private val login: JwappLogin) {
    private val client = login.client
    private val token = login.jwtToken
    fun getGrade() = ...
}

// 新
class JwappApi(private val site: SiteSession) {
    fun getGrade() = site.executeWithReAuth(
        Request.Builder().url("...").build()
    )
}

// 调用处
val site = sessionManager.getSite("jwapp")
val api = JwappApi(site)
api.getGrade()
```

### 3.3 MFA 流程（状态机驱动）

```kotlin
// 调用方（UI 层）
val login = UnifiedLogin(loginUrl = JWXT_URL)
var (state, info) = login.login(username, password)
while (true) {
    when (state) {
        LoginState.SUCCESS -> { /* session 已认证 */ break }
        LoginState.FAIL -> { showError(info as String); break }
        LoginState.REQUIRE_MFA -> {
            val ctx = info as MFAContext
            val phone = ctx.sendVerifyCode()
            val code = uiAskUserMfaCode(phone)  // suspend，等用户输入
            ctx.verifyPhoneCode(code)
            val (s, i) = login.login()  // 无参数继续推进
            state = s; info = i
        }
        LoginState.REQUIRE_CAPTCHA -> { ... }
        LoginState.REQUIRE_ACCOUNT_CHOICE -> { ... }
    }
}
```

**优势**：
- MFA 信号 = 状态机的某个状态，不是全局可变变量
- 一次 login 流程内只有一个 MFA 弹窗（不可能并发）
- 状态机驱动 = 调用方知道下一步做什么，不需要看回调
- 无需 `mfaSerialMutex` 全局锁

## 四、网络切换边界处理

> 这是最关键、最易出错的部分。新架构必须在以下所有场景下"无感"工作。

### 4.1 用例矩阵

| 场景 | 触发条件 | 期望行为 |
|---|---|---|
| **A. 校内 → 校外** | WiFi 切换到 4G | NORMAL backend 的 cookies 仍保留（重新进校内时直接复用）；切到 WEBVPN backend；触发 WebVPN 自身登录（如未登录） |
| **B. 校外 → 校内** | 4G 切换到校园 WiFi | WEBVPN backend cookies 保留；切到 NORMAL backend；如果之前在 NORMAL 已登录，直接复用 |
| **C. 校外冷启动** | 用户首次校外打开 app | NORMAL backend 不创建网络请求；直接走 WEBVPN backend；先登 webvpn，再用 webvpn-CAS 登业务站点 |
| **D. 校内冷启动** | 用户首次校内打开 app | 直接走 NORMAL backend；如有需要的子系统按需懒登录 |
| **E. WEBVPN 失效** | webvpn cookie 过期 / 服务端 invalidate | `validateLogin` 返回 false；自动重登 webvpn；业务请求重放 |
| **F. 校内 vpnClient 仍可用** | 用户切回校内但 webvpn cookies 还有效 | 不强行清空 WEBVPN backend cookies（用户切回校外可秒复用） |
| **G. NORMAL 中某站点失效但其他正常** | 比如 jwapp token 过期，jwxt 还活 | 只对 jwapp 单点重 _login，不影响 jwxt 等 |
| **H. 网络切换中正在请求** | 切换瞬间有 in-flight 请求 | 失败的请求自动 `retry-after-auth-refresh`；不抛 timeout 给用户 |
| **I. 用户主动 logout** | 退出账号 | 同时清空 NORMAL + WEBVPN 两个 backend 的 cookies；删除 persistent storage |
| **J. WEBVPN backend 未建立时业务调用** | warmup 还没跑完，用户立刻点功能 | 业务调用阻塞在 `ensureLogin`（带 timeout），不抛"离线模式" |
| **K. WebVPN MFA 与 JWXT MFA** | 校外冷启动两次 MFA 都需要 | 状态机串行：先 webvpn 状态机走完，再 JWXT 状态机；用户体感是两次连续提示，但永远不会"重叠" |
| **L. MFA 弹窗中切换网络** | 用户在 MFA 弹窗时网络变了 | 当前 login 状态机被 cancel；弹窗关闭；新 access mode 启动新 login（如需要） |

### 4.2 关键不变量（Invariants）

1. **每个 SessionBackend 的 cookies 仅属于该 access mode**，永不混淆。
2. **同一时刻，一个 SiteSession 只能有一个 ensureLogin 在进行**（`loginLock`）。
3. **同一时刻，一个 SessionBackend 上的 WebVPN 自身登录只能有一个**（`SessionManager.webvpnLoginLock`）。
4. **MFA 不会跨网络切换持续**：切换瞬间正在进行的 login 协程被 cancel，弹窗自动关闭。
5. **MFA 永远只有一个弹窗**：状态机串行驱动，不可能并发触发。
6. **业务 API 失败 → 自动 invalidate + 重登 + 重放，不向用户暴露 401/网络错**。

### 4.3 网络监听与切换响应

```kotlin
// SessionManager.onNetworkChanged()
fun onNetworkChanged(newMode: AccessMode) {
    val oldMode = currentAccessMode
    if (oldMode == newMode) return

    // 取消所有正在进行的 login 协程（不清 cookies！）
    activeLoginJobs.forEach { it.cancel("Network changed: $oldMode → $newMode") }
    activeLoginJobs.clear()

    // 标记切换
    currentAccessMode = newMode

    // 不主动登录任何东西；下次业务 ensureLogin 会自然触发
    // 也不清空旧 backend 的 cookies，等下次切回去可以秒复用
}
```

## 五、分阶段迁移路径

### Phase 1：新建状态机（不动旧代码）

**产出**：
- `auth/UnifiedLogin.kt`（≈400 行）：CAS 状态机 + MFAContext + RSA 加密
- `auth/WebVpnUnifiedLogin.kt`（≈30 行）：WebVPN URL 改写子类
- `auth/LoginState.kt`：枚举
- 单元测试（用 MockWebServer 验证 MFA 状态推进）

**验证**：在一个隔离的 dev screen 中调用 `UnifiedLogin`，确认 MFA 流程能跑通。

**风险**：低。旧代码不动。

### Phase 2：SessionBackend + SiteSession 框架

**产出**：
- `auth/SessionBackend.kt`：双 backend 容器
- `auth/SiteSession.kt`：业务站点基类
- `auth/SessionManager.kt`：顶层管家
- 注册 5 个核心 site：`jwxt`, `jwapp`, `attendance`, `library`, `campus_card`（先 5 个，验证通过再加剩余）

**验证**：
- 在 `MainActivity` 里同时持有旧 `AppLoginState` 和新 `SessionManager`；
- 在一个新页面（DebugScreen）里测 `sessionManager.getSite("jwapp").executeRequest(...)`；
- 切换网络验证 cookies 隔离。

**风险**：中。需要 PersistentCookieJar 双实例，要确保磁盘存储不冲突。

### Phase 3：业务 API 类去耦合

**产出**：
- `JwappApi(site)`、`CjcxApi(site)`、`ScheduleApi(site)`、`EmptyRoomApi(site)` 等改为接 `SiteSession`
- 旧 `JwappApi(JwappLogin)` 等保留 deprecated 兼容期一段时间

**验证**：
- 每个改造完的页面手动跑通；
- 关键路径：成绩查询、课表、空教室、图书馆、校园卡。

**风险**：中。业务 API 调用点散布全 app，要逐个迁移。

### Phase 4：MFA UI 状态机化

**产出**：
- `MfaController.kt`：状态机 host，处理 `LoginState.REQUIRE_MFA` 时弹 dialog
- 删除 `pendingMfaLogin / pendingMfaTarget / pendingMfaType / onMfaCodeVerified` 全局信号
- 删除 `mfaSerialMutex`（不再需要，状态机串行天然不冲突）

**验证**：
- 校外冷启动 → WebVPN MFA → JWXT MFA：两次连续，不重叠
- MFA 弹窗中切换网络 → 自动取消，无悬挂

**风险**：高。MFA 涉及用户体验关键路径，需要充分测试。

### Phase 5：启动 warmup 简化

**产出**：
- `startBackgroundLoginWarmup` 改为只触发 `JWXT.ensureLogin()`（建立 SSO 锚点）
- 其他子系统改为**懒登录**：用户进入页面才触发
- 删除 11 路并行 + 错峰 150ms + `clearAllCachedLogins`

**验证**：
- 冷启动只弹一次 MFA（JWXT）
- 用户进入未登录的页面，能在 1-2 秒内透明完成登录

**风险**：低。简化代码。

### Phase 6：清理旧代码

**产出**：
- 删除 13 个 `*Login.kt` 子类
- 删除 `LoginType` 枚举（用 siteKey 字符串替代）
- 删除 `AppLoginState` 中 13 个独立 login 字段
- 删除 `useWebVpn` 参数

**验证**：
- 编译通过
- 完整回归测试

**风险**：低（只是清理）。

## 六、风险评估与回滚

| 阶段 | 风险等级 | 回滚成本 |
|---|---|---|
| Phase 1 | 低 | 删除新文件即可 |
| Phase 2 | 中 | 删除新文件 + 还原 cookieJar 配置 |
| Phase 3 | 中 | 业务 API 类按文件回滚 |
| Phase 4 | 高 | 还原 MFA 全局信号 + mutex（建议在独立分支） |
| Phase 5 | 低 | 还原 warmup 函数 |
| Phase 6 | 低 | 不应该需要回滚（只清理） |

每个 Phase 独立 commit、独立可发布。如果某 Phase 出问题，前一 Phase 的产出仍可用。

## 七、成功指标

重构完成后，应能观察到：

- **代码量净减少 1500-2000 行**（13 个 Login 子类 + warmup 错峰 + MFA 信号代码消失）
- **MFA 弹窗永远不会重叠**（状态机驱动）
- **网络切换不会出现"已连接 N 系统但全部失败"**（cookies 隔离 + lazy validateLogin）
- **业务 API 失败自动透明重登**（site.executeWithReAuth 统一处理）
- **用户感知的 spinning 状态消失**（懒登录 + 失败重试，无超时弹窗）
- **冷启动只看到一次 MFA**（JWXT 唯一锚点 + 其他懒登录）

## 八、附录

### 8.1 站点局部 token 清单（Phase 2 注册时使用）

| siteKey | 局部 token 名 | 取 token 路径 |
|---|---|---|
| `jwxt` | (无，仅 cookies) | jwxt-CAS service callback |
| `jwapp` | `jwt_token` | jwapp-CAS service → response 提取 JWT |
| `attendance` | `auth_token` | bkkq OAuth → URL `?token=` 提取 |
| `pg_attendance` | `auth_token` | yjskq OAuth → URL `?token=` 提取 |
| `library` | (无，仅 cookies) | rg.lib.xjtu.edu.cn:8086 CAS |
| `campus_card` | `php_session_id` | ncard CAS ticket → /portal Cookie |
| `dzpz` | `loginidweaver` | dzpz OAuth → cookie |
| `venue` | `oauth_code` | org.xjtu.edu.cn OAuth |
| `coupon` | `auth_token` | 自定义 OAuth |
| `class` | (无) | CAS service |
| `lms` | (无) | CAS service |
| `jiaocai` | `frReport_session` | FR 报表 session |
| `ywtb` | `userInfo` (data) | ywtb 自定义 |

### 8.2 PersistentCookieJar 拆分

每个 SessionBackend 用一个独立 SharedPreferences key：
- NORMAL backend: `cookies_normal.xml`
- WEBVPN backend: `cookies_webvpn.xml`

切换网络时不动 SharedPreferences，仅切换内存中的 active backend。

### 8.3 与现有 Compose UI 的兼容

`LocalAppLoginState` 保留，但内部代理到 `SessionManager`：
- `loginState.jwxtLogin` → `sessionManager.getSite("jwxt")`
- `loginState.loginCount` → `sessionManager.activeSiteCount`

UI 代码无需改动，迁移期内 API 等价。
