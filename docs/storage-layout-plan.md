# 统一存档层 —— 实现方案与工单拆分

> **这份文档是工单拆分，不是规格。** 规格（JSON schema、键名清单、TTL 档位、A1–A13 判据、未决问题 #1–#10）在
> `docs/storage-layout.md`（唯一真源，478 行）；本文只回答「怎么落到代码、切成几个能独立提交的工单、每个工单的门禁是什么」，
> 不复述规格里的任何条目。凡本文出现「§x.y / A1–A13」一律指 `docs/storage-layout.md` 的编号；「§16.x / D1–D18」指
> `docs/desktop-port-plan.md` 的编号。

> 口径（本文的写作前提，全部来自已拍板项）：本次是**重构不是功能更新**（§16.5 D15「安卓怎么做就怎么做」）；
> 目标 = 统一存档层 + 三端多账号（§16.3 批②），并顺带解决审计 #02/#07/#09 的「三端不一致侧」（§16.6 P2），
> **安卓侧既有行为一行不改**（C1 / §16.4 第 1 条）。门禁**只增不减**（§16.2 第 4 条）：
> `:core:jvmTest` **479** / `:app:testDebugUnitTest` **429** / `:desktop:test` **100** / `:data:jvmTest` **259** / `:server:test` **82**
> —— 这五个数字我在本 checkout（HEAD `3dc2f52a`）用 `grep -rho "@Test"` 逐模块复核过，逐字相符。

---

## 1. 实现架构（先把缝画清）

### 1.1 放哪个模块：`:core` 的 commonMain（`com.xjtu.toolbox.archive`）

理由（逐条都是「不这样就得返工」的硬约束）：

| 候选 | 结论 | 理由 |
|---|---|---|
| `:app` | ✗ | 桌面与 web 都要**消费同一份**档案代码；放进 `:app` 等于三份实现 |
| `:data` | ✗ | `data/build.gradle.kts` 只有 **jvm + android** 两个目标（文件里明写「加 wasm/ios 会全线崩」）⇒ `:web` 消费不了；而 web 是 D8/D9 的目标端之一 |
| 新模块 `:archive` | △ 不必要 | 档案层不需要任何新依赖：JSON 走 `:core` 已有的 `AppJson`（`core/src/commonMain/kotlin/com/xjtu/toolbox/util/JsonExt.kt`），账号规则走 `:core` 的 `AccountContext`，平台投影走新的 `expect/actual`。新模块只会多一层 Gradle 配置与一次变体解析 |
| **`:core` commonMain** | **✓** | 三个目标（android / jvm / wasmJs）都有 ⇒ 三端都能吃；`:app` 经 `:core` 也能吃；`AppJson` / `AccountContext` / `KeyValueStore` 都已在这一层 |

包名与既有缝并列：`core/src/commonMain/kotlin/com/xjtu/toolbox/archive/`。

### 1.2 缝的构成（两条 `expect` + 一个门面 + 一个传输对）

```
                 ┌──────────────────────────────────────────────┐
   :app / :desktop / :web  ──装配──▶  ArchiveHost（数据袋，不是缝）
                 │    accounts / createdBy / appVersion / rowFamilies
                 └──────────────────┬───────────────────────────┘
                                    ▼
                        StoreArchive（object，:core commonMain）
                     export(host, sink, options) / import(host, source, options) / inspect(source)
                                    │
              ┌─────────────────────┴─────────────────────┐
              ▼                                           ▼
  ArchiveStoreBackend  ← expect fun            ArchiveFileBackend? ← expect fun
  （端内 store 投影：枚举/读/写/删）              （files/** 那一层；wasm 返回 null）
  actual: androidMain / jvmMain / wasmJsMain    actual: androidMain / jvmMain / wasmJsMain
                                    │
                                    ▼
                   ArchiveSource / ArchiveSink（传输：目录 / zip / SAF / 下载）
                   实现在各端（不在 :core；:core 的 commonTest 用内存实现）
```

**为什么是两条缝而不是一条**：两条的**可用性**与**可测性**不同端不同 —— wasm 没有文件系统（`files/**` 那一层在
web 上根本不存在，只能返回 `null`），而 store 投影三端都有；`files/**` 那一层在 Android 上还要碰
`cacheDir`（课表族，§8.7）与 `filesDir`（会话），在桌面上碰数据根（§1.2）。合成一条缝会让「本端没有这一半」
变成一堆 `?:` 兜底，且 web 的实现被迫写一半空壳。

**为什么传输要单独抽**：A3（字节稳定）、A5（跨端互换）、A7（坏档案不动端）三条判据必须在**没有真文件系统**的
环境里跑（`:core:jvmTest`），所以 `:core` 只能看见 `ArchiveSource` / `ArchiveSink` 两个窄接口，具体是
`localStorage` 还是 `SAF` 还是 `.deb` 的目录都不进共享层。

### 1.3 与既有缝的关系：并列，但**复用它们的实现**

| 既有缝 | 关系 | 理由（逐条可核对） |
|---|---|---|
| `KeyValueStore`（`core/.../platform/KeyValueStore.kt`） | **并列，不复用为投影口** | ① 它是**按名字开的句柄**（`expect fun keyValueStore(name): KeyValueStore`），**无法枚举端上存在哪些 store** —— 而档案的 A1 判据是「对每个 store、每个 suffix，档案 keys == 端内 keys」，没有枚举就没法知道"每个"是谁；② 它**没有 `getLong` / `getFloat`**，而 §2.2 明确要求 `l`(int64) / `f`(float) 两类必须带标签往返（`last_auto_update_check_at` 是 long、`card_today_*` 是 float，Android 侧实测类型如此）⇒ 拿它当投影口会**静默丢类型**。③ 它的 `clear()` 是端内行为，档案要的是逐键合并（§6.4），粒度不同 |
| `SecureStore`（`data/.../platform/SecureStore.kt` 的 `secureKeyValueStore`） | **不参与**（只参与"排除"） | D17/A8：凭据族默认不进档案。它需要的是「能被**点名排除**」——那件事由 §1.4 的 `ArchiveRegistry` 声明式地做，不需要读写它 |
| `DataFiles`（`data/.../platform/DataFiles.kt` 的 `dataFilesDir(): File`） | **形状复用，句柄并列** | 档案的 `files/agent_sessions/**` ⇄ 端内 `agent_sessions<suffix>/` 的根**就是** `dataFilesDir()`。但 `:core` **不能**依赖 `:data`（`:data` 已 `api(project(":core"))`，反过来就是环）⇒ `:core` 自己出一个 `expect fun archiveDataRoot(): File?`，Android actual = `context.filesDir`、jvm actual = 同一个 XDG 字面量；**两者必须同值**，由 S0/S2 里那条「值相等」的断言钉住（见 §2 的 S2 判据） |

**结论一句话**：新缝（`ArchiveStoreBackend` / `ArchiveFileBackend`）与既有缝**并列**，只在其 actual 内部**复用同一批底层事实**
（Android = `getSharedPreferences` / `filesDir` / `cacheDir` / `SecurePrefs` 的**文件清单**；桌面 = `dataRoot()` 下那批
`.properties`；web = `localStorage` 的 `xjtu-toolbox.` 前缀），不新建任何端内文件格式（C1 红线）。

### 1.4 接口签名草案（Kotlin，`:core` commonMain）

```kotlin
package com.xjtu.toolbox.archive

// ── 结果：失败是值不是异常。A7 要求「坏档案 ⇒ 端内零变化」，异常路径没法断言"零变化"之后的样子 ──
sealed interface ArchiveResult<out T> {
    data class Ok<T>(val value: T) : ArchiveResult<T>
    data class Failed(val reason: ArchiveFailure, val message: String) : ArchiveResult<Nothing>
}

enum class ArchiveFailure {
    NOT_FOUND,          // 目录/zip 不存在
    UNREADABLE,         // IO / 解不开
    BAD_MANIFEST,       // 缺 manifest.json / format 不对 / JSON 截断（§6.4 末段）
    UNSUPPORTED_VERSION,// manifest.version 不认识 ⇒ 明确失败，端内零改动（§2.1 / A7）
    SUFFIX_COLLISION,   // 两个 accountId 落在同一 suffix（§4 / A9 的「检测并报错」）
    LOCKED,             // 另一个导入进行中（一次性导入的闸门，见 §6.2）
    IO,
}

// ── 值的类型标签：§2.2 的 t ∈ s/b/i/l/f/set ──
sealed interface StoreValue {
    val tag: String                      // "s" "b" "i" "l" "f" "set"
    data class S(val v: String) : StoreValue { override val tag get() = "s" }
    data class B(val v: Boolean) : StoreValue { override val tag get() = "b" }
    data class I(val v: Int) : StoreValue { override val tag get() = "i" }
    data class L(val v: Long) : StoreValue { override val tag get() = "l" }
    data class F(val v: Float) : StoreValue { override val tag get() = "f" }
    /** 去重 + 升序（§2.2 的排序硬要求）；空数组合法且 ≠ 键不存在。 */
    data class SetValue(val v: List<String>) : StoreValue { override val tag get() = "set" }
}

data class StoreSnapshot(
    val rawName: String,                 // 端内名，含后缀（§2.0 的映射规则左边）
    val keys: Map<String, StoreValue>,    // 排序交给 codec（A3）
    /** 旁挂时间戳（§5.2 第 5 条）：Android 普通 prefs 返回 null；桌面/web 从 `#written_at` 折出来。 */
    val writtenAt: Map<String, Long> = emptyMap(),
)

/* ── 缝 A：端内 store 投影（三个 actual） ── */
interface ArchiveStoreBackend {
    /** 端上**真实存在**的 store 名（原样，含后缀）。只列"用户数据"那一类，端内私有项由 registry 决定排除。 */
    fun storeNames(): List<String>
    /** 读一个 store 的全部键；不存在 ⇒ null。**类型必须带出来**（不能用 `KeyValueStore` 那种只有 Int 的接口）。 */
    fun readStore(rawName: String): StoreSnapshot?
    /**
     * 按声明类型写回。**只写差异**（调用方算好），且必须走本端原来的落点（§8.1：Android 不许新建 archive 专属 prefs 文件）。
     * `set` 必须用本端的集合类型落地（Android = `putStringSet`，§8.4；桌面 = `\u0000` join；web = 逗号串）。
     */
    fun writeStore(rawName: String, values: Map<String, StoreValue>)
    fun removeKeys(rawName: String, keys: Set<String>)
    /** 端上存在的 store 里，哪些是 registry 标了「不进档案」的（给 manifest.redacted 用）。 */
    fun excludedNames(): List<String> = emptyList()
}

/* ── 缝 B：`files/**` 那一层（wasm 返回 null） ── */
interface ArchiveFileBackend {
    /** 相对路径枚举（`agent_sessions/index.json` 这种，**目录名不带 suffix**，§2.3）。 */
    fun list(prefix: String): List<String>
    fun read(relPath: String): String?
    /** 必须先写临时再换名（§8.8：新代码没有理由继承 `KeyValueScheduleCacheStore` 的无原子写降级）。 */
    fun write(relPath: String, content: String)
    /**
     * `files/agent_sessions/` ⇄ 端内 `agent_sessions<suffix>/`、`files/schedule/<key>.json` ⇄ Android 的
     * `cacheDir/data_cache<suffix>/<key>.json`。suffix 由调用方给（来自 manifest / AccountContext）。
     */
    fun dataRoot(suffix: String): Any?      // 具体类型各端自定，不给共享层看（共享层只用 relPath 那几个动作）
}

/* Android：Store 投影需要 Context（`core/.../KeyValueStore.android.kt` 的 `initAndroidPlatform` 已把
   applicationContext 交进 :core，`internal fun androidPlatformContext()` 可直接复用）。
   ⚠️ 为了能在 `:app:testDebugUnitTest` 里用 `data/androidMain/.../SecurePrefs.kt` 的
   `InMemorySharedPreferences` 做断言（Android 单测没有真 Context），Android 的 actual **必须**是
   `class AndroidPrefsBackend(prefsOf: (String) -> SharedPreferences, namesOf: () -> List<String>)`
   这种「注入 provider」的形状，`actual fun archiveStoreBackend()` 只是它的默认装配。 */

/* ── 门面 ── */
object StoreArchive {
    fun export(host: ArchiveHost, sink: ArchiveSink, options: ExportOptions = ExportOptions()): ArchiveResult<ArchiveStats>
    fun import(host: ArchiveHost, source: ArchiveSource, options: ImportOptions = ImportOptions()): ArchiveResult<ArchiveStats>
    /** 只读预览：UI 拿它显示「这份档案里有什么、哪些没跟过来」（manifest.redacted）。 */
    fun inspect(source: ArchiveSource): ArchiveResult<ArchivePreview>
}

data class ExportOptions(
    /** D17：默认 false。置 true 时把凭据族也写出去，并在 manifest.redacted 之外**多留一条 warn 日志**（A8）。 */
    val includeCredentials: Boolean = false,
    val includeRebuildable: Boolean = true,   // 课表族缓存（§8.7 标成"可重建"）
)

data class ImportOptions(
    /** §6.4：默认合并；`replace` 是显式选项。 */
    val mode: ImportMode = ImportMode.MERGE,
    val includeCredentials: Boolean = false,
    /** 固定时钟注入（A3 的字节稳定；也是 §6.4 的 `at` 比较基准）。 */
    val now: () -> Long,
)

enum class ImportMode { MERGE, REPLACE }

/** 端侧装配：**数据袋**，三端各一个（`:app` / `:desktop` / `:web`）—— 它不是缝，是宿主签名。 */
class ArchiveHost(
    val backend: ArchiveStoreBackend,
    val files: ArchiveFileBackend?,                    // web = null
    val accounts: List<ArchiveAccount>,                // {accountId(原值), suffix, accountType, lastUsedAt}
    val createdBy: String,                             // android / desktop-linux / desktop-windows / web / serve
    val appVersion: String,
    val rowFamilies: List<ArchiveRowFamily> = emptyList(), // 三张"行数组"表，本端没有就给空
)

/** `files/{courses,media,jiaocai}/*.json` 三族。行形状 = 端内列名逐字（§2.3），绝不发明 accountId（§8.3）。 */
interface ArchiveRowFamily {
    val archivePath: String        // "files/courses/custom_courses.json"
    val store: String              // "custom_courses"，进 manifest.ttlClass
    fun exportRows(): JsonElement          // 排序在实现里做（§2.3 的三条排序口径）
    fun importRows(rows: JsonElement): Int // 返回落地行数；合并语义照 §6.4
}

/** 传输（各端实现，不在 :core） */
interface ArchiveSource {
    fun readManifest(): String?
    fun storeNames(): List<String>
    fun readStore(name: String): String?
    fun filePaths(): List<String>
    fun readFile(rel: String): String?
}
interface ArchiveSink {
    fun writeManifest(json: String)
    fun writeStore(name: String, json: String)
    fun writeFile(rel: String, content: String)
}

/** A1/A2/A9/A10 的纯函数部分（不依赖任何端） —— 放在 S1 一次做完，测试放 `:core:commonTest`。 */
object ArchiveNames {
    fun suffixFor(accountId: String?): String                    // 逐字照 AccountContext.suffixFor（A9）
    fun split(rawNames: List<String>, suffixes: List<String>): List<StoreId>  // 最长后缀优先
    fun detectCollision(accounts: List<ArchiveAccount>): List<String>          // ⇒ SUFFIX_COLLISION
}
data class StoreId(val raw: String, val base: String, val suffix: String)

/** 端内名 → 档案路径（§2.0 那条唯一映射规则；A13 的可断言形式）。 */
object ArchivePaths {
    fun storePath(base: String, suffix: String): String   // "accounts/_x/stores/agent_config.json" / "global/stores/app_settings.json"
    fun rawStoreName(base: String, suffix: String): String // 反向：base + suffix
}
```

**`ArchiveRegistry`（`:core`，数据不是代码分支）**：把规格 §2.4 / §3.2 / §5.2 第 7 条那些**分类**做成一张表：
store 名 → `{ 账号档? , 是否凭据族（不进档案）, TTL 档, 本端承载（plain KV / secure KV / Room 行 / 文件族）}`。
它是**唯一**允许在共享层里出现的"store 知识"，S1 建表、S2 用表；新增 store 时只改这张表（并有守卫测试：
同名不得同时是 plain 与 secure，S0 判据里也用到）。

---

## 2. 工单拆分（S0…S8）

**通用规则（每个工单都适用）**

- 每个工单**独立提交、独立跑门禁**：自己的新测试只进自己的模块，不依赖别的工单的未合并代码。
- 门禁 = 该工单涉及的模块的既有任务（`./gradlew :core:jvmTest` 等），**数字只增不减**。
- **不跑 gradle 的验收写进文档**（例如"手工探针"）必须显式标成「本批跑不了」（见 §6.3）。
- 「需要动 `:app`？」列 = 是否触碰 `app/` 下任何文件（含 manifest / res / build.gradle.kts）。

| # | 目标 | 涉及文件 / 模块（**全部我亲自 ls/grep 过**） | 验收判据 | 依赖 | 规模 | 动 `:app` |
|---|---|---|---|---|---|---|
| **S0** | **桌面 0 号前置条件**：`keyValueStore(name)` 的 jvm actual 从「进程内内存」改成「真落盘」，并让三份门禁的既有断言逐字不变（判据 A12 类口径） | 见 §4（单独展开，含精确路径） | A12（429 不减）、`:core:jvmTest` ≥479、`:desktop:test` ≥100；新增：同根文件存在 + 第二实例读回 + `dataFilesDir() == archiveDataRoot()` | 无（**最先**） | 中 | 否 |
| **S1** | 档案**模型 + codec + 纯函数**（零端接线）：`manifest`/`stores/<store>.json` 的读写、稳定排序、`#written_at` 归一化成 `at`、suffix 拆分与碰撞检测、registry 建表 | 新建 `core/src/commonMain/kotlin/com/xjtu/toolbox/archive/{ArchiveModel.kt,ArchiveCodec.kt,ArchiveNames.kt,ArchivePaths.kt,ArchiveRegistry.kt,StoreArchive.kt,ArchiveSource.kt}`；测试 `core/src/commonTest/kotlin/com/xjtu/toolbox/archive/{ArchiveCodecTest.kt,ArchiveNamesTest.kt,ArchiveRegistryTest.kt,ArchiveFixture.kt}`（夹具写成 **Kotlin 常量**，理由：KMP 的 test resources 是逐目标的，而 `:testkit` 是 `kotlin.jvm`（`testkit/build.gradle.kts` 的 KDoc）wasm 消费不了） | **A3**（注入固定时钟两次导出字节相同）· **A8**（对 `export()` 产物断言禁止路径）· **A9**（`suffixFor` 五档 + 碰撞必须报错）· **A10**（`keys` 里永不出现 `*#written_at`，时间在 `at`）· 夹具往返 | 无 | 中 | 否 |
| **S2** | **三端 store 投影**：`archiveStoreBackend()` 的三个 actual + `archiveDataRoot()` / cache 根 + registry 驱动的排除 | 新建 `core/src/androidMain/kotlin/com/xjtu/toolbox/archive/ArchiveStoreBackend.android.kt`、`core/src/jvmMain/.../ArchiveStoreBackend.jvm.kt`、`core/src/wasmJsMain/.../ArchiveStoreBackend.wasmJs.kt`；改动/引用 `core/src/{androidMain,jvmMain,wasmJsMain}/kotlin/com/xjtu/toolbox/platform/{KeyValueStore.*.kt}`（只做实际事实的复用，不改接口）；`:app` 侧新增 `app/src/test/java/com/xjtu/toolbox/archive/AndroidPrefsBackendTest.kt`（用 `data/src/androidMain/.../SecurePrefs.kt:112` 的 `InMemorySharedPreferences`）；`:desktop` 侧新增 `desktop/src/test/kotlin/com/xjtu/toolbox/desktop/ArchiveStoreBackendJvmTest.kt` | **A1**（jvm actual：`archive.keys == 端内 keys`；Android actual：同一断言跑在 `InMemorySharedPreferences` 上）· **A2**（值逐字，含 `set` 空数组 ≠ 键不存在）· **A13 的前半**（`archiveDataRoot()` 与 `dataFilesDir()` 同值；Android 侧 `== context.filesDir`） | S1 | 大 | **是**（仅测试 + 无生产改动） |
| **S3** | **`files/**` 那一层**：`archiveFileBackend()` 的两个 actual（wasm 返回 null）+ 课表族/会话/空教室缓存的读写 + `archiveCacheRoot()` | 新建 `core/src/{androidMain,jvmMain,wasmJsMain}/.../ArchiveFileBackend.*.kt`；读侧参照 `data/src/commonMain/.../agent/AgentSessionStore.kt`、`core/.../schedule/{ScheduleCache.kt,KeyValueScheduleCacheStore.kt}`、`app/.../data/DataCache.kt` | **A10**（导回桌面/web 后 `KeyValueScheduleCacheStore.ageMsOfJson` 算出同一个 age）· **A13 的后半**（`cp -r` + import + 读值三连，走 `dataRootOverride`）· 新代码必须原子写（§8.8） | S1, S2 | 大 | **是**（仅测试） |
| **S4** | **`:app` 接线点**：三张 Room 表的行适配器 + 一次性导入触发 + 导出动作 + SAF 通道 + 备份规则排除 | 新增 `app/src/main/java/com/xjtu/toolbox/archive/{AppArchiveHost.kt,RoomArchiveFamilies.kt,AppArchiveUx.kt}`；**最小 diff** 改动 `app/src/main/java/com/xjtu/toolbox/XjtuApp.kt`（`attachBaseContext` 里一行 + 一处启动检查）、`app/src/main/java/com/xjtu/toolbox/settings/AppSettings.kt`（`dataPrivacySection` 里**追加**两行动作）、`app/src/main/res/xml/data_extraction_rules.xml`（两个块各加一行 `<exclude domain="file" path="archive*"/>`）；读取（只读）`app/src/main/java/com/xjtu/toolbox/data/AppDatabase.kt` 的三个 DAO、`app/.../media/{DownloadTaskEntity.kt,AppDownloadTaskStore.kt}`、`app/.../jiaocai1/Jiaocai1Shelf.kt`、`app/.../schedule/RoomCustomCourseStore.kt`（类名待定，我用 `grep` 只核到 `CustomCourseDao` / `CustomCourseEntity` 在 `:app`，具体实现类名见"未核对"） | **A12 ≥429**（现有 429 一条不减、断言不改）· **A6**（档案没有、端内有 ⇒ 值不变，用 `InMemorySharedPreferences` + 临时目录驱动）· **A7**（坏档案 ⇒ 端内键集合与值零变化）· **A4**（Android 侧 `SettingsStore().darkMode` 等原读法逐字相同） | S1, S2, S3 | 大 | **是**（这是唯一允许动生产代码的工单，且只允许 §3 列的那几处） |
| **S5** | **桌面宿主**：数据根扫描 + 一次性导入 + 导出到目录/zip + 设置页入口 | 新增 `desktop/src/main/kotlin/com/xjtu/toolbox/desktop/archive/{DesktopArchiveHost.kt,DesktopArchiveIo.kt}`；改动（追加）`desktop/src/main/kotlin/com/xjtu/toolbox/desktop/DesktopSettings.kt`（`dataPrivacySection` 由 `null` 变成一个真组）、`desktop/src/main/kotlin/com/xjtu/toolbox/desktop/Main.kt`（启动检查）；测试 `desktop/src/test/kotlin/com/xjtu/toolbox/desktop/DesktopArchiveJvmTest.kt` | **A13**（`accounts/<suffix>/stores/schedule_cache.json` ⇄ `schedule_cache<suffix>.properties`，纯字符串拼接，`dataRootOverride` 隔离）· **A11**（断言对象只能是**解析后的键值**，不比字节）· `:desktop:test` ≥100 | S1–S3 | 中 | 否 |
| **S6** | **web 宿主**：`localStorage` 投影消费 + 下载/上传（浏览器无 FS） | 新增 `web/src/wasmJsMain/kotlin/com/xjtu/toolbox/web/{WebArchiveHost.kt,WebArchiveIo.kt}`；改动（追加）`web/src/wasmJsMain/kotlin/com/xjtu/toolbox/web/WebSettingsHost.kt`（`dataPrivacySection` 由 `null` 变真组）、`ToolboxWebApp.kt`（入口动作）；JS 侧照 `web/.../Data.kt` 已有的 `@JsFun` 形态（Blob / `URL.createObjectURL` / `<a download>` / `<input type=file>`） | **本批只能编译 + 手工**（D14 未做 ⇒ 无 wasmJsTest，见 §6.3）；`manifest.noAccount=true` + 只写 `global/`（§9 未决 #4） | S1–S3 | 中 | 否 |
| **S7a** | **D9 的 web/serve 侧多账号**：账号表与账号页（serve 持凭据，浏览器切账号 = 让 serve 换一套凭据） | `:server`：新增 accounts 相关路由（同目录已有 `SessionRoutes.kt` / `ServeSession.kt` / `AccessTokenGate.kt` 的先例）；`:web`：`web/src/wasmJsMain/kotlin/com/xjtu/toolbox/web/WebSupport.kt` 的 `openableRouteIds` 加 `AppRoute.Accounts` + `ToolboxWebApp.kt` 的 `AppPage` 加分支 + `.kt` 侧账号屏宿主；`core/.../account/{AccountManagerScreen.kt,AccountsController.kt}` **只读复用** | 路由差集 7 → 6（§16.2 第 1 条的路由全等口径）；`:server:test` ≥82；账号页三端同一套层级（同一份 `AccountManagerScreen`） | S1–S3 | 大 | 否 |
| **S7b** | **桌面/web 的 suffix 补齐清单**（审计 #09 的端侧那半 + §3.5④） | 逐项核过：`desktop/src/main/kotlin/com/xjtu/toolbox/desktop/media/DesktopDownloadTaskStore.kt:48`（`secureKeyValueStore(FILE_NAME)`，无后缀）· `desktop/.../jiaocai1/DesktopJiaocai1Storage.kt:46`（同上）· `desktop/.../score/DesktopScoreReportCache.kt:41`（键里带学号、store 不带后缀）· `desktop/.../DesktopScheduleHost.kt:84`（**已有**后缀）· web 的 `WebScheduleHost.kt` 的 `cache(CUSTOM_COURSE_STORE)`（无后缀，§9 未决 #8 要等 S7a 有账号才谈） | 每一项的端内名变化都要**一次性读入旧名**（旧形状只读）；`desktop/src/test/.../DesktopDownloadsJvmTest.kt:55` 的 `wipeSecureStore("download_tasks")` 之类**测试接线**要跟着改（**只改接线，不改断言**），`:desktop:test` ≥100 | S7a（web 那半）· S1（registry 分类） | 中 | 否 |
| **S8** | **D14 `:web` 测试源集**（本批**最后**做，理由见 §7.2） | `web/build.gradle.kts`（加 `wasmJsTest` 源集与依赖；`core/build.gradle.kts:45-52` 的注释解释了为什么当初不声明 wasm 测试环境：`skiko.mjs` 在 nodejs 下 `ERR_MODULE_NOT_FOUND`，`browser()` 下没这个问题）；新增 `web/src/wasmJsTest/kotlin/.../{ArchiveInteropTest.kt,WebRouteCoverageTest.kt}` | **A5 的三端形态**（同一份夹具三端各 import 一次）· D14 的另两条（SSE 帧解析纯函数 / 白名单与页面说明同源） | S1–S3（夹具已存在）、S6 | 中 | 否 |

**依赖图**：`S0 → S1 → {S2, S3} → {S4, S5, S6} → S7a → S7b → S8`。
S0 与 S1 之间**没有**代码依赖（S0 改的是键值缝的 actual，S1 写全新文件），但 S0 必须**先**做
（§0 第 3 条把它定成第 0 号前置条件：不补它，桌面「档案拷过去能用」是假的，S5 的验收会在假前提上通过）。

---

## 3. `:app` 红线的具体做法（逐条对着 §8.1–8.10 说清"每个工单在 `:app` 侧只允许做什么"）

**总则**：整个批②里，`:app` 的**生产代码**只允许在 **S4** 里动，且只动四类东西 ——
① `XjtuApp.attachBaseContext`/`onCreate` 里各一行启动检查；② 设置页「数据与隐私」组里**追加**的导入/导出动作；
③ 备份/迁移规则里**追加**的排除行；④ 新增文件（新包 `app/.../archive/`）。
**现有的每一行**（含 429 条断言、strings、布局、文案）逐字不变（A12 是这条的机器判据）。

| §8 条目 | 每个工单在 `:app` 侧的红线 |
|---|---|
| **8.1** store 名 / 键名 / 值类型一个字不能改 | S2 的 Android actual **只能** `getSharedPreferences(name, MODE_PRIVATE)` 读写**已存在的**名字；**不许**新建 archive 专属 prefs（如 `archive_import_tmp`）。导入需要"先落地再原子换名"（§6.1 第 3 条）时，落地用的临时区**必须在文件系统**（`filesDir/archive_staging/`，S4）或**本端内存**，不能是新的 prefs 文件 |
| **8.2** 「旧形状只读」在 Android 上不可实现 | S4 的导入**不改变**任何既有写路径的落点：写回一律 `prefs.edit()`，与今天同一批键同一批文件。口径按未决 #2 的替代表述执行（"键名逐字 + 档案是端内 store 的一个投影"）—— **这条要用户拍板**（§7 第 2 项） |
| **8.3** Room 版本 v7 与三张表结构不能动 | S4 的 Room 适配器**只读 DAO**（`customCourseDao()` / `downloadTaskDao()` / `jiaocai1ShelfDao()` 的既有查询）→ 写 JSON 行数组；导入也走既有 DAO 的既有 insert/update/delete。**不许**加表、加列、加迁移、`MIGRATION_7_8`（那在 HEAD 根本不存在，见 `docs/audit-verify-1-correctness.md` #09 那一节） |
| **8.4** `getStringSet` 的 `ClassCastException` 语义 | S2 的 Android actual 对 `t="set"` **必须** `putStringSet`（`LinkedHashSet`），禁止为了省事写成逗号串或 JSON 串（那会让 `VenueFavorites` / `CampusVenueApi` 那一族在 Android 上 `ClassCastException`） |
| **8.5** `app_settings` 的双写者 | S1 的 registry 把 `app_settings` 标成**单文件全键**（`SettingsStore` 那 10 个 + `:app` `CredentialStore` 那 12 个）；S4 的导入**绝不**把它拆成两个文件（拆了就 `AppearanceSettings` 的 `OnSharedPreferenceChangeListener` 失效） |
| **8.6** 同一键的跨端类型不一致（`rsa_key_time`） | S2 / S4：导入端**按声明类型落地**，类型不匹配时按目标端读法容错（`toLongOrNull` / `toBooleanStrictOrNull` / `getString`），**绝不抛**。注：`rsa_key_time` 所在 store 属凭据族（A8 默认不进档案），所以这条只在显式开关下才会被触发 |
| **8.7** `DataCache.clearIfPackageChanged` 会删掉整个 `data_cache*` | S3 / S4：课表族在档案里标**可重建**，且**不得**作为"档案完整"的判据；S4 的启动检查顺序必须与换包清理分开（换包清理在 `attachBaseContext` 最前，导入检查在其后，**不得**把导入结果写进 `data_cache*` 里当"已导入"标记 —— 那会被换包清掉并导致重复导入） |
| **8.8** `KeyValueScheduleCacheStore` 没有原子写 | S3 的 `files/schedule/**` 落盘一律"先 `.tmp` 再 rename"（新代码可以做原子，Android 的 `DataCache` 旧实现一个字不改） |
| **8.9** 429 条一条不许减 | S2 / S4 的新代码只进 `:core`/`:app` 的**新文件**与新的测试文件；`:app` 侧只允许"一次 `archiveExport/import` 调用"级的接线。**禁止**改动任何现有测试文件的断言（可以改 KDoc/注释，S0 里就有这类改动） |
| **8.10** DPAPI 信封跨机不可解是设计如此 | S1 的 registry 把 `xjtu_credentials` / `cookies_*` / `sites_*` / `agent_config_secure*` / `github_community` / `serve` 标成凭据族 ⇒ 默认不进档案（D17）；S4 把它们**点名列进 `manifest.redacted[]`**，UI 上只说"哪些没跟过来"，不去试解信封 |

**另外三处不在 §8 里、但同样属于 `:app` 红线的**（我读代码时发现的）：

1. `app/src/main/res/xml/data_extraction_rules.xml`：`cloud-backup` 与 `device-transfer` 两个块目前排除了
   `xjtu_credentials*` / `xjtu_accounts*` / `cookies_*` / `agent_config*` / `campus_card*` / `agent_sessions*`，
   **没有** `archive*`。档案里含学号（未决 #6），所以 S4 必须**追加**两行 `<exclude domain="file" path="archive*"/>`
   —— 这是纯追加，且是隐私口径的必要条件（不是可选的美化）。
2. `app/src/main/res/xml/file_provider_paths.xml`：现在只有 `cache-path exports/` / `cache-path apk_cache/` /
   `external-files-path Download/ClassReplay/` / `files-path Download/ClassReplay/`。**两条通道二选一**：
   (a) **SAF**（`ACTION_OPEN_DOCUMENT_TREE` / `ACTION_CREATE_DOCUMENT`）⇒ **不需要**改这个文件、不需要新权限，
   但要一次 Activity result 的接线（`:app` 侧，属 §8.9 允许的"接线点"）；(b) 复用现有 FileProvider ⇒ 要加一行
   `<files-path name="archive" path="archive/"/>`，并且 `FileProvider.getUriForFile` 只能给**文件**不能给目录。
   **建议 (a) SAF**（规格未决 #3 的两个方向都是「一次权限/体验确认」，SAF 零权限、零 manifest 改动）。
3. `app/src/main/java/com/xjtu/toolbox/data/CredentialStore.kt:239` 有 `KEY_NICKNAME = "cached_nickname"`，
   而规格 §3.2 第 2 行（`xjtu_credentials`）的键清单里**没有**它 —— 这只影响"凭据族不进档案"的那一族（A8 之外），
   **不阻塞任何判据**，但按 §16.2 第 2 条「三处同改」的纪律，`docs/storage-layout.md` §3.2 那一行该补一个键名。

---

## 4. S0：桌面的 0 号前置条件（单独展开）

### 4.1 现状（我读到的原文）

`core/src/jvmMain/kotlin/com/xjtu/toolbox/platform/KeyValueStore.jvm.kt` 的 actual 是
`private class MemoryKeyValueStore : KeyValueStore`（一个 `mutableMapOf<String, Any>()`），工厂是
`private val stores = mutableMapOf<String, KeyValueStore>()` + `stores.getOrPut(name) { MemoryKeyValueStore() }`
—— 文件头的 KDoc 自己写着「刻意不落盘 —— 免得测试之间互相污染」。
`data/src/jvmMain/kotlin/com/xjtu/toolbox/platform/SecureStore.jvm.kt` 则是**真落盘**的
`FileKeyValueStore`（`java.util.Properties` + `Files.setPosixFilePermissions` 0600 + 同步写）。

后果（规格 §0 第 3 条逐条列过）：桌面上 `library_favorites` / `venue_favorites` / `inbox*` / `feature_hints` /
`empty_room` / `course_colors*` / `schedule_diff*` / `bulletin_store` / `feedback` / `games` / `haptics_prefs` /
`jiaocai1_reader_prefs` / `schedule_source*` / `schedule_changes*` / `attendance_records_*` / `score_report`
**全部只活在内存里**。而 `desktop/src/main/kotlin/com/xjtu/toolbox/desktop/DesktopSettings.kt` 的 KDoc
（第 19 行附近）已经点明了这件事：「为什么不用 `:core` 的 `keyValueStore(name)`：jvm 那份是**进程内内存**实现……
在这一端先按 store 逐个接上落盘实现」—— 逐个接就是本工单要终结的状态。

### 4.2 改哪里（精确路径）

1. **`core/src/jvmMain/kotlin/com/xjtu/toolbox/platform/KeyValueStore.jvm.kt`**（重写）
   - 新增 `class JvmFileKeyValueStore(private val file: File) : KeyValueStore`，实现逐字照
     `data/src/jvmMain/.../SecureStore.jvm.kt` 里那份 `FileKeyValueStore`（同一套 `Properties.store/load`、
     同一套 `\u0000` 集合 join、同一套 desync 语义、同一套 0600 权限）；
   - `actual fun keyValueStore(name: String)` = 全局 memo（`ConcurrentHashMap`）+ 文件
     `<root>/<name>.properties`；
   - 新增 `var keyValueStoreRootOverride: File? = null`（测试与多实例用，同 `dataRootOverride` 的形状）
     与 `internal fun keyValueStoreRoot(): File`（默认字面量**与 `SecureStore.jvm.kt` 的
     `File(System.getProperty("user.home"), ".local/share/xjtu-toolbox")` 逐字相同**）；
   - 新增 `internal fun newJvmFileKeyValueStore(name: String): KeyValueStore`（**不查 memo**）——
     S0 的"第二实例从磁盘读回"那条断言需要它。
2. **`core/src/jvmMain/kotlin/com/xjtu/toolbox/platform/KeyValueStore.jvm.kt` 的根与 `:data` 的根必须同值**：
   `:core` 不能依赖 `:data`（`:data` 已 `api(project(":core"))`），所以字面量会重复一次。**用一条断言钉住**，
   不要靠注释：S2 里那条 `archiveDataRoot() == dataFilesDir()` 的测试同时覆盖这一点（`:app` 与 `:desktop`
   都能同时看到两个符号）。
3. **`data/src/jvmMain/kotlin/com/xjtu/toolbox/platform/SecureStore.jvm.kt`**（可选，推荐）：
   把 `private class FileKeyValueStore` 换成 `:core` 的 `JvmFileKeyValueStore`（**纯等价替换**，
   `dataRootOverride` 保持 `var` 不动、`wipeSecureStore` 保持"文件不存在就什么都不做"的语义）。
   好处 = 桌面只有一份文件实现（A13 的"纯字符串拼接"映射从此有一个实现可指）；
   风险 = `:data:jvmTest` 259 条里 `JvmCredentialStoreJvmTest` / `ProtectedKeyValueStoreJvmTest` 直接钉了
   文件字节形状与 0600 ⇒ 替换必须逐字等价，**任一条变红就回退这一步**（它是可选的）。
4. **测试隔离（关键，决定"既有 100/479 条期望不变"）**：
   - `core/build.gradle.kts` 给 `jvmTest` 任务加一段（文件里现在**没有**任何 test 配置块，新增即可）：
     ```kotlin
     tasks.named<Test>("jvmTest") {
         val root = layout.buildDirectory.dir("testStoreRoot")
         doFirst { root.get().asFile.deleteRecursively() }   // 每次跑都干净 ⇒ 跨运行不残留
         systemProperty("xjtu.toolbox.keyValueRoot", root.get().asFile.absolutePath)
     }
     ```
   - `desktop/build.gradle.kts`：给 `test` 任务加同一段（根名换成 `testStoreRoot`）。
     `desktop/build.gradle.kts` 里也没有 test 配置块（我 grep 过：只有 `kotlin { }` 里的
     `testImplementation` 与三个 `tasks.register`），新增一块即可。
   - `keyValueStoreRoot()` 的解析顺序：`keyValueStoreRootOverride ?: System.getProperty("xjtu.toolbox.keyValueRoot") ?: 默认字面量`。
     **这样不需要改任何一个现有测试文件**（`desktop/src/test` 里已有 6 个文件设 `dataRootOverride`，
     其余不设的靠系统属性兜住；这条正是"不改现有断言"的关键）。

### 4.3 怎么保证「现在没落盘的 store 改成落盘」不改变现有期望

三条论证 + 一条守卫：

1. **语义等价**：新实现与 `MemoryKeyValueStore` 在**同进程内**的行为逐条相同（同一份 memo、同一个
   `get/put/remove/contains/clear` 语义、`getStringSet` 仍返回副本、`putString(null)` 仍等于删键）。
   唯一变化是「进程退出后还在」。
2. **跨运行不残留**：Gradle 侧 `doFirst { deleteRecursively() }` 保证每次跑测试都是空根 ⇒
   **跨运行**的状态与今天（每次跑都是新 JVM、新内存）逐字相同。
3. **进程内顺序不变**：今天同一 JVM 内同名 store 已经是"全局一份"（`MemoryKeyValueStore` 的 memo），
   S0 不改这一点 ⇒ 用例之间的可见性**一模一样**。我逐个核过会受影响的测试文件，它们本来就显式清理：
   `core/src/jvmTest/.../bulletin/BulletinStoreJvmTest.kt:34`（`clear()`）、
   `core/src/commonTest/.../settings/SettingsStoreTest.kt:23`（`clear()`）、
   `core/src/commonTest/.../venue/VenueMappingTest.kt:257`（`clear()`）、
   `core/src/jvmTest/.../library/LibraryFavoritesJvmTest.kt`（`@AfterTest clear()`）、
   `core/src/jvmTest/.../feedback/FeedbackStoreJvmTest.kt`（`@BeforeTest clear()`）、
   `desktop/src/test/.../DesktopAuthScoreReportJvmTest.kt:101,116`（`keyValueStore("score_report_unit_test").apply { clear() }`）、
   `desktop/src/test/.../DesktopDownloadsJvmTest.kt`（`withFakeCampus` 里 `dataRootOverride` + `wipeSecureStore`）。
   **没有**任何测试断言"不落盘"（我 grep 过 `不落盘|内存`，命中的都是 KDoc 与**别的**语义，例如
   `BulletinStoreJvmTest:79` 的「`snooze` 只在进程内」是内存态而非 store）。
4. **守卫测试**（S0 自带，进 `:core:jvmTest`）：写 → 用 `newJvmFileKeyValueStore(name)` 建第二实例 → 读回相同；
   且 `<root>/<name>.properties` 文件存在、权限是 0600（Linux）。再加一条：`ArchiveRegistry` 里
   **没有任何 store 名同时被 plain 与 secure 两类使用**（否则两边写同一个 `.properties` 文件会互相覆盖；
   我核过今天的实际用法：`payment_code` 在桌面走 `secureKeyValueStore`（`ToolboxDesktopApp.kt:759`）、
   `score_report` 走 plain（`DesktopScoreReportCache.kt:41`）、`download_tasks`/`jiaocai1_shelf` 走 secure
   （`DesktopDownloadTaskStore.kt:48` / `DesktopJiaocai1Storage.kt:46`）⇒ 今天无冲突）。

**要顺手改的注释**（不是断言）：`core/src/jvmTest/.../library/LibraryFavoritesJvmTest.kt` 的
「jvm 的 `KeyValueStore` 是进程内单例（`MemoryKeyValueStore`）」、同型的两处（`FeedbackStoreJvmTest` /
`SettingsStoreTest` 的文件头）、`core/src/commonTest/.../schedule/CourseColorsHexTest.kt:22,62`、
`desktop/src/test/.../RenderScreens.kt:659`（「桌面那份键值缝是进程内内存实现」）。
**只改这些句子的表述，不改任何断言**（§8.9）。

### 4.4 迁移期间老数据怎么办

**答案是：本机这一批 store 没有老数据。** 它们在桌面上从来**没有落过盘**（内存实现，进程退出即丢），
所以 S0：
- **不需要**任何一次性读入旧形状的代码；
- **不会**让任何用户可见的东西"重置"（本来就每次都是空的）；
- 唯一需要留意的历史面是**桌面数据根里已有的 6 个 `.properties`**（`cookies_normal_*` / `github_community` /
  `serve` / `sites_normal_*` / `xjtu_accounts` / `xjtu_credentials`，规格 §1.2 实测清单）—— 它们全部是
  **secure** 那一路写的，S0 不碰它们的读法，只保证 plain 那一路用**同一套文件形状 + 同一套 0600 权限**写进同一根，
  于是 A13 的"纯字符串拼接"映射对两类 store 都成立。
- 反向风险（会让老用户看到变化的唯一一条）：某个 store 名**同时**被 plain 与 secure 使用 ⇒ 由 §4.3 的守卫测试拦住。

---

## 5. 多账号（D9）的落法

### 5.1 本次重构范围内具体改什么

| 端 | 今天 | 批②要做（D9） |
|---|---|---|
| **Android** | 已有：`AccountStore`（密文 `xjtu_accounts`，`data/src/commonMain/.../account/AccountStore.kt`）+ `AccountContext`（`core/.../account/AccountContext.kt:56-59` 的 `suffixFor`）+ `AccountManager`（`app/.../account/AccountManager.kt` 的 `switchToLocked` / `wipeAccountFiles`）+ `AccountManagerScreen`（`core/.../account/AccountManagerScreen.kt`） | **零改动**（§16.5 D15）。仅作为**基准**：`suffixFor` 的五个逐字结论（§4 的表）是三端唯一的后缀规则真源 |
| **桌面** | 已有：`AccountStore`（同一份 `:data` 实现，落在 0600 的 `xjtu_accounts.properties`）+ `DesktopAccounts`（`desktop/.../DesktopAccounts.kt`，`AccountsController` 的实现）+ `AccountsController` 缝在 `:core` | 补齐 `DesktopAccounts` 的**清场范围**（它自己 KDoc 里那条「桌面唯一按账号分的缓存只有成绩报表」要跟着 S7b 变） |
| **web** | **没有账号**（`web/.../WebScheduleHost.kt:38-40` 的 KDoc 明写「这一端没有账号（凭据在 campus-api / serve 进程里）」，`WebSupport.openableRouteIds` 的差集里列着 `AppRoute.Accounts`） | **S7a**：账号归属 serve 进程（D9 原话「web = serve 进程（DPAPI 保护），浏览器上切换账号 = 让 serve 换一套凭据」）⇒ ① `:server` 出一组账号端点（`SessionRoutes.kt` / `ServeSession.kt` 旁边）；② `:web` 把 `AppRoute.Accounts` 加进 `openableRouteIds` + `AppPage` 分支，屏**直接复用 `core/.../account/AccountManagerScreen.kt`**（丙口径的"同一套层级"= 同一份屏，不是重画一遍）；③ `AccountContext.activeAccountId` 在 serve 侧由登录态写（`server/.../ServeSession.kt` 已经在置 `null`/值，见 `docs/agent-web-spec.md` 缺口 B），于是 web 的 `safeSuffix()` 自动接上 `"default"` |
| **`download_tasks` 的 `accountId`**（§16.6 P2 点名的第二例） | `:app` 的 Room 表无此列（`app/.../media/DownloadTaskEntity.kt`），桌面那份键值也没按账号分（`DesktopDownloadTaskStore.kt:48`） | **S7b**：桌面上给 store 名加后缀（它的 KDoc 自己写着"没有老数据，桌面端第一次有这个队列"）；**档案里仍按端内列名逐字写行**（不加 `accountId` 列，§8.3） |

### 5.2 明确不动的部分

- **`:app` 侧既有行为一行不改**（§16.5 D15）：`AccountManager` / `wipeAccountFiles` / `ACCOUNT_PREFS`（14 项）
  / `AccountMigration` / `SecurePrefs` 的迁移语义 / `data_extraction_rules.xml` 的既有排除项，全部不碰
  （只有 S4 那一条**追加**的 `archive*` 排除）。
- **`AccountContext.suffixFor` 的规则一个字不改**（它是三端唯一的后缀真源；A9 就是钉它）。
- **凭据的落地方式**三端各用各的（Android keystore / 桌面 DPAPI / serve 侧 DPAPI，D9 原文），
  本次**不统一**它们（统一的是"档案里不带凭据"，D17）。

### 5.3 审计 #02 / #07 / #09 的「三端不一致侧」—— 挂到具体工单上（含我的核对结论）

| 审计条 | 「三端不一致侧」到底是什么（我核过的结论） | 挂到哪个工单 |
|---|---|---|
| **#02** 切账号不重置 `AgentViewModel`（A 的聊天/画像写进 B 的目录） | **真有侧可修，但它直撞 C1**：`AgentSessionStore`（`:data`）每次 IO 现取 `safeSuffix()` ⇒ **磁盘侧三端一致**；不一致在**内存态**：`core/.../agent/AgentViewModel.kt:201-208` 的 `bind()` 只跑一次（`:core` 共享代码，三端一起变）。端侧做法：在 `AgentHost` 上加一个**可选槽位**（`fun onAccountChanged() {}` 默认空实现）⇒ `:app` 不实现（Android 行为**逐字不变**），桌面/serve 实现（重载会话列表 + 清内存历史）。**Android 侧要不要顺手修 #02 需要拍板**（D15 vs D7-口径 的直接冲突，见 §7 第 5 项） | **S7a 的端侧隔离部分** + §7 拍板项 |
| **#07** `payment_code` 未按账号隔离 | **没有「三端不一致侧」可修**：三端**今天都是**无后缀（`docs/audit-verify-1-correctness.md` #07 那节自己写着「三端同一存储名」；我复核了 `app/.../pay/AppPaymentCode.kt:48`、`desktop/.../ToolboxDesktopApp.kt:759`、`web/.../ToolboxWebApp.kt:824`）。**加后缀 = 三端同改 + 老用户重勾一次**，那正是规格未决 #2 的同一个取舍 ⇒ 整条属 (a) 类（Android 既有行为）。本次唯一可做且必须做的：档案 registry 里把 `payment_code` 标成 **`global/`（三端一致地 global）** ⇒ 这是一条**数据模型**声明，不改任何端的行为 | **S1（registry 分类）** + §7 拍板项（要不要加后缀） |
| **#09** `download_tasks` 无 `accountId` | 不一致在「**本机公共文件 vs 任务列表归谁**」这条产品口径（`docs/audit-verify-1-correctness.md` #09 那节的结论：「先定产品口径再动手」）。档案侧可做的：行形状**逐字照端内列名**（不加 `accountId`，§8.3）、registry 标 `global` + `可重建`；端侧可做的：**桌面**给 store 加后缀（新代码、无老数据） | **S1（registry）+ S7b（桌面后缀）** + §7 拍板项 |

---

## 6. 迁移与验收

### 6.1 导入/导出的入口落在每端哪里

| 端 | 导出 | 导入 | 一次性导入的触发点 |
|---|---|---|---|
| **Android** | 设置页「数据与隐私」组里**追加**一行动作 → 生成 zip 到 `filesDir/archive/` → 交给**系统**：`ACTION_CREATE_DOCUMENT`（SAF，零权限）或分享（`Intent.ACTION_SEND` + FileProvider；后者要加一条 `<files-path name="archive" path="archive/"/>`，见 §3） | 同上那一行的"导入"分支：`ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE` 选一次 → 复制到 `filesDir/archive_staging/` → 调 `StoreArchive.import` | **`XjtuApp.attachBaseContext`**（与 `DataCache.clearIfPackageChanged` **同一位置同一理由**，规格 §6.2 已定）：检查 `filesDir/archive/` 里有没有待导入的档案 ⇒ 有就跑一次（幂等，§6.1 第 1 条），跑完**不删档案**（用户可能还要拷给别人）而是写一个"已导入(档案 id + format/version + 内容摘要)"标记 —— ⚠️ 这个标记**不能**放 `data_cache*`（§8.7 换包会清）⇒ 放 `filesDir/archive/.imported.json` |
| **桌面** | 设置页「数据与隐私」（`DesktopSettingsHost.dataPrivacySection` 现在返回 `null` ⇒ S5 把它变成真组）→ 导出到 `<数据根>/archive/` 或用户选的目录 | 同一组里的"导入"：从 `<数据根>/archive/` 或文件选择器选 zip | **启动时扫数据根**（规格 §6.2）：`dataRoot()` 下有档案目录/zip ⇒ 跑一次；**必须走 `dataRootOverride`**（`desktop/src/test` 里 6 个文件已经在用，S5 的测试同样用） |
| **web** | `WebSettingsHost.dataPrivacySection`（现在 `null`）→ 在内存里组装 zip → `Blob` + `URL.createObjectURL` + `<a download>`（照 `web/.../Data.kt` 的 `@JsFun` 形态） | 同一组：`<input type=file>` 读 zip → 内存里解 → `StoreArchive.import` | **页面加载时**检查"有没有待导入的档案"；D9 之前 = 人工（上传），D9 之后 = serve 侧（规格未决 #4） |

### 6.2 一次性导入的闸门与顺序（三端同一条）

1. 有档案才跑（`NOT_FOUND` 之外**不报错**，静默跳过）；
2. 闸门是"已导入标记 ∈ 端内私有项"（A8 的排除清单之外，不参与档案往返）；
3. 先在 staging 落地 + 自校验（`manifest.version` 认识、JSON 能解、suffix 无碰撞）→ 再逐 store 合并（§6.4）
   → 任一步失败 ⇒ **端内零变化**（A7）；
4. `:app` 的触发点必须在 `DataCache.clearIfPackageChanged` **之后**（§8.7）、`SecurePrefs.prewarm` **之前后皆可**
   （不读凭据族）。

### 6.3 A1–A13：本批能跑 / 本批跑不了

| 判据 | 本批能跑吗 | 跑在哪（哪个工单落它） |
|---|---|---|
| **A1** 键集合相等 | △ 部分 | ✅ jvm actual：`:core:jvmTest`（S2）· ✅ Android actual：`:app:testDebugUnitTest` 用 `InMemorySharedPreferences`（S2/S4）· ❌ **web actual**（D14 未做） |
| **A2** 值逐字相等 | △ 部分 | 同 A1 · ✅ `set` 空数组 ≠ 键不存在（S1 的 codec + S2 的两个 actual） |
| **A3** 导出字节稳定 | ✅ | S1，`:core:jvmTest`（注入固定时钟） |
| **A4** 往返可读 | △ 部分 | ✅ 桌面：`:desktop:test`（S5，用 `SettingsStore` / `ScheduleCache.readCurrentTerm` / `LibraryFavorites.all()` 的原读法）· ✅ Android 单测（S4，用 `InMemorySharedPreferences`）· ❌ web（D14） |
| **A5** 跨端互换（同一夹具三端各 import 一次） | ❌ **本批跑不了三端形态** | 夹具**本批就写**（S1，`core/src/commonTest/.../ArchiveFixture.kt` 的 Kotlin 常量，不能放 `:testkit` —— 它是 `kotlin.jvm`）；**三端各跑一次**要等 **S8**（`:web` 测试源集）与 Android 真 Context（Robolectric/instrumented，本批不做） |
| **A6** 不丢键 + 不覆盖本机 | ✅ | S1（合并规则单测）+ S4/S5 |
| **A7** 坏档案不动端 | ✅ | S1（表驱动，用内存 source/sink）+ S4/S5（端内零变化） |
| **A8** 凭据默认不出现 | ✅ | S1（对 `export()` 产物断言禁止路径 + `redacted` 字段 + 一条 warn 日志） |
| **A9** 后缀规则逐字 + 碰撞报错 | ✅ | S1（`:core:jvmTest` 跑 commonTest 的纯函数用例） |
| **A10** `#written_at` 归一化 | ✅ | S1（codec）+ S3（导回桌面/web 后 `ageMsOfJson` 同 age） |
| **A11** 桌面断言对象只能是解析后的键值 | ✅（写法约束，不是新代码） | S5（桌面门禁的写法：不比 `.properties` 字节） |
| **A12** `:app:testDebugUnitTest` ≥ 429 | ✅ | 现有门禁（每个工单都要过） |
| **A13** 桌面「档案→数据根」映射是纯字符串拼接 | ✅ | S5（`dataRootOverride` 下 `cp -r` + import + 读值三连）；**前半**（两根同值）放 S2 |

**本批跑不了的三处，原因是同一个**：`:web` 现在**没有测试源集**（`web/build.gradle.kts` 只有 `wasmJsMain`），
`:core` 也**刻意没声明** wasm 测试环境（`core/build.gradle.kts:45-52`：nodejs 下 Compose 的 `skiko.mjs`
会 `ERR_MODULE_NOT_FOUND`，所以 wasm 的运行时证明一直靠"`:web` 在真浏览器里跑真数据"）。
⇒ 一切"web 侧自动断言"都排在 **S8**。

### 6.4 与并行会话的路径冲突（照规格 §9 末段的同名提醒）

规格登记过它可能被并行会话（`sa-1e6c6838`）侵占的路径：`data/src/commonMain/.../platform/SecureStore.kt`、
`data/src/jvmMain/.../platform/{SecureStore.jvm.kt,CredentialStore.jvm.kt,ProtectedKeyValueStore.kt}`、
`docs/windows-delivery.md`。**S0 的可选第 3 步正要动 `SecureStore.jvm.kt`** ⇒ 派活前先确认那条路径没人占；
若已被占，S0 **跳过第 3 步**（它是可选的），只做 `:core` 自己那份（功能完全成立，只是文件实现有两份）。

---

## 7. 风险与未决（需要主代理或用户拍板的点）

1. **档案容器：JSON 目录 vs `.properties` 目录**（规格未决 #1）。**我建议按规格现状走 JSON**：wasm 没有
   `java.util.Properties`，为了"落盘形状字面全等"要在 commonMain 重写一套转义（`\uXXXX` / ISO-8859-1 / 行续接 /
   `= : # !` 与首部空格），且值类型标签仍要另解（Properties 只有字符串）——一份新增的易错代码换一个"少跑一次
   import"的体验。**需要用户确认**，因为它决定 §16.0「丙 全等」的字面是否被满足。
2. **「旧形状只读、新形状写」在 Android 上做不到**（§8.2 / 未决 #2）。**建议改口径为**「端内 store 的**键名与值语义**
   逐字不变；档案是它的投影」。这是 §16.4 第 3 条的内在矛盾，只能由用户拍板。
3. **Android 的档案落地通道**（未决 #3）：我核到的事实是 `app/src/main/res/xml/file_provider_paths.xml` 里
   **没有** `archive/` 这一条（只有 `exports/`、`apk_cache/`、`Download/ClassReplay/`）。**建议 SAF**（零权限、
   零 manifest 改动），代价是一次 Activity result 接线 + 一次"权限/体验"确认。**需要拍板**。
4. **web 的档案落地与账号归属**（未决 #4）：D9 落地前只能 `global/` + `noAccount=true`；落地后 suffix 取 serve 的当前账号。
   **我建议 S6 先按 `noAccount` 做**，S7a 之后再切 —— 否则 S6 得等 S7a，批② 的"三端多账号"与"统一存档层"会串成一条链、失去可独立提交性。
5. **#02 要不要顺手修 Android 的可见行为**（D15 / D16 的口径 vs D7-口径「三端全等」的**直接冲突**）：
   `AgentViewModel` 在 `:core`，修它就是三端一起变；不修则 web/桌面靠 `AgentHost.onAccountChanged()` 这条**可选槽位**
   做端侧隔离，而 Android 保持现状。**这条必须用户拍板**（它跟 #01 的性质一样：安卓既有行为）。
6. **`payment_code` 要不要加账号后缀**（未决 #2 的邻位 / 审计 #07）：加 = 三端同改 + 老用户重勾一次；
   不加 = 保持现状并在档案里标 `global`。**建议本批不加**（且这也是"三端一致"的现状）。
7. **`download_tasks` 的产品口径**（审计 #09）：本机公共 vs 按账号。**建议本批不动 Android**、桌面加后缀（S7b），
   registry 标 `global` + 可重建；产品口径单独立项。
8. **S0 的"两份文件实现"**：`:core` 的 `JvmFileKeyValueStore` 与 `:data` 的 `FileKeyValueStore` 是否合并
   （S0 可选第 3 步）。合并的好处是 A13 的映射只有一个实现可指；风险是 `:data:jvmTest` 259 条里那些钉字节形状的
   用例。**建议合并，但独立成一个 commit**，红了就单独回退。
9. **未决 #10 那批「未核对键」**（`score_cursor` / `schedule_diff` / `course_colors` / `home_usage` /
   `pidai_appearance` / `schedule_widget_prefs` / `empty_room_cache` 的完整键清单）：**不阻塞 A1–A13**
   （A1 是"档案 keys == 端内 keys"的**结构性**断言，不需要预先知道键名），但**阻塞** `ArchiveRegistry` 里
   TTL 档与"是否可重建"的填写 ⇒ S1 建表时那几格先写"待定（理由：规格未决 #10 未核）"，别猜。
10. **规格 §3.2 的两处小偏差**（我读代码时发现，供主代理决定要不要回写规格）：
    ① `xjtu_credentials` 的键清单缺 `cached_nickname`（`app/src/main/java/com/xjtu/toolbox/data/CredentialStore.kt:239`）；
    ② `agent_config_secure` 在 jvm 上没有 DPAPI 信封（`docs/agent-web-spec.md` 的「缺口 A」自己记了）⇒
    本批**不顺手修**（它属 D10 那一批），只在 registry 里按"凭据族 ⇒ 不进档案"处理。
11. **S0 的根是否也要覆盖 `:server`**：`server/src/main/kotlin/com/xjtu/toolbox/server/AccessToken.kt:57` 用的是
    `secureKeyValueStore(STORE_NAME)`（已落盘）⇒ 不受 S0 影响；但 serve 进程里那些走 plain `keyValueStore` 的
    `:core` store（`inbox` / `feature_hints` / …）会**跟着 S0 一起变成落盘**，那对 serve 是好事（常驻进程本来就该落盘），
    但要确认它不改变 `:server:test` 82 条的期望（S0 的门禁里要带上 `:server:test`）。

### 7.1 建议的最后一批

`S8`（D14 的 `:web` 测试源集）**排在最后**最省，理由三条：
① 它的第一批测试正好是"存档层互认"（A5）与"路由清单 = 分支"（§16.2 第 1 条）—— 这两样在 S1–S7 之后**已经有现成夹具与现成清单**，
之前建源集只能测空气；② `core/build.gradle.kts:45-52` 记着 wasm 测试环境的坑（nodejs 下 `skiko.mjs`
`ERR_MODULE_NOT_FOUND`，必须用 `browser()`）⇒ 建源集本身要一次试错，放在批②**末尾**做，试错只影响 S8 一个工单；
③ 若 S7a（web 账号页）之后还要补屏，S8 的"路由覆盖测试"能**一次性**把新增的分支全部钉住（不然要写两遍）。

---

## 8. 怎么读这份文档

一句话：**这份文档是工单拆分** —— 规格（格式、键名、TTL、判据 A1–A13、未决问题）只认 `docs/storage-layout.md`，
本文只提供"哪些文件、什么判据、谁阻塞谁、能不能独立提交"，两处冲突时**以规格为准**并把冲突列进 §7。

---

## 附录 A：我实际读过的文件（证据清单，只读）

**规格与决策文档**
- `docs/storage-layout.md`（全文 478 行，两段读完）· `docs/desktop-port-plan.md` §16（995–1093 行逐字）
- `docs/audit-verification.md`（grep 命中行 + 第 28–57 / 119–121 / 184–234 / 269–312 行的段落）
- `docs/audit-verify-1-correctness.md`（#02 139–208 行；#07 361–400 行；#09 443–470 行）
- `docs/agent-web-spec.md`（grep：存档/落盘/键名/agent_* 相关 20 余行）· `docs/api-contract.md`（§7 904–945 行 + grep「三处同改」）

**缝与平台 actual（全文读）**
- `core/src/commonMain/kotlin/com/xjtu/toolbox/platform/KeyValueStore.kt`
- `core/src/androidMain/kotlin/com/xjtu/toolbox/platform/KeyValueStore.android.kt`
- `core/src/jvmMain/kotlin/com/xjtu/toolbox/platform/KeyValueStore.jvm.kt`
- `core/src/wasmJsMain/kotlin/com/xjtu/toolbox/platform/KeyValueStore.wasmJs.kt`
- `core/src/commonMain/kotlin/com/xjtu/toolbox/platform/Share.kt` + `Share.android.kt` + `Share.jvm.kt` + `Share.wasmJs.kt`
- `data/src/commonMain/kotlin/com/xjtu/toolbox/platform/SecureStore.kt` · `data/src/jvmMain/.../SecureStore.jvm.kt`
- `data/src/commonMain/kotlin/com/xjtu/toolbox/platform/DataFiles.kt` · `data/src/jvmMain/.../DataFiles.jvm.kt` · `data/src/androidMain/.../DataFiles.android.kt`
- `data/src/androidMain/kotlin/com/xjtu/toolbox/data/SecurePrefs.kt`（29–100、112–152 行）
- `data/src/jvmMain/kotlin/com/xjtu/toolbox/platform/CredentialStore.jvm.kt`（1–70 行）

**账号 / 会话**
- `core/src/commonMain/kotlin/com/xjtu/toolbox/account/{AccountContext.kt,AccountManagerScreen.kt,AccountsController.kt}`（grep + 关键段）
- `data/src/commonMain/kotlin/com/xjtu/toolbox/account/AccountStore.kt`（全文）
- `app/src/main/java/com/xjtu/toolbox/account/AccountManager.kt`（300–360 行）· `AccountMigration.kt`（grep）
- `desktop/src/main/kotlin/com/xjtu/toolbox/desktop/DesktopAccounts.kt`（1–60 行）
- `app/src/main/java/com/xjtu/toolbox/XjtuApp.kt`（全文）

**设置 / 课表 / 缓存 / Store**
- `core/src/commonMain/kotlin/com/xjtu/toolbox/settings/SettingsStore.kt`（全文 133 行）
- `core/src/commonMain/kotlin/com/xjtu/toolbox/settings/SettingsHost.kt`（grep 三个槽位）
- `core/src/commonMain/kotlin/com/xjtu/toolbox/schedule/{ScheduleCache.kt,ScheduleCacheStore.kt,KeyValueScheduleCacheStore.kt,CustomCourseStore.kt}`（全文）
- `app/src/main/java/com/xjtu/toolbox/data/DataCache.kt`（全文）· `AppDatabase.kt`（1–60 + 160–200 行）
- `data/src/commonMain/kotlin/com/xjtu/toolbox/agent/{AgentSessionStore.kt,AgentConfigStore.kt}`（1–60 行）
- `core/src/commonMain/kotlin/com/xjtu/toolbox/agent/AgentMemory.kt`（1–60 行）
- `core/src/commonMain/kotlin/com/xjtu/toolbox/inbox/InboxModels.kt`（grep）· `space` 略

**桌面 / web**
- `desktop/src/main/kotlin/com/xjtu/toolbox/desktop/{DesktopSettings.kt,DesktopScheduleHost.kt（60–115 行）,DesktopAccounts.kt}`
- `desktop/.../score/DesktopScoreReportCache.kt` · `.../media/DesktopDownloadTaskStore.kt`（1–80 行）· `.../jiaocai1/DesktopJiaocai1Storage.kt`（grep 常量）
- `desktop/build.gradle.kts`（grep + tail）· `web/build.gradle.kts`（全文 70 行）· `core/build.gradle.kts`（全文 157 行）· `data/build.gradle.kts`（全文）
- `web/src/wasmJsMain/kotlin/com/xjtu/toolbox/web/{WebScheduleHost.kt（25–115 行）,WebScoreReportCache.kt,ServeSession.kt（1–60 行）,WebSupport.kt（1–80 行）,Data.kt（grep @JsFun）}`
- `server/src/main/kotlin/com/xjtu/toolbox/server/{ServeConfig.kt（1–50 行）,ServeSession.kt（1–40 行）,AccessToken.kt（grep）}`

**`:app` 资源与测试面**
- `app/src/main/AndroidManifest.xml`（provider / backup / permission 段）· `app/src/main/res/xml/{data_extraction_rules.xml,file_provider_paths.xml}`（全文）
- `app/src/main/java/com/xjtu/toolbox/settings/AppSettings.kt`（100–175 行）· `app/.../schedule/AppScheduleHost.kt`（85–120 行）· `app/.../data/CredentialStore.kt`（grep 键名）
- `app/src/test/.../data/InMemorySharedPreferencesTest.kt`（全文）· `app/src/test/...` 文件清单（76 个文件）
- 门禁基数复核：`grep -rho "@Test"` → core 479 / app 429 / desktop 100 / data 259 / server 82（逐字相符）
- `git status --porcelain`（**空**：本 checkout 没有别的子代理的未提交改动）· `git rev-parse HEAD` = `3dc2f52a` · `settings.gradle.kts`（全文）

## 附录 B：未核对项（不许当结论用）

1. **`:app` 里 Room 三张表的"行适配器"实现类名**：我只核到 DAO/实体（`CustomCourseDao` / `CustomCourseEntity`、
   `DownloadTaskDao` / `DownloadTaskEntity`、`Jiaocai1ShelfDao` / `Jiaocai1ShelfEntity`）与
   `AccountManager.kt:339` 那处 `customCourseDao().deleteByAccount(...)`；`RoomCustomCourseStore` 这个类名是
   **按命名惯例写进 S4 的**，实际类名待定（理由：`grep` 未命中）。
2. **`web/.../WebSupport.openableRouteIds` 的完整清单尾部**（我只读到第 80 行，`AppRoute.Accounts` 是否已在其中
   未核到终点；`WebSupport.kt:42-43` 的 KDoc 说它在差集里 ⇒ S7a 的第一件事是核这一行）。
3. **`:server` 是否已有账号相关端点**（我对 `server/.../SessionRoutes.kt` 做过 `grep accounts|AccountStore|activeAccount`，
   **零命中**；但 `ServeSession.kt` 我只读了前 40 行，登录态里怎么维护账号列表**未核**）。
4. **`score_cursor<suffix>` / `schedule_diff<suffix>` / `course_colors<suffix>` / `home_usage` /
   `pidai_appearance` / `schedule_widget_prefs` / `empty_room_cache<suffix>` 的完整键清单**（规格未决 #10 同样未核；
   见 §7 第 9 项）。
5. **`DesktopDownloadTaskStore.FILE_NAME` 的字面值**（我只核到它通过 `secureKeyValueStore(FILE_NAME)` 取值、
   `DesktopJiaocai1Storage.FILE_NAME = "jiaocai1_shelf"`、`KEY_ROWS = "rows"`；`download_tasks` 这个字面值来自
   `DesktopDownloadsJvmTest.kt:55` 的 `wipeSecureStore("download_tasks")` 旁证）。
6. **`:desktop:test` 的 Gradle 任务名**：`desktop/build.gradle.kts` 里没有 `test { }` 配置块（grep 过），
   任务名按 kotlin-jvm 插件的默认 `test` 读；S0 要加的 `tasks.named<Test>("test")` 块需在实施时确认一次。
7. **`:core` 的 Android 目标是否有 host test 任务**（`core/build.gradle.kts` 的 `android { }` 里没有任何
   `withHostTest` / `unitTests` 配置，我也没有跑 gradle 确认）⇒ 我把 S2 的 Android 断言放在
   **`:app:testDebugUnitTest`**（`:app` 看得到 `:core` 的 androidMain 类型），而不是 `:core` 的 Android 测试任务。
