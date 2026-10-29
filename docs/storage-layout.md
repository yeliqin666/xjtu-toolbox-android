# 统一存档格式（三端持久化的唯一真源）

> 状态：2026-10-14 设计稿（会议 D8/D9/D10，见 docs/desktop-port-plan.md §16）。本文件是该格式的唯一真源。

> 口径说明：我按 D8 原文「目录/文件名 + **JSON 形状** + 键名 + 账号后缀 `safeSuffix()` + TTL」把**档案**这一层定成 JSON；三端**端内**落盘形状一个都不动（C1 红线）。这条读法与「落盘形状也统一」的字面有张力，我把它列成未决问题 #1 并给了替代方案。

---

## 0. 结论摘要（先看这五条）

1. **三端端内形状今天就已经是三种**：Android = `shared_prefs/<name>.xml` + Room + `cache/data_cache<suffix>/*.json`；桌面 = `~/.local/share/xjtu-toolbox/<name>.properties`（0600）；web = `localStorage["xjtu-toolbox.<store>.<key>"]`。**没有任何一端读得懂另一端的落盘**（今天不存在跨端读路径）。
2. **键名层面已经高度统一**：三端都走同一批 store 名与键名（`:core` 的 `keyValueStore(name)` 缝），所以「唯一真源」的缺口只剩**容器形状**（XML / Properties / localStorage）与**四种值类型编码分歧**（见 §3.5）。
3. **后端有一批 store 在桌面上根本没落盘**（`:core` 的 jvm actual 是进程内内存，`KeyValueStore.jvm.kt:8`、`:41`）。在补这个缺口之前，「档案可直接拷过去用」在桌面上是**假的**：`library_favorites` / `venue_favorites` / `inbox*` / `feature_hints` / `empty_room` / `course_colors*` / `schedule_diff*` / `bulletin_store` / `feedback` / `games` / `haptics_prefs` / `jiaocai1_reader_prefs` / `schedule_source*` / `schedule_changes*` / `attendance_records_*` / `score_report` **全部只活在内存里**。这是本规格的**第 0 号前置条件**。
4. **已发现 5 处「同名同键、形状不同」的真实分歧**（§5.1、§3.5），其中 `favorite_venue_ids` 是**同一 (store, key) 两种值类型**，直接违反「键名逐字即等价」的假设。
5. **「旧形状只读、新形状写」这条在 Android 上做不到**（一做就破坏 C1 行为不变），必须回用户面前改口径 —— 见未决问题 #2（这条是 §16.4 第三点的内在矛盾，不是口味问题）。

---

## 1. 目录与文件名清单（三端真路径）

### 1.1 Android（`applicationId = com.xjtu.toolbox`，`app/build.gradle.kts:16,26`）

| 族 | 真路径 | 证据 |
|---|---|---|
| 普通键值 | `/data/data/com.xjtu.toolbox/shared_prefs/<store>.xml` | `KeyValueStore.android.kt:26` `getSharedPreferences(name, MODE_PRIVATE)` |
| 密文键值 | `/data/data/com.xjtu.toolbox/shared_prefs/<store>.xml`（同名同目录，内容由 `EncryptedSharedPreferences` 加密） | `SecureStore.android.kt:78` → `SecurePrefs.open(...)`；`SecurePrefs.kt` 的 `shared_prefs/$name.xml` 删除逻辑 |
| 迁移用明文残留 | `.../shared_prefs/<store>_fallback.xml`（`SecurePrefs.open` 迁完即删） | `SecureStore.android.kt:78` 的 `legacyFallbackName` 默认 `"${name}_fallback"` |
| 一会话一文件 | `/data/data/com.xjtu.toolbox/files/agent_sessions<suffix>/index.json`、`convo_<id>.json` | `AgentSessionStore.kt:26,27,31` |
| Room | `/data/data/com.xjtu.toolbox/databases/xjtu_toolbox.db`（+ `-wal`/`-shm`） | `AppDatabase.kt:174-178` `Room.databaseBuilder(..., "xjtu_toolbox.db")` |
| 缓存目录 | `/data/data/com.xjtu.toolbox/cache/data_cache<suffix>/<key>.json` | `DataCache.kt:35` `dirName = "data_cache${suffixFor(accountId)}"` |
| 头像等杂项 | `files/avatar<suffix>.jpg` / `avatar_custom<suffix>.jpg` / `avatar<suffix>.url` | `HelloProfileStore.kt:108,111,114,176` |

**档案落地目录：未核对。**今天 `:app` 没有任何「导入/导出档案」入口；建议 `filesDir/archive/`（= `dataFilesDir()` 的同一根，`DataFiles.android.kt:11`），通道 SAF 或 adb —— 见未决问题 #3。

### 1.2 桌面（Linux 与 Windows 同一个 XDG 口径）

数据根：`dataRoot() = File(System.getProperty("user.home"), ".local/share/xjtu-toolbox")`（`SecureStore.jvm.kt:27-33`，可用 `dataRootOverride` 换根）。

| 平台 | 真路径 |
|---|---|
| Linux | `/home/<user>/.local/share/xjtu-toolbox/<store>.properties` |
| Windows | `C:\Users\<user>\.local\share\xjtu-toolbox\<store>.properties` |

**Windows 上刻意不走 `%LOCALAPPDATA%`**：`docs/windows-delivery.md:243-245` 明写「改成 `%LOCALAPPDATA%` 会挪动凭据/会话文件的落点（等于让老用户重新登一次）——留到后面」。

本机实测（只读 `ls`，权限列原文）：

```
-rw-------  cookies_normal_2253415556.properties      # 键 all_cookies
-rw-------  github_community.properties               # 本次空（无键行）
-rw-------  serve.properties                          # 键 access_token
-rw-------  sites_normal_2253415556.properties        # 键 campus_card / fitness / jwxt / library / venue / ywtb
-rw-------  xjtu_accounts.properties                  # 本次空（无键行）
-rw-------  xjtu_credentials.properties               # 键 fp_visitor_id / password / rsa_key_time / rsa_public_key / username
```

文件名规则 = `<name>.properties`（`SecureStore.jvm.kt:112-114`）。**每个文件首行是 `Properties.store` 写的时间戳注释**（实测 `#Wed Oct 14 08:10:56 CST 2026`）⇒ 文件字节**不稳定**，不能当断言对象。

### 1.3 web（浏览器）

`localStorage`，键 = `"xjtu-toolbox." + <store> + "." + <key>`（`KeyValueStore.wasmJs.kt` 的 `PREFIX` 与 `private fun k(key)`）。

- 值编码：`String` 原文；`Boolean` → `"true"`/`"false"`；`Int` → 十进制串；`StringSet` → **逗号 join**（`keyValueStore.wasmJs.kt` 的 `getStringSet`/`putStringSet`）。
- **没有文件系统、没有任何账号后缀**（`WebScheduleHost.kt:38-40` 的 KDoc 明写「这一端没有账号（凭据在 campus-api / serve 进程里）」）。
- 档案落地通道（下载/上传）**未核对**：`:web` 里现在没有任何 import/export 代码。

---

## 2. 档案文件的 JSON schema

### 2.0 顶层布局

```
<archive>/                                  # 目录即档案本体（可整拷）；zip 只是运输形态
  manifest.json
  global/                                   # 无账号后缀的那一档（三端同名同键）
    stores/<store>.json
    files/<相对路径>
  accounts/<suffix>/                        # suffix = AccountContext.safeSuffix() 逐字（含前导 _）
    stores/<store>.json
    files/<相对路径>
```

**档案↔端内文件名的唯一映射规则（可断言）**：
> 端内名 = `<store>` + （账号档 ? `<suffix>` : `""`）；桌面再加 `.properties`；web 键前缀再加 `xjtu-toolbox.` + `.`。
> 即 `accounts/_2253415556/stores/schedule_cache.json` ⇄ `schedule_cache_2253415556.properties` ⇄ `xjtu-toolbox.schedule_cache_2253415556.<key>` ⇄ Android `shared_prefs/schedule_cache_2253415556.xml`。

### 2.1 `manifest.json`

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `format` | string | 否 | 固定 `"xjtu-toolbox-archive"` |
| `version` | int | 否 | 本文件版本 1；不认识就**明确失败**（不改端内任何东西） |
| `createdAt` | string | 否 | ISO-8601 **带时区**（照 `docs/api-contract.md` §4 第 3 条：禁止 epoch） |
| `createdBy` | string | 否 | `android` / `desktop-linux` / `desktop-windows` / `web` / `serve` |
| `appVersion` | string | 否 | 例 `5.1.1 (471)`；桌面 `vX.Y.Z (desktop)`（`DesktopSettingsHost.versionText`）；web `Web 构建（wasm）` |
| `noAccount` | bool | 可缺省=false | web 在 D9 之前导出时置 true（§9 未决 #4） |
| `accounts[]` | array | 否（可为空数组） | 每项 `{accountId:string, suffix:string, accountType:string, lastUsedAt:long}`；`accountId` 为**原值**（用于发现 suffix 碰撞），`suffix` 为 `safeSuffix()` 逐字 |
| `stores[]` | array&lt;string&gt; | 否 | 档案里出现的 store 名，**按码点升序**（写出的档案字节稳定） |
| `files[]` | array&lt;string&gt; | 否 | 档案里出现的 `files/` 下相对路径，同上排序 |
| `redacted[]` | array&lt;string&gt; | 可缺省=空 | 按策略剔掉的熵源条目标识（形如 `accounts/_2253415556/stores/xjtu_credentials.json#password`），导入端据此提示「哪些东西没跟过来」 |
| `ttlClass` | object | 可缺省 | `{ "<store>": "term|day|week|forever|none" }`，只作**提示**（见 §5.2 第 7 条：TTL 不由档案裁决） |

### 2.2 `…/stores/<store>.json`（键值档，一个 store 一个文件）

```json
{
  "format": "xjtu-toolbox/store",
  "version": 1,
  "store": "app_settings",
  "suffix": "",
  "keys": {
    "dark_mode":            { "t": "s",   "v": "dark" },
    "show_quick_actions":   { "t": "b",   "v": true },
    "eula_accepted_version":{ "t": "i",   "v": 4 },
    "last_auto_update_check_at": { "t": "l", "v": 1760000000000 },
    "card_today_dinner_cache":   { "t": "f", "v": 12.5 },
    "favorite_seats":       { "t": "set", "v": ["A-1","B-2"] },
    "schedule_2026-2027-1": { "t": "s",   "v": "[{\"courseName\":\"…\"}]", "at": 1760000000000 }
  }
}
```

- `t` ∈ `s`(string) · `b`(boolean) · `i`(int32) · `l`(int64) · `f`(float) · `set`(字符串数组)。**必须带类型标签**：Android 的 prefs 是有类型的（XML `<long>`/`<float>`/`<set>`），Properties 与 localStorage 只有字符串，没有标签就没法往返。
- `v` **不可为 null**：三端 `putString(key, null)` 都等于删键（`KeyValueStore.android.kt:29-32` 的注释与实现、`SecureStore.jvm.kt:72-76`）。「键存在但值是空」= `{"t":"s","v":""}`；「键不存在」= 该键不在 `keys` 里。`set` 的空数组 ≠ 键不存在（这条已是 `KeyValueStore` 的显式口径，`ProtectedKeyValueStore` 的 KDoc 也钉着）。
- `at` 可选，类型 long（毫秒，UTC epoch），含义 = 「这条记录最近一次写入」。缺省 = 该端没有时间戳（Android 普通 prefs 没有 mtime）。
- **排序稳定性（硬要求）**：`keys` 的成员按 key 的 UTF-8 码点升序写出；`set` 的 `v` 去重 + 升序；`stores[]`/`files[]` 升序。同一份端内数据两次导出必须**字节相同**（固定时钟注入）。
- `store` 字段必须与文件名一致（发现改名的漂移）。
- `suffix` 字段照抄目录名（账号档为 `_2253415556`，全局档为 `""`）。

### 2.3 `…/files/**`（一事一文件的档）

**总则：凡值本身已经是 `AppJson` 编出来的 JSON，档案一律原样搬运、不重新建模**（避免第三份模型漂移）。`AppJson` 的形状已是事实契约：`ignoreUnknownKeys=true` / `coerceInputValues=true` / `encodeDefaults=true` / `explicitNulls=false`（`core/src/commonMain/kotlin/com/xjtu/toolbox/util/JsonExt.kt:18-25`）⇒ **所有字段都写出、`null` 不写出**。

| 档案路径 | 内容 | 排序稳定性 |
|---|---|---|
| `files/agent_sessions/index.json` | `AgentSession[]`（同 `AgentSessionStore` 的 index 形状：`id/title/createdAt/updatedAt/locked`） | 写出前按 `updatedAt` 降序（`list()` 现有口径） |
| `files/agent_sessions/convo_<id>.json` | `StoredConversation` 原样 | 原样 |
| `files/courses/custom_courses.json` | Room `custom_courses` 表**全体行**的 JSON 数组；列名逐字（`CustomCourseEntity`） | 按 `(accountId, termCode, dayOfWeek, startSection, startMinuteOfDay, id)` 升序 —— 不依赖 DB 的返回顺序 |
| `files/media/download_tasks.json` | Room `download_tasks` 行数组 | 按 `(createTime desc, id)` |
| `files/jiaocai/jiaocai1_shelf.json` | Room `jiaocai1_shelf` 行数组 | 按 `(addedAt desc, ssno)` |
| `files/schedule/<key>.json` | 课表族缓存的**原始 JSON 字符串原样**（`schedule_<term>` / `schedule_optimized_<term>` / `exams_<term>` / `schedule_textbooks_<term>` / `schedule_term_names` / `start_date_<term>` / `term_weeks_<term>`） | 键名升序；文件内是值本身的 JSON，不做包装 |
| `files/cache/empty_room/**` | 空闲教室缓存族（`<key>` 原文 + `savedAt`） | 键名升序 |

`agent_sessions<suffix>/` 与 `data_cache<suffix>/` 的 **目录名**在档案里**不带 suffix**（suffix 已经在 `accounts/<suffix>/` 那一层），避免同一件事写两遍。

### 2.4 不进档案的（端内私有，理由）

| 端内项 | 为什么不进 |
|---|---|
| Android `data_cache<suffix>/` 本身 | 换包即被整体清空（`DataCache.clearIfPackageChanged`，`DataCache.kt:87-114`），**不是用户数据**。课表族的**值**可以进（见 §2.3），但必须标成「可重建」 |
| `data_cache_meta.install_stamp`（`DataCache.kt:68,90`） | 纯本端换包判据，值只对本机有意义 |
| `serve.access_token`（`serve.properties`，`AccessToken.kt` 的 `STORE_NAME`/`KEY`） | 是 serve 进程的门禁令牌，跨端复制 = 把别人的门钥匙带过去 |
| 桌面 `<store>_fallback.properties` / Android `<store>_fallback.xml` | 只是 `SecurePrefs` 的一次性明文迁移残留（`SecureStore.android.kt:78`） |

---

## 3. 键名清单：三端现状逐字对照

### 3.1 键值缝的三端实现（这是所有对照的基准）

| 端 | actual | 文件 | 关键语义 |
|---|---|---|---|
| Android | `KeyValueStore.android.kt:69` | `<store>.xml` | `getStringSet` 取 `SharedPreferences.getStringSet` 并**拷一份**；`putString(key,null)` = 删键（:29-32）；`clear()` 只清这个文件（:66） |
| 桌面（jvm） | `KeyValueStore.jvm.kt:41` | **无（进程内 `MemoryKeyValueStore`）** | 全套 `Map<String,Any>`，`clear()` 清 map —— **不落盘**，这就是前置条件 #0 |
| 桌面（真落盘那份） | `SecureStore.jvm.kt:112` | `<store>.properties`，`persist()` 里 `Files.setPosixFilePermissions` 0600 | `boolean`=`toBooleanStrictOrNull()`、`int`=`toIntOrNull()`、`set`=`\u0000` join（:78,:81,:87,:93） |
| web | `KeyValueStore.wasmJs.kt:74` | `localStorage["xjtu-toolbox.<store>.<key>"]` | `boolean`=`"true"/"false"`；`int`=`toIntOrNull()`；`set`=**逗号** join；`clear()` 只扫本前缀 |

### 3.2 端内 store 名清单（三端逐字对照；「核对」列 = 我实际读到键名的程度）

| # | store 名 | 账号后缀 | Android 文件 / 桌面文件 / web 键前缀 | 已核对的键 | 核对 |
|---|---|---|---|---|---|
| 1 | `app_settings` | 无 | `app_settings.xml` / `app_settings.properties` / `xjtu-toolbox.app_settings.*` | `nav_bar_style` `dark_mode` `dynamic_color` `default_tab` `network_mode` `home_theme` `show_quick_actions` `venue_auto_solve_captcha` `schedule_attendance_badge` `schedule_source`（`SettingsStore.kt` companion）+ `custom_color_hex` `custom_color_enabled` `custom_color_hex_light` `custom_color_hex_dark`（`SettingsStore.kt` 里的字面量键；自选主题色按浅色 / 深色分存，旧键 `custom_color_hex` 当两档的兜底）+ `eula_accepted_version` `update_channel` `receive_preview_updates` `last_run_version` `last_auto_update_check_at` `account_type` `empty_room_cdn_tip` `recent_site_keys` `site_usage` `update_notice_<ver>` `last_seen_changelog_version` `cached_nickname`（`:app` `CredentialStore.kt` companion；`cached_nickname` 由 2026-10-14 批②工单拆分补记，见 `docs/storage-layout-plan.md` §7 的裁定 R7） | 全 |
| 2 | `xjtu_credentials` | 无 | 密文 xml / `xjtu_credentials.properties`（Windows 下值 = `xjtu-sealed:1:dpapi:…`）/ **web 无** | `username` `password` `fp_visitor_id` `rsa_public_key` `rsa_key_time` `account_type` | 全 |
| 3 | `xjtu_accounts` | 无 | 密文 xml / `xjtu_accounts.properties` / **web 无** | `accounts` `active_account` `migration_done` | 全 |
| 4 | `xjtu_cookies` | 无 | 密文 xml / `.properties` | `all_cookies` | 全（迁移残留池） |
| 5 | `cookies_normal<suffix>` / `cookies_webvpn<suffix>` | 是 | `SessionBackend.kt:116` `"cookies_${mode.key}$suffix"`；`AccessMode.kt` 的 key = `normal`/`webvpn` | `all_cookies` | 全 |
| 6 | `sites_normal<suffix>` / `sites_webvpn<suffix>` | 是 | `SessionBackend.kt:117`；`SiteSnapshots` | 键 = 站点 key（实测 `campus_card` `fitness` `jwxt` `library` `venue` `ywtb`）；值 = `{"at":<ms>,"tokens":{<siteKey>:<token>}}`，`MAX_AGE_MS`=12h | 全 |
| 7 | `github_community` | 无 | 密文 xml / `.properties` / `xjtu-toolbox.github_community.*` | `token` `login` | 全 |
| 8 | `agent_config<suffix>` | 是 | `AgentConfigStore` | `provider` `model` `base_url` `assistant_name` `search_engine` `thinking_enabled` `reasoning_effort` `show_reasoning` | 全 |
| 9 | `agent_config_secure<suffix>` | 是 | 密文 xml / `.properties`（DPAPI） | `api_key` | 全 |
| 10 | `agent_config_fallback<suffix>` | 是 | 旧明文残留，只读 | — | 全（只读，不进档案） |
| 11 | `agent_memory<suffix>` | 是 | `AgentMemory.kt:35,40,118,121,124` | `__order`（`\u0001` 分隔）+ 动态键 | 全 |
| 12 | `agent_sessions<suffix>/`（目录） | 是 | `AgentSessionStore.kt:31` | `index.json` + `convo_<id>.json` | 全 |
| 13 | `library_favorites` | 无 | `LibraryFavorites.kt:32,33,35` | `favorite_seats`（集合）；同 store 还有 `library_campus` `pending_home_campus` `seat_view_mode`（`LibraryViewModel.kt:32,33,36,37,85`） | 全（无后缀，**与 Android 现状一致**） |
| 14 | `venue_favorites` | 无 | `VenueFavorites.kt:34,35,37` / `CampusVenueApi.kt:472,519` | `favorite_venue_ids` —— **两处值类型不同，见 §3.5①** | 全 |
| 15 | `empty_room` | 无 | `EmptyRoomViewModel.kt:35,321`；`AgentTool.kt:967`；web `ToolboxWebApp.kt:481`；桌面 `ToolboxDesktopApp.kt:772` | `empty_room_last_campus` `empty_room_source` `empty_room_last_buildings_<campus>` `empty_room_live_buildings_<campus>`（值 = `\|` join）+ web 的 `empty_room_cdn_tip` | 全 |
| 16 | `empty_room_cache<suffix>` | 是 | `EmptyRoomCache.kt:44`（Android only） | `<key>` 与 `<key>_time`（long）；键名来自 `EmptyRoomStore` 常量与调用点（`room_seat_sizes` 等） | store 名与机制全，逐键**部分** |
| 17 | `dorm_power<suffix>` | 是 | `DormPowerStore.kt:37` | `readings` `used` | 全 |
| 18 | `campus_card<suffix>` | 是 | `CampusCardCache.kt:79` | `card_balance_cache` `card_name_cache` `card_cache_time`(long) `card_today_*`(**float**) `card_recent_tx_cache`（旧键，已 remove） | 全 |
| 19 | `campus_card_data_cache<suffix>` | 是 | `CampusCardCache.kt:31,79` | `snapshot` | 全 |
| 20 | `inbox<suffix>` | 是 | `InboxModels.kt:248` | `data` | 全 |
| 21 | `schedule_source<suffix>` | 是 | `ScheduleSourceRouter.kt:159,161` | `served` | 全 |
| 22 | `schedule_changes<suffix>` | 是 | `ScheduleSourceRouter.kt:181` | 键 = 学期码，值 = `ScheduleChangeEvent[]` | 全 |
| 23 | `schedule_diff<suffix>` | 是 | `ScheduleDiff.kt:15` | `snap_*` + 记录用键 | **未逐条核对** |
| 24 | `course_colors<suffix>` | 是 | `CourseColors.kt:49` | 键 = 课程标识 | **未逐条核对** |
| 25 | `attendance_records_ug<suffix>` / `_pg<suffix>` | 是 | `AttendanceRecordStore.kt:57` | 键 = 学期码，值 = `Shard{termCode,records,fetchedAt,fullScanAt}` | 全 |
| 26 | `bulletin_store` | 无 | `BulletinStore.kt:20,55-58` | `cached_json` `dismissed_ids` `acked_ids` | 全 |
| 27 | `feedback` | 无 | `FeedbackStore.kt:39-44,50,191-196` | `anon_id` `tickets` `read_replies` `prompt_last_at` `prompt_done_version` `reply_fetch_at` `reply_cache` `public_qa` `public_qa_at` | 全 |
| 28 | `games` | 无 | `GameStore.kt:24,26` | `best_<game>`、`<game>_win_<difficulty>`、`<game>_loss_*`、`<game>_draw_*`、`merge*` | 全 |
| 29 | `haptics_prefs` | 无 | `Haptics.kt:41,42,49` | `enabled` | 全 |
| 30 | `jiaocai1_reader_prefs` | 无 | `Jiaocai1Prefs.kt:13,14,17` | `vertical_scroll` | 全 |
| 31 | `feature_hints` | 无 | `Jiaocai1UsageNotice.kt:20,21,39`；`AppNavHost.kt:236,357,410`；web `ToolboxWebApp.kt:516,543,674`；桌面 `ToolboxDesktopApp.kt:618,799,951` | `library_hint_shown` `venue_hint_shown` `lms_hint_shown` `jiaocai1_hint_shown` | 全 |
| 32 | `payment_code` | 无 | `PaymentCodeModels.kt:94,95`；app `AppPaymentCode.kt:48`；web `ToolboxWebApp.kt:824`；桌面 `ToolboxDesktopApp.kt:759` | `selected_voucher_ids` `voucher_selection_configured` | 全 |
| 33 | `home_usage` | 无 | `ServiceUsageTracker.kt:13,16,23` | 键 = 站点 key | **未逐条核对** |
| 34 | `home_stats_cursor` | 无 | `HomeStats.kt:186,203,209,217` | `home_stat_*`、`proactive_*`、`attendance`、`campus_card` | 部分 |
| 35 | `score_cursor<suffix>` | 是 | `HomeStats.kt:172` | 未读 | **未核对** |
| 36 | `pidai_appearance` | 无 | `PidaiAppearance.kt:70` | `skin` `character` `color` `shape` `pidai_skins` `plain` `classic` | 部分 |
| 37 | `pidai_proactive` | 无 | `ProactiveBubble.kt:132,133` | `level` | 全 |
| 38 | `crash_reporter` | 无 | `CrashReporter.kt:32,33,41,44` | `enabled` | 全 |
| 39 | `onboarding` | 无 | `OnboardingStore.kt:20,21,31` | `done` | 全 |
| 40 | `notice_watch` | 无 | `NoticeWatchStore.kt:12-20,103,106` | `enabled` `sources` `sources_configured` `seen_links` `baselined_sources` `last_sync_at`(long) `last_titles` `push_keywords` | 全 |
| 41 | `reminder_watch` | 无 | `ReminderStore.kt:29` | `schedule` `lms` `library` | 全 |
| 42 | `session_keepalive` | 无 | `SessionKeepAlive.kt:68,75,76` | `enabled` `interval_min` | 全 |
| 43 | `campus_probe` | 无 | `CampusProbe.kt:50` | `code` `data` `ywtb_token` | 全 |
| 44 | `lms_downloads` | 无 | `LmsDownloadStore.kt:18,19,34,134` | `records` | 全 |
| 45 | `schedule_widget_prefs` | 无 | `ScheduleWidgetProvider.kt:87,161,165,171` | 浏览日期键（`getLong`） | 部分 |
| 46 | `notice_widget_cache` | 无 | `NoticeWidgetStore.kt:15,17,27,39,44` | `updated_at`(long) `day_<i>`(long) | 全 |
| 47 | `serve` | 无 | 桌面 `serve.properties` | `access_token` —— 不进档案 | 全 |
| 48 | `download_tasks` | 无 | 桌面 `DesktopDownloadTaskStore.kt:48,194,195`；Android = Room 表 | `rows`（桌面）/ Room 列（`AppDatabase.kt:29-50`） | 全 |
| 49 | `jiaocai1_shelf` | 无 | 桌面 `DesktopJiaocai1Storage.kt:46,133,134`；Android = Room 表 | `rows` / Room 列（`AppDatabase.kt:84-101`） | 全 |
| 50 | `score_report`（桌面） / `score_report_cache`（web） | 无 | `DesktopScoreReportCache.kt:41` / `WebScoreReportCache.kt:18,34,35` | 键 `score_report_<学号>`（桌面）/ `all`（web）；见 §5.1② | 全 |
| 51 | `schedule_cache` | **桌面=是，web=否** | `DesktopScheduleHost.kt:84` / `WebScheduleHost.kt:73,104` | 见 §3.3 | 全 |
| 52 | `custom_courses` | 无 | `KeyValueCustomCourseStore.KEY`；桌面 `DesktopScheduleHost.kt:88`；web `WebScheduleHost.kt:76,107`；Android = Room 表 | `custom_courses`（单键，含全部账号的行） | 全 |
| 53 | `data_cache_meta` | 无 | `DataCache.kt:68` | `install_stamp` —— 不进档案 | 全 |

### 3.3 课表族键名（唯一真源：`ScheduleCache`，值走 `ScheduleCacheStore` 缝）

| 键 | 值类型 | 备注 |
|---|---|---|
| `schedule_current_term` | string | `CURRENT_TERM_KEY` |
| `schedule_last_term` | string | `LAST_TERM_KEY` |
| `schedule_term_list` | `List<String>` | `TERM_LIST_KEY` |
| `schedule_optimized_<term>` | `List<CourseItem>` | 剔过节假日 |
| `schedule_<term>` | `List<CourseItem>` | **未剔除**的原始课表；桌面小组件按它直读（`ScheduleCache.rawKey` 的 KDoc 明确「改名等于让小组件读不到」） |
| `exams_<term>` | `List<ExamItem>` | |
| `schedule_textbooks_<term>` | `List<TextbookItem>` | |
| `start_date_<term>` | string（ISO `LocalDate`） | |
| `term_weeks_<term>` | int | 与开学日期一起查、一起存 |
| `schedule_term_names` | `Map<String,String>` | `ScheduleTermStore.CACHE_KEY`，TTL = `Long.MAX_VALUE` |
| `<key>#written_at` | long-as-string | **只在桌面/web 的 KV 实现里出现**（`KeyValueScheduleCacheStore` 的 `AGE_SUFFIX`）—— 档案里必须折进 `at`，见 §6.3 |

### 3.4 `app_settings` 的双写者（这是最容易漏的一条）

`:core` 的 `SettingsStore` 只读写 10 个设置键，但**同一个文件**还被 `:app` 的 `CredentialStore` 写 12 个非设置键（更新渠道 / 站点使用记录 / EULA 版本……），其 KDoc 明写「两个对象写同一个文件是安全的」（`SettingsStore.kt` 的类 KDoc；Android 侧 `getSharedPreferences` 按名字返回同一实例；`:app` 侧 `CredentialStore.kt:59,111-113`）。**档案导出 `app_settings` 必须是全体键**，否则跨端一拷就丢 `update_channel` / `site_usage` 等键。

### 3.5 已发现的四处「同名不同形状」分歧（必须写进真源）

① **`venue_favorites.favorite_venue_ids`：同一 (store, key) 两种值类型。**
- `VenueFavorites.kt:37`（Android/桌面路径）→ `putStringSet`（Android = XML `<set>`，Properties = `\u0000` join）
- `CampusVenueApi.kt:458-470`（web 路径）→ `getString/putString` + `joinToString(",")`
- 两者**不兼容**：Android 上对 String 值调 `getStringSet` 会 `ClassCastException`（`VenueFavorites` 的 KDoc 自己写着这条）。今天不炸只是因为 `CampusVenueApi` 只在 web 上被装配（`ApiMode.SERVE`）。
- 档案口径：`t="set"`，`v` 为数组 —— **两个端内编码都只是它的一种表示**。

② **成绩报表缓存：三端两个 store 名 + 三种载荷形状。**
- Android：`cache/data_cache<suffix>/score_report_<学号>.json`，TTL 7d（mtime）
- 桌面：store `score_report` + 键 `score_report_<学号>`，载荷 `{"at":ms,"grades":[…]}`，TTL 7d
- web：store `score_report_cache` + 键 `all`，载荷 = **裸数组**，**没有 TTL、没有 `at`**

③ **`empty_room_cdn_tip` 不在同一个 store 里**：`:app` 在 `app_settings`（`CredentialStore.kt` 的 `KEY_EMPTY_ROOM_CDN_TIP`，取值走 `appPrefs`），web/桌面在 `empty_room`（`ToolboxWebApp.kt:481-486`）。

④ **桌面 `schedule_cache` 带账号后缀、web 不带**（`DesktopScheduleHost.kt:84` vs `WebScheduleHost.kt:73`）；`score_report*` 与 `custom_courses` 同理。

---

## 4. 账号命名空间后缀规则（照 `AccountContext` 逐字）

`core/src/commonMain/kotlin/com/xjtu/toolbox/account/AccountContext.kt`：

```kotlin
fun safeSuffix(): String = suffixFor(activeAccountId)

fun suffixFor(accountId: String?): String {
    val id = accountId ?: return "default"
    return "_" + id.replace(Regex("[^a-zA-Z0-9]"), "_")
}
```

逐字结论（可断言）：

| 输入 `accountId` | `suffixFor` 结果 | 说明 |
|---|---|---|
| `null` | `"default"` | **无前导下划线** |
| `""` | `"_"` | 空串**不是** null ⇒ 进替换分支，结果是单个下划线（这是一个真实且恶心的形状，必须钉住） |
| `"2253415556"` | `"_2253415556"` | 实测本机文件即此名 |
| `"2-2534"` | `"_2_2534"` | 非字母数字逐字符换 `_` |
| `"x@y"` | `"_x_y"` | 同上 |

- **正则替换是逐字符的**（`Regex.replace` 把每个不匹配字符换成 `_`），不是整串替换 —— 所以 `"a-b"` → `_a_b`，不会长度塌缩。
- **它是多对一的**：`"a-b"` 与 `"a_b"` 都得到 `_a_b`。档案因此：目录名用 suffix 逐字（保证映射是纯字符串拼接），但 `manifest.accounts[].accountId` 记**原值**，且导入端在发现「两个 accountId 映射到同一 suffix」时必须**明确失败**，不许静默合并（今天产品里这已经是既有碰撞面，档案不该把它放大）。
- 匿名档（未登录/登出中间态）在档案里**也占一个目录**（`accounts/default/` 或 `accounts/_/`），且**不得**被当成「无账号」——与 `AccountContext` 的 KDoc「值为 null 时各 Store 应回退到默认（旧/匿名）路径」一致。
- **web 今天没有账号这回事**（`WebScheduleHost.kt:38-40`），所以 web 导出的档案只能落 `global/` 并把 `manifest.noAccount` 置 true（D9 之后 web 的账号 = serve 进程的账号，那时才谈 suffix）。

---

## 5. TTL / 缓存失效规则

### 5.1 三端现状逐条（差异就是这张表）

| # | 对象 | Android | 桌面 | web |
|---|---|---|---|---|
| 1 | 课表族（`ScheduleCache`） | `DataCache`：TTL 按**文件 mtime**，过期**读时删文件**；`TERM_TTL_MS`=90d；`FOREVER`=`Long.MAX_VALUE`；写入 `.tmp` + rename **原子** | `KeyValueScheduleCacheStore`：TTL 按**同 store 的 `<key>#written_at`**，过期读时删**两个键**；**没有原子写**（KDoc 如实声明） | 同桌面（同一份 `KeyValueScheduleCacheStore`），只是落在 localStorage |
| 2 | 空闲教室 | `EmptyRoomCache`：TTL 按 `<key>_time`(long)，过期**不删**，只返回 null；另有 `readJsonAnyAge`/`readRoomListStale` **忽略 TTL** 的兜底档；CDN 1d / 直查 7d / 代码表 7d / 座位 365d | **不缓存**（`EmptyRoomStore` 传 null） | **不缓存** |
| 3 | 成绩报表 | `DataCache`，7d，mtime | `at` 在载荷里，7d，过期删键；**但它的默认 store 是 `keyValueStore` ⇒ jvm 上是内存，等于没缓存**（`DesktopScoreReportCache.kt:41`） | **无 TTL、无 `at`** |
| 4 | 站点快照 | `at` 在载荷里，12h，过期**不删**（只是不恢复）；另拒绝过期 JWT | 同 Android（同一份 `SiteSnapshots`） | 无（web 无站点会话） |
| 5 | 凭据 RSA 公钥 | 24h（`last_auto_update_check_at` 那种 long 同族） | 24h（`JvmCredentialStore`，`rsa_key_time` 存**字符串**；`:app` 侧读的是 `getLong` ⇒ 同一键两种类型，见 §8.6） | 无（凭据不在浏览器） |
| 6 | 反馈回复 | `reply_fetch_at` 6h / 挂起 10min | 同 | 同（`:core` `FeedbackStore`） |
| 7 | 考勤记录 | `FRESH_TTL_MS`=2h / `FULL_RESCAN_MS`=3d | 同 | web 无考勤取数 |
| 8 | 收纳 | `KEEP_MS`=30d / `FINISHED_KEEP_MS`=7d | 同 | 同 |
| 9 | 时钟回拨 | 不适用（mtime） | `age = (now - ts).coerceAtLeast(0)` | 同桌面 |
| 10 | 时钟来源 | 墙上时钟（`System.currentTimeMillis`） | `Clock.System.now().toEpochMilliseconds()` 默认 | 同 |
| 11 | 单调时钟 | `SystemClock.elapsedRealtime()`（`SecureStore.android.kt:90`）**从不落盘** | `System.nanoTime()`（`SecureStore.jvm.kt:121`）**从不落盘** | wasm 版同口径 |

### 5.2 统一后的唯一规则（写进真源）

1. **只有墙上时钟可以落盘**：`at` / `<key>_time` / `#written_at` 一律 = UTC epoch 毫秒；`elapsedRealtimeMs()` 的来源（`SecureStore.kt` 的 KDoc）**永远不进档案**。
2. **档案里只存 `at`，不存 TTL 判定结果**：TTL 是消费方（代码）的属性，不是数据的属性。同一份数据被三端读到时用各自的 TTL 判 —— 这正好满足「端行为不变」。
3. **过期动作统一为「读时判 null」**；「删不删」保留各端现状（Android DataCache 删、EmptyRoomCache 不删、KV 删），因为删是端内行为、用户不可见；**档案里永不删**。
4. **负数 age 一律夹到 0**（照 `KeyValueScheduleCacheStore.ageMsOfJson` 的现状，理由是时钟回拨安全）。
5. **`#written_at` / `<key>_time` 这类「旁挂时间戳键」在档案里必须归一化成 `at`**，端内名不出现在 `keys` 里（导回桌面/web 时按需重建）。这是一条硬归一化规则，也是最好写测试的一条。
6. **fallback（忽略 TTL 的兜底读法）在档案语义里不存在**：档案里过期的条目仍然在，只是读它的端自己决定要不要当「stale 兜底」用。
7. **TTL 档位唯一真源放本文档**（各端常量只是它的实现）：`forever`(课表族 `schedule_current_term` 等) · `term=90d`(课表/考试/教材) · `week=7d`(成绩报表 / 直查教室 / 代码表) · `day=1d`(CDN 教室) · `year=365d`(教室座位数) · `12h`(站点快照) · `6h`(反馈回复) · `2h`(考勤新鲜) · `3d`(考勤全量重扫) · `30d/7d`(收纳) · `none`(web 的成绩缓存 —— 见下)。
8. **web 的成绩缓存要补 `at` 与 7d TTL**（对齐 Android/桌面）。这是 **web 行为变化**，与 C1 无关（C1 只保 Android），符合 D7-口径「web 向 Android 对齐」⇒ 允许，列为 web 侧待改项。

---

## 6. 迁移路径（旧形状 → 新形状）

**前置**：迁移代码放 `:core`（commonMain）能被三端共用；`:app` 侧只允许加「接线点」，不许动现有测试断言。

### 6.1 三条不变量

1. **幂等**：`import(archive)` 跑两次 == 跑一次（合并语义见 6.4）。
2. **旧形状只读**：迁移**读**旧形状，**写**新形状。⚠️ 这条在 Android 上只做到 §8.2 那种弱形式 —— 见未决问题 #2。
3. **失败可回滚**：导入**先在临时目录/临时 prefs 命名空间里全量落地并自校验，再原子换名**；任何一步失败 ⇒ 端内文件一个都没变。（现成先例：`AccountMigration` 的闸门 + 「关键步骤抛异常即不置 `migration_done`，下次启动重试」+「目标已存在则跳过」，以及 `DataCache` 的 `.tmp`+rename。）

### 6.2 触发时机（三端各一）

| 端 | 触发 | 依据 |
|---|---|---|
| Android | `XjtuApp.attachBaseContext`（早于所有 ContentProvider）里做「档案目录存在 ⇒ 一次性导入」的检查；导出走设置页一个动作按钮 | 与 `DataCache.clearIfPackageChanged` 同一位置同一理由（`DataCache.kt:87-114` 的 KDoc 解释了为什么必须在这里） |
| 桌面 Linux/Windows | 启动时扫数据根里有没有 `<archive>` 落地目录；导出手动动作 | 桌面已有 `dataRootOverride` 的测试隔离口径（`SecureStore.jvm.kt:27`），迁移也要走它 |
| web | 页面加载时检查「有没有待导入的档案」（D9 之后 = serve 侧）；导出走下载 | 浏览器无 FS，见未决 #4 |

### 6.3 逐族迁移规则

| 旧形状 | 新形状（档案） | 迁移要点 |
|---|---|---|
| Android SharedPreferences `<store>.xml` | `stores/<store>.json` | 按 XML 节点类型映射 `t`（`string/int/long/float/boolean/set`），**不许丢 float/long**（`card_today_*` 是 float、`last_auto_update_check_at` 是 long） |
| Android `EncryptedSharedPreferences` | 同上（默认**不导出**，见 §9 未决 #5） | 密文文件的键名也是加密的，只有 `SecurePrefs.open` 能读 ⇒ 必须走 `prefs.all`，不能解析 XML |
| 桌面 `<store>.properties` | `stores/<store>.json` | 只有字符串；`b`/`i`/`l`/`f` 靠 `toBooleanStrictOrNull`/`toIntOrNull`/`toLongOrNull`/`toFloatOrNull` 反推（与 `SecureStore.jvm.kt:78,81` 同口径）；`set` 靠 `\u0000` 分隔 |
| web `localStorage` | 同上 | `b`→`"true"/"false"`；`i`→十进制；`set`→逗号 split（照 `KeyValueStore.wasmJs.kt`） |
| Android `cache/data_cache<suffix>/<key>.json` | `files/schedule/<key>.json` | mtime → `at`；`.tmp` 残片要忽略 |
| 桌面/web `<key>#written_at` | 折进 `at` | 见 §5.2 第 5 条 |
| Room 三张表 | `files/{courses,media,jiaocai}/*.json` | 只读表 → 写 JSON；**不许改库版本/表结构**（§8.3） |
| `agent_sessions<suffix>/` | `files/agent_sessions/*` | 整目录搬；`index.json` 与 `convo_*.json` 名字不变 |
| `custom_courses` KV（桌面/web 单键） | `files/courses/custom_courses.json` | 拆成行数组（与 Room 同形），`id` 保持原值（`KeyValueCustomCourseStore` 的 `id = maxOf(id)+1` 口径必须保留，否则两端的 id 会重号） |

### 6.4 冲突与合并（导入时）

- 档案与本端**都有的键**：按 `at` 新者胜；任一方缺 `at` ⇒ **本端胜**（移植时不动本机已有偏好，这比「档案无条件覆盖」更安全，也更符合「旧形状只读」）。
- 本端有、档案没有：**保持不变**（这是「不丢键」断言 A6 的内容）。
- 档案有、本端没有：写入。
- `manifest.version` 不认识 / `format` 不对 / 缺少 `manifest.json`：**明确失败**，端内零改动。
- `set` 值：并集还是覆盖？**覆盖**（同 `at` 规则）—— 并集会让「取消收藏」永远无法同步。

---

## 7. 「档案可直接拷过去用」的验收判据（可自动化断言）

| # | 断言 | 怎么钉 |
|---|---|---|
| **A1** | **键集合相等**：对每个 store、每个 suffix，`档案.keys == 端内 store 实际键集合`（含 `app_settings` 的**全体键**，不只 `SettingsStore` 那 10 个） | `:core` commonTest 一份夹具 + 三端各自跑 |
| **A2** | **值逐字相等**：string `==`；bool/int/long/float `==`；set 按**元素集合**比（不比顺序）；`set` 空数组 ≠ 键不存在 | 同上 |
| **A3** | **导出字节稳定**：注入固定时钟后 `export(x)` 两次字节相同（`keys`/`set`/`stores[]`/`files[]` 已排序） | `:core` jvmTest（注入 now） |
| **A4** | **往返可读**：`import(export(端A))` 之后，端 A 用**原来的读法**读出的每条与导出前逐字相同（例：`SettingsStore().darkMode`、`ScheduleCache.readCurrentTerm(cache)`、`LibraryFavorites.all()`） | 三端各自的门禁 |
| **A5** | **跨端互换**：**同一份档案夹具**在三端各 `import` 一次，各自断言同一批值（这是 D14「存档层互认测试」的落点） | 夹具放 `:core` 的 commonTest（**不能放 `:testkit`** —— 那个模块是 `kotlin.jvm` 插件，wasm 消费不了，见 `testkit/build.gradle.kts`） |
| **A6** | **不丢键 + 不覆盖本机**：导入前后「端内键集合 ⊇ 档案键集合」，且「档案没有、端内有」的键**值不变** | 合并规则的单测 |
| **A7** | **坏档案不动端**：空目录 / 缺 manifest / `version=99` / JSON 截断 ⇒ 返回明确失败，且端内所有 store 的键集合与值**零变化** | 表驱动用例 |
| **A8** | **凭据默认不出现**：档案里不含 `xjtu_credentials` / `cookies_*` / `sites_*` / `agent_config_secure*` / `github_community` / `serve` 的条目（除非显式开关，此时 `redacted` 字段为空、且有一条 warn 日志） | 对 `export()` 的产物做「禁止路径」断言 |
| **A9** | **后缀规则逐字**：`suffixFor(null)=="default"`、`suffixFor("")=="_"`、`suffixFor("2253415556")=="_2253415556"`、`suffixFor("2-2534")=="_2_2534"`、`suffixFor("a-b")==suffixFor("a_b")`（碰撞必须被**检测并报错**而不是合并） | `:core` 纯函数用例 |
| **A10** | **`#written_at` 归一化**：档案的 `keys` 里**永不出现** `*#written_at`；该时间出现在 `at`；导回桌面/web 后 `KeyValueScheduleCacheStore.ageMsOfJson` 能算出同一个 age | `:core` + 桌面 |
| **A11** | **桌面的断言对象只能是「解析后的键值」** —— `.properties` 的首行注释带时间戳，**字节不可比**（本机实测：`#Wed Oct 14 08:10:56 CST 2026`）；`Properties.store` 还把所有非 ASCII 转成 `\uXXXX`（`DesktopScheduleJvmTest` 已有一条注释钉着这点） | 桌面门禁的写法约束 |
| **A12** | **`:app:testDebugUnitTest` 通过数 ≥ 429**（基线见 `docs/desktop-port-plan.md` §14.5/§14.6 的 429 例 / 0 失败） | 现有门禁，不需新写 |
| **A13** | **桌面「档案→数据根」的映射是纯字符串拼接**：`accounts/_2253415556/stores/schedule_cache.json` ⇄ `schedule_cache_2253415556.properties`（可用一次 `cp -r` + import + 读值三连来端到端钉） | 桌面 jvmTest（`dataRootOverride`） |

---

## 8. 与 `:app` 红线的冲突点（逐条，含「哪里只能读不能写、为什么」）

**红线原文**：`docs/desktop-port-plan.md` §16.0 D7-口径「基准是 Android 现有行为」· §16.1 D8「Android 内部仍走 SharedPreferences/Room（不动 C1 红线：`:app` 429 条一条不减、行为逐字不变）」· §16.2 第 4 条「数字回归网只增不减」· §16.4 第 1 条。

| # | 冲突点 | 只能读不能写的是什么 | 为什么 |
|---|---|---|---|
| 8.1 | **Android 的 store 名 / 键名 / 值类型一个字不能改** | 迁移代码只能**读** `SharedPreferences`（`prefs.all`）与 **写回同一批键**；不许新建 archive 专属 prefs 文件、不许改名 | 改名 = 老用户数据读不出来（`SecureStore.kt` / `AccountStore` 的 KDoc 反复写「改了所有人要重登」） |
| 8.2 | **「旧形状只读」在 Android 上不可实现** | 一旦让 `<store>.xml` 只读，Android 行为就变了（今天的写路径全部落在这些文件上） | D8 与 §16.4 第 3 条的**内在矛盾**。可行替代表述：**「键名逐字」+「档案是端内 store 的一个投影」**，而不是「文件形状只读」。必须回用户拍板 —— 未决问题 #2 |
| 8.3 | **Room 库版本（v7）与三张表结构不能动** | 档案对 `custom_courses`/`download_tasks`/`jiaocai1_shelf` 的表示只能是**行数组 JSON**；不许加表、不许加列（`accountId` 那一列的回填是 `AccountMigration` 的既有动作，只能读它的结果） | `AppDatabase.kt:16-24`；加表加列会牵动 `MIGRATION_*` 与 `SerializableCompanionGuardTest` 式的一致性约束 |
| 8.4 | **`getStringSet` 的 `ClassCastException` 语义** | 档案里 `set` 是数组，但写回 Android **必须** `putStringSet`（不能图省事写成逗号串） | `VenueFavorites` 的 KDoc 明写「用 `getString` 去读一个集合键会直接 `ClassCastException`」。§3.5① 那两个写者正是靠「不混端」才没炸 |
| 8.5 | **`app_settings` 的双写者** | 档案导出必须读**全体键**；导入时**不许**在 Android 上把 `app_settings` 拆成两个文件 | `SettingsStore.kt` 类 KDoc；拆文件 = `AppearanceSettings` 的 `OnSharedPreferenceChangeListener` 失效 + 老数据分裂 |
| 8.6 | **同一键的跨端类型不一致已存在**（`rsa_key_time`：`:app` 读 `getLong`，桌面写字符串） | 导入端必须按**声明类型**落地，并在类型不匹配时按目标端的读法容错（`getString/toIntOrNull/toBooleanStrictOrNull`），**绝不抛** | 一抛就等于「这份档案导入失败」，而用户只想知道「为什么密码没带过来」 |
| 8.7 | **`DataCache.clearIfPackageChanged` 会在换包时删掉整个 `data_cache*`** | 课表族在档案里必须标成**可重建**，且**不得**作为「档案完整」的判据 | `DataCache.kt:87-114` 的 KDoc 解释了为什么换包必清（旧 Gson 的字段名被 R8 混淆过）。把缓存当用户数据 ⇒ 升级后档案「看起来缺了东西」 |
| 8.8 | **`KeyValueScheduleCacheStore` 没有原子写** | 档案的课表族文件落地必须先写临时文件再换名（**新代码**可以做原子，Android 的旧实现不动） | 该类的 KDoc 如实声明降级；档案是新代码，没有理由继承这个降级 |
| 8.9 | **`:app` 429 条测试一条不许减** | 新代码只能进 `:core`/`:data`/新模块；`:app` 侧只允许加接线点（一次 `archiveExport/import` 调用），**不许改任何现有断言** | §16.2 第 4 条 + §14.5 的 429 例基线 |
| 8.10 | **DPAPI 信封跨机不可解是设计如此** | 档案默认**不导出**凭据档（否则 Windows→Windows 也是 `SecretProtectionException`，Linux→Linux 反而明文满天飞） | `ProtectedKeyValueStore` / `SecretProtector` 的 KDoc：换用户/换机器/跨平台复制会**明确报错**，这是保护本身在生效，「请重新登录」 |

---

## 9. 未决问题（需用户或后续实现拍板；附我的建议与理由）

1. **档案容器：JSON 目录 vs `.properties` 目录。**
   - 我按 D8 原文「**JSON 形状**」+ 本任务要求「逐文件 JSON schema」选了 **JSON 目录**。
   - 张力：这样「落盘形状也统一」只统一到**档案层**，桌面数据根仍不是档案本体（拷过去要跑一次 import）。
   - 若用户坚持字面全等：档案改成 `<store>.properties`，桌面数据根**直接就是档案的一个视图**（`cp -r` 零步骤）。代价：**wasm 没有 `java.util.Properties`**，必须在 commonMain 重写它的转义规则（`\uXXXX`、ISO-8859-1、行续接、`= : # !` 与首部空格的转义），并与 JVM `Properties.store/load` 逐字节往返一致 —— 这是一份新增的易错代码，且值类型标签仍要另外解决（Properties 只有字符串）。**建议：JSON。**
2. **「旧形状只读、新形状写」在 Android 上做不到**（§8.2）。**建议改口径为**：「端内 store 的**键名与值语义**逐字不变；档案是它的投影；三端**新读取方**一律只读档案或读同一批键名」。这既保住 C1，又保住 D8 的实际目标（跨端互认）。
3. **Android 的档案落地通道未定**（SAF 选择器 / `adb push` 到 `filesDir/archive/` / 分享出 zip）。**建议**：落地目录 `filesDir/archive/`（与 `dataFilesDir()` 同根，零新权限），导入用一次 SAF 读取或 adb push；导出用现有分享缝（`Share.android.kt`）发 zip。这需要一次「权限/体验」确认，我没核到任何既有实现。
4. **web 的档案落地与账号归属**：浏览器无 FS（只能 download/upload），且 **web 今天没有账号**（`WebScheduleHost.kt:38-40`）。**建议**：D9 落地前，web 导出的档案只写 `global/` + `manifest.noAccount=true`；D9 之后 web 的 suffix 取 **serve 进程当前账号**（会话在服务端，浏览器只发消息 —— 与 D10 的屁岱口径一致）。
5. **凭据是否进档案**（`xjtu_credentials` / `cookies_*` / `sites_*` / `agent_config_secure.api_key` / `github_community.token` / `serve.access_token`）。**建议默认不导出**，理由见 §8.10；要导出必须显式开关 + 明确警告 + 写进 `manifest.redacted`。**这条要不要请示用户，取决于用户对「拷过去就能用」的期望里包不包含「不用重新登录」** —— 我认为包不包含都很重要，所以列为未决而不是直接决定。
6. **隐私口径**：档案里 `accountId`（学号）/`accounts[].password`（若不导出凭据则无）是明文。**建议不加密**（加密了另两端读不了），但在 `manifest` 显式写出、并在 UI 上提示「这份档案里含学号」。
7. **TTL 是否进 manifest**。**建议只作提示字段**，不作为裁决依据（§5.2 第 2 条）—— 否则同一份数据换端会被另一个 TTL 判死，反而破坏「档案可直接拷过去用」。
8. **`schedule_cache` 在 web 上要不要加后缀**。**建议加**（对齐 Android/桌面），但只能在 D9 之后做（web 没有账号，suffix 只能来自 serve）。加之前必须处理「已有的无后缀 localStorage 数据」→ 一次性读入（旧键只读）。
9. **导入是合并还是替换**。**建议合并**（§6.4：`at` 新者胜，缺 `at` 本端胜，`set` 覆盖），并把「替换」留成显式选项（`mode=replace`），因为替换会静默删掉用户在本机新加的收藏/日程。
10. **未核对项**（不要当结论用）：`score_cursor<suffix>` 的键、`schedule_diff<suffix>` 的 `snap_*` 全键、`course_colors<suffix>` 的键形态、`home_usage` 的键、`pidai_appearance` 的完整键集、`schedule_widget_prefs` 的键、`empty_room_cache<suffix>` 的全部业务键名（我只核到机制：`<key>` + `<key>_time`）。核对方法：按 §3.2 表的文件路径逐个 `grep -n "put[A-Z]\|get[A-Z]"`。

### 与并行会话（`sa-1e6c6838`）的协调说明

我**没有**在这个 checkout 里发现它写的任何文件：`git status --porcelain` 只有 ` M docs/desktop-port-plan.md`（且该文件被任务禁止我碰，我全程只读）。另有一个 worktree `/mnt/data/agent-verify`（detached HEAD `223e5b75`），但那是**更早的提交**上的、与「凭据迁移」无关的改动（agent 相关搬迁），不是 `sa-1e6c6838` 的产物。⇒ **请主代理在派活前确认它要写的路径**；本规格可能被它侵占的路径是：`data/src/commonMain/.../platform/SecureStore.kt`（凭据缝的 KDoc/语义）、`data/src/jvmMain/.../platform/{SecureStore.jvm.kt,CredentialStore.jvm.kt,ProtectedKeyValueStore.kt}`、`docs/windows-delivery.md` 的 §5（DPAPI 那一节）。若它要动这几处，本规格的 §8.10 / §3.2 第 2 项必须由它一并更新。

---

## 10. 我实际读过的文件（证据清单）

**缝与平台 actual**
- `core/src/commonMain/kotlin/com/xjtu/toolbox/platform/KeyValueStore.kt` · `core/src/androidMain/.../KeyValueStore.android.kt` · `core/src/jvmMain/.../KeyValueStore.jvm.kt` · `core/src/wasmJsMain/.../KeyValueStore.wasmJs.kt`
- `data/src/commonMain/kotlin/com/xjtu/toolbox/platform/SecureStore.kt` · `data/src/jvmMain/.../SecureStore.jvm.kt` · `data/src/androidMain/.../SecureStore.android.kt`
- `data/src/commonMain/kotlin/com/xjtu/toolbox/platform/DataFiles.kt` · `data/src/jvmMain/.../DataFiles.jvm.kt`
- `data/src/jvmMain/kotlin/com/xjtu/toolbox/platform/ProtectedKeyValueStore.kt` · `.../SecretProtector.kt` · `.../CredentialStore.jvm.kt`
- `data/src/androidMain/kotlin/com/xjtu/toolbox/data/SecurePrefs.kt`

**账号 / 会话 / 凭据**
- `core/src/commonMain/kotlin/com/xjtu/toolbox/account/AccountContext.kt`
- `core/src/commonMain/kotlin/com/xjtu/toolbox/auth/AccessMode.kt`
- `data/src/commonMain/kotlin/com/xjtu/toolbox/account/AccountStore.kt` · `data/src/commonMain/.../auth/SessionBackend.kt` · `.../auth/SiteSnapshots.kt` · `.../network/PersistentCookieJar.kt` · `.../community/SecureGithubSession.kt`
- `app/src/main/java/com/xjtu/toolbox/data/CredentialStore.kt` · `app/src/main/java/com/xjtu/toolbox/account/AccountMigration.kt`
- `server/src/main/kotlin/com/xjtu/toolbox/server/AccessToken.kt` · `server/src/main/kotlin/com/xjtu/toolbox/server/ServeSession.kt`（前 120 行）

**设置 / 课表 / 日程**
- `core/src/commonMain/kotlin/com/xjtu/toolbox/settings/SettingsStore.kt` · `core/src/commonMain/kotlin/com/xjtu/toolbox/schedule/{ScheduleCache.kt,ScheduleCacheStore.kt,KeyValueScheduleCacheStore.kt,CustomCourseStore.kt,ScheduleTermStore.kt,ScheduleDiff.kt,CourseColors.kt}`
- `data/src/commonMain/kotlin/com/xjtu/toolbox/schedule/ScheduleSourceRouter.kt`（130-185 行）· `data/src/commonMain/.../attendance/AttendanceRecordStore.kt`（30-80 行）
- `app/src/main/java/com/xjtu/toolbox/data/DataCache.kt` · `app/src/main/java/com/xjtu/toolbox/data/AppDatabase.kt`

**桌面**
- `desktop/src/main/kotlin/com/xjtu/toolbox/desktop/{DesktopSettings.kt,DesktopAccounts.kt,DesktopScheduleHost.kt}`
- `desktop/src/main/kotlin/com/xjtu/toolbox/desktop/{media/DesktopDownloadTaskStore.kt,jiaocai1/DesktopJiaocai1Storage.kt,score/DesktopScoreReportCache.kt}`（关键行）
- `desktop/src/main/kotlin/com/xjtu/toolbox/desktop/ToolboxDesktopApp.kt`（618/689/759/772/799/951 行附近）
- `desktop/src/test/kotlin/com/xjtu/toolbox/desktop/DesktopScheduleJvmTest.kt`（150-236 行）

**web**
- `web/src/wasmJsMain/kotlin/Main.kt`（1-40 行）· `web/src/wasmJsMain/kotlin/com/xjtu/toolbox/web/{WebScheduleHost.kt,WebScoreReportCache.kt,WebCommunity.kt,WebSettingsHost.kt}` · `.../ToolboxWebApp.kt`（476-486 / 516-560 / 674-700 / 820-826 行）

**`:core` 其余 store**
- `agent/AgentMemory.kt`、`library/{LibraryFavorites.kt,LibraryViewModel.kt}`、`venue/VenueFavorites.kt`、`core/net/{CampusVenueApi.kt,CampusLibraryApi.kt}`、`inbox/InboxModels.kt`、`bulletin/BulletinStore.kt`、`feedback/FeedbackStore.kt`、`game/GameStore.kt`、`ui/Haptics.kt`、`jiaocai1/{Jiaocai1Prefs.kt,Jiaocai1UsageNotice.kt}`、`dormpower/DormPowerStore.kt`、`emptyroom/{EmptyRoomStore.kt,EmptyRoomViewModel.kt}`、`pay/PaymentCodeModels.kt`、`util/JsonExt.kt`、`legal/EulaScreen.kt`（仅 grep 确认无落盘）
- `data/src/commonMain/kotlin/com/xjtu/toolbox/agent/{AgentConfigStore.kt,AgentSessionStore.kt}`
- `core/src/commonMain/kotlin/com/xjtu/toolbox/media/DownloadTaskStore.kt`（1-60 行）

**`:app` 其余 prefs（只经 grep 读键名）**
- `widget/{ScheduleWidgetProvider.kt,NoticeWidgetStore.kt,CampusCardWidgetProvider.kt}`、`notification/{ReminderStore.kt,NoticeWatchStore.kt}`、`card/{CampusCardCache.kt,CampusCardRefresh.kt,AppCampusCardStore.kt}`、`emptyroom/EmptyRoomCache.kt`、`home/{HomeStats.kt,HomeStatsRefresher.kt,ServiceUsageTracker.kt}`、`agent/{ProactiveBubble.kt,PidaiAppearance.kt,AgentTool.kt}`、`error/CrashReporter.kt`、`onboarding/OnboardingStore.kt`、`auth/{CampusProbe.kt,SessionKeepAlive.kt}`、`lms/{LmsDownloadStore.kt,LmsDueStore.kt}`、`nav/AppNavHost.kt`

**文档**
- `docs/desktop-port-plan.md` §5（92-127 行）、§16（997-1050 行：§16.0/§16.1 D8/D9/D10、§16.2 四条判据、§16.3 施工顺序、§16.4 三条风险）、§14.2/§14.5 的 429 例基线（396/637/714/842/935/952 行）
- `docs/api-contract.md` §3（28-44 行）、§4（44-60 行）、§5.2（171-200 行）、§7（904-944 行）
- `docs/windows-delivery.md` §5.1–§5.4（105-164 行）、§7（192 行）、数据目录（69 / 243-245 行）
- `docs/linux-delivery.md`（435-472 行：数据目录与安装形态）

**实测（只读 shell）**
- `ls -la ~/.local/share/xjtu-toolbox/`（6 个 `.properties`，全部 `-rw-------`）+ 逐文件 `cut -d= -f1` 列键名（未读任何 token 值）
- `git log/status/branch`（确认 HEAD `4d6049b3`、只有 `docs/desktop-port-plan.md` 被改）· `git worktree list` · `find`/`grep` 若干

**未核对**（明确声明，不许当结论）：Android 档案落地通道、web import/export 实现、`score_cursor<suffix>` / `schedule_diff<suffix>` / `course_colors<suffix>` / `home_usage` / `pidai_appearance` / `schedule_widget_prefs` / `empty_room_cache<suffix>` 的**完整键清单**（只核到 store 名与部分键）、`:core` 的 wasm 测试源集是否存在（D14 计划建）、WebView 的 localStorage/IndexedDB 是否也参与账号清场（`DesktopAccounts` 的 KDoc 提到 `:app` 会清 WebView 存储，我未核实现处）。
