# 外部审计复核（第一组：正确性与 Bug #01 ~ #12）

复核对象：`/tmp/audit-20261014.md` 第 50–496 行（`#### 1. 正确性与 Bug（#01 ~ #12）`）。
复核仓库：`/mnt/nas/data/works/xjtu-toolbox-android`，分支 `refactor/kmp-multiplatform`，HEAD `5e9b2e53`（2026-10-14）。
复核方式：**只读**（`read` / `grep` / `sed` / `awk` / `find` / `git log` / `git show`）。
**未跑 gradle、未跑任何测试、未改任何已有文件**（本文件是本次唯一新建的文件）。
文中所有示例值、路径、账号后缀一律为编造值；未复制任何真实凭据。

---

## 0. 两条前置事实（后面 12 条都要用到）

### 0.1 审计的行号/路径基线**不是 HEAD**（KMP 重构前）：`app/src/main/java/...` → `core|data/src/commonMain/kotlin/...`

审计点名的 14 个文件里，**12 个**在本仓已搬家（包名不变，模块变了）：

| 审计点的路径 | HEAD 实际位置 |
|---|---|
| `app/src/main/java/com/xjtu/toolbox/score/ScoreCalculator.kt` | `core/src/commonMain/kotlin/com/xjtu/toolbox/score/ScoreCalculator.kt` |
| `app/.../agent/AgentViewModel.kt` / `AgentScreen.kt` / `AgentConfigPanel.kt` / `AgentVision.kt` / `AgentPrompt.kt` | `core/src/commonMain/kotlin/com/xjtu/toolbox/agent/…` |
| `app/.../pay/PaymentCodeScreen.kt` | `core/src/commonMain/kotlin/com/xjtu/toolbox/pay/PaymentCodeScreen.kt` |
| `app/.../auth/XJTULogin.kt` / `CasSiteSession.kt` / `SessionManager.kt` / `Sites.kt` / `JudgeApi.kt` / `GraduateJudgeApi.kt` / `VenueApi.kt` | `data/src/commonMain/kotlin/com/xjtu/toolbox/…`（`Sites.kt` 只留 `HelloSession`，`LibrarySession` 在 `data/.../auth/LibrarySession.kt`） |
| `app/.../auth/CampusProbe.kt` / `notification/HeadlessSessions.kt` / `media/DownloadTaskEntity.kt` | **仍在 `:app`**（就是审计指的路径） |

（核法：`find . -name '<文件>' -not -path '*/build/*'`；例：`find . -name 'ScoreCalculator.kt' -not -path '*/build/*'`。）

### 0.2 有两处「（已确认）证据」在本仓**任何 ref 里都不存在**

```bash
# ① #01 引的那段 scoreToGpaFromString 是另一份实现（多了 `grade.trim()` / `raw.isEmpty()`），
#    且它引的映射行在本仓全部历史里零命中（HEAD 与 origin/main / origin/dev 的映射都是「11 级」那一套）：
git log --all --oneline -S 'g == "良" -> 3.3'                  # 0
git log --all --oneline -S 'g == "中" -> 2.3'                  # 0
git log --all --oneline -S 'g == "及格" || g == "通过" -> 1.0'  # 0
git log --all --oneline -S 'g == "优" -> 4.0'                  # 2（这一行确实存在）
# ② #09 引的 `MIGRATION_7_8` 在本仓不存在（HEAD/所有分支都是 version = 7，最多到 6→7）：
grep -n 'MIGRATION_' app/src/main/java/com/xjtu/toolbox/data/AppDatabase.kt
for b in main origin/main origin/dev gitea-local/main; do git show $b:app/src/main/java/com/xjtu/toolbox/data/AppDatabase.kt | grep -c MIGRATION_7_8; done  # 全 0
# ③ #04 引的 `HttpClients.probe`（HttpClients.kt:99）在本仓不存在：
grep -n 'val ' data/src/commonMain/kotlin/com/xjtu/toolbox/network/HttpClients.kt   # 只有 `base`
```

结论：审计「已确认」标签**不可当既成事实**，下文一律以 HEAD 源码为准，并逐条标注它的引文/行号是否可信。

### 0.3 判定口径

- **成立** = 断言的技术事实在 HEAD 成立，且触发路径我读通了；**部分成立** = 主干成立但有事实性错误/夸大/漏兜底；**不成立** = 与 HEAD 矛盾；**无法判定** = 需要上游（学校系统/问卷样本/抓包）才能定。
- 严重度是我**自己**按「影响 × 触发面 × 有无兜底」重判的（不抄审计的）。
- 每组末尾按要求回答一次 §16 **D7 口径**（修它会不会改变 Android 现有可见行为）——见每条的「D7」行。

---

## 1. 判定总表

| # | 判定 | 我重判的严重度 | 一句话结论 | 证据（路径:行号） |
|---|---|---|---|---|
| #01 | **部分成立** | 中 | 清洗正则确实吃掉小数点（`"59.5"→595→4.3` 是真代码事实，我用 JVM 复算过）；但审计引的映射表/行号全错（`良/中/及格` 在 HEAD 都**有**映射），「优秀/良好…漏判」在 HEAD 只剩双字那几种、且被一条测试**钉住**为「不参与加权」；上游是否真下发小数成绩，本仓无任何证据 | `core/.../score/ScoreCalculator.kt:47-70`（引用处 51/55）、`:62-65`；调用方 `data/.../score/ScoreReportApi.kt:122`、`data/.../jwapp/JwappApi.kt:157`；优先级兜底 `ScoreCalculator.kt:80-81,186`；测试 `core/src/commonTest/.../ScoreCalculatorTest.kt:22-59`、`data/src/jvmTest/.../ScoreReportApiJvmTest.kt:38-43` |
| #02 | **成立** | **高** | 切账号时 `AgentViewModel` 的 `sessions/messages/llmHistory` 一律不重载（`bind()` 只跑一次、`accountId` 变化只刷新 config），而落盘目录每次写盘现取 `safeSuffix()` ⇒ A 的聊天记录＋身份画像被写进 B 的 `agent_sessions_<B>/` | `core/.../agent/AgentScreen.kt:88,92,94-99`；`core/.../agent/AgentViewModel.kt:201-208,265-286,394-402`；`data/.../agent/AgentSessionStore.kt:29-33,83-95`；`core/.../account/AccountContext.kt:50-59`；`app/.../account/AccountManager.kt:82-114` |
| #03 | **成立** | 中 | `ssoErrorMessage` 那一路确实把 `HTTP 401` 原样塞进 `LoginResult.FAIL`，下游 `isCredentialFailure` 的子串表里真有 `"401"` ⇒ 目标站 401 会被判成「密码错」触发全局熔断；同函数作者已经在 `ssoFailure` 那一路规避过（注释自陈） | `data/.../auth/XJTULogin.kt:443-447,491-496`（规避注释在 497-499）、`:361-403`；`data/.../auth/CasSiteSession.kt:58-64,95-99`；`data/.../auth/SessionManager.kt:205-221`；`app/.../auth/AppLoginState.kt:45-66,245-262` |
| #04 | **成立** | 中 | 明文 HTTP 探针 + `reach()` 只看 `code < 500` 确实是「Captive Portal 必误判成校内强结论」的完整成因；但审计引的 `HttpClients.probe` 不存在（HEAD 是 CampusProbe 自己 new 的 client），且误判有 10 分钟上界＋设置里有手动逃生门 | `app/.../auth/CampusProbe.kt:43-48,114-121,127-142,145-172,174-178`；`app/.../auth/AppLoginState.kt:147-149,311-340`；`data/.../auth/SessionManager.kt:64,77,100-109`；`app/src/test/.../CampusProbeTest.kt:16-75`（只测 `decide`，**不测** `reach`） |
| #05 | **成立** | 中 | 后台临时 `SessionManager` 默认 `NORMAL` 且从不读 `networkMode`/不探测 ⇒ 进程被杀后图书馆签到提醒在校外/流量下必失败；但「100% 失败」略夸大：重试在用户接回校园网/前台起来后能成功（`Result.retry` + 默认退避） | `app/.../notification/HeadlessSessions.kt:65-104`；`data/.../auth/SessionManager.kt:64,77,89-94`；`data/.../auth/LibrarySession.kt:28`＋`SiteSession.kt:50,170`；`app/.../notification/LibraryReminderWorker.kt:36-52,138-154` |
| #06 | **成立** | 低 | 注释两处写「退出 Composable 时通过 `DisposableEffect` flush」，代码里**一个 `DisposableEffect` 都没有**（只有两行注释），`scheduleSave` 的 500ms 也随组合一起被取消 ⇒ 粘完 Key 立刻返回会丢 | `core/.../agent/AgentConfigPanel.kt:78-103`（注释 81/94、`scheduleSave` 96-103）、`:158-173`（API Key 字段）；对照 `core/.../agent/AgentScreen.kt:186-194`（同一作者在别处是会写 `DisposableEffect` 的） |
| #07 | **成立** | 中 | `keyValueStore("payment_code")` 在 Android 就是**不加账号后缀**的 `SharedPreferences("payment_code")`，而 12 秒一轮的出码循环会把这份（属于 A 的）勾选每次同步给上游 ⇒ 切账号后 B 的券被按 A 的勾选打开/关掉 | `core/src/androidMain/.../platform/KeyValueStore.android.kt:24-26,69`；`core/.../pay/PaymentCodeModels.kt:75-96`；`core/.../pay/PaymentCodeScreen.kt:44,95-100,154-161,306-330`；`app/.../pay/AppPaymentCode.kt:46-50`；`app/.../account/AccountManager.kt:318-350`（`ACCOUNT_PREFS` 无 `payment_code`） |
| #08 | **成立** | 低 | 删除会话 / 清缓存都不删 `filesDir/agent_images<后缀>/` 里的压缩件，全仓只有「发送前撤图」一处删；但 `AgentVision.deleteAll` 这个名字在 HEAD 已不存在（改成宿主 `deleteImageFiles`），且删账号**会**清这个目录 | `app/.../agent/AndroidAgentHost.kt:285-287,356-386`；`core/.../agent/AgentChat.kt:304-308`（唯一调用点）；`core/.../agent/AgentViewModel.kt:250-258,139-150`；`data/.../agent/AgentSessionStore.kt:55-58`；`app/.../settings/AppSettings.kt:652-653`；`app/.../account/AccountManager.kt:327-330` |
| #09 | **成立** | 低 | `download_tasks` 确实没有 `accountId`、DAO 全表无条件、账号清理也不碰它；但审计描述的 `MIGRATION_7_8` / `jiaocai1_shelf` 加 `accountId` / `clearAccountRoomData` 在 HEAD 全不存在（引文出自旧版本） | `app/.../media/DownloadTaskEntity.kt:13-31,42-43,66-67`；`app/.../data/AppDatabase.kt:22,34-182`；`app/.../media/AppDownloadTaskStore.kt:31`；`app/.../media/AppDownloadManager.kt:20-35`；`core/.../media/DownloadManager.kt:81`；`app/.../account/AccountManager.kt:339` |
| #10 | **成立** | 低 | `catch` 里把 `appInitialized = true` 属实（成功路径也置、失败路径也置），**兄弟实现 `SchoolCourseApi` 的同一段 `catch` 不置**（失败下次还会重试）—— 这条不一致就是最硬的证据；影响限于当前页面实例（`JudgeApi` 是随 source 一次性构造） | `data/.../judge/JudgeApi.kt:157,164-182`；`data/.../schedule/SchoolCourseApi.kt:57-74`；`data/.../judge/AppJudgeSources.kt:28-43`；`data/src/jvmTest/.../JudgeApiJvmTest.kt:113-114` |
| #11 | **部分成立** | 低 | 「只认 `options.first() == "优秀"`」属实：**升序量表**（`不合格,合格,良好,优秀`）该判断不命中、整表全优；但「第一道题固定改良好」是**测试钉住的**当前行为，且 UI 明说「会有一项自动改为「良好」」——那半条是产品口径不是 Bug | `data/.../judge/GraduateJudgeApi.kt:290-296`；选项来源 `:379-381`；`data/src/jvmTest/.../GraduateJudgeApiTest.kt:39-40,81-83`；`data/.../judge/AppJudgeSources.kt:63` |
| #12 | **成立** | 低 | 注释写「字符**全**落在 0x80..0xFF 时」，代码写的是 `none { … }`（有一个就重解）⇒ 正常 UTF-8 中文串里只要有 `·`/`×`/`é` 就被 ISO-8859-1 转字节（CJK→`?`）再按 GBK 解，整串毁掉；我用 JVM 复算得到 `"兴庆校区·东南门" → "????\uFFFD???"` | `data/.../venue/VenueApi.kt:70-79,113-116,143-144,185,289`；测试只钉了「ASCII 名不动 / GBK 页修回」`data/src/jvmTest/.../VenueApiJvmTest.kt:89-95,156-160` |

**我重判出的「致命 / 高」清单**：**#02（高）** —— 唯一一条我判「高」；**没有一条我判「致命」**（审计判「致命」的 #01 我一审判「中」，理由见该节）。

---

## 2. 逐条复核

## #01 `ScoreCalculator` 清洗正则漏小数点 / 等级制漏判

**审计原文的断言**：正则白名单没有 `.`，`"59.5"` 被清成 `"595"` → `>= 95` → 满绩 `4.3`；`优秀/良好/中等/合格` 未映射 ⇒ 静默漏算。严重度审计判「致命」。

**我读到的代码事实**

```kotlin
// core/src/commonMain/kotlin/com/xjtu/toolbox/score/ScoreCalculator.kt:47-55
private fun scoreToGpaFromString(raw: String): Double? {
    val g = raw
        .replace('＋', '+')
        .replace('－', '-')
        .replace(Regex("[^a-zA-Z0-9+\\-\\u4e00-\\u9fff]"), "")   // ← 白名单里没有 '.'；小数点被删
        .uppercase()
    return when {
        g.isEmpty() -> null
        g.toDoubleOrNull() != null -> scoreToGpa(g.toDouble())   // ← "595" 走这里 → scoreToGpa(595.0) → 4.3
```
```kotlin
// ScoreCalculator.kt:62-67（HEAD 的中文档 —— 与审计引文不同）
g == "优+" -> 4.3; g == "优"  -> 4.0; g == "优-"  -> 3.7
g == "良+" -> 3.3; g == "良"  -> 3.0; g == "良-"  -> 2.7
g == "中+" -> 2.3; g == "中"  -> 2.0; g == "中-"  -> 1.7
g == "及格" -> 1.3; g == "不及格" -> 0.0
g == "通过" || g == "不通过" -> null      // 二等级制：刻意的（KDoc 第 22 行）
```

用 JVM 复算（`jshell`，等价 class，**不是**跑本仓构建/测试）：

```
"59.5"  -> '595'  -> 595.0     "84.5" -> '845' -> 845.0
"95"    -> '95'   -> 95.0      "优秀" -> '优秀' -> null（不进数字分支）
"及格"  -> '及格' -> null（然后命中 g == "及格" -> 1.3）
"95/100"-> '95100'-> 95100.0   "90.0" -> '900'  -> 900.0
```

**谁调用它（可达性）**

1. **报表路**（真实可达）：`data/.../score/ScoreReportApi.kt:122` 把帆软报表 HTML 里的成绩文本直接喂 `scoreToGpa(scoreText)`；结果进 `ReportedGrade.gpa`（`core/.../score/ScoreReportModels.kt:15-21`），再被
   - `JwappScoreViewModel.kt:171-176`（未评教课程用报表补的成绩，`gpa` 原样带上）、`:268-277`（`toScoreItem`）带到成绩页；
   - 成绩页加权：`core/.../jwapp/JwappScoreScreen.kt:177-188` 调 `calculateGpaForCourses`；
   - 屁岱：`app/.../agent/AgentTool.kt:1103-1124`（`get_grades` → `calculateOneCourseContribution` + `accumulate` → 文案里印 `加权 GPA`）。
2. **jwapp 分项路**：`data/.../jwapp/JwappApi.kt:157`（`serverGpa > 0` 才用服务器的，否则回落到本地映射）。

**兜底/优先级（审计漏掉的重要一层）**：`courseGpa`（`ScoreCalculator.kt:80-81`）和 `calculateOneCourseContribution`（`:186`）都**优先用上游下发的精确绩点**（`gpa`/`XFJD`），而 `JwappScoreViewModel.kt:171-176` 会用教务 `xscjcx` 的 `XFJD` 覆盖 jwapp 那份 ⇒ 在**成绩页主路径**上，只要 `xscjcx` 拉到了，字符串歧义就不参与计算。真正裸露的是：`xscjcx` 拿不到（`:152-163` 失败即 `precise == null`）、或该课没对上、以及**未评教课程**（`:202-210` 走报表）。

**判定与理由：部分成立。**
- 正则漏 `.` 是**真的**（`ScoreCalculator.kt:51`）；`"59.5" → 595 → 4.3` 的算术也是**真的**（上表）。审计的核心机制站得住。
- 但审计引文本身不可信：它引的 `app/src/main/java/com/xjtu/toolbox/score/ScoreCalculator.kt:44-67` 那段函数体（`val raw = grade.trim()` / `if (raw.isEmpty())` / 单档 `良→3.3` / `something else -> null` 收尾）**在本仓任何一次提交里都不存在**（§0.2 的 `git log -S` 全 0 命中）；HEAD 与 `origin/main` 都是「11 级 + 通过/不通过→null」。
- 「`优秀/良好/中等/合格` 漏判」：HEAD 只剩**双字**那几种会落到 `else -> null`（`良/中/及格` 是**有**映射的，审计说它们漏判 —— 错）。而且 `"优秀"→null` 被一条测试**显式钉住**：`data/src/jvmTest/.../ScoreReportApiJvmTest.kt:38-43` 的注释与断言（「『优秀』不在其中 ⇒ 不参与加权」）。这是「决策 + 测试」而非「漏写」，要说它是 Bug，得先改口径。
- **可达性没被钉死的关键**：全仓**没有任何**证据表明上游会下发带小数点的成绩文本 —— `testkit` 的报表夹具是 `95 / 优秀 / 88`（`JwxtFakeUpstream.kt:775-776,891`），jwapp 夹具是整数（`JwappFakeUpstream.kt:126`）。所以「任何带小数点的成绩字符串」这句是**假定**，不是观测。
- 无测试钉住相反行为（`ScoreCalculatorTest.kt:22-59` 只有整数与单字等级）。

**真严重度：中**（不是审计的「致命」）。理由：机制为真、后果严重（不及格被算成满绩），但**触发条件未经证实**（需要上游真下发小数或「95/100」这类带分隔符的文本），且成绩页主路径有「服务器精确绩点优先」这层兜底，受影响面主要是 `xscjcx` 失败时/未评教课程/屁岱算分。**判据留给读者**：只要能给出一次真实的「带小数成绩」抓包（或补一条夹具），本条应立刻升为**高/致命**；反过来，如果上游口径就是整数，它只是「等一个上游变更就炸」的隐患。

**最小复现路径**
- 纯函数（可编译期复算，无需网络）：`ScoreCalculator.scoreToGpa("59.5")` → 期望 `0.0`（`<60`），实际 `4.3`。
- 端到端：成绩页（`JwappScoreScreen`）→ 某门走报表的课（未评教/`xscjcx` 失败）成绩文本为 `59.5` → 期望该门不计/0.0，实际 `ScoreCalculator.kt:122 → ReportedGrade.gpa=4.3` → 屏上绩点 4.3、加权 GPA 被抬高；屁岱 `get_grades` 同样把它按 4.3·学分 累加。

**D7（是否改变 Android 现有可见行为）**：**改变**（错误数值→正确数值）——属于修 Bug 类行为变更，但要注意**已落盘缓存**：`ReportedGrade` 会随成绩报表缓存进 `DataCache`（`ScoreReportModels.kt:37-51`），修复后旧缓存仍带 `4.3`，TTL 内不刷新。三端共用这份 `:core` 代码（桌面/web 同步受益）。

---

## #02 切账号不重置 `AgentViewModel`，A 的聊天记录与画像写进 B 的目录

**审计原文的断言**：`bind()` 只跑一次、`accountId` 变化只刷新 config → 账号 B 看到 A 的聊天；继续发消息会把 A 的历史与身份画像写进 B 的落盘目录。

**我读到的代码事实**

```kotlin
// core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentScreen.kt:88,92,94-99
val vm: AgentViewModel = viewModel(key = "agent") { AgentViewModel(host) }
LaunchedEffect(Unit) { vm.bind(host.sessions()) }
LaunchedEffect(host.accountId) {
    config = host.loadConfig()
    // （原来的"没配 key 就自动跳设置页"被刻意删掉了；这里**没有**任何 vm 重置）
}
```
```kotlin
// core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentViewModel.kt:201-208
fun bind(store: AgentSessionPersistence) {
    if (this.store != null) return          // ← 只绑定一次，之后再进屏也不重载
    this.store = store
    sessions.addAll(store.list())
    ...
}
// :265-286 persist() → store.save(id, StoredConversation(stored, JsonArray(llmHistory)…))
```
```kotlin
// data/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentSessionStore.kt:29-33,83,93-95
private val dir: File
    get() = File(rootDirOf(), "agent_sessions" + AccountContext.safeSuffix()).apply { mkdirs() }
private fun convoFile(id: String) = File(dir, "convo_$id.json")     // ← 每次读写都现取当前账号后缀
```
- 唯一「认账号」的地方是**工具表**：`AgentViewModel.kt:394-402`（`toolsAccountId != accountNow` 就重建 tools）。`sessions / messages / llmHistory / currentSessionId` **没有任何一处**按账号重置（全仓 `grep -n 'accountChanged\|resetForAccount'` 在 `agent/` 下零命中）。
- 切账号确实**不会**动这颗 VM：`AccountManager.switchToLocked`（`app/.../account/AccountManager.kt:82-114`）只清 `AppLoginState` 内存态（`AppLoginState.kt:172-191`）+ 换 backend + 换 WebStorage，既不 `recreate()` 也不清导航栈；而 `viewModel(key="agent")` 挂在 `MainScreen` 那个 NavEntry（`AppNavHost.kt:152-153` 的 `entry<AppRoute.Main>`）上，切账号时 Main 一直在栈里 ⇒ VM 存活。
- 画像里**真有**这些字段：`app/.../AgentTool.kt:148-236` 的 `userContext()` 会拼 `姓名 / 学号 / 学院 / 书院 / 专业 / 班级 / 辅导员（含电话）/ 班主任`，由 `AgentViewModel.kt:422-427` 塞进 `AgentPrompt.build(userContext=…)`（`AgentPrompt.kt:49`）→ 存进 `llmHistory[0]` 落盘。
- 屏上直接渲染 `vm.messages`（`core/.../agent/AgentChat.kt:147,151,161,219`），不重新从 store 读。

**判定与理由：成立。** 机制、可达性、落盘路径三条都读通了；没有测试钉住相反行为（`grep` 全仓无 `agent_sessions` 的账号切换测试）。唯一与审计不符的是**接口名**（`sessionStore` → `host.sessions()`；`bind` 行号 202-208 → 201-208），不影响结论。

**真严重度：高。** 理由：跨账号**写入**（不可逆污染）+ 隐私外泄（A 的聊天/画像出现在 B 的屏幕与磁盘），触发只需「本硕连读/帮同学登过」这种被 App 主动支持的多账号场景，且**没有任何 UI 提示**。不到「致命」：不涉及凭据/资金/远程执行，单账号用户完全不受影响。

**最小复现路径**
1. 账号 A 登录 → 底栏「屁岱」聊两句（或用 `readZyxfFile`/成绩工具让画像补齐）→ 我的 → 账号管理 → 切到账号 B；
2. 回底栏点「屁岱」→ **实际**：屏上还是 A 的对话（含 A 的姓名/学号/辅导员电话）；
3. 在 B 里再发一条消息 → `ls filesDir/agent_sessions_<B后缀>/` 出现 `convo_<A的会话 id>.json`，内容含 A 的完整历史（**期望**：B 只看到自己的会话，A 的文件不动）。

**D7**：**改变 Android 现有可见行为**（切账号后屁岱展示新账号的会话列表/空会话）。修法建议与审计一致（`onAccountChanged()`：取消 `streamJob`、清 `messages/llmHistory/currentSessionId`、`store.list()` 重载），但**别**顺手改掉「一段对话只生成一次 system prompt」那条既有口径。

---

## #03 SSO 落地 401 被判成 CAS 密码错误，触发全局密码熔断

**审计原文的断言**：`open()` 阶段目标站返回 `HTTP 401`（无登录表单）时 `ssoErrorMessage` 含 `"HTTP 401"`，下游 `isCredentialFailure` 命中 `"401"` → 抛 `PasswordInvalidatedException` → 全局熔断要求重输密码。

**我读到的代码事实**

```kotlin
// data/src/commonMain/kotlin/com/xjtu/toolbox/auth/XJTULogin.kt:443-447
} else if (executionInput.isEmpty() && existingClient != null && response.code >= 400) {
    ...
    ssoErrorMessage = "目标服务返回错误（HTTP ${response.code}），可能暂时不可用"
```
```kotlin
// XJTULogin.kt:491-499（**作者自己知道这个坑**）
// 不转成 FAIL：上层把含「401」「密码错误」的 FAIL 当密码失效，会触发熔断。
// 包成 IOException，站点层才会照常记登录失败、进冷却
ssoFailure?.let { throw it as? IOException ?: IOException(it.message ?: …) }
ssoErrorMessage?.let { msg -> return LoginResult(LoginState.FAIL, msg) }   // ← 494-496，仍走 FAIL
```
```kotlin
// data/src/commonMain/kotlin/com/xjtu/toolbox/auth/CasSiteSession.kt:58-64,95-99
LoginState.FAIL -> {
    val msg = result.message
    if (isCredentialFailure(msg)) throw PasswordInvalidatedException(siteName, msg)
    ...
private fun isCredentialFailure(msg: String): Boolean =
    msg.contains("用户名或密码", true) || msg.contains("密码错误", true) ||
        msg.contains("账号或密码", true) || msg.contains("401")     // ← 就是它
```
- 分支可达性：`existingClient != null` 是 App 内的常态（`CasSiteSession:44-48` 用 `backend.client` 构造，`XJTULogin.kt:262-276`），`open()` 用默认 client（跟重定向），`response` 是**整条链的末响应**（`XJTULogin.kt:361-403`）⇒ 目标站真的回 401 就会落进 443。
- 只有 **HTTP 401** 会误判：`"HTTP 404"`/`"HTTP 500"` 不含子串 `"401"`。
- 熔断面：`SessionManager.reportPasswordInvalidated`（`SessionManager.kt:208-214`）→ `checkPasswordValid` 对所有站点抛（`:216-221`）；`AppLoginState.kt:60-66` 弹窗（`isLoggedIn` 时）；**恢复条件**：用户输入**不同**的凭据（`AppLoginState.kt:252-262`）或切账号。
- 另有一处同向设计：凭据 POST 收到 401 时作者写死文案 `"用户名或密码错误"`（`XJTULogin.kt:624-627`）—— 也就是说 `"401"` 这个关键字**唯一**的实际用途就是命中 `ssoErrorMessage` 那句。

**判定与理由：成立。** 这是「同一函数里两条失败路径口径不一致」的经典漏改，注释白纸黑字承认了下游的脆弱逻辑，却只修了 `ssoFailure` 一路。无测试钉住相反行为（全仓 `grep '目标服务返回错误\|ssoErrorMessage'` 只命中源码本身）。

**真严重度：中**（不是审计的「高」）。理由：后果确实重（全局自动登录锁死＋要重输密码），但触发面**极窄** —— 必须是「某个校内子系统在 SSO 重定向落地时恰好返回 401 且无登录表单」；404/5xx/连接失败都不会误判，且用户重输（或切换）一次即可自解。审计说「某个子系统因自身网关配置或临时故障返回 401」是**推断**，全仓没有任何抓包/夹具佐证。

**最小复现路径**
- 结构化复现（无需真实上游）：让 `XJTULogin.login` 的入口响应为 `401` 且无 `loginForm`（可仿 `testkit` 的假上游加一条路由）→ 观察 `LoginResult(FAIL, "目标服务返回错误（HTTP 401），可能暂时不可用")` → `CasSiteSession.runCasLogin` 抛 `PasswordInvalidatedException`（**期望**：`IOException`＋仅该站点冷却）。
- 手工验证熔断面：命中后任意打开一个校内系统页面 → 立刻 `PasswordInvalidatedException("密码已失效，请更新")`，不再尝试登录。

**D7**：**改变 Android 现有可见行为**（从「弹窗叫人更新密码」变成「只报这个站点登录失败」）——只在 401 场景可见，属修 Bug。修法用审计建议的 `LoginResult.isCredentialError` 更彻底，但那是改公共返回类型，三端都要跟。

---

## #04 `CampusProbe` 明文 HTTP 探针 + 只看 `code < 500`

**审计原文的断言**：探针走明文 `http://rg.lib.xjtu.edu.cn:8086/`，`reach()` 只要 `code < 500` 就算可达；酒店/机场/Captive Portal 的 302/200 会被判成「校内强结论」并缓存 10 分钟，导致校外无法切 WebVPN。

**我读到的代码事实**

```kotlin
// app/src/main/java/com/xjtu/toolbox/auth/CampusProbe.kt:43-48
private val PROBE_URLS = listOf(
    "http://rg.lib.xjtu.edu.cn:8086/",     // ← 明文，且排在第一个
    "https://iclassface.xjtu.edu.cn/",
    "https://kq.xjtu.edu.cn/",
)
```
```kotlin
// CampusProbe.kt:114-121（不是审计说的 HttpClients.probe；HEAD 的 HttpClients 只有 `base`）
private val client by lazy {
    HttpClients.base.newBuilder()…connectTimeout(2s)…callTimeout(4s).followRedirects(false).build()
}
// :156-157  谁先到谁说话
is Signal.Probe -> if (signal.ok) return Verdict(true, strong = true, why = "内网探针可达")
// :174-178
private fun reach(call: Call): Boolean = try { call.execute().use { it.code < 500 } } catch (_: Exception) { false }
```
- 10 分钟缓存：不在 `CampusProbe` 里，而在 `app/.../auth/AppLoginState.kt:147-149`（`CAMPUS_CACHE_MS`）与 `:317-321`（命中缓存直接返回）。误判后 `redetect` 会把 `SessionManager` 切到 `AccessMode.NORMAL`（`AppLoginState.kt:119-143` → `SessionManager.kt:100-109`），跟随全局模式的站点（含 library/iclassface/ssn/jiaocai/gste…）随后全部直连。
- 逃生门（审计没提）：设置里「连接模式」可强制 `direct`/`vpn`，`detectCampusNetwork` 会短路（`AppLoginState.kt:311-316`），用户可自救；换网也会 `unsettleAccessMode` 重判（`NetworkChangeWatcher`）。
- 测试只覆盖 `decide()`（`app/src/test/java/com/xjtu/toolbox/auth/CampusProbeTest.kt:16-75` 用注入的 `Signal.Probe(true/false)`），**`reach()` 的 HTTP 语义一条都没测** ⇒ 这条缺陷没有任何测试兜着。

**判定与理由：成立。** 「明文 HTTP → 毫秒级抢先回来；Captive Portal/透明代理回 302/200；`302 < 500` ⇒ `ok=true` ⇒ `Verdict(onCampus=true, strong=true)` 且取消其余协程」这条链是纯代码事实（`followRedirects(false)` 让 3xx 直接成为末响应，不需要 TLS 握手所以必然最快）。审计引的 `HttpClients.kt:99` 不存在（旧版），但机制描述与 HEAD 一致。

**真严重度：中**（与审计的「高」不同）。理由：触发需要「Captive Portal / 透明 HTTP 代理」这类网络，而这类网络下用户通常**本来也上不了网**（认证前）；误判在认证完成后仍有 10 分钟尾巴 + 每次误判窗口都是分钟级，且用户有设置里的手动逃生门。它会让「校外自动切 WebVPN」这条核心体验在特定网络下失效，但可自愈、无数据风险。

**最小复现路径**
1. 连一个带认证门户的 Wi-Fi（或把网关换成会 302 的透明代理）→ 冷启动 App / 切一次网；
2. **实际**：`CampusProbe.detect()` 日志出现 `verdict=Verdict(onCampus=true, strong=true, why=内网探针可达)`；随后 10 分钟内需要 WebVPN 的站点（图 seat/iclassface）全部直连超时；
3. **期望**：`Verdict(onCampus=false)` 并切到 WebVPN。（判据只看 `reach()` 那一行的 `code < 500`，任何人可静态复算。）

**D7**：**改变 Android 现有可见行为**（这类网络下从「失败」变成「走 WebVPN 成功」）。修法（校验响应特征 / 复用内网 IP 校验）不影响校内/校外正常网络。

---

## #05 后台 `HeadlessSessions` 固定直连，图书馆提醒在校外必失败

**审计原文的断言**：进程被回收后 `HeadlessSessions.manager()` 新建的 `SessionManager` 默认 `NORMAL`、不读 `networkMode`、不探测 ⇒ `LibraryReminderWorker` 在校外/流量下 100% 超时。

**我读到的代码事实**

```kotlin
// app/src/main/java/com/xjtu/toolbox/notification/HeadlessSessions.kt:65-104（截关键行）
suspend fun manager(): SessionManager? {
    SessionManager.active?.let { return it }                       // 前台活着就用前台那份
    val account = AccountStore().activeAccount() ?: return null
    ...
    val manager = SessionManager()
    ...
    manager.reconfigureForAccount(AccountContext.suffixFor(account.accountId))
    manager.setCredentials(account.accountId, account.password)
    // ← 没有 CredentialStore.networkMode、没有 CampusProbe、没有 onNetworkChanged
```
```kotlin
// data/src/commonMain/kotlin/com/xjtu/toolbox/auth/SessionManager.kt:64,77
private val _currentAccessMode = MutableStateFlow(AccessMode.NORMAL)   // 默认直连
private val accessModeSettled = MutableStateFlow(true)                 // 默认"已判定"⇒ 不等待
// :126  private fun backendFor(site) = if (!site.mustUseWebVpn) NORMAL else _currentAccessMode.value
```
```kotlin
// app/.../notification/LibraryReminderWorker.kt:43-52
val site = HeadlessSessions.site(LoginType.LIBRARY) ?: return Result.success()
withContext(Dispatchers.IO) { LibraryApi(AppLibrarySession(site)).getMyBooking() }
catch (e: Exception) { Log.w(TAG, "查预约失败，稍后重试：${e.message}"); return Result.retry() }
```
- `LibrarySession` 就是 `mustUseWebVpn = true`（`data/.../auth/LibrarySession.kt:28`；默认值见 `SiteSession.kt:50`），座位首页是 `rg.lib.xjtu.edu.cn:8086`（校内可达）⇒ 校外直连只能超时。
- 前台不受影响：`AppLoginState` 在进程存活时会把 `SessionManager.active` 设好（`app/.../auth/AppLoginState.kt:387`），而 `HeadlessSessions.kt:66` 优先复用它。
- 重试语义（审计没算）：`Result.retry()` + 无自定义退避（`LibraryReminderWorker.kt:138-154` 只设了网络约束）⇒ WorkManager 默认指数退避会反复重试；一旦用户接回校园网 Wi-Fi（同一 headless manager 仍是 NORMAL，但校内直连就通）或前台起来，提醒会**迟到但能发出去**。所以审计的「100% 失败」应限定为「一直不在校园网内」的情形。

**判定与理由：成立。** 代码事实与可达路径都对（`SessionManager.kt:546-552` 的注释也自陈「App 进程已死时这里是 null，由后台任务自己按当前账号建一个临时的」），无测试钉住相反行为。

**真严重度：中**（与审计的「高」不同）。理由：影响面是「一条提醒」（不是数据/资金），且（a）仅**进程被杀**时走 headless，（b）WorkManager 会重试、回到校园网即送达，只在「用户在整个窗口内都不在校园网」时彻底丢失。但它确实是**静默**失败（用户以为设了提醒），且 `HeadlessSessions.manager()` 里补一次 `networkMode` 判断的成本极低。

**最小复现路径**
1. 前台（校园网或 WebVPN 下）预约图书馆座位 → 让 App 被系统回收（`adb shell am kill`）；
2. 关 Wi-Fi 走 4G/5G（或直接在校外）→ 等 25 分钟签到点；
3. **实际**：`logcat` 里 `HeadlessSessions`/`LibraryReminderWorker` 反复 `查预约失败，稍后重试：…timeout`，通知始终不来；**期望**：提醒到达。

**D7**：**改变 Android 现有可见行为**（校外/流量下会多出这条提醒）。属于补齐既有承诺，不动前台逻辑。

---

## #06 `AgentConfigPanel` 防抖保存缺 `DisposableEffect`

**审计原文的断言**：注释承诺「退出 Composable 时 `DisposableEffect` flush」，但代码里没有；粘完 API Key 后 500ms 内返回会丢配置。

**我读到的代码事实**

```kotlin
// core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentConfigPanel.kt:78-103
/**
 * 防抖保存：每次调用都把挂起的保存任务延后 500ms，…
 * 切页 / 退出 Composable 时通过 `DisposableEffect` flush.        ← 注释（第 81、94 行各写了一遍）
 */
var pendingSaveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
val scope = rememberCoroutineScope()
fun scheduleSave() {
    pendingSaveJob?.cancel()
    pendingSaveJob = scope.launch { kotlinx.coroutines.delay(500); saveNow(); pendingSaveJob = null }
}
```
```kotlin
// AgentConfigPanel.kt:158-173   API Key 字段
onValueChange = { apiKey = it; scheduleSave() }      // 走 500ms 防抖
keyboardActions = KeyboardActions(onDone = { saveNow() })   // 只有 IME「完成」才立即存
```
- `grep -n 'DisposableEffect\|onDispose' AgentConfigPanel.kt` ⇒ **只命中第 81、94 行那两句注释**；`rememberCoroutineScope()`（第 89 行）的 scope 随该 Composable 离开组合被取消 ⇒ `delay(500)` 抛 `CancellationException`。
- 关闭路径确实会把它移出组合：`AgentScreen.kt:132-138`（`BackHandler` → `showConfig = false`）→ `:199-229` 的 `AnimatedContent(targetState = showConfig)` 换页（退出动画 120–180ms，之后旧内容被 dispose）。
- 同一文件里 `saveNow` 会把局部 `remember` 起来的全部字段一次性写盘（`:64-76`）；而这些局部状态也随组合销毁 ⇒ **不是"没保存"，是输入内容也没了**。
- 对照：同一作者在 `AgentScreen.kt:186-194` 用 `DisposableEffect(Unit) { onDispose { … } }` 做顶栏按钮的清理 ⇒ 「忘了写」而不是「不会写」。

**判定与理由：成立。** 代码与注释直接矛盾，无测试钉住（该面板没有 Compose 测试）。审计引的 `saveDebounced` 名字在 HEAD 叫 `scheduleSave`（`AgentConfigPanel.kt:96`），行号 72-97/383-385 也已漂移，但事实无误。

**真严重度：低**（与审计的「中」不同）。理由：需要「最后一次敲键/粘贴后 <500ms 内关闭面板或按系统返回」才触发；丢了可以重贴（不是不可恢复的数据丢失），且 IME「完成」键与每个非文本控件都会立即保存。真实痛点是「粘贴后顺手返回」这个动作确实常见 —— 若产品认为「配置丢失」不可接受，可升到中。

**最小复现路径**：屁岱 → 右上设置 → 在「API Key」里粘贴一串值 → 立刻按系统返回键 → 再进设置：字段为空、`shared_prefs/agent_config_secure*.xml` 里也没有该值（**期望**：值已保存）。

**D7**：**改变 Android 现有可见行为**（关闭面板时会把当前输入落盘）。补 `DisposableEffect` 正是注释里承诺的行为，不涉及其他端。

---

## #07 加餐券勾选未按账号隔离（切账号后被 12 秒循环同步给新账号）

**审计原文的断言**：`payment_code` 这个 SharedPreferences 没拼账号后缀 ⇒ 账号 A 的券勾选被带到 B，且每 12 秒把 A 的券 ID 发给 B 的接口；A 若勾成空集，B 的券会被静默全关。

**我读到的代码事实**

```kotlin
// core/src/androidMain/kotlin/com/xjtu/toolbox/platform/KeyValueStore.android.kt:24-26,69
private class AndroidKeyValueStore(private val name: String) : KeyValueStore {
    private val prefs: SharedPreferences get() = requireContext().getSharedPreferences(name, MODE_PRIVATE)
actual fun keyValueStore(name: String): KeyValueStore = AndroidKeyValueStore(name)     // name 无账号后缀
```
```kotlin
// app/src/main/java/com/xjtu/toolbox/pay/AppPaymentCode.kt:46-50
PaymentCodeScreen(
    source = remember(site) { PaymentCodeApi(site) },
    selections = remember { PaymentSelectionStore(keyValueStore("payment_code")) },      // ← 全局一份
```
```kotlin
// core/src/commonMain/kotlin/com/xjtu/toolbox/pay/PaymentCodeScreen.kt:44,95-100,154-161
private const val REFRESH_SECONDS = 12
var selectedVoucherIds by remember(selections) { mutableStateOf(selections.selectedIds()) }
var selectionConfigured by remember(selections) { mutableStateOf(selections.isConfigured()) }
while (isActive) {
    val code = … source.getBarCode()
    if (latestSelectionConfigured) { source.updateVoucherStatus(latestSelectedVoucherIds) }   // 每 12s 推一次
```
```kotlin
// core/.../pay/PaymentCodeModels.kt:82-90（空集的语义 —— 审计那条「B 的券被全关」的依据）
/** 只有动过才在出码时把选择同步给上游：没动过就同步会把「一张不用」推上去。 */
fun save(ids: Set<String>) { store.putString(KEY_SELECTED_VOUCHERS, ids.joinToString(",")); store.putBoolean(KEY_SELECTION_CONFIGURED, true) }
```
- 账号隔离清理列表里**没有** `payment_code`：`app/.../account/AccountManager.kt:346-350` 的 `ACCOUNT_PREFS`（14 项）与 `:318-340` 的 `wipeAccountFiles` 都不含它（只有 `agent_*`/`avatar*`/`campus_card`/… 那些带后缀的）。全仓 `grep 'payment_code'` 只有 `AppPaymentCode.kt:48`、桌面 `ToolboxDesktopApp.kt:759`、web `ToolboxWebApp.kt:824`（三端同一名字，都没后缀）。

**判定与理由：成立。** 存储名不隔离、出码循环无条件推送、空集语义是「一张不用」，三条串起来就是审计描述的行为；测试（`desktop/src/test/.../DesktopAuthPaymentCodeJvmTest.kt`）只钉了「键名与 `:app` 逐字相同、能落盘读回」，**没有**任何多账号隔离断言（恰恰把「跨账号共享」钉成了预期）。审计引的 `PAYMENT_PREFS` 常量、`PaymentCodeScreen.kt:57-59,103-104,177-180` 与 `AccountManager.clearAccountPrefs` 在 HEAD 都已改名/搬走（现在是 `PaymentSelectionStore` + `keyValueStore` + `wipeAccountFiles`），事实无误。

**真严重度：中。** 理由：跨账号**写上游状态**（属于资金/权益侧），且用户完全看不到（券被静默关掉 → 刷卡扣余额）；但触发需要多账号 + 之前动过勾选，最坏损失是「本该用券的这单用了余额」。没有数据破坏，故不到高。

**最小复现路径**
1. 账号 A 打开付款码 → 把加餐券全取消勾选（或勾 1 张）→ 关闭；
2. 账号管理切到账号 B → 打开付款码；
3. **实际**：券列表的勾选状态 = A 的（B 看到 A 的券 ID 或"全不勾"），并且每 12 秒 `POST /berserker-app/vouchers/updateVoucherStatus`（`data/.../pay/PaymentCodeApi.kt:53,129`）带 A 的 ID 集合打到 B 的校园卡会话；**期望**：B 用自己的默认勾选。

**D7**：**改变 Android 现有可见行为**（切账号后券勾选不再延续）——但三端同时受影响（web/desktop 用的同一个存储名），要一起改口径（后缀化 + 加入账号清理列表）。注意：改成带后缀会让**升级后第一次打开**读不到旧勾选（老用户需要重勾一次），这是唯一的产品口径取舍点。

---

## #08 删会话/清缓存都不删 `agent_images` 压缩件

**审计原文的断言**：`AgentVision.deleteAll` 只在「发送前撤图」被调用，删会话/清缓存都不清理 `filesDir/agent_images`，图片永久堆积。

**我读到的代码事实**

```kotlin
// app/src/main/java/com/xjtu/toolbox/agent/AndroidAgentHost.kt:356-362,373-386
private const val AGENT_IMAGE_DIR = "agent_images"
private fun agentImageDir(context: Context): File =
    File(context.filesDir, "$AGENT_IMAGE_DIR${AccountContext.safeSuffix()}").apply { mkdirs() }
    …
    val out = File(agentImageDir(context), "${UUID.randomUUID()}.jpg")   // ← 每张一个新文件名，永不复用
// :285-287
override fun deleteImageFiles(paths: List<String>) { paths.forEach { runCatching { File(it).delete() } } }
```
```kotlin
// 全仓唯一的生产调用点（`:core` 的 AgentChat）
core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentChat.kt:304-308
onRemoveAttachment = { path -> attachments = attachments - path; host.deleteImageFiles(listOf(path)) }
```
- **不存在**的调用点：`AgentViewModel.deleteSession`（`AgentViewModel.kt:250-258`）、`switchSession`、`rewindAndResend`/`dropLastUserTurn`（`:139-150`）、`AgentSessionStore.delete`（`data/.../AgentSessionStore.kt:55-58`，只删 index/会话 JSON）、`SettingsScreen` 清缓存（`app/.../settings/AppSettings.kt:652-653` 只清 `cacheDir`，且 `filesDir` 不在其中）。
- 有兜底的一处：删账号会清目录（`AccountManager.kt:327-330` 的 `agent_images$suffix`）。
- 审计引的 `AgentVision.deleteAll(paths)`（`AgentVision.kt:139-142`）**在 HEAD 已不存在** —— `:core` 的 `AgentVision` 现在只剩 `supportsVision/userContent/pruneOldImages`（`core/.../agent/AgentVision.kt:38-120`），删文件走宿主缝 `AgentHost.deleteImageFiles`（`AgentHost.kt:146`）。

**判定与理由：成立（实质成立，引文需更正）。** 机制、缺失的四个调用点、单文件 UUID 命名导致的只增不减都核实了；无测试钉住。

**真严重度：低**（与审计的「中」不同）。理由：无功能破坏、无跨用户泄露（目录私有且按账号隔离），代价是内部存储单调增长与「删了还在」的预期落差；只有长期/重度用户才会明显（每张压缩件百 KB 量级）。影响面真实但缓慢，适合作为 P2 随手修。

**最小复现路径**：屁岱 → 发一张图（`filesDir/agent_images<后缀>/` 多一个 `<uuid>.jpg`）→ 会话列表删掉该会话 → `ls` 目录：文件**还在**；设置 → 清除缓存后再 `ls`：**还在**（期望：随会话删除；至少「清缓存」应把它算进去）。

**D7**：**改变 Android 现有可见行为**（删会话后缩略图文件消失，重进会话时本来就已被 `imageFileExists` 过滤掉，屏上无差异）。桌面端 `DesktopAgentHost` 的图片四件是默认值（`desktop/.../DesktopAgentHost.kt:82-84`），不受影响。

---

## #09 `download_tasks` 无多账号隔离

**审计原文的断言**：Room 表 `download_tasks` 没有 `accountId`，DAO 全表操作，账号清理也不碰它 ⇒ A 的下载任务出现在 B 的下载管理里。

**我读到的代码事实**

```kotlin
// app/src/main/java/com/xjtu/toolbox/media/DownloadTaskEntity.kt:13-31（列清单里**没有** accountId）
@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val activityId: Int, …)
// :42-43  suspend fun getAll(): List<DownloadTaskEntity>   （@Query("SELECT * FROM download_tasks ORDER BY createTime DESC")）
// :66-67  suspend fun deleteCompleted()                    （@Query("DELETE FROM download_tasks WHERE status = 'completed'")）
```
```kotlin
// app/src/main/java/com/xjtu/toolbox/media/AppDownloadTaskStore.kt:31
override suspend fun getAll(): List<DownloadTask> = dao.getAll().map { it.toModel() }     // 无账号过滤
// core/src/commonMain/kotlin/com/xjtu/toolbox/media/DownloadManager.kt:81
suspend fun getAllTasks(): List<DownloadTask> = store.getAll()                            // 下载管理屏用它
```
```kotlin
// app/src/main/java/com/xjtu/toolbox/data/AppDatabase.kt:22（版本）、:142-170（最后一版迁移 6→7，只重建 jiaocai1_shelf）
// app/src/main/java/com/xjtu/toolbox/account/AccountManager.kt:339（账号清理只动 custom_courses）
runCatching { AppDatabase.getInstance(app).customCourseDao().deleteByAccount(accountId) }
```
- 审计说的 `MIGRATION_7_8`、`jiaocai1_shelf` 加 `accountId`、`clearAccountRoomData` **在 HEAD 全不存在**（`grep -n MIGRATION_ AppDatabase.kt` 只到 `MIGRATION_6_7`；`jiaocai1_shelf` 实体 `app/.../jiaocai1/Jiaocai1Shelf.kt:11-12` 无 `accountId` 列；全仓 `deleteAllForAccount` 零命中）—— 这段引文来自旧版本或臆造。

**判定与理由：成立（实质）。** 「表无账号列 + 查询全表 + 清理不包含」是三条可复算的代码事实；无测试钉住隔离行为。但要把话说准：**下载的视频文件本身是"本机公共文件"**（`AppDownloadFileSystem` 落在应用专属外部目录，与账号无关），所以「B 看到 A 的任务」可能是有意为之 —— 审计的建议里也承认这一点（要么明确标注公共，要么隔离）。

**真严重度：低**（与审计的「中」不同）。理由：同一台设备上、同一个人的两份下载记录；最坏是「帮同学登录一次后，对方看到你的课程名/文件名」这类**设备共享**场景下的元数据外泄，且不涉及凭据/正文内容；`deleteCompleted` 之类的跨账号操作是轻微的越权删除。若产品口径是「本地文件属本机公共」，本条应直接**降为口径文档问题**（0 代码改动）。

**最小复现路径**：账号 A 在思源学堂发起一个回放下载（进下载管理列表）→ 切账号 B → 打开「下载管理」→ **实际**显示 A 的任务；删掉账号 A（保留缓存）→ 记录仍在（**期望**：按账号隔离，或至少在 UI 上标注「本机公共」）。

**D7**：**改变 Android 现有可见行为**（B 不再看到 A 的下载任务）。按 §16 D7，**这一条建议先定产品口径再动手** —— 它是 12 条里唯一「修法本身会重新定义『本地已下载文件归谁』」的条目。

---

## #10 `JudgeApi` 预热标志在失败时也置 true / 重登后不重预热

**审计原文的断言**：`catch` 里也 `appInitialized = true`，预热失败后当前页面无论怎么重试都固定 404；会话过期重登后新会话没注册 module session，同样 404。

**我读到的代码事实**

```kotlin
// data/src/commonMain/kotlin/com/xjtu/toolbox/judge/JudgeApi.kt:157,164-182
private var appInitialized = false
/** jwapp 框架要求每个 app 模块先 GET 入口让服务端注册 module session，否则后续业务 API 返回 {"code":"404"}。 */
private suspend fun ensureAppInitialized() {
    if (appInitialized) return
    try { … site.executeWithReAuth(req).close(); appInitialized = true }
    catch (_: Exception) { /* 预热失败不阻塞业务，下游接口会自行报错 */ appInitialized = true }   // ← 180
}
```
**同一模式在兄弟实现里是反的**（这条最硬）：

```kotlin
// data/src/commonMain/kotlin/com/xjtu/toolbox/schedule/SchoolCourseApi.kt:62-74
private suspend fun ensureAppInitialized() {
    if (appInitialized) return
    try { … site.executeWithReAuth(req).close(); appInitialized = true }
    catch (e: Exception) { Log.w(TAG, "ensureAppInitialized failed", e) }     // ← 不置 true，下次会重试
}
```
- 实例寿命：`data/.../judge/AppJudgeSources.kt:28-29`（`UndergraduateJudgeSource` 持有 `private val api = JudgeApi(site)`，随 source 创建）⇒ 「永久 404」的边界是**当前页面/数据源实例**；`AppJwappScoreSource.kt:75`、`HomeStatsRefresher.kt:112` 都是每次新建，不受影响。
- 重登那一半：`SiteSession.executeWithReAuth`（`:316-346`）会在认证失效时重登并重放，`loginEpoch`（`:81-90`）**已经**是现成的「登录代数」，但 `JudgeApi.appInitialized` 从不看它（全仓 `grep -n loginEpoch` 无 JudgeApi 命中）⇒ 审计建议的「epoch 判定」在本仓有现成零件。
- 测试只钉「预热那一枪真的打过」（`data/src/jvmTest/.../JudgeApiJvmTest.kt:113-114`），没有任何「预热失败后重试」的用例。

**判定与理由：成立。** 「失败也置 true」是与兄弟实现相矛盾的真实缺陷（对比证据很强）；「重登后不重预热」在逻辑上成立，但**依赖上游行为**（jwapp 的 module session 是否绑 `JSESSIONID`）——本仓无法证伪，标注为推断。

**真严重度：低**（与审计一致）。理由：触发是「进评教页时恰好网络抖动」；影响限于当前页面实例（重进页面即自愈）；`executeWithReAuth` 的重放机制多半能掩盖重登那一半。修法一行（去掉 catch 里的赋值），性价比高。

**最小复现路径**：进评教页前断网/开飞行模式让 `index.do` 失败 → 恢复网络 → 在本页点重试 → **实际**业务接口固定 `{"code":"404"}`（`appInitialized` 已是 true、不再预热）；退出页面重新进入（新 source/新 api）→ 正常（**期望**：本页重试即可恢复）。

**D7**：**基本不改变可见行为**（只在失败路径上多打一枪 `index.do`）。若同时引入 `loginEpoch` 判定，正常路径行为也不变。

---

## #11 `GraduateJudgeApi` 全优防拦截只认「首选项=优秀」

**审计原文的断言**：`options.firstOrNull()?.second == "优秀"` 在升序量表（`不合格,合格,良好,优秀`）下不命中 ⇒ 整表全优被服务端拒；且固定改第一道题会形成全校统一指纹。

**我读到的代码事实**

```kotlin
// data/src/commonMain/kotlin/com/xjtu/toolbox/judge/GraduateJudgeApi.kt:290-296
val gradeText = listOf("不合格", "合格", "良好", "优秀")[level]         // level 3 = 优秀
data.questions.filter { it.view == "radio" && it.id !in data.answers }
    .forEach { data.setById(it.id, gradeText) }
if (level == 3) {
    data.questions.firstOrNull { it.view == "radio" && it.options.firstOrNull()?.second == "优秀" }
        ?.let { data.setById(it.id, "良好") }                          // ← 只认「第一个选项是优秀」的量表
}
```
- `options` 的来源：`:379-381` 把 webix 的 `{"id":…,"value":…}` 读成 `id to value` 对（`second` = 显示文字），顺序即服务端下发顺序。
- **测试钉住的是当前行为**：`data/src/jvmTest/.../GraduateJudgeApiTest.kt:39-40` 的夹具是「优秀在前」的量表，`:81-83` 断言 `q1 = 80（良好）`、`q2 = 100（优秀）` ⇒ 「只降第一道」是**被断言的既有行为**，审计建议的 random() 会让这条测试红。
- **第二半条其实是产品口径**：确认文案自己就写着「（系统不允许全部「优秀」，会有一项自动改为「良好」），确定继续？」（`data/.../judge/AppJudgeSources.kt:63`），用户在提交前就看到这条。
- 没有夹具覆盖「升序选项」这一档（这正是审计指出的缺口）。

**判定与理由：部分成立。** 第一半（升序量表漏判）是真实的健壮性缺口 —— 代码把「选项顺序」当成了「量表方向」的代理；但「服务端会拒全优」这条前提来自注释与作者经验，本仓无上游证据，也无法在本地复现。第二半（统一指纹）不是 Bug：它是**被测试固定、且向用户明示**的行为，属于产品/风控判断（改它要同时改文案与测试）。

**真严重度：低。** 理由：后果是「提交被拒、用户需手动改一道题」，可恢复；触发前提（gste 存在升序量表）未知；而「指纹」是策略问题，不是代码缺陷。

**最小复现路径**（需上游样本，标注为无法本地复现）：拿到一张选项升序的 gste 问卷 → `completeQuestionnaire(grade = 3)` → 所有 radio 都被写成「优秀」，防拦截分支不命中 → 提交被服务端拒。可本地做的等价复算：把夹具 `:39-40` 的两题选项顺序倒过来，`assertEquals("80", data.answers["q1"])` 会变成 `"100"`（**这可以从代码直接推出，不需要跑测试**）。

**D7**：**改变 Android 现有可见行为**（哪一道题被降档 / 升序问卷能否提交）。改条件为 `options.any { "优秀" in it.second }` 是向后兼容的小修；**random() 那半条不要照抄**（会破测试且要重写「确定性可复现」的口径）。

---

## #12 `VenueApi.fixGbk` 判据与注释相反，正常中文串会被毁

**审计原文的断言**：注释说「字符全落在 0x80..0xFF」，代码写的是 `none { … }`（有一个就重解）⇒ 合法 UTF-8 里带 `·`/`×`/`é` 时，CJK 经 `toByteArray(ISO_8859_1)` 全变 `?`。

**我读到的代码事实**

```kotlin
// data/src/commonMain/kotlin/com/xjtu/toolbox/venue/VenueApi.kt:70-79
/**
 * 服务端偶尔用 GBK 编码返回中文，OkHttp 按 latin-1 解会得到每个字节一个字符
 * 的乱码。字符**全**落在 0x80..0xFF 时按 GBK 重解一次。        ← 注释
 */
private fun String.fixGbk(): String {
    if (isEmpty() || none { it.code in 0x80..0xFF }) return this   // ← 代码：不含 0x80..FF 才跳过
    return runCatching { String(toByteArray(Charsets.ISO_8859_1), charset("GBK")) }.getOrDefault(this)
}
```
应用点：`extractNotice`（`:113,115`）、场馆 `name/address`（`:143-144`）、场地名（`:185`）、错误 `message`（`:289`）。

JVM 复算（`jshell`）：

```
"兴庆校区·东南门"（含 U+00B7）
  fixGbk → "????\uFFFD???"     hex: 3f 3f 3f 3f fffd 3f 3f 3f     ← 4 个汉字全变 '?'，· 变 U+FFFD
对照：纯中文（无 0x80..FF 字符）→ none{} = true → 原样返回，不被触碰
```
- 测试钉住的只有两端：ASCII 名不动、GBK 页的中文被修回（`data/src/jvmTest/.../VenueApiJvmTest.kt:89-95,156-160`；夹具 `testkit/.../VenueFakeUpstream.kt:356` 是「声明 latin-1、实际 GBK」）。**没有**「正确中文 + 拉丁字符」这一档 ⇒ 缺点没被兜住。

**判定与理由：成立。** 注释与代码直接矛盾（作者意图是「全是 0x80..0xFF 才重解」，那正是审计建议的修法），且 `ISO_8859_1` 的不可映射字符→`?` 的行为我在 JVM 上复算过。唯一未证的是**上游真会出现这种串**（场馆名/地址里带 `·`）——本仓夹具里没有任何 `·`，所以这是「等待上游数据」的隐患。

**真严重度：低**（与审计一致）。理由：影响是显示层文案乱码（场馆名、地址、错误提示），不回写、不影响下单；触发需要场馆数据里出现拉丁字符。

**最小复现路径**：让场馆接口的某个 `name`/`address` 返回含 `·` 的正常 UTF-8（如 `"兴庆·文体中心"`）→ 屏上显示 `"????\uFFFD????"` 一类的乱码（**期望**：原样显示）。复算：`"兴庆校区·东南门".let{ if (it.none{c->c.code in 0x80..0xFF}) it else String(it.toByteArray(ISO_8859_1), GBK) }`。

**D7**：**改变 Android 现有可见行为**（从乱码变成正常文本），并且**保留** GBK 那一路的修复能力。建议的修法（`any { it.code > 0xFF } → 原样返回` + 解码后查 `\uFFFD`）不改变现有测试断言。

---

## 3. 本组的置信度与未核对项

**总体置信度：中高。** 12 条里 8 条我把「代码事实」读到了可直接复算的程度（含 2 条用 JVM 复算了字符/正则层的行为：#01、#12）；4 条（#02/#05/#07/#09）是「代码路径 + 调用点」级证据。**没有一条**结论依赖审计自己的转述。

**我没核到底的（按重要度）**

1. **#01 的触发前提**：本仓没有任何真实抓包/夹具显示上游会下发 `"59.5"` 这类带小数的成绩文本（`testkit` 全是整数或「优秀」/「通过」）。我把它判成「中」而不是「致命」，正是因为这一条**只能由有真机/抓包的人定**。同理无法确认的是 `95/100`、全角数字（`"９５"` → `""` → `null`，只会「漏算」不会「错算」）这类变体的出现频率。
2. **#03 / #10 / #11 的上游行为**：某站 SSO 落地是否真会回 401（#03）；jwapp 的 module session 是否绑 `JSESSIONID`、重登后是否真会 404（#10）；gste 是否存在升序选项的问卷、服务端是否真会拒「全优」（#11）。这三条我都只核到「代码这么写会怎样」，**没有**上游证据，已在各节标注为推断/未知。
3. **#04 的网络侧**：我没有也不允许在真实 Captive Portal 下手测（本机也没有）；结论来自「`followRedirects(false)` + `code < 500`」的静态语义，`reach()` 本身**没有任何测试**。
4. **#05 的时间线**：我只静态确认了 headless manager 默认 NORMAL；「WorkManager 重试是否真能在窗口内救回来」取决于用户何时回到校园网，我按「会迟到但可能送达」写，**没有**实测时序。
5. **#07 的上游默认语义**：`updateVoucherStatus(空集) = 一张不用` 这条来自代码注释（`PaymentCodeModels.kt:82-85`），我没有校园卡后端的实证；若上游对空集是 no-op，则审计的第 2 条影响（B 的券被全关）不成立、只剩「勾选状态跨账号串味」。
6. **没有跑任何构建/测试**（按任务约束）：所以「测试钉住了什么」全部来自**读测试文件**而不是读运行结果；#11 里「倒过来会让断言变红」是从代码推断，未执行验证。
7. **审计基线的具体提交**：我能证明审计的行号/引文**不对应 HEAD**（§0.1/§0.2），但**无法定位它到底基于哪一次提交** —— #01 引的那段 `scoreToGpaFromString` 在本仓**任何 ref** 都不存在（`git log --all -S` 零命中），#09 的 `MIGRATION_7_8` 也是。因此那几处更像是审计自身的引文错误，而不是「旧提交」。
8. 本组**不涉及**正在被其他子代理改动的文件（`core/.../net/**`、`web/**`）；#02 相关的 `core/.../agent/**` 我只读未改。
