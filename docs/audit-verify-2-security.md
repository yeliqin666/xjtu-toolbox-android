# 外部审计复核（第二组：安全与隐私 #13 ~ #20）

- 仓库根：`/mnt/nas/data/works/xjtu-toolbox-android`
- 分支 / HEAD：`refactor/kmp-multiplatform` @ `5e9b2e53`
- 复核方式：只读（read / grep / sed / git log / git show），逐条读当前 HEAD 的源码与历史，未跑任何构建或测试
- 被复核对象：`/tmp/audit-20261014.md` 第 2 节（行 548–872）
- 口径：**以当前 HEAD 为准**。审计引用的部分代码/行号在 HEAD 上不存在（下面逐条标明），凡「审计引文与 HEAD 不符」都作为复核结论的一部分写出。
- 合规声明：本报告不含任何真实凭据、令牌、学号或姓名；涉及疑似密钥字面量处只描述长度并略去取值。

## 判定总表

| # | 判定 | 我重判的严重度 | 一句话结论 | 主要证据（路径:行号） |
|---|---|---|---|---|
| #13 | 部分成立 | 高 | 深链与外部 Intent 确实能**无任何确认**触发「自动发送 + 全部工具」，Markdown 图片也是真实的外带通道；但「致命」过重：链条要模型配合，图片只认 `https://`，`web_fetch` 已有建连级内网拦截 | `app/src/main/AndroidManifest.xml:44-64`；`app/src/main/java/com/xjtu/toolbox/MainActivity.kt:101-110`；`app/src/main/java/com/xjtu/toolbox/nav/DeepLinkRouter.kt:13,27-44`；`core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentScreen.kt:104-110`；`app/src/main/java/com/xjtu/toolbox/agent/AndroidAgentHost.kt:416-456`；`core/.../agent/AgentMarkdown.kt:174,229-231,367-378`；`app/.../agent/AgentTool.kt:1302-1311`；`app/.../agent/AgentWeb.kt:82-145,175-176` |
| #14 | 成立 | 高 | `CookieManager` 里的 CAS `TGC` 等 cookie **从不清理**（全仓 `removeAllCookies` 零命中）；切账号只清 WebStorage，登出/删账号连 WebStorage 都不清 | `app/src/main/java/com/xjtu/toolbox/account/AccountManager.kt:66-72,96-98,245-295`；`app/src/main/java/com/xjtu/toolbox/browser/BrowserScreen.kt:100-135,397,419-420` |
| #15 | 部分成立 | 中 | HEAD 源码里**没有**任何硬编码凭据（改走 CI 环境变量 + 已 gitignore 的 `feedback.properties`，`propOrEnv` 在本仓从未存在）；但 secret 确实会被打进 release APK，反编译取 secret 换 token 读回复表这条链在架构上仍然成立 | `app/build.gradle.kts:32-59`；`.github/workflows/build.yml:74-78`；`data/src/commonMain/kotlin/com/xjtu/toolbox/feedback/FeedbackApi.kt:20-31,85,116-137,173-210` |
| #16 | 部分成立 | 中 | 备份排除清单与审计引文**完全不同**（审计引的是旧版名字），但仍确实漏排 `campus_probe`、`xjtu_toolbox.db`、`agent_images`、`agent_memory`、`inbox` 等；「登出/删账号不清 `campus_probe`」不成立（有钩子清） | `app/src/main/res/xml/data_extraction_rules.xml:1-32`；`app/src/main/java/com/xjtu/toolbox/auth/CampusProbe.kt:50,66-80`；`app/.../auth/AppLoginState.kt:373`；`data/.../auth/SessionManager.kt:462-471,496-499`；`app/.../data/AppDatabase.kt:174-177`；`app/.../agent/AndroidAgentHost.kt:356`；`core/.../agent/AgentMemory.kt:118` |
| #17 | 部分成立 | 低 | 「`followRedirects(true)` 覆盖了公网策略」**不成立**：策略本体在本仓是建连时的 `GuardedSocket`，而审计引的 `PublicOnlyDns` / `followSslRedirects(false)` 在本仓全部历史里零命中；图片客户端确实没挂策略（但只走 `https`） | `app/.../agent/AgentWeb.kt:124-145,175-176`；`app/.../agent/AgentTool.kt:1302-1311`；`app/.../agent/AndroidAgentHost.kt:424-436`；`core/.../agent/AgentMarkdown.kt:174,229-231` |
| #18 | 部分成立 | 低 | 「只剥 `Log.d`/`Log.v`」与「`SiteSession` 明文打 URL+响应片段」「`AccountManager` 明文打学号」都属实；但审计举的「`LmsApi` 打印带 token/timestamp 签名的下载 URL」在 HEAD 不成立（打的是校内无签名 `blob` 地址） | `app/proguard-rules.pro:40-44`；`data/.../auth/SiteSession.kt:338-342,409-413`；`app/.../account/AccountManager.kt:93,185,272,294`；`app/.../account/AccountMigration.kt:41,114`；`data/.../lms/LmsApi.kt:268,272,283,293,299,338-342` |
| #19 | 部分成立 | 低 | 「记住的偏好 · 只存在这台设备上，不上传」与实际「每次对话整段拼进 system prompt 发给用户自己的第三方模型」确实不符，README 说「每类工具都能单独关掉」而工具开关已删也属实；但审计引的文案、`add_todo`/`complete_todo` 工具在 HEAD 已不存在 | `core/.../agent/AgentConfigPanel.kt:354-359`；`core/.../agent/AgentMemory.kt:49-52,106`；`core/.../agent/AgentViewModel.kt:425-430`；`core/.../agent/AgentPrompt.kt:50`；`README.md:55`；`data/.../agent/AgentConfigStore.kt:67-68`；`app/.../agent/AgentTool.kt:53,63,70-72` |
| #20 | 成立 | 中 | 文件名直接拼 `tag_name` 派生的 `info.version`，下载完只查前两字节是否 `PK`，不校验摘要、不校验包名/签名 | `app/src/main/java/com/xjtu/toolbox/update/AppUpdater.kt:243,263-270,286-296,307-315` |

---

## #13 深链 `xjtu://agent?q=` 无确认自动执行 + 间接注入 + Markdown 图片外带

**审计原文的断言**：外部网页/二维码/第三方 App 通过 `xjtu://agent?q=`（乃至 `intent://…S.extra_launch_route=…`）就能让屁岱**无确认自动**发送问句、拿到 27~29 个工具的权限；被注入的资料文本可操纵它把姓名/学号/成绩/课表/消费记录拼成 Markdown 图片 URL 外带，还能静默写持久偏好与应用设置。

**我读到的代码事实**

1. 入口是导出的、带 `BROWSABLE` 的自定义 scheme，且**没有 host 限定**：

```xml
<!-- app/src/main/AndroidManifest.xml:44-64 -->
<activity android:name=".MainActivity" android:exported="true" ...>
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="xjtu" />
    </intent-filter>
```

2. 启动意图的解析**不区分内外部调用者**，data 与 extras 一视同仁地喂给同一通道：

```kotlin
// app/src/main/java/com/xjtu/toolbox/MainActivity.kt:101-110
private fun consumeLaunchIntent(intent: Intent?) {
    val deepLink = intent?.let(DeepLinkRouter::resolve)
    launchRoute.value = deepLink?.route
        ?: intent?.getStringExtra(EXTRA_LAUNCH_ROUTE)?.let(::appRouteOf)
    launchTab.value = intent?.getStringExtra(EXTRA_LAUNCH_TAB)?.let { ... }
    (deepLink?.prompt ?: intent?.getStringExtra(EXTRA_LAUNCH_PROMPT))
        ?.let { AgentPendingPrompt.set(it) }
}
```

`xjtu://agent?q=` → `DeepLinkTarget(AppRoute.Agent, q)`（`nav/DeepLinkRouter.kt:31-35`，其 KDoc 第 13 行自陈「交给 AgentScreen **自动发送**」）；`EXTRA_LAUNCH_ROUTE` 的值走 `appRouteOf`（`core/.../nav/AppRoute.kt:196-213`），所以 `browser?url=…` 这种**参数化路由**也能由外部 Intent 直接指定。

3. 收到就发，没有任何确认：

```kotlin
// core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentScreen.kt:104-110
LaunchedEffect(config.isConfigured, AgentPendingPrompt.generation) {
    if (!config.isConfigured) return@LaunchedEffect
    val pending = AgentPendingPrompt.consume() ?: return@LaunchedEffect
    showConfig = false
    vm.newSession()
    vm.sendMessage(pending.text, config, llmAnnex = pending.snapshot)
}
```

（审计引的是 `AgentScreen.kt:91-97` / `vm.send(q, config, …)`；HEAD 上这一屏已搬进 `:core`，条件键从 `config.isReady` 变为 `config.isConfigured`，**语义未变**。）

4. 写操作工具确实无二次确认：`preference`（`AgentTool.kt:418-424` → `memory.remember`）、`app_setting`（`AgentTool.kt:478-484` → `AndroidAgentHost.kt:161-291` 的键白名单写入 `network_mode` / `account_type` / `receive_preview_updates` …）、`add_schedule_event`（`:441-451`）。`set_alarm` 走系统闹钟界面（`AndroidAgentHost.kt:114` `EXTRA_SKIP_UI=false`），那一档有系统级确认。工具总数 **27**（`AgentToolCatalog.kt` 里 27 处 `arr.add(agentTool(`），不是审计写的 29；`add_todo`/`complete_todo` 在 HEAD 不存在。全仓无「工具级确认条/审批」实现（grep `确认|confirm|approve|Approval` 只命中配置页的免责说明与 `awaiting_user_confirm` 文案）。

5. 图片外带通道真实存在，但有两条限制：**只认 `https://`**，且加载客户端没挂公网策略：

```kotlin
// core/.../agent/AgentMarkdown.kt:174
private val imageLineRe = Regex("""^!\[([^\]]*)]\((https://[^)\s]+)\)$""")
// :367-378 —— 渲染到屏幕即发请求
androidx.compose.runtime.LaunchedEffect(url) { if (bitmap == null && !failed) { bitmap = images.load(url); ... } }
```

```kotlin
// app/.../agent/AndroidAgentHost.kt:424-436
private val client by lazy { HttpClients.base.newBuilder()
    .connectTimeout(8, …).readTimeout(15, …).build() }   // 没有 applyPublicNetworkPolicy()
override suspend fun load(url: String): ImageBitmap? {
    if (!url.startsWith("https://") || url in failed) return null
```

系统提示词也明说 App 显示图片（`AgentPrompt.kt:25`），审计引的 `AgentPrompt.kt:25` 位置对得上。

6. 审计说的「间接注入面缺隔离标记」基本属实：`ToolReply.EXTERNAL_DATA` 只加在 5 处（`AgentTool.kt:1258,1271,1648,2369,2397`，分别属于 `getNotifications` / `webSearch` / `webFetch`）；`readZyxfFile`（`:1746-1774`）、`getLmsActivityDetail`（`:2062-2100`）、`readLmsAttachment`（`:2106-2140`）、`findFaculty`（`:505+`）确实直接返回外部文本、**没有**边界标记（标记常量见 `core/.../agent/ToolReply.kt:12`）。

**判定与理由**：**部分成立**。
- 成立的部分：「外部入口 → 无确认自动发送（前提：用户已配好 API Key，即 `config.isConfigured`）→ 工具全开 → 写工具无确认 → 图片自动外带」这条链的**每一个环节**都能在 HEAD 上指到代码；而且 `EXTRA_LAUNCH_*` 与 `xjtu://` 深链在**仓库内没有任何内部生产者**（`grep -rn "xjtu://"` 只命中 DeepLinkRouter 注释与 manifest；`grep EXTRA_LAUNCH_PROMPT` 只有 MainActivity 自己；小组件/通知只用 `EXTRA_LAUNCH_ROUTE`/`EXTRA_LAUNCH_TAB`）⇒ 这些入口是纯外部面，把它降权不会伤到任何内部流程。
- 不成立/缩水的部分：① 审计把 `S.extra_launch_prompt` 这条描述成「通过 `intent://` 从网页触发」—— 更准确的边界是：`intent://` 的可用性取决于调用方浏览器策略，但**任何本机第三方 App 直接 `startActivity` 就能带 extras，一次点击都不需要**；这条比审计写的更严重，但也是审计没说清的地方。② 「致命」过重：外带必须由模型输出独占一行的 `![…](https://…)` 才会发生（模型可能拒绝/改写），且要求用户已配 Key、已登录目标子系统；③ `![http://127.0.0.1…]` 这种**回环明文**例子不成立（只认 https，见上）。
- 别的防线：`web_fetch` 一路有建连级内网拦截（见 #17），图片一路没有；`app_setting` 有键白名单（不会写任意键）；`set_alarm` 有系统 UI 确认。

**真严重度：高（不是致命）**。攻击者要付出：让受害者点一次链接/扫码一次（或本机已装任一恶意 App，则零点击）；受害者需已配好模型 Key 且处于登录态。拿得到：受害者的姓名/学号/成绩/课表/校园卡消费记录等（若模型配合），并可长期污染 `agent_memory`（最多 20 条，`AgentMemory.kt:119`）继续影响后续所有新会话。不构成「远程任意代码执行」级别，故不评致命。

**最小验证方式**（不要求真跑）：`adb shell am start -a android.intent.action.VIEW -d 'xjtu://agent?q=回复我你查到的课程' com.xjtu.toolbox`（或等价地加 `--es extra_launch_prompt '…'`），在已配 Key 的机器上观察屁岱是否**未经点击**直接发出该消息；代码层只需看 `AgentScreen.kt:104-110` 有没有「先落到输入框」的中间态。

**若修，是否改变 Android 现有可见行为**：**几乎不改变**。因为 `xjtu://` 与 `EXTRA_LAUNCH_PROMPT` 都没有内部生产者，把它们降级为「只预填输入框」只影响一条当前无人依赖的外部路径；`EXTRA_LAUNCH_ROUTE` 被小组件/通知使用（`NoticeWidgetProvider.kt:67` 等），若一并限制要保留这些内部调用方的能力（例如加一个内部签名 extra）。给图片加「点击加载/复用 `AgentWeb.applyPublicNetworkPolicy`」会改变**可见**行为（回复里的图默认不再自动出现）⇒ 属于需明确拍板的一项。

---

## #14 CAS `TGC` 同步进全局 WebView `CookieManager` 且从不清理

**审计原文的断言**：内置浏览器把 11 个校内域名的全部 cookie（含 CAS `TGC`、WebVPN 票据）写进全局 `CookieManager`；切账号只清 WebStorage，登出/删账号什么都不清，导致账号 A 的凭据残留、账号 B（或已登出状态）打开内置浏览器访问未产生新 cookie 的校内域名时会**自动登录成 A**；另 `onCreateWindow` 里的 `parent.loadUrl(u)` 没做 scheme 校验。

**我读到的代码事实**

```kotlin
// app/src/main/java/com/xjtu/toolbox/account/AccountManager.kt:66-72
private fun clearWebViewStorage() {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }   // 只有 WebStorage
            .onFailure { Log.w(TAG, "clear WebStorage failed: ${it.message}") }
    }
}
```

- 它只被 `switchToLocked` 调用（`:96-98`）；`logoutCurrent()`（`:284-295`）与 `removeAccountLocked()`（`:245-278`）**完全没调**。
- 全仓 `grep -rn "removeAllCookies\|removeSessionCookies" --include=*.kt --include=*.java --include=*.kts` → **0 命中**（审计这条属实）。
- 唯一会清账号存储的地方是 `wipeAccountFiles`（`:318-345`），它清的是 SharedPreferences / files / Room 行，**不含** WebView 的 cookie 库。

注入侧：

```kotlin
// app/src/main/java/com/xjtu/toolbox/browser/BrowserScreen.kt:107-135
val domains = (listOf(
    "login.xjtu.edu.cn", "cas.xjtu.edu.cn", "org.xjtu.edu.cn",
    "jwxt.xjtu.edu.cn", "ywtb.xjtu.edu.cn", "bkkq.xjtu.edu.cn",
    "ncard.xjtu.edu.cn", "rg.lib.xjtu.edu.cn", "jwapp.xjtu.edu.cn",
    "lms.xjtu.edu.cn", "webvpn.xjtu.edu.cn"
) + extraDomains).distinct()
for (domain in domains) {
    val cookies = jar.loadAllForHost(domain)
    for (cookie in cookies) { … webCookieManager.setCookie("https://$domain/", cookieStr) … }
}
webCookieManager.flush()
```

调用点在每次页面开始时（`BrowserScreen.kt:419-420`：当前账号 `site` 与当前账号的 WebVPN client），并且 `BrowserScreen.kt:397` 还开了 `setAcceptThirdPartyCookies(this, true)`。`onCreateWindow` 的 `parent.loadUrl(u)`（`:507`）确实没有像 `shouldOverrideUrlLoading`（`:468-475`）那样先判 `http(s)`。

**判定与理由**：**成立**（作为一条「凭据生命周期」缺陷），且审计关于 `removeAllCookies` 的观察在 HEAD 依然准确。
- 可达性：设备本地即可复现，不需要远程攻击者 —— 用户自己切账号后，用另一个账号打开内置浏览器访问一个「新账号还没碰过」的校内域名（例如 `cas.xjtu.edu.cn`、`jwapp.xjtu.edu.cn`），WebView 就带着旧账号的 `TGC`；CAS 会据此免密登录 ⇒ 单点登录覆盖几乎所有校内系统。登出后同理（`logoutCurrent` 不碰 WebView），「登出」在浏览器这一路实际不生效。
- 有没有别的防线：**没有**。OkHttp 侧的 cookie jar 是按账号命名空间隔离的（`sessionManager.reconfigureForAccount`），这恰恰放大了不对称：网络层已隔离，WebView 层没隔离。`WebStorage` 清理只挡住 localStorage 那一路（`AccountManager.kt:96-98` 的注释也承认「移动交大等页面把令牌存在 localStorage」）。
- `onCreateWindow` 的 scheme 缺口：属实，但把「`<a target=_blank href="javascript:…">` 一定会在父页执行」当作必然结论**证据不足**（`shouldOverrideUrlLoading` 对 `javascript:` 的触发行为随 WebView 版本而异，我没有实机验证），我把它记为**附带风险**而非独立结论。

**真严重度：高**。攻击者要付出：拿到设备（或与该账号共用同一台设备的下一个使用者）＋打开内置浏览器访问校内站点；拿得到：CAS 单点登录根凭据 `TGC` 所及范围内**以受害者身份**的所有校内系统会话（含 WebVPN 票据 `wengine_vpn_ticket`）。不是远程可利用，所以不是致命；但「登出/删账号后凭据仍在，且能免密登录」是本条的核心，量级高于一般的本地信息泄露。

**最小验证方式**：`grep -rn removeAllCookies app/`（预期 0 命中）；`AccountManager.kt:66-72` 与 `:284-295` 对照阅读；实机验证：账号 A 用内置浏览器打开一次 `webvpn.xjtu.edu.cn` → 切到账号 B（或登出）→ 内置浏览器打开 `cas.xjtu.edu.cn` 看是否直接是 A 的登录态。

**若修，是否改变 Android 现有可见行为**：**不改变功能行为**（只是把「残留登录态」去掉）。唯一可见影响是用户如果本来就靠残留 cookie 免密，会变成需要重新走一次 CAS —— 这正是正确语义。建议只在 `clearWebViewStorage()` 里补 `CookieManager.removeAllCookies(null/false)` + `flush()`（审计的建议 1 可用），或在切账号/登出/删账号三处都调一次。

---

## #15 飞书应用凭据硬编码进客户端

**审计原文的断言**：`app/build.gradle.kts:43-59` 用 `propOrEnv("FEEDBACK_APP_ID", "cli_…")` 等形式硬编码了 app_id / app_secret / base_token / 两张表 id；线上 APK 反编译即可取出，进而全表读回复、灌垃圾、打爆免费配额。

**我读到的代码事实**（本节按合规要求**略去所有取值**，只给长度与位置）

```kotlin
// app/build.gradle.kts:32-59（HEAD 原文，节选）
// 反馈后端（飞书多维表格）的凭据。优先读环境变量（CI），否则读
// 仓库根目录 feedback.properties（已 gitignore）。两者都没有时留空字符串——
// FeedbackApi.isConfigured 为 false，反馈页降级成 GitHub Issue。
run {
    val fp = rootProject.file("feedback.properties")
    val props = Properties().apply { if (fp.exists()) fp.inputStream().use { load(it) } }
    fun cfg(key: String): String { … System.getenv(name)… ?: props.getProperty(key)… }
    buildConfigField("String", "FEEDBACK_APP_ID", "\"${cfg("appId")}\"")
    buildConfigField("String", "FEEDBACK_APP_SECRET", "\"${cfg("appSecret")}\"")
    …
}
```

- HEAD **没有任何密钥字面量**：`grep -rIno "cli_[A-Za-z0-9]*"` 全仓 0 命中；`git log --all -S "cli_a954f12e30381bd6"`（审计引的那串）**0 命中**；`git log -S "FEEDBACK_APP_ID"` 命中的都是「把它改成注入式」的那几次提交，`propOrEnv` 这个名字在本仓全部历史里不存在。审计引的 `app/build.gradle.kts:43-59` 与实际 HEAD 的同段代码语义相反（HEAD 恰恰是**不硬编码**）。
- `feedback.properties` 在 `.gitignore:61`（`release.jks`/`keystore.properties` 同样在列，`:19-21`），CI 侧用 secrets 注入：`.github/workflows/build.yml:74-78`（另一处 `:301-305`）。
- 但 secret 确实**进 APK**：它是 `buildConfigField`，`FeedbackApi` 用它换 `tenant_access_token`（`FeedbackApi.kt:116-137`，`POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal`）。项目自己的 KDoc 也承认这一点（`FeedbackApi.kt:20-31`：「`app_secret` 打进安装包，反编译必然能取到，所以按『泄露了也不痛』来设计权限」），并写明权限分离：提交表只写、回复表只读、回复表里不放用户原文。
- 审计说的「`fetchAll(token, ids.toSet(), useFilter = false)` 无条件全表遍历」在 HEAD 不成立：`replies()`（`FeedbackApi.kt:173-210`）按工单号 `contains` 过滤，且 KDoc 明说 `useFilter=false` 那条兜底路径**实际跑不到**。
- 配额 DoS 的信号在代码里存在（`FeedbackStore` 一带的注释提到基础版每月约 1 万次配额），但这不是「硬编码」的后果，而是「凭证在客户端」的后果 —— 与源头同一件事。

**判定与理由**：**部分成立**。
- 不成立：审计给出的**证据**（源码里硬编码的具体字面量、`propOrEnv`、`:147` 的全表拉取）在 HEAD 与全部历史里都不存在，属于对旧版或臆测的描述。
- 成立：结论方向（客户端持有可换 token 的 secret，反编译即可取得，且该 token 权限包含「读整张回复表」「写提交表」）在架构上仍然成立 —— 只要 release 包带着 CI 注入的凭据发布。审计建议的「把 secret 收回服务端、经 `server/` 转发 + 限流」在这条链上依然是正确方向。
- 别的防线：密钥不进仓库（gitignore + CI secrets）；表级权限分离（只读回复表、只写提交表）；凭据缺失时整条反馈链降级为外链（`FeedbackApi.isConfigured`，`:96-101`）。

**真严重度：中**。攻击者要付出：拿到任一渠道发布的 APK 并反编译（成本很低，脚本级），前提是构建时确实注入了凭据；拿得到：换出 tenant token → 读回复表全量（工单号+开发者回复，不含用户原文）、往提交表灌垃圾、耗尽当月配额使全校反馈瘫痪。评「中」而不是「高」的原因：泄露面被作者用表级权限显式压过（回复表不含用户原文），影响是数据/可用性而非账号接管；也不能评「低」，因为配额瘫痪对「反馈+崩溃上报」这条链是全局性的。

**最小验证方式**：`grep -n "buildConfigField" app/build.gradle.kts`（看取值来源）、`git ls-files | grep -i "feedback.properties\|keystore.properties"`（预期空）、`sed -n '20,35p' data/src/commonMain/kotlin/com/xjtu/toolbox/feedback/FeedbackApi.kt`（读它自己写下的安全模型）；要证伪「secret 在包里」只能反编译一个真实 release APK 看 `BuildConfig.FEEDBACK_APP_SECRET`（本次未做，也无法在只读+不构建的前提下做）。

**若修，是否改变 Android 现有可见行为**：**会改变联网拓扑**（客户端直连飞书 → 经 `server/` 转发），反馈页的失败模式、离线行为、崩溃上报时序都会变；属于跨端契约级改动，不是小修。仅「不再把 secret 编进包」而不加服务端转发，会直接把反馈功能变成不可用（`isConfigured=false`）⇒ 不能单独改。

---

## #16 `allowBackup="true"` 但备份排除规则漏了敏感存储

**审计原文的断言**：manifest 开了 `allowBackup="true"`，而 `data_extraction_rules.xml` 只排除了 `xjtu_secure_prefs*` / `xjtu_accounts*` / `agent_config*` / `cookie_jar*` / `session_snapshots*` / `data_cache*` / `credential_store_fallback*`；漏排了 `campus_probe.xml`（明文 `ywtb_token` JWT，且登出/删账号不清）、`github_community*.xml`、`xjtu_toolbox.db`、`agent_images/`、`agent_sessions*/`、`agent_memory*`、`inbox*`、`self_todo*`、`attendance_records_*`、`dorm_power*`、`hello_profile*`、`crash_pending.xml`。

**我读到的代码事实**

- manifest 侧属实：`android:allowBackup="true"` + `android:dataExtractionRules="@xml/data_extraction_rules"`（`app/src/main/AndroidManifest.xml:35-36`）。
- 但**排除清单的名字与审计引的完全不同**（HEAD 实际内容）：

```xml
<!-- app/src/main/res/xml/data_extraction_rules.xml:4-17（cloud-backup，device-transfer 同列） -->
<exclude domain="sharedpref" path="xjtu_credentials.xml"/> … xjtu_credentials_fallback.xml
<exclude domain="sharedpref" path="xjtu_accounts.xml"/> … xjtu_accounts_fallback.xml
<exclude domain="sharedpref" path="xjtu_cookies.xml"/> <exclude … path="xjtu_vpn_cookies.xml"/>
<exclude domain="sharedpref" path="cookies_normal*"/> <exclude … path="cookies_webvpn*"/>
<exclude domain="sharedpref" path="agent_config*"/> <exclude … path="notice_watch.xml"/>
<exclude domain="sharedpref" path="campus_card*"/> <exclude domain="file" path="agent_sessions*"/>
```

`xjtu_secure_prefs` / `cookie_jar*` / `session_snapshots*` / `data_cache*` / `credential_store_fallback*` 这些路径在本仓当前不存在（属旧版命名）。注意 `agent_sessions*`（`file` 域）在 HEAD **是**排除了的，审计这条说反了。

- 确实**仍然漏排**（逐项核过存储实现）：
  - `sharedpref` `campus_probe`（`app/.../auth/CampusProbe.kt:50` `PREFS = "campus_probe"`，存一网通办 JWT）。但它**不是明文**：走 `SecurePrefs`（`EncryptedSharedPreferences`，`CampusProbe.kt:67-79`），落盘为 AES-GCM 密文，主密钥在硬件 Keystore、**不随备份迁移**（`data/src/androidMain/.../data/SecurePrefs.kt:54-95`）。
  - `database` 域**一条排除都没有**，而 Room 库叫 `xjtu_toolbox.db`（`app/.../data/AppDatabase.kt:174-177`），里面 `custom_courses` 带明文学号 `accountId`（同文件 `:86-92` 的迁移语句）。
  - `file` 域只排了 `agent_sessions*`，漏了 `agent_images<suffix>/`（`app/.../agent/AndroidAgentHost.kt:356` `AGENT_IMAGE_DIR = "agent_images"`，用户发给屁岱的私密图）与 `crash_pending/`（`app/.../error/CrashReporter.kt:31,48`，上报前已脱敏，见其 KDoc）。
  - `sharedpref` 漏排：`agent_memory<suffix>`（`core/.../agent/AgentMemory.kt:118` `STORE = "agent_memory"`，经 `:core` 的 `keyValueStore` = **明文** `MODE_PRIVATE`，见 `core/src/androidMain/.../platform/KeyValueStore.android.kt:69`）、`inbox<suffix>`（`core/.../inbox/InboxModels.kt:248`）、`dorm_power*`、`attendance_records_ug/pg`、`schedule_diff`、`course_colors`、`empty_room_cache`、`payment_code`、`app_settings` 等（`AccountManager.kt:345-349` 的 `ACCOUNT_PREFS` 列了其中大部分）。
  - `github_community`：OAuth token 存 `secureKeyValueStore`（`data/.../community/SecureGithubSession.kt:18-23,66`）⇒ 与 `campus_probe` 一样是密文；审计说的「降级时在 `github_community_fallback.xml` 明文长期留存」不成立 —— `SecurePrefs.openUncached` 迁移完就 `deleteSharedPreferences(legacyFallbackName)`（`SecurePrefs.kt:74-93`）。
- 审计的第二条断言（登出/删账号不清 `campus_probe` 的 token）**不成立**：`SessionManager.reconfigureForAccount` 在「真换了账号后缀」时调 `onAccountSwitched`（`data/.../auth/SessionManager.kt:462-471`），而 `:app` 把这个钩子接到 `CampusProbe.ywtbToken = null`（`app/.../auth/AppLoginState.kt:373`），setter 会 `remove(KEY_TOKEN)` 写回（`CampusProbe.kt:74-80`）；`logoutCurrent` 与 `removeAccountLocked` 都走 `reconfigureForAnonymous()`（`AccountManager.kt:292,268`）→ `reconfigureForAccount(ANONYMOUS)`（`SessionManager.kt:496-499`）⇒ 钩子会被触发。

**判定与理由**：**部分成立**。
- 成立：结论（系统云备份/换机助手会把一批用户内容导出，而排除清单没有覆盖它们）在 HEAD 依然成立，且比审计说的更精确：数据库域**零排除**、`agent_images` 未排除、多个明文 prefs 未排除。
- 不成立：审计引的排除清单（旧版名字）、`agent_sessions*` 未排除、`campus_probe` 在登出后残留 token、`github_community_fallback` 明文长期留存 —— 四处都与 HEAD 不符。
- 别的防线：备份本身走用户自己的 Google 账号（Android 云备份是端到端加密的），换机助手需用户在物理设备上操作；最敏感的凭据文件（`xjtu_credentials` / `xjtu_accounts` / cookie 库 / `agent_config` / `campus_card`）**在 HEAD 已排除**；`campus_probe` 与 `github_community` 是密文且密钥不离设备。
- 残余的确切风险：**明文**的 `xjtu_toolbox.db`（含学号）、`agent_memory`（用户偏好/个人情况）、`inbox`（消息/待办）、`payment_code`（付款码偏好）、`agent_images/`（私密图片）会进备份。

**真严重度：中**。攻击者要付出：拿到用户自己的备份渠道（云账号）或经手设备换机迁移，无法从网络侧直接触及；拿得到：学号（明文 DB 列）、聊天图片、偏好与待办等用户内容。评不上「高」是因为最值钱的凭据（账号密码、TGC/cookie 库、API Key）在 HEAD 已经排除、且剩余项多为用户自有内容的副本。

**最小验证方式**：读 `app/src/main/res/xml/data_extraction_rules.xml`（只有 32 行，肉眼可穷举），与 `AccountManager.kt:345-349` 的 `ACCOUNT_PREFS`、`AppDatabase.kt:177`、`AndroidAgentHost.kt:356` 三处交叉比对；实机可用 `adb shell bmgr backupnow com.xjtu.toolbox` 观察日志中被跳过的域（需设备与备份账号，本次未做）。

**若修，是否改变 Android 现有可见行为**：**基本不改变日常行为**，只改变「换机/恢复后还剩什么」。注意两点：① 若改成 `allowBackup="false"`（审计的建议），换机后**全部本地状态**（含 Widget 配置、外观、首页布局、已读游标、支付码偏好）都会丢，是明显的用户可见变化 —— 项目更可能选「白名单保留非敏感项」这条；② 把 `xjtu_toolbox.db` 整个排除会连带丢掉用户的**自定义日程/书架/下载记录**，这些是用户以为自己备份了的数据，需要产品拍板。

---

## #17 `AgentWeb` 公网策略被重定向覆盖 + 校园网段放行 + `MdImageLoader` 未挂策略

**审计原文的断言**：`AgentWeb.applyPublicNetworkPolicy()` 设了 `followRedirects(false)`/`followSslRedirects(false)`/`dns(PublicOnlyDns)`，但 `AgentTool.kt:98` 的 `.followRedirects(true)` 把它们覆盖回 true；`isPrivateOrReserved` 不拦 6to4/NAT64；`MdImageLoader` 基于 `HttpClients.base` 且完全没挂策略，所以模型输出回环/内网图片 URL 就能发起请求。

**我读到的代码事实**（HEAD 与审计引文差异很大）

```kotlin
// app/src/main/java/com/xjtu/toolbox/agent/AgentWeb.kt:175-176（HEAD）
fun applyPublicNetworkPolicy(builder: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder =
    builder.socketFactory(publicOnlySocketFactory)
```

策略本体是**建连时**的地址检查，不是重定向开关：

```kotlin
// AgentWeb.kt:136-145
private class GuardedSocket : Socket() {
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val target = endpoint as? InetSocketAddress
        val addr = target?.address
        if (addr == null || (isNonPublicAddress(addr) && !isSystemProxyEndpoint(target))) {
            throw java.io.IOException("拒绝访问非公开地址")
        }
        super.connect(endpoint, timeout)
    }
}
```

消费侧（`app/.../agent/AgentTool.kt:1302-1311`）除了挂策略，每个请求还先做一次 URL 预检：

```kotlin
private val webClient by lazy {
    AgentWeb.applyPublicNetworkPolicy(HttpClients.base.newBuilder())
        …
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain -> … AgentWeb.requirePublicHttpUrl(request.url.toString()) … }
}
```

- 所以「`.followRedirects(true)` 覆盖策略」**不成立**：`followRedirects(true)` 覆盖的是一个不存在的标志位；重定向后的新地址在真正 `connect()` 时仍会过 `GuardedSocket`（KDoc `AgentWeb.kt:112-122` 也明写这条设计）。`grep -rn "PublicOnlyDns"`（含 `--all` 历史）与 `git log --all -S "followSslRedirects(false)" -- AgentWeb.kt`：`PublicOnlyDns` 全历史 0 命中。
- 校园网段：`isNonPublicAddress`（`AgentWeb.kt:82-110`）只拦回环/链路本地/site-local/组播/any、`0.0.0.0/8`、`100.64/10`、`fc00::/7`、IPv4-mapped/compat 的 IPv6；`202.117.x.x` 确实放行（`198.18.0.0/15` 是**刻意**放行的，见 `:90-94` 注释：Clash fake-ip）。
- 图片一路：

```kotlin
// app/.../agent/AndroidAgentHost.kt:424-436
private val client by lazy { HttpClients.base.newBuilder()
    .connectTimeout(8, …).readTimeout(15, …).build() }   // ← 没有 applyPublicNetworkPolicy()
override suspend fun load(url: String): ImageBitmap? {
    if (!url.startsWith("https://") || url in failed) return null
```

并且 markdown 只把 `![…](https://…)` 认成图（`core/.../agent/AgentMarkdown.kt:174`）。

**判定与理由**：**部分成立**。
- 不成立：审计的核心机制（重定向覆盖公网策略）在 HEAD 无法复现；`PublicOnlyDns` / `followSslRedirects(false)` 在本仓历史上从未出现，属**臆测或来自其他代码库**。审计举的 `![http://127.0.0.1:xxxx/…]` 例子也不成立（只认 https；且回环/https 若真连上会因自签证书校验失败）。
- 成立：图片客户端**确实**没有挂 `applyPublicNetworkPolicy()`，而 OkHttp 默认跟随重定向 ⇒ 攻击者的 https 页面可以 302 到 `http://127.0.0.1:PORT/…` 或 `http://10.x/…`，这一跳不过任何守卫（起点是 https 就通过了 `startsWith` 检查）。这是**盲 SSRF**：图片渲染在受害者屏上，攻击者读不到响应体，但可以让受害者设备上的内网服务收到一次 GET（对无鉴权的内网管理接口/Router API 足以产生副作用）。`202.117.0.0/16` 放行同样是事实，但那是**刻意的**设计（App 自身就要访问校内公网 IP 服务，`CampusProbe.kt:45-49` 的探针就打在 `rg.lib.xjtu.edu.cn:8086` / `kq.xjtu.edu.cn`），拦掉它会改变产品行为；6to4/NAT64 前缀未拦属理论缺口，需要对应网络基础设施才可能走到，我判为不构成实际可利用面。

**真严重度：低**（就本组而言最低的一条）。攻击者要付出：诱导受害者让屁岱输出/渲染一张 https 图（要模型配合），且该 https 端点要能把浏览器 302 到内网；拿得到：**一次盲 GET**（无回显、无数据读取；除非内网服务被这次 GET 触发了状态变更）。`web_fetch` 一路没有这个问题（建连级检查 + 每请求预检）。作为对比，审计给的是「中」，我下调到「低」。

**最小验证方式**：`sed -n '124,145p;175,176p' app/src/main/java/com/xjtu/toolbox/agent/AgentWeb.kt` 看策略本体；`sed -n '416,436p' app/src/main/java/com/xjtu/toolbox/agent/AndroidAgentHost.kt` 看图片客户端有没有挂策略；`git log --all -S "PublicOnlyDns"` 验证审计引文不存在。实机（若允许）：起一个返回 302 到 `http://127.0.0.1:8080/x` 的 https 端点，让回复里出现该图的 markdown 行，观察本机端口是否收到请求。

**若修，是否改变 Android 现有可见行为**：**加 `.applyPublicNetworkPolicy()` 到图片客户端：不改变正常行为**（外网图都是 https 公网地址，照常加载；只有指向内网/回环的目标会被拒）。**拦 `202.117.0.0/16`：会改变行为**（校内一部分服务、部分院系站点可能直接打不开），不建议在客户端一刀切。

---

## #18 Release 包只剥 `Log.d`/`Log.v`，`Log.w`/`i`/`e` 打印明文学号与未脱敏响应

**审计原文的断言**：`proguard-rules.pro:40-44` 只移除 `Log.d`/`Log.v`；`SiteSession.kt:329-333` 的 `Log.w` 未脱敏 URL + 响应片段；`AccountManager.kt` 与 `AccountMigration.kt` 用 `Log.i` 打明文学号；`LmsApi.kt` 用 `Log.w`/`Log.e` 打带 token/timestamp 签名的完整下载 URL；另外 R8 不移除 `Log.d` 参数求值，release 包白白跑 `redactBody` 的正则。

**我读到的代码事实**

```proguard
# app/proguard-rules.pro:40-44
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
```

```kotlin
// data/src/commonMain/kotlin/com/xjtu/toolbox/auth/SiteSession.kt:336-342（HEAD）
val failedUrl = WebVpnUtil.getOriginalUrl(response.request.url.toString()) ?: response.request.url.toString()
Log.w(TAG, "[$siteKey] auth failure: code=${response.code} url=$failedUrl " +
    "preview=${bodyPreview?.take(160)?.replace("\n", " ")}")
```

同一文件 `:409-413` 的 Ktor 出口同样未脱敏（`grep redactUrl|redactBody` 在这两处**均未调用**；脱敏工具是现成的：`core/.../util/LogRedact.kt:52,55`，别处已在用，如 `CasQrLogin.kt:117,143`）。

- 学号明文日志属实：`AccountManager.kt:93`（`"Switching account: … -> $accountId"`）、`:185`（`"addAccount ok: $username"`）、`:272`、`:294`；`AccountMigration.kt:41`、`:114`。
- `LmsApi` 那一组：`LmsApi.kt:268,272,283,293,299` 打的是 `download $url → 403` / `downloadToStream failed: $url` 这类，而 `$url` 来自 `blobUrl()`（`:257-262`，`$baseUrl/api/uploads/{id}/blob`）或 `authenticatedRequest(url)`（`:338-342`，**只加 Referer/UA，不加 token 查询参数**）。所以「打印带 token/timestamp 签名的完整下载 URL」在 HEAD **不成立**（LMS 鉴权走 cookie，URL 本身无签名）。全仓 grep `Log.(i|w|e)(… token` 只剩 4 处，均不含 token 值（如 `HelloApi.kt:74` 打的是键名 `<handoff>`、`AttendanceLogin.kt:182` 打的是交换失败的 data、`ZyxfApi.kt:278` 打的是 fileId）。
- 性能副作用那条：`redactBody` 内部确实是 5 个正则（`LogRedact.kt:37-48`：`PARAM_RE`/`JSON_RE`/`INPUT_RE`/`LONG_TOKEN_RE`/`DIGITS_RE`），但 R8 是否在剥离 `Log.d` 时一并消除参数求值，我**无法在不构建的前提下核实** ⇒ 记为未核对项，不作为结论。

**判定与理由**：**部分成立**。
- 成立：「只剥 d/v」属实；`SiteSession` 两处认证失败日志确实未脱敏（含响应体前 160 字，认证失败响应体通常是 CAS 登录页 HTML，但也可能是别的 401/403 响应体，可能带 session 相关片段）；`AccountManager`/`AccountMigration` 明文打学号属实（`accountId` 就是学工号）。
- 不成立：把 `LmsApi` 描述成「打印带 token/timestamp 签名的完整 URL」与 HEAD 不符。
- 别的防线（影响可达性）：`Log.w/i/e` 只在 **logcat** 上可见，读取 logcat 需要 `READ_LOGS`（普通 App 拿不到；Android 10+ 进一步收紧）或 ADB 调试授权 ⇒ 「同一台设备上具有日志读取权限的组件」在本机是严格受限的；Preview 渠道包（`isMinifyEnabled=false`）会打印全部 `Log.d`，但那本来就是内部渠道。真正**会离开进程**的那条路（崩溃上报）在 HEAD 已经统一走脱敏（`LogRedact.kt` KDoc 第 6 行、`CrashReporter` 的 `redact`）。

**真严重度：低**。攻击者要付出：拿到设备上的 ADB 权限或一个能读日志的高权限组件（本机安全边界已被突破）；拿得到：学号与一段未脱敏的 401/403 响应片段（不是凭据本身）。评不上「中」是因为这些明文日志不随崩溃上报出去、也不进备份，只有 logcat 一条路。

**最小验证方式**：`sed -n '40,44p' app/proguard-rules.pro`；`sed -n '336,342p;409,413p' data/src/commonMain/kotlin/com/xjtu/toolbox/auth/SiteSession.kt`（看有没有 `.redactUrl()`/`.redactBody()`）；`grep -n "Log\.[iwe](TAG" app/src/main/java/com/xjtu/toolbox/account/AccountManager.kt`。实机：装 release 包 + `adb logcat -s SiteSession AccountManager` 触发一次认证失败即可观察。

**若修，是否改变 Android 现有可见行为**：**不改变用户可见行为**。给 `SiteSession` 两处补 `.redactUrl()`/`.redactBody(160)`、给学号打码，只影响开发者排障文本（两种脱敏工具都已在别处使用，口径一致）。封装 `AppLog`（`inline fun d(tag, msg: () -> String)`）也只是内部实现，不改行为。

---

## #19 文案与代码实际行为不符（「记住的偏好 · 不上传」/「工具可单独关掉」/「全部只读」）

**审计原文的断言**：`AgentConfigPanel.kt:346-350` 写「只存在这台设备上，不上传」，但 `AgentPrompt.kt:50`+`AgentViewModel.kt:430` 每轮都把全部偏好拼进 `system` 消息发给第三方模型；`README.md:58` 说「每类工具都能在设置里单独关掉」，而 `AgentConfigStore.kt:192-194` 注明开关已删；`AgentTool.kt:88` 类注释写「全部只读，无写/预订/支付接口」但同文件有 6 个写工具。

**我读到的代码事实**

```kotlin
// core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentConfigPanel.kt:347-359（HEAD）
item {
    // 记住的偏好必须**可见可删**：模型往本机写了东西，用户有权知道写了什么。
    val memory = remember { AgentMemory() }
    var memories by remember { mutableStateOf(memory.all()) }
    if (memories.isNotEmpty()) {
        Card(…) {
            Text("记住的偏好", …)
            Text("只存在这台设备上，不上传。最多 ${AgentMemory.MAX_ITEMS} 条。增删从下一个新对话起生效。", …)
```

- 位置从审计引的 `:346-350` 移到 `:354-359`，且 `if (memories.isNotEmpty())` 使卡片在空偏好时不显示（审计引的「对话中说的习惯会自动记下」那句在 HEAD 不存在）。
- 「不上传」的前提确实是假的：偏好整段进 system prompt，而后者的内容会随对话 POST 给用户自选的模型服务商：

```kotlin
// core/.../agent/AgentViewModel.kt:425-430
val systemPrompt = AgentPrompt.build(… memoryBlock = registry.memoryBlock() …)
// core/.../agent/AgentPrompt.kt:50   → memoryBlock.trim() 拼进 system
// core/.../agent/AgentMemory.kt:106   fun promptBlock()
```

`AgentMemory` 自己的 KDoc 反而写对了（`:49-52`「偏好会写进系统提示，发给该账号配置的服务商」）⇒ 是**UI 文案**与实现不符，不是实现与设计不符。另有反制披露：配置页顶部有一张卡片明说「第三方中转能看到你的提问和查询结果」（`AgentConfigPanel.kt:119-128`）。

- README 属实：`README.md:55` `- 每类工具都能在设置里单独关掉`；而工具开关键已删（`data/.../agent/AgentConfigStore.kt:67-68`：`p.remove("max_tool_calls")` / `p.remove("disabled_caps")`），全仓没有面向用户的工具级开关（`AgentCapability` 是**端**声明的能力档，宿主决定画不画，不是用户设置）。
- 类注释属实但已自我修正：`app/.../agent/AgentTool.kt:63` 仍写「全部只读，无写/预订/支付接口」，而同一段 KDoc 的 `:70-72` 又自陈「唯一一处例外是 `add_schedule_event`」，另有 `preference`（`:418`）、`set_alarm`（`:435`）、`app_setting`（`:478`）三个写工具 —— 所以「全部只读」这句是**过时残留**，不是隐蔽的欺骗。审计列的 `add_todo` / `complete_todo` 在 HEAD 不存在（全仓 0 命中），所以是 4 个写工具而非 6 个。

**判定与理由**：**部分成立**。
- 成立（三条里的两条 + 半条）：UI 上「不上传」与「整段进 system prompt 发给第三方模型」冲突（用户可能据此前置地交待在别处不会说的敏感习惯）；README「每类工具都能单独关掉」与已删开关冲突；`AgentTool.kt:63` 的「全部只读」与同文件写工具冲突。
- 不成立：审计引的具体文案、`AgentConfigPanel.kt:346-350` 的行号、`AgentTool.kt:88`、以及 `add_todo`/`complete_todo` 两个工具，在 HEAD 都不是那样。
- 别的防线：配置页有一张显式风险告知卡（第三方能看到提问与查询结果），偏好列表在设置页**可见可删**（`AgentConfigPanel.kt:348-350,360+`），偏好按账号隔离存储。

**真严重度：低**。这不是可被攻击者利用的漏洞，而是**告知准确性**问题：没有任何「攻击者」从中获利，受害者的损失是「以为只在本地的东西被发给了模型服务商」。按用户实际预期管理的重要性，我仍认为值得改（尤其「不上传」是绝对化承诺）。

**最小验证方式**：`sed -n '347,360p' core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentConfigPanel.kt`；`sed -n '425,431p' core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentViewModel.kt`；`sed -n '53,56p' README.md`；`sed -n '64,69p' data/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentConfigStore.kt`。实机：配好 Key 记住一条偏好，抓包看发给模型的 `system` 里是否含该内容。

**若修，是否改变 Android 现有可见行为**：**只改文案**（`AgentConfigPanel.kt:356`）与 README/KDoc ⇒ 无行为变化，成本最低。若按审计建议**恢复写操作工具总开关**，那是新增能力（会改变设置页形态），且与「工具开关已删」的既有决定相反，需产品拍板；本条不影响跨端口径。

---

## #20 自动更新下载 APK 不校验摘要，`version` 未做路径过滤

**审计原文的断言**：`AppUpdater.kt:237-272` 用远端 `tag_name` 派生的 `version` 拼文件名，下载完只查前 2 字节是否 `PK`，不校验 SHA-256，也不用 `getPackageArchiveInfo` 校验包名/签名。

**我读到的代码事实**

```kotlin
// app/src/main/java/com/xjtu/toolbox/update/AppUpdater.kt:241-243
val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
val target = File(updateDir, "xjtu-toolbox-${info.version}.apk")
val partial = File(updateDir, "${target.name}.part")
```

```kotlin
// AppUpdater.kt:263-270
if (partial.length() < 100_000L || partial.inputStream().use {
        it.read() != 0x50 || it.read() != 0x4B        // 只看是不是 ZIP 头
    }) { partial.delete(); error("下载内容不是有效安装包") }
```

- `info.version` 的来源：GitHub 分支取 `tag_name.removePrefix("v")`（`:190-196`），Gitee 分支取 `get("tag_name")?.stringValue?.removePrefix("v")`（`:307-315`）；两处都**没有**字符过滤。
- `install()`（`:286-296`）把文件经 `FileProvider` 交给系统安装器（`ACTION_INSTALL_PACKAGE` + `EXTRA_NOT_UNKNOWN_SOURCE`），**没有** `getPackageArchiveInfo` / 签名比对（全文件 0 命中，见 `grep getPackageArchiveInfo`）。
- 文件里确实出现过 `MessageDigest.getInstance("SHA-256")`（`:142`），但那是灰度分桶（`isRolloutHit`，`"$rolloutId:$tag"` 取哈希取模），**与 APK 完整性无关** —— 容易被误读成「已经校验了摘要」，值得在修的时候分开。
- 通道是 https（`api.github.com` / `gitee.com/api/v5`，`:220-222`），`REQUEST_INSTALL_PACKAGES` 已在 manifest 声明（`AndroidManifest.xml:7`）。

**判定与理由**：**成立**（代码事实全部对上，只有行号有偏移：`237-272` → HEAD 为 `243/263-270`）。

**真严重度：中**。攻击者要付出：**劫持发行渠道**——拿到作者的 GitHub 或 Gitee 账号（或镜像/代理链路被控）才能让客户端拉到别的包；单靠网络中间人做不到（https + 系统信任链）。拿得到：一次诱导性的安装弹窗。具体收益被系统边界压住：同包名换签名会被系统拒绝覆盖安装；换包名则会作为**新应用**安装，仍需用户在系统安装器上点确认（且需已授予「安装未知应用」）。所以它不是「静默拿下设备」，而是**发行渠道被攻破后客户端毫无察觉**。文件名拼接的理论路径穿越（`tag_name` 含 `../`）同样只在渠道被控时才有意义，量级同上。

**最小验证方式**：`sed -n '241,270p' app/src/main/java/com/xjtu/toolbox/update/AppUpdater.kt`（看 `version` 直接进文件名 + 只查 `PK`）；`grep -n "getPackageArchiveInfo\|digest" app/src/main/java/com/xjtu/toolbox/update/AppUpdater.kt`（预期空）；`sed -n '286,296p'` 看安装调用前有没有校验。实机：把 Gitee 渠道的 `latest` 响应改成一个 `tag_name` 含 `/` 的假 release（需代理或本地 DNS，本次未做）。

**若修，是否改变 Android 现有可见行为**：**不改变正常行为**（对 `version` 做字符白名单、加一次 SHA-256/包名校验，正常包一律照常通过）。GitHub Releases API 的 asset 已带 `"digest": "sha256:…"` 字段（Gitee 不一定有），所以「摘要从哪来」需要按渠道分别设计：读不到摘要时是**拒绝安装**还是**降级为仅校验包名/签名**，这一处会改变用户可见行为（升级被拒），需要拍板。

---

## 本组的置信度与未核对项

**整体置信度：高**（8 条都读到了 HEAD 上的原文，并对审计引用的关键行号做了差异标注）。下面是我认为**没有充分核对**、或无法在「只读 + 不构建」前提下核实的东西：

1. **#13 的外带链条是「机制存在」而非「已复现」**。我逐环读到了代码（无确认自动发送 → 工具全开 → `![…](https://…)` 自动 GET），但没有实证「模型被注入后确实会输出那张图片行」。这条链的最后一跳依赖模型行为，审计把它当成确定性结论，我没有照抄。
2. **#13 的 `intent://` 触发路径未验证**。审计用 `intent://…#S.extra_launch_*;end` 作为入口。我确认了「**任何本机 App 直接 `startActivity` 带 extras 就能触发**」（`MainActivity.kt:101-110` 不区分调用者），但没验证浏览器对 `intent://` 的策略（不同浏览器/版本的拦截规则不同）⇒ 报告里没有把浏览器这条路径当既成事实。
3. **#14 的 `onCreateWindow` + `javascript:` 结论**。`parent.loadUrl(u)`（`BrowserScreen.kt:507`）确实缺少 `shouldOverrideUrlLoading`（`:468-475`）那样的 scheme 校验，但「`<a target=_blank href="javascript:…">` 会在父页执行」随 WebView 版本而异，我没有实机验证，只记为附带风险。
4. **#16 的备份行为只做了静态核对**。我没有跑 `bmgr`，也没有实际恢复一次备份；「哪些域真正被导出」是按 `data_extraction_rules.xml` + 各存储实现推断的（依据：`SecurePrefs` 与 `:core` 的 `keyValueStore` 落点清楚，`MODE_PRIVATE` 明文与 `EncryptedSharedPreferences` 密文可区分）。另外我**没有**逐个穷举全部 20+ 个 prefs 名（只核了 `AccountManager.kt:345-349` 的 `ACCOUNT_PREFS` 列表与几个显眼的），可能的漏排项只会比报告里列的更多。
5. **#18 的性能副作用（R8 是否消除 `Log.d` 参数求值）未核**：需要构建一次 release 包再反编译字节码，本次禁止构建，故不作为结论。
6. **#15 的「secret 真的在包里」无法静态证明**：仓库里没有字面量，只有 CI secrets 注入路径（`.github/workflows/build.yml:74-78`）。要坐实必须反编译一个真实 release APK（未做）。
7. **#17 的盲 SSRF 未实机验证**：`AndroidAgentImages` 的客户端确实没挂策略、OkHttp 默认跟随重定向、起点只要求 `https://`，从我读到的代码看是成立的；但「https 页面 302 到 http://127.0.0.1 会被跟随」这一步我没跑（需要自建 https 端点 + 让回复里出现该图）。
8. **本报告没有覆盖审计第 2 节之外的内容**（#1–#12、#21–#36），也没有评价当前正在被其他子代理修改的 `core/.../core/net/**` 与 `web/**` —— 那些文件不在本次只读复核范围内。
9. **#13/#14/#17 里涉及的 `202.117.0.0/16` 是否为「校内网」**：我按 `isNonPublicAddress` 的判定与 `CampusProbe` 的探针地址推断这些是校内可达的公网 IP，没有从学校侧确认网段归属。
