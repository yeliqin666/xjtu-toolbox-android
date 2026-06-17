# 登录与会话架构重构计划 v2.0

> 状态：定稿 v2.0 (2026-06-17，纳入 issue #22 + yan-xiaoo 源码精读修订)
> 范围：`auth/`、`MainActivity` 中的 `AppLoginState` / 启动 warmup / MFA 全套、所有业务 API 类的 Login 类型耦合、网络切换处理
> 目标：脱胎换骨，一次到位地解决"混乱不稳、复用失败、功能残废"——遇到不适配的旧代码全删

---

## 一、当前架构十大病灶

| # | 现象 | 根因 |
|---|---|---|
| 1 | **13 个 `*Login.kt` 子类**（Jwxt/Jwapp/Ywtb/Attendance/Library/CampusCard/Dzpz/Venue/Coupon/Class/Lms/Jiaocai/Gmis/Gste）| 把"取局部 token / sessionid"误当成 Login 类的责任；实际是 `postLogin` 钩子的活 |
| 2 | **Login 对象 = 认证凭证 + 业务句柄** | 既塞 token/sessionid/userInfo，又是 OkHttpClient 持有者；缓存它就等于把"登录动作"和"业务调用"耦合 |
| 3 | **MFA 信号 + 回调地狱** | `pendingMfaLogin / pendingMfaTarget / pendingMfaType / onMfaCodeVerified` 全局可变变量；11 路并行 autoLogin 各自可能触发 MFA |
| 4 | **`mfaSerialMutex` 全局串行锁 + 重入判断** | 补丁——掩盖"为什么会有 11 路并发请求 MFA"的真问题 |
| 5 | **`useWebVpn: Boolean` 参数透传** | 只有 `AttendanceLogin` 真用，其他子系统忽略；语义模糊 |
| 6 | **`vpnClient` + `sharedClient` 双 client 各持独立 cookieJar** | **直接导致 issue：webvpn-MFA 后的 TGC 写在 vpnClient cookieJar，jwxt 直连用 sharedClient 看不到 → 二次触发 MFA** |
| 7 | **`startBackgroundLoginWarmup` 11 路并行 + 错峰 150ms** | 补丁——为了规避 RSA 公钥端拥塞和 MFA 冲突手动调度 |
| 8 | **业务 API 类绑定 Login 类型**（`JwappApi(JwappLogin)` / `CjcxApi(JwxtLogin)` / `ScheduleApi(JwxtLogin)`）| 业务类需要哪个 client、哪个 token 不应是它的关注点 |
| 9 | **"复用失败"** | 各子系统各自走一遍 OAuth/SSO callback；Login 对象本身又持有局部 token；缓存"复用"只是表象 |
| 10 | **issue #22 — 密码错误连锁封号** | 11 路并行 autoLogin，任一系统密码错误时其余仍并行打 CAS，连续 401 触发服务端风控直接封号；当前无熔断机制 |

---

## 二、MFA 触发模型（最重要的不变量）

> **❗这是整个重构的灵魂：理解清楚了，所有边界处理水到渠成。**

### 2.1 MFA 触发条件

```
触发 = (CAS 端无 fpVisitorId 设备信任标志)
     AND (mfaEnabled = true)
     AND (POST /cas/mfa/detect 返回 need=true)
```

`fpVisitorId` 全局唯一（用 `cfg.loginId`），TGC 一旦写入 `backend.session.cookies`，CAS 端会标记该设备为可信任，**同 backend.session.cookies 内的所有后续 CAS 访问全部 SSO 直通**。

### 2.2 期望 MFA 次数（重构后必须达成）

| 场景 | webvpn 自身 MFA | 业务 CAS MFA | 总计 |
|---|---|---|---|
| **校园网首次冷启动**（NORMAL backend 无 cookies）| 不需要 | **1**（首个走 CAS 的子系统触发，之后所有 SSO 复用）| **1** |
| **校园网热启动**（NORMAL backend 有有效 cookies）| 不需要 | 0（SSO 直通）| **0** |
| **校外首次冷启动**（WEBVPN backend 无 cookies）| **1**（同时建立 login.xjtu.edu.cn 域 TGC）| 0（TGC 已建）| **1** |
| **校外热启动** | 0 | 0 | **0** |
| **网络切换** | 切换到的 backend 若 cookies 失效 → 1，否则 0 | | 最多 1 |

> **当前 app 在校外冷启动看到 "WebVPN MFA + JWXT MFA 两次"** 的根因是：`vpnClient` 和 `sharedClient` 各自独立的 PersistentCookieJar，webvpn-MFA 写入的 TGC 不被 sharedClient 看到，导致 jwxt 直连时 CAS 重新要求 MFA。**重构必须把 cookies 收编到 SessionBackend 双实例里——同 backend 内所有子系统共享 session.cookies。**

### 2.3 关键引用：上游 NewLogin 的 SSO 复用判定

```python
# auth/new_login.py:278
if not initial_safety_verify and self.execution_input is None and "/cas/login" not in response.url:
    self._already_authenticated_response = response  # → login() 直接返回 SUCCESS
```

`NewLogin(loginUrl, session=backend.session)` 构造时立即 GET loginUrl，CAS 看到 TGC 即 302 到 service URL，`execution_input` 为空 → 标记为 SSO 已认证 → `login()` 直接 `LoginState.SUCCESS`，**完全跳过 MFA**。

---

## 三、不走标准 CAS 的特殊子系统

> ⚠️ 上游 `CommonLoginSession` 仅纳入"通过 CAS service callback 完成认证"的子系统。下面这些不能直接套同一基类，重构时要单独建模。

| 子系统 | 路径 | 与 CAS MFA 关系 | 重构归属 |
|---|---|---|---|
| **校园卡 ncard** | 独立 ticket SSO；凭据从 `org.xjtu.edu.cn` 取 | 不直接触发 MFA；依赖同 backend 已建 TGC | `SiteSession` 子类；`runLogin()` 不调 CAS NewLogin，直接走 ticket 流程 |
| **DZPZ 电子票证** | CAS → OAuth → cookie | 走 CAS，复用 TGC，无 MFA | 标准 `SiteSession` |
| **Coupon 餐券** | 自定义 OAuth | 复用 TGC | 标准 `SiteSession` |
| **Venue 场馆** | OAuth code | 复用 TGC | 标准 `SiteSession` |
| **JIAOCAI 教材中心** | FineReport 独立 frReport_session | CAS service ticket → 换 frReport cookie | 标准 `SiteSession`，但 `validateLogin()` 用 frReport-specific 探活接口 |
| **GMIS 研究生** | CAS service callback | 复用 TGC | 标准 `SiteSession` |
| **Library 图书馆** | rg.lib.xjtu.edu.cn:8086 CAS | 复用 TGC | 标准 `SiteSession` |
| **JWXT / JWAPP / LMS / Attendance** | 标准 CAS | 同 backend 第一次触发，之后 SSO | 标准 `SiteSession` |

---

## 四、目标架构

```
auth/
├── UnifiedLogin.kt           ← CAS 状态机；当前 XJTULogin 的纯化版（剔除业务字段、剔除 init 立即网络）
│   ├── LoginState (enum)     ← REQUIRE_MFA / REQUIRE_CAPTCHA / SUCCESS / FAIL / REQUIRE_ACCOUNT_CHOICE
│   ├── MFAContext            ← MFA_DETECT + SAFETY_VERIFY 两路
│   └── login(): LoginResult
│
├── WebVpnUnifiedLogin.kt     ← 继承 UnifiedLogin，30 行重写 GET/POST → URL 改写为 webvpn
│
├── SessionBackend.kt         ← 每个 AccessMode 一个 backend
│   ├── accessMode (NORMAL/WEBVPN)
│   ├── client: OkHttpClient  ← 持有 PersistentCookieJar(prefsName = "cookies_${accessMode}")
│   ├── loginLock: Mutex      ← 防并发登录
│   ├── webvpnHasLogin / hasLogin / lastUsed
│   └── clearAuthState() / markValidated()
│
├── SiteSession.kt            ← 业务站点会话基类（替代 13 个 *Login.kt）
│   ├── backend: SessionBackend  ← 由 SessionManager 注入，与 access_mode 一致
│   ├── siteKey / siteName / supportsWebVpn
│   ├── localToken: MutableMap<String, String>  ← 局部 token (jwapp JWT、attendance Synjones-Auth、ncard php_session_id …)
│   ├── ensureLogin(username, password): suspend
│   ├── abstract suspend fun runLogin(...)      ← 子类只实现"建好 CAS 后如何取局部 token"
│   ├── abstract suspend fun validateLogin(): Boolean
│   ├── isAuthFailureResponse(resp): Boolean
│   ├── executeWithReAuth(req): Response        ← 业务 API 通用入口；401/SafetyVerify 自动 invalidate + 重登 + 重放
│   └── handleAuthFailure(401): 通过 SessionManager.reportAuthFailure() 触发全局熔断
│
└── SessionManager.kt         ← 顶层管家
    ├── backends: Map<AccessMode, SessionBackend>
    ├── sites: Map<String, SiteSession>
    ├── currentAccessMode: AccessMode  ← 由网络监听更新
    │
    ├── ─── 登录入口 ───
    ├── ensureWebvpnBackendLogin()      ← WEBVPN backend 自身登录（仅 WEBVPN access_mode 时调用）
    ├── getSite(siteKey): SiteSession
    │
    ├── ─── 网络监听 ───
    ├── onNetworkChanged(newMode)       ← cancel 所有 in-flight login，切换 access_mode，cookies 不动
    ├── resolveAccessMode(): AccessMode ← 探测 jwxt.xjtu.edu.cn 是否可达，5min 缓存
    │
    ├── ─── 全局熔断 (issue #22) ───
    ├── @Volatile var passwordInvalidated: AtomicBoolean
    ├── reportAuthFailure(siteKey, http401)  ← 任一站点 401 即设置 latch + cancel 所有 active login jobs
    ├── clearPasswordInvalidatedLatch()      ← 用户更新密码后调用
    │
    ├── ─── MFA 状态机宿主 (Phase 4) ───
    ├── activeMfaFlow: StateFlow<MfaUiState?>
    └── consumeMfaCode(code: String)
```

### 4.1 业务 API 改造范例

```kotlin
// 旧
class JwappApi(private val login: JwappLogin) {
    private val client = login.client
    private val token = login.jwtToken
    fun getGrade() = client.newCall(...).execute()
}

// 新
class JwappApi(private val site: SiteSession) {
    fun getGrade() = site.executeWithReAuth(
        Request.Builder()
            .url("https://jwapp.xjtu.edu.cn/api/...")
            .header("Authorization", site.localToken["jwt_token"]!!)
            .build()
    )
}

// 调用处
val site = sessionManager.getSite("jwapp")
JwappApi(site).getGrade()
```

### 4.2 MFA 状态机驱动（无全局信号）

```kotlin
// SiteSession.runLogin()
val login = UnifiedLogin(loginUrl = JWXT_URL, client = backend.client)
var result = login.login(username, password)
while (true) {
    when (result.state) {
        LoginState.SUCCESS -> { takeLocalTokens(login); break }
        LoginState.FAIL -> {
            if (result.message.contains("用户名或密码")) {
                sessionManager.reportAuthFailure(siteKey, http401 = true)
            }
            throw LoginException(result.message)
        }
        LoginState.REQUIRE_MFA -> {
            val ctx = result.mfaContext!!
            val phone = ctx.sendVerifyCode()
            // ↓ 把 MFA 状态推送给 UI，suspend 等待用户输入
            val code = sessionManager.askMfa(siteKey, siteName, phone)
            ctx.verifyCode(code)
            result = login.login()  // 推进状态机
        }
        LoginState.REQUIRE_CAPTCHA -> { ... }
        LoginState.REQUIRE_ACCOUNT_CHOICE -> { ... }
    }
}
```

UI 层只观察 `sessionManager.activeMfaFlow`，**永远只有一个 MFA 流程在跑**（被 `backend.loginLock` + `site.loginLock` 串行化），无需全局 mutex。

---

## 五、网络内外网切换边界处理

### 5.1 用例矩阵（必须全部支持）

| 场景 | 期望行为 |
|---|---|
| **A. 校内 → 校外** | NORMAL cookies 保留（再切回时秒复用）；切到 WEBVPN backend；触发 webvpn 自身登录（如未登录） |
| **B. 校外 → 校内** | WEBVPN cookies 保留；切到 NORMAL backend；如已登过，直接复用 |
| **C. 校外冷启动** | NORMAL backend 不发起任何请求；走 WEBVPN backend；先登 webvpn → 业务子系统懒登录 |
| **D. 校内冷启动** | 走 NORMAL backend；首个进入页面的子系统懒登录 |
| **E. WEBVPN 失效** | `validateLogin` false → 自动重登 webvpn → 业务请求重放 |
| **F. 校内 vpnClient 仍可用** | 不强清 WEBVPN cookies，留作切回用 |
| **G. 单子系统失效但其他正常** | 只对失效站点单点重登，不影响其他 |
| **H. 网络切换中正在请求** | in-flight 请求失败时 `executeWithReAuth` 自动重试，不暴露 timeout |
| **I. 用户主动 logout** | 同时清空两个 backend cookies；删除持久化存储 |
| **J. WEBVPN backend 未建立时业务调用** | 业务 `ensureLogin` 阻塞（带 timeout），不进 fake 离线模式 |
| **K. WebVPN MFA 与 业务 MFA** | **新架构下：永远只有 1 次 MFA**——webvpn-MFA 后 TGC 已写入 backend.session.cookies，业务子系统 SSO 直通 |
| **L. MFA 弹窗中切换网络** | 当前 login 协程 cancel；弹窗关闭；新 backend 启动新 login |
| **M. 任一子系统返回 401（issue #22）** | `SessionManager.reportAuthFailure()` 设置全局熔断 latch；cancel 所有 in-flight login jobs；后续 ensureLogin 立即抛 `PasswordInvalidatedException`；UI 弹窗"密码可能已修改，请进入设置更新" |

### 5.2 七大不变量

1. **每个 SessionBackend 的 cookies 仅属于该 access_mode**，永不混淆。
2. **同一时刻一个 SiteSession 只能有一个 ensureLogin 在进行**（`site.loginLock`）。
3. **同一时刻一个 SessionBackend 上的 webvpn 自身登录只能有一个**（`backend.loginLock`）。
4. **MFA 不会跨网络切换持续**：切换瞬间正在进行的 login 协程被 cancel，弹窗自动关闭。
5. **MFA 永远只有一个弹窗**：状态机串行驱动，不可能并发触发。
6. **业务 API 失败 → 自动 invalidate + 重登 + 重放**，不向用户暴露 401/网络错。
7. **任一子系统密码错误 → 立即全局熔断**，停止其他 autoLogin（issue #22）。

### 5.3 网络切换响应代码骨架

```kotlin
fun onNetworkChanged(newMode: AccessMode) {
    val oldMode = currentAccessMode
    if (oldMode == newMode) return

    // 取消所有正在进行的 login 协程；in-flight 业务请求让它自然失败由 executeWithReAuth 处理
    activeLoginJobs.forEach { it.cancel("Network changed: $oldMode → $newMode") }
    activeLoginJobs.clear()

    currentAccessMode = newMode
    // ❗ 不主动登录任何东西；不清空旧 backend cookies；下次业务调用自然触发 lazy ensureLogin
}
```

---

## 六、分阶段迁移路径

### Phase 1.5（**优先做，因为 issue #22 灾难性最大**）：密码错误熔断

**产出**：
- `MainActivity.AppLoginState` 增加 `passwordInvalidatedLatch: AtomicBoolean`
- `autoLogin` 在每个子系统调用前检查 latch，已置位则立即 short-circuit 返回
- 在 401 / "用户名或密码错误" 错误处置位 latch + cancel 所有 active login jobs
- 弹窗 `PasswordInvalidatedDialog`：提示用户"密码可能已修改，请在设置中更新密码"
- 用户更新密码后清除 latch

**代码量**：≈150 行，不破坏现有架构。

**风险**：低；可作为 hot-fix 单独发布。

### Phase 1：UnifiedLogin 状态机净化

**产出**：
- 把 `XJTULogin` 重命名为 `UnifiedLogin` 并搬出业务字段
- `init { }` 块中"立即 GET loginUrl"逻辑挪到 `prepare()` 显式方法，构造时不发网络
- 提取 `fpVisitorId` 为单例（`cfg.loginId`）

**风险**：中；需要回归 13 个子类的 init 假设。

### Phase 2：SessionBackend + SiteSession + SessionManager 三件套

**产出**：
- `auth/SessionBackend.kt`、`SiteSession.kt`、`SessionManager.kt`
- `PersistentCookieJar` 双实例：`cookies_normal` / `cookies_webvpn`
- 注册 5 个核心 site 验证：`jwxt`, `jwapp`, `attendance`, `library`, `lms`

**风险**：中。

### Phase 3：业务 API 类去耦合 + 校园卡 / OAuth 子系统

**产出**：
- `JwappApi(site)`、`CjcxApi(site)`、`ScheduleApi(site)`、`EmptyRoomApi(site)`、`AttendanceApi(site)` 等改为接 `SiteSession`
- 校园卡 / DZPZ / Coupon / Venue / JIAOCAI / GMIS 各注册一个 `SiteSession` 子类，`runLogin()` 实现各自的 token 取法

**风险**：中。

### Phase 4：MFA UI 状态机化

**产出**：
- `SessionManager.activeMfaFlow: StateFlow<MfaUiState?>`
- Compose `MfaDialog` 只观察这个 flow，不再用 `pendingMfaLogin/onMfaCodeVerified` 全局信号
- **删除** `mfaSerialMutex`、`pendingMfaLogin`、`pendingMfaTarget`、`pendingMfaType`、`onMfaCodeVerified`

**风险**：中-高，需要测试校外冷启动单 MFA 路径。

### Phase 5：启动 warmup 简化

**产出**：
- `startBackgroundLoginWarmup` 改为只触发 1-2 个核心 site（jwxt 或 webvpn-self），其他全部懒登录
- 删除 11 路并行 + 错峰 150ms + `clearAllCachedLogins`
- Phase 1.5 的 `passwordInvalidatedLatch` 迁移到 `SessionManager.passwordInvalidated`

**风险**：低。

### Phase 6：清理（**全删旧代码**）

**产出**：
- **删除** 13 个 `*Login.kt` 子类
- **删除** `LoginType` 枚举
- **删除** `AppLoginState` 中 13 个独立 login 字段
- **删除** `useWebVpn: Boolean` 参数透传
- **删除** `vpnClient` / `sharedClient` 双 client 切换胶水代码
- **删除** `clearAllCachedLogins` / `clearVpnClient` 等补丁
- 编译通过 + 完整回归测试

**风险**：低（只是清理）；预期净减 1500-2000 行代码。

---

## 七、Phase 1.5 详细设计（issue #22）

### 7.1 触发条件

任一子系统执行 `XJTULogin.login()` 收到：
- HTTP 401
- 或 `LoginResult(state=FAIL, message="用户名或密码错误")`

### 7.2 行为

1. `AppLoginState.passwordInvalidatedLatch.set(true)`（AtomicBoolean）
2. 遍历 `loginScopes` 取消所有 active login Job
3. 触发 `passwordInvalidatedDialogVisible.value = true`
4. 后续每次 `autoLogin(siteKey)` 前检查 latch，已置位则立即 return
5. UI 显示对话框，提示"密码已被修改，请在设置中更新密码"+ 跳转设置按钮

### 7.3 清除时机

- 用户在设置页保存新密码（`saveCredentials` 后立即 `passwordInvalidatedLatch.set(false)`）
- 用户主动 logout 后

### 7.4 防误判

仅在**确凿的密码错误**（401 或明确错误消息）时熔断；网络错误、CAS 服务端 5xx、MFA 失败等不触发。

### 7.5 与重构的衔接

Phase 5 重构时把这套搬到 `SessionManager.passwordInvalidated` 上，UI dialog 改为观察 StateFlow，无需修改用户感知行为。

---

## 八、风险评估与回滚

| 阶段 | 风险 | 回滚成本 |
|---|---|---|
| Phase 1.5 | 低 | 单 commit revert |
| Phase 1 | 中 | 还原 XJTULogin |
| Phase 2 | 中 | 删新文件 + 还原 cookieJar |
| Phase 3 | 中 | 业务 API 类按文件回滚 |
| Phase 4 | 高 | 还原 MFA 全局信号；建议独立分支 |
| Phase 5 | 低 | 还原 warmup |
| Phase 6 | 低 | 不应需要回滚 |

每个 Phase 独立 commit、独立可发布。

---

## 九、成功指标

- **代码量净减 1500-2000 行**（13 个 Login 子类 + warmup 错峰 + MFA 信号代码消失）
- **MFA 弹窗永远不会重叠**（状态机驱动）
- **校外冷启动只看到 1 次 MFA**（webvpn 自身登录时同时建立 TGC）
- **网络切换不会出现"已连接 N 系统但全部失败"**（cookies 隔离 + lazy validateLogin）
- **业务 API 失败自动透明重登**（site.executeWithReAuth 统一处理）
- **用户感知的 spinning 状态消失**（懒登录 + 失败重试，无超时弹窗）
- **issue #22 解决**：密码错误时立即停止其他 autoLogin，弹窗提示用户更新密码

---

## 十、附录

### 10.1 站点局部 token 清单（Phase 3 注册时使用）

| siteKey | 局部 token 名 | 取 token 路径 | 是否走 CAS |
|---|---|---|---|
| `jwxt` | (无，仅 cookies) | jwxt CAS service callback | ✅ |
| `jwapp` | `jwt_token` | jwapp CAS service → response 提取 JWT | ✅ |
| `attendance` | `synjones_auth` | bkkq OAuth → URL `?token=` 提取 | ✅ |
| `pg_attendance` | `synjones_auth` | yjskq OAuth → URL `?token=` 提取 | ✅ |
| `library` | (无，仅 cookies) | rg.lib.xjtu.edu.cn:8086 CAS | ✅ |
| `campus_card` | `php_session_id` | ncard 独立 ticket（凭据来自 org.xjtu.edu.cn）| ⚠️ 间接 |
| `dzpz` | `loginidweaver` | dzpz OAuth → cookie | ✅ |
| `venue` | `oauth_code` | org.xjtu.edu.cn OAuth | ✅ |
| `coupon` | `auth_token` | 自定义 OAuth | ✅ |
| `class` | (无) | CAS service | ✅ |
| `lms` | (无) | CAS service | ✅ |
| `jiaocai` | `frReport_session` | FR 报表 session | ✅ |
| `gmis` | (无) | CAS service | ✅ |

### 10.2 PersistentCookieJar 双实例

```kotlin
val normalCookieJar = PersistentCookieJar(context, prefsName = "cookies_normal")
val webvpnCookieJar = PersistentCookieJar(context, prefsName = "cookies_webvpn")

val normalBackend = SessionBackend(AccessMode.NORMAL, cookieJar = normalCookieJar)
val webvpnBackend = SessionBackend(AccessMode.WEBVPN, cookieJar = webvpnCookieJar)
```

切换网络时不动磁盘，仅切 active backend。

### 10.3 与现有 Compose UI 的兼容

`LocalAppLoginState` 保留兼容期，内部代理：
- `loginState.jwxtLogin` → `sessionManager.getSite("jwxt")`
- `loginState.loginCount` → `sessionManager.activeSiteCount`

UI 代码无需改动，迁移期 API 等价。

### 10.4 Phase 1.5 issue #22 弹窗文案

```
标题：登录密码已变更
内容：检测到您的统一身份认证密码已被修改。
     为避免连续登录失败导致账号锁定，已暂停其他系统自动登录。
     请进入「设置」更新密码后继续使用。
按钮：[去设置]  [稍后]
```
