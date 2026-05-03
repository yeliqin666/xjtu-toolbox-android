# 设置页 & 更新体系规划

## 一、设置页（SettingsScreen）

独立路由页面，从"我的"Tab 进入（在设置区块顶部加一行入口，替代当前散落在 ProfileTab 中的零碎设置）。

### 1. 外观

| 设置项 | 类型 | 默认值 | 备注 |
|--------|------|--------|------|
| 深色模式 | 三选一 | 跟随系统 | 跟随系统 / 始终浅色 / 始终深色 |
| 底栏风格 | 二选一 | 悬浮胶囊 | 悬浮胶囊 / 经典底栏，热切换无需重启 |
| 默认启动 Tab | 四选一 | 首页 | 首页 / 日程 / 工具 / 我的 |

### 2. 网络

| 设置项 | 类型 | 默认值 | 备注 |
|--------|------|--------|------|
| 连接模式 | 三选一 | 自动检测 | 自动检测 / 强制直连 / 强制 WebVPN |

> 当前自动检测偶尔误判，强制模式是实用兜底。

### 3. 提醒（v2 规划，需 WorkManager）

| 设置项 | 类型 | 默认值 | 备注 |
|--------|------|--------|------|
| 课前提醒 | 开关 + 分钟数 | 关 / 15min | 基于日程数据，本地 Notification |
| 作业截止提醒 | 开关 | 关 | 需 LMS 登录 |
| 考勤打卡提醒 | 开关 | 关 | 基于课程时间 |

### 4. 数据

| 设置项 | 类型 | 备注 |
|--------|------|------|
| 缓存大小 | 只读 | 显示 cacheDir 总大小 |
| 清除缓存 | 按钮 | 清除后 Toast 提示 |
| LMS 下载位置 | 只读 | 显示 Downloads/岱宗盒子 路径 |

### 5. 更新

| 设置项 | 类型 | 默认值 | 备注 |
|--------|------|--------|------|
| 启动时检查更新 | 开关 | 开 | 控制是否在 `onCreate` 自动检查 |
| 更新渠道 | 二选一 | 稳定版 | 稳定版 / 测试版（对应 Gitee `prerelease` 标记） |

### 6. 关于（内嵌或独立子页）

- 版本号（长按复制 `versionName + versionCode + buildType`）
- 更新日志（复用现有 `CHANGELOG_MAP`，折叠展示）
- 开源许可（OssLicensesMenuActivity 或自定义页面）
- 项目主页（GitHub / Gitee 链接）
- 反馈建议（跳转 GitHub Issues）
- 用户协议 & 隐私政策

---

## 二、存储方案

使用已有的 `CredentialStore.appPrefs`（普通 `SharedPreferences`，key = `app_settings`），新增键：

```
nav_bar_style        = "floating" | "classic"       (默认 floating)
dark_mode            = "system" | "light" | "dark"   (默认 system)
default_tab          = "HOME" | "COURSES" | "TOOLS" | "PROFILE" (默认 HOME)
network_mode         = "auto" | "direct" | "vpn"     (默认 auto)
auto_check_update    = true | false                  (默认 true)
update_channel       = "stable" | "beta"             (默认 stable)
```

在 `CredentialStore` 中封装 getter/setter，Compose 端用 `mutableStateOf` 持有，
修改时同步写回 SharedPreferences，UI 即时重组。

---

## 三、更新弹窗 & CI Changelog 改造

### 现状问题

1. **CI 不写 changelog**：GitHub Release 用 `generate_release_notes: true`（只是 commit 列表），Gitee Release body 写死 "同步自 GitHub Release"。用户在 App 里看不到有意义的更新说明。
2. **App 无更新弹窗**：目前只在"关于"区域手动点击检查更新，没有启动时自动弹窗告知新版本变化。
3. **App 内 changelog 是硬编码**：`CHANGELOG_MAP` 写在 `MainActivity.kt` 里，每次发版需手动维护，且 CI 无法引用。

### 改造方案

#### A. Changelog 统一数据源

在项目根目录维护 `CHANGELOG.md`，格式约定：

```markdown
## 3.2.0
- 🎨 新增设置页：深色模式、底栏风格、启动 Tab 等
- 🔔 新增启动时自动检查更新 + 更新弹窗
- 🔧 修复 Gitee APK 上传超时导致构建失败

## 3.1.0
- 💳 校园卡迁移至新平台 ...
```

#### B. CI 读取 changelog 写入 Release body

修改 `build.yml`：
1. 从 `CHANGELOG.md` 提取当前版本的段落
2. 写入 GitHub Release 的 `body` 参数（替代 `generate_release_notes`）
3. 同步写入 Gitee Release 的 `body` 字段

```yaml
- name: Extract changelog
  id: changelog
  run: |
    VERSION="${{ steps.version.outputs.version }}"
    # 提取 ## $VERSION 到下一个 ## 之间的内容
    BODY=$(sed -n "/^## ${VERSION}$/,/^## /{ /^## ${VERSION}$/d; /^## /d; p }" CHANGELOG.md)
    echo "body<<EOF" >> $GITHUB_OUTPUT
    echo "$BODY" >> $GITHUB_OUTPUT
    echo "EOF" >> $GITHUB_OUTPUT
```

#### C. App 启动时自动检查 + 更新弹窗

在 `MainScreen` 的 `LaunchedEffect(Unit)` 中：
1. 读取 `auto_check_update` 设置，若为 true 则后台检查 Gitee latest release
2. 检测到新版本时，弹出 `SuperBottomSheet`，内容包含：
   - 新版本号
   - **Release body**（即 changelog，从 Gitee API 的 `body` 字段获取）
   - 下载按钮 / 查看按钮
3. 用 `isUpdateNoticeSeen(version)` 控制同一版本只弹一次

#### D. App 内 changelog 双轨

- **在线优先**：从 Release body 获取（Markdown 渲染）
- **离线兜底**：保留 `CHANGELOG_MAP` 硬编码（冷启动无网时可用）
- 长期目标：CI 构建时自动从 `CHANGELOG.md` 生成 Kotlin 代码到 `BuildConfig`，废弃手动维护

---

## 四、"我的"页调整

当前"我的"页底部有：设置区块（网络模式 + 退出登录）、关于区域、开源社区卡片。

改造后：
- 设置区块 → 改为**一行入口**（"设置"，右箭头，点击进入 SettingsScreen）
- 退出登录 → 保留在"我的"页（高频操作不必藏进设置）
- 关于区域 + 检查更新 + 开源社区 → **迁移到设置页的"关于"区块**
- "我的"页底部更干净，只留：设置入口 + 退出登录

---

## 五、实现优先级

### P0（第一版，立即做）✅ 已完成 (2026-04-29)
1. ✅ 创建 `SettingsScreen.kt`，注册路由 → `ui/settings/SettingsScreen.kt` + `Routes.SETTINGS`
2. ✅ 深色模式切换（三选一）→ `Theme.kt` 支持 `darkModeOverride` 参数
3. ✅ 底栏风格切换（热切换）→ `MainScreen` 根据 `credentialStore.navBarStyle` 动态选择 `FloatingNavigationBar` / `NavigationBar`
4. ✅ 默认启动 Tab → `MainScreen` 初始化 `selectedTabOrdinal` 时读取 `credentialStore.defaultTab`
5. ✅ 清除缓存 → `SettingsScreen` 数据分组，含 `cacheDir.deleteRecursively()`
6. ✅ 关于子页（版本号 + changelog + 链接）→ `SettingsScreen` 关于分组 + `ChangelogSheet` + `EulaSheet`
7. ✅ 把"关于"和"检查更新"从 ProfileTab 迁移到 SettingsScreen

### P1（跟进）✅ 已完成 (2026-04-29)
8. ✅ 创建 `CHANGELOG.md` → 根目录 `CHANGELOG.md`（CI 读取改造待后续）
9. ✅ 启动时自动检查更新 + 弹窗（含 changelog 展示）→ `AppNavigation` 中 `LaunchedEffect` + `AutoUpdateDialog`
10. ✅ 网络模式切换（auto / direct / vpn）→ `SettingsScreen` 网络分组（UI 已实现，后端强制模式逻辑待补）

### P2（后续）
11. ⏳ 提醒系统（WorkManager）
12. ✅ 更新渠道选择（stable / beta）→ `SettingsScreen` 更新分组
13. ⏳ 开源许可页面（OssLicensesMenuActivity）
14. ⏳ CI changelog 改造（需修改 `.github/workflows/build.yml`）
15. ⏳ 校园黄页（新功能，需抓取 API）
16. ⏳ 校历数据源迁移

---

## 八、WebVPN MFA 问题（新增 - 2026-04-29）

### 现状
WebVPN 已切换为新版 CAS 登录页，加载了 MFA 微前端（`mf/newCenter/remoteEntry.js`、`mfa.css`），
CAS 服务端根据风控策略决定是否触发手机验证码。

当前 `loginWebVpn()` 实现使用 **全新 OkHttpClient**（`existingClient=null`），不带 TGC cookie，
导致每次必然走完整 CAS 登录流程，高概率触发 MFA。而 `loginWebVpn()` 的 `REQUIRE_MFA` 分支直接返回 false，
无 UI 交互能力（后台静默调用），因此 WebVPN 登录实际上已不可用。

### 解决思路
**复用 sharedClient 的 TGC cookie 走 SSO 免登**：
- JWXT 登录先于 WebVPN 登录完成，此时 `sharedClient` 已有有效的 TGC cookie
- 用 `sharedClient` 请求 `https://webvpn.xjtu.edu.cn/login?cas_login=true`
- CAS 看到 TGC → 直接 302 带回 ST ticket → WebVPN 验证 ticket → 下发 WEngine_vpn_cookie
- 无需表单提交，绕开 MFA

### HAR 验证
`新webvpn登录.har` 中 POST 到 CAS 的请求体不含 username/password（仅含 `secState`、`execution`、`fpVisitorId`），
说明浏览器已有 TGC，CAS 通过 secState 机制完成了无感 SSO。

### 实现状态
⏳ 待实现：修改 `loginWebVpn()` 优先尝试 TGC SSO

---

## 六、校园黄页（新功能）

### 来源
`https://workflow.xjtu.edu.cn/selectpage/page/site/yellowPage`
新版一网通办平台的校园电话/部门/服务目录，Vue SPA 前端，需要抓取其后端 API。

### 功能规划
- 部门列表 + 搜索（按名称/关键词）
- 电话号码一键拨打
- 常用部门收藏/置顶
- 可能需要统一认证登录（待确认 API 是否需要鉴权）

### 入口
放在"实用工具"Tab，与空闲教室、通知公告并列。

---

## 七、校历数据源迁移

### 现状
当前校历数据来自旧版 EIP 门户：
```
http://one2020.xjtu.edu.cn/EIP/schoolcalendar/terms.htm
```
需要 CAS SSO 建立 JSESSIONID 后 POST 获取。

### 新数据源
`https://workflow.xjtu.edu.cn/selectpage/page/site/calendar`
新版一网通办的校历页面（Vue SPA），后端 API URL 待抓取（可通过浏览器 DevTools Network 面板抓包确认）。

### 迁移计划
1. 抓取 `workflow.xjtu.edu.cn` 校历页面的 XHR 请求，确认 API endpoint 和认证方式
2. 如果新 API 更稳定且数据更完整，迁移 `SchoolCalendarApi.kt` 的 `BASE_URL`
3. 保留旧 API 作为 fallback（双源兜底）

---

## 八、更新弹窗 changelog 累积展示

### 需求
用户可能跳过多个版本升级（如 2.8.0 → 3.2.0），更新弹窗应展示**从上次使用版本到当前版本的全部 changelog**。

### 实现方案
1. 本地记录 `last_seen_version`（写入 `appPrefs`），每次启动时读取
2. 更新弹窗中，从 `CHANGELOG.md`（在线）或 `CHANGELOG_MAP`（离线兜底）中提取：
   `last_seen_version < version <= current_version` 的所有条目
3. 按版本号降序排列，每个版本一个折叠段
4. 用户关闭弹窗后，将 `last_seen_version` 更新为当前版本
5. 在线模式下，从 Gitee Release API 拉取多个 release 的 body 字段（`/repos/{owner}/{repo}/releases`），按 `tag_name` 过滤范围内的版本

---

## 九、交大消息中心（新功能）

### 来源
`https://ywtb.xjtu.edu.cn/main.html#/EventsCenter?pageType=fromNav`
一网通办的个人消息中心，包含 OA 通知、审批结果、系统消息等**需认证**的个人推送。

### 与现有通知功能的区别

| | 现有 NotificationApi | 交大消息中心 |
|---|---|---|
| 数据 | 各学院官网公开公告（爬虫） | 个人 OA/审批/系统消息 |
| 认证 | 无需登录 | 需一网通办 Session |
| 个性化 | 否（全校公告） | 是（与个人相关的消息） |

### 实现方案
1. 抓取 EventsCenter 的 XHR API（Vue SPA，需浏览器 DevTools 抓包）
2. 复用 `YwtbLogin` 的认证 Session 调用 API
3. 新建 `YwtbMessageApi.kt` + `YwtbMessageScreen.kt`
4. 入口：首页消息角标 / "实用工具"Tab / 下拉通知面板

### 优先级
P1 — 比较实用，且认证链路已有（`YwtbLogin`），开发量主要在 API 抓取和 UI。

---

## 十、应用内日程 & 提醒系统

### 需求
当前"日程"实际是课表展示，没有自定义事件和提醒能力。需要一个**应用内**的日程系统：
- ✅ 不依赖系统日历（CalendarProvider）
- ✅ 不往系统备忘录/日历写入事件
- ✅ 能触发系统级通知提醒（Android Notification）
- ✅ 数据完全在 App 内管理
- ✅ API 标准、可扩展

### 技术架构

```
┌─────────────────────────────────────────┐
│  UI 层（Compose）                        │
│  日程列表 / 日历视图 / 事件编辑器        │
└───────────────┬─────────────────────────┘
                │
┌───────────────▼─────────────────────────┐
│  数据层（Room Database）                 │
│  ScheduleEvent 表：                     │
│    id, title, description, startTime,   │
│    endTime, repeatRule, reminderOffsets, │
│    category, courseId(nullable),         │
│    createdAt, updatedAt                 │
│                                         │
│  ReminderRecord 表：                    │
│    id, eventId, triggerTime, fired      │
└───────────────┬─────────────────────────┘
                │
┌───────────────▼─────────────────────────┐
│  调度层（WorkManager / AlarmManager）    │
│  - 事件创建/更新时计算 triggerTime      │
│  - 注册 OneTimeWorkRequest 或精确闹钟   │
│  - 触发时通过 NotificationManager 弹出  │
│  - 支持提前 5/10/15/30/60 分钟提醒     │
└─────────────────────────────────────────┘
```

### 事件来源（自动 + 手动）
1. **课表自动导入** — 从现有课表数据自动生成 ScheduleEvent（category = COURSE）
2. **LMS 作业截止** — 从 LmsActivity 的 endTime 自动生成（category = HOMEWORK）
3. **考勤签到** — 从考勤课程时间自动生成（category = ATTENDANCE）
4. **手动创建** — 用户自定义事件（category = CUSTOM）

### 提醒实现
- **WorkManager**（推荐）：适合"大约在某个时间提醒"，系统可能延迟几分钟，但省电
- **AlarmManager.setExactAndAllowWhileIdle()**：精确到秒，适合考勤打卡这种时间敏感场景
- 混合策略：普通事件用 WorkManager，标记为"精确"的事件用 AlarmManager
- 通知渠道：创建 `schedule_reminder` NotificationChannel，用户可在系统设置中单独控制

### 与系统日历的关系
- 默认不写入系统日历
- 可选"导出到系统日历"功能（用户主动触发，而非自动同步）
- 已有的 `ScheduleExport.kt`（ICS 导出）可复用

### 优先级
P1（核心框架 + 课前提醒）→ P2（作业/考勤自动导入 + 手动事件）

---

## 十一、统一通知中心（架构整合）

### 问题
三套独立的通知来源，各自有独立的 UI、数据模型和刷新机制：

| 来源 | 现有实现 | 认证 | 时效性 | 触达方式 |
|------|---------|------|--------|---------|
| 学院公告 | NotificationApi（爬虫） | 无 | 被动查询 | 用户手动进入查看 |
| 交大消息 | 无（待开发） | YWTB Session | 准实时 | 待定 |
| 日程提醒 | 无（待开发） | 无（本地） | 精确定时 | 系统通知 |

碎片化导致：用户得分别去三个地方看，没有统一入口，也没有未读计数。

### 统一数据模型

```kotlin
// 所有通知的抽象包装
data class UnifiedNotification(
    val id: String,                    // 全局唯一 ID
    val type: NotificationType,        // ANNOUNCEMENT / MESSAGE / REMINDER
    val title: String,
    val summary: String,
    val timestamp: Long,               // 统一时间戳（公告=发布时间，消息=推送时间，提醒=触发时间）
    val source: String,                // "教务处" / "一网通办" / "课前提醒"
    val isRead: Boolean,
    val actionUrl: String? = null,     // 点击跳转（公告=网页链接，消息=详情页路由，提醒=事件详情）
    val priority: Priority = NORMAL    // LOW / NORMAL / HIGH / URGENT
)

enum class NotificationType {
    ANNOUNCEMENT,  // 学院公告（现有 NotificationApi）
    MESSAGE,       // 交大消息（YWTB EventsCenter）
    REMINDER       // 日程提醒（本地触发）
}
```

### 统一 UI

```
┌─────────────────────────────────────┐
│  🔔 通知中心（首页入口，角标未读数） │
├─────────────────────────────────────┤
│  [全部] [消息] [公告] [提醒]  ← 筛选 │
├─────────────────────────────────────┤
│  📌 明天 8:00 高等数学 课前提醒     │ ← REMINDER, HIGH
│  📨 您的请假审批已通过              │ ← MESSAGE
│  📢 教务处: 2026年选课通知          │ ← ANNOUNCEMENT
│  📢 机械学院: 毕业设计答辩安排      │ ← ANNOUNCEMENT
│  ⏰ 今天 23:59 操作系统作业截止     │ ← REMINDER
│  ...                                │
└─────────────────────────────────────┘
```

### 统一数据管道

```
┌──────────────────────────────────────────────┐
│  NotificationRepository（单一数据仓库）        │
│                                              │
│  ┌─────────────┐ ┌──────────────┐ ┌────────┐│
│  │ AnnounceSrc │ │ MessageSrc   │ │ReminderSrc│
│  │ (爬虫,无认证) │ │(YWTB API,认证)│ │(Room,本地)│
│  └──────┬──────┘ └──────┬───────┘ └───┬────┘│
│         │               │             │      │
│         └───────────────┼─────────────┘      │
│                         ▼                    │
│            Room 表: unified_notifications    │
│            (本地缓存 + 去重 + 已读状态)       │
└──────────────────────┬───────────────────────┘
                       │
          ┌────────────▼────────────┐
          │  定时刷新（WorkManager）  │
          │  - 公告: 每 2h 后台拉取  │
          │  - 消息: 每 15min 轮询   │
          │  - 提醒: AlarmManager    │
          │  有新内容 → 系统通知推送  │
          └─────────────────────────┘
```

### 通知渠道（Android NotificationChannel）

| Channel ID | 名称 | 重要性 | 用途 |
|------------|------|--------|------|
| `schedule_reminder` | 日程提醒 | HIGH（弹头、振动） | 课前/作业/考勤 |
| `ywtb_message` | 交大消息 | DEFAULT（通知栏） | OA/审批 |
| `announcement` | 学院公告 | LOW（静默） | 爬虫公告聚合 |

用户可在系统设置中**分别**控制每个渠道的开关、声音、振动。

### 迁移路径（渐进式）

**Phase 1** — 基础设施
- 创建 `UnifiedNotification` 数据模型 + Room 表
- 创建 `NotificationRepository`，先把现有 `NotificationApi` 包装进去
- 首页加通知入口（铃铛图标 + 未读角标）

**Phase 2** — 接入消息源
- 接入交大消息中心（YWTB EventsCenter API）
- 接入日程提醒（Room Event → Reminder 触发）

**Phase 3** — 后台推送
- WorkManager 定时拉取公告 + 消息
- 有新内容时弹系统通知
- 设置页加各渠道开关

### 设置页对应项（回扣第三节"提醒"）

```
提醒与通知
├── 课前提醒        [开关] 提前 [15] 分钟
├── 作业截止提醒    [开关]
├── 考勤打卡提醒    [开关]
├── 交大消息推送    [开关] 轮询间隔 [15min]
├── 学院公告推送    [开关] 关注的学院 [多选]
└── 免打扰时段      [22:00 - 7:00]
```
