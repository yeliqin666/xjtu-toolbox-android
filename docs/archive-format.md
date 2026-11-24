# 存档格式

设置里的「导出 / 导入存档」在 Android、桌面、Web 三端共用同一种格式，实现在 `feature/settings/.../archive/`。三端各自的落盘容器不统一（Android 是 SharedPreferences + Room + `cache/data_cache*`，桌面是数据根下的 `<store>.properties`，Web 是 `localStorage["xjtu-toolbox.<store>.<key>"]`），统一的是 store 名、键名、值语义和存档这一层。

## 目录布局

```
<archive>/                      # 目录即存档，zip 只是传输形态
  manifest.json
  global/                       # 无账号后缀的 store
    stores/<store>.json
    files/<相对路径>
  accounts/<suffix>/            # suffix = AccountContext.suffixFor(accountId)
    stores/<store>.json
    files/<相对路径>
```

端内名 = `<store>` + 账号后缀；桌面再加 `.properties`，Web 再加 `xjtu-toolbox.` 前缀。例如 `accounts/_2253415556/stores/course_colors.json` ⇄ `course_colors_2253415556.properties`。

账号后缀：`null` → `default`，其余为 `_` + 把非字母数字逐字符换成 `_`（`""` → `_`）。这是多对一映射，`manifest` 记录原始 `accountId`，导入时发现两个账号映射到同一后缀必须报错，不得合并。

## `manifest.json`

| 字段 | 说明 |
|---|---|
| `format` | 固定 `xjtu-toolbox-archive` |
| `version` | 当前为 `1`；不认识就报错，端内不做任何改动 |
| `createdAt` | ISO-8601 带时区 |
| `createdBy` | `android` / `desktop-linux` / `desktop-windows` / `web` / `serve` |
| `appVersion` | 导出端的版本文本 |
| `noAccount` | 可缺省；无账号概念的端导出时为 `true` |
| `accounts[]` | `{accountId, suffix, accountType, lastUsedAt}` |
| `stores[]` / `files[]` | 存档内出现的 store 名与文件相对路径，按码点升序 |
| `redacted[]` | 按策略剔除的条目，导入端据此提示哪些内容没有带过来 |
| `ttlClass` | 可缺省，仅作提示，TTL 由读取方代码决定 |

## `stores/<store>.json`

```json
{
  "format": "xjtu-toolbox/store",
  "version": 1,
  "store": "app_settings",
  "suffix": "",
  "keys": {
    "dark_mode":      { "t": "s", "v": "dark" },
    "favorite_seats": { "t": "set", "v": ["A-1", "B-2"] },
    "last_check_at":  { "t": "l", "v": 1760000000000, "at": 1760000000000 }
  }
}
```

- `t` ∈ `s` / `b` / `i`（int32）/ `l`（int64）/ `f`（float）/ `set`（字符串数组），必须带类型标签，否则 Properties 与 localStorage 无法往返。
- `v` 不可为 `null`：三端写 `null` 都等于删键。空字符串是 `{"t":"s","v":""}`，空 `set` 不等于键不存在。
- `at` 可选，UTC epoch 毫秒，表示最近一次写入。桌面 / Web 的旁挂键 `<key>#written_at` 在存档里一律折进 `at`，不作为独立键出现。
- 输出必须字节稳定：`keys` 按 key 码点升序，`set` 去重升序；注入固定时钟后两次导出字节相同。
- `store`、`suffix` 字段与文件路径一致。

## `files/**`

已经是 `AppJson` 编码的 JSON 原样搬运，不重新建模。

| 路径 | 内容 |
|---|---|
| `files/agent_sessions/index.json`、`convo_<id>.json` | 屁岱会话索引与会话内容 |
| `files/courses/custom_courses.json` | 自建课程行数组，`id` 保持原值 |
| `files/media/download_tasks.json` | 下载任务 |
| `files/jiaocai/jiaocai1_shelf.json` | 教材书架 |
| `files/schedule/<key>.json` | 课表族缓存（课表、考试、教材、学期、开学日等），课表族只走这里，不走 `stores/` |
| `files/cache/empty_room/**` | 空闲教室缓存 |

数组按固定键排序输出，不依赖数据库返回顺序。

## 不进存档的内容

凭据与会话（`xjtu_credentials`、`xjtu_accounts`、`xjtu_cookies`、`cookies_*`、`sites_*`、`agent_config_secure*`、`github_community`）、serve 访问令牌（`serve`）、`*_fallback` 迁移残留、`data_cache_meta`（换包判据），以及 Android `data_cache*` 目录本身（换包即清空，不是用户数据）。

## 导入

默认合并：

- 两边都有的键按 `at` 新者胜，任一方缺 `at` 时本机胜；
- 本机有、存档没有的键保持不变；存档有、本机没有的写入；
- `set` 整体覆盖，不做并集（否则「取消收藏」永远同步不过去）。

用户可在导入时显式选择「以存档为准」：只对存档里出现过的 store 与 `files/` 族生效，删除本机多出的键；存档没出现的 store（含其他账号）一个字不动。上文的凭据类 store 无论如何都不删。覆盖前不自动备份。

导入先在临时位置全量落地并自检，再原子替换；缺 `manifest.json`、`format` 或 `version` 不认识、JSON 截断时报错，端内零改动。

## TTL

存档只存 `at`，不存过期判定；同一份数据被不同端读到时按各自代码里的 TTL 判断。只有墙上时钟可以落盘，负的 age 按 0 处理。各档位：课表族 `forever` / `term`（90 天）、成绩报表 7 天、CDN 空教室 1 天、直查空教室 7 天、座位数 365 天、站点快照 12 小时、反馈回复 6 小时、考勤 2 小时（全量重扫 3 天）、收纳 30 天 / 7 天。

## 测试

跨端互换用同一份存档夹具，三端各导入一次并断言同一批值（Web 侧见 `ArchiveInteropTest`）。另需覆盖：字节稳定、往返可读、坏存档不改端内数据、凭据不出现、后缀规则、`#written_at` 归一化。桌面只能比解析后的键值，`.properties` 首行时间戳注释导致字节不可比。
