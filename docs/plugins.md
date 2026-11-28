# 插件

插件是**一个 zip 包**，里面放清单和（可选的）代码或网页。盒子打开它的时候：

- **dex 插件**：用一个独立的 `DexClassLoader` 把包里 APK 的入口类加载进 App 进程，插件只返回界面描述（`UiNode` 树），宿主用 miuix 自己画；
- **web 插件**：不跑代码，只是"用盒子内置浏览器打开这个网页"；
- **declarative 插件**：暂不支持（解析到就报错）。

插件源码与模板在单独仓库：<https://github.com/xjtu-dzhz/dzhz-plugin-template>（dex）、<https://github.com/xjtu-dzhz/dzhz-plugin-web-template>（web）。两个都是 GitHub 模板仓库，带 Actions 自动构建。

---

## 包格式

```
plugin.zip
├── dzhz.plugin.json     清单
├── dzhz.plugin.sig      对清单『原始字节』的 SHA256withECDSA 签名（Base64）
├── plugin.apk           dex 插件的代码（路径由 dex.apk 指定）
└── ...                  files[] 里声明过的其他文件
```

清单示例（dex）：

```json
{
  "manifestVersion": 1,
  "id": "top.dzhz.plugin.sample",
  "name": "示例插件",
  "summary": "一句话简介，列表里最多三行",
  "version": "1.0.0",
  "versionCode": 12,
  "minAppVersionCode": 101,
  "author": { "name": "Hz6826", "url": "https://github.com/Hz6826" },
  "authorAvatar": "",
  "projectUrl": "",
  "icon": "",
  "type": "dex",
  "apiVersion": 2,
  "dex": {
    "apk": "plugin.apk",
    "entryClass": "top.dzhz.plugin.sample.TemplatePlugin",
    "pages": [
      { "id": "home", "title": "组件一览", "group": "基础", "icon": "calendar", "iconColor": "#315FD4" },
      { "id": "about", "title": "关于", "shortcut": false }
    ],
    "tools": [
      { "name": "echo", "definition": { "type": "function", "function": { "name": "echo", "description": "原样返回" } } }
    ]
  },
  "files": [
    { "path": "plugin.apk", "sha256": "…64 位小写十六进制…", "size": 2538474 }
  ],
  "pubkey": "…ECDSA P-256 公钥，SPKI DER 的 Base64…"
}
```

| 字段 | 说明 |
|---|---|
| `id` | 小写字母开头，2–32 位，可含 `.` `_`。装过之后就是这插件的身份 |
| `name` | 1–14 个字（详情页顶栏放得下） |
| `versionCode` | 每次更新必须变大 |
| `minAppVersionCode` | 需要的最低 App 版本；插件用新组件时要跟着提 |
| `type` | `dex` / `web` / `declarative` |
| `apiVersion` | dex 插件必填，必须等于宿主的 `PluginApi.VERSION`（现在是 2） |
| `files[]` | 包里**所有**可被宿主读取的文件，逐条带 sha256 与大小 |
| `pubkey` | 签名公钥；第一次安装时它的 sha256 被记下来 |

## 校验

安装时按这个顺序过一遍，任何一条不过就整包拒装：

1. 条目 ≤ 64；路径不许绝对路径、`..`、反斜杠、反斜杠；
2. `dzhz.plugin.json` `dzhz.plugin.sig` 必须在根目录；
3. 清单字段校验（id / 名字长度 / versionCode / 页面数 ≤8 / 工具数 ≤16 …）；
4. **验签**：`SHA256withECDSA` 校验清单原始字节；
5. **逐条核对 `files[]`**：存在、大小对、sha256 对；
6. dex 插件还要把包里的 APK 解出来，反射检查入口类实现了 `PluginEntry`。

解压时**再核一次** sha256 和大小（防"校验完再替换"），解压总量上限 32 MB。文件落到 `files/plugins/<id>/<versionCode>/`，先写 `.staging` 再改名，写完 `setReadOnly()`（Android 14+ 要求 dex 所在文件不可写）。

## 信任：TOFU

第一包装进来时记住**公钥 SPKI 的 sha256**。之后：

- 同一个 `id` 的更新，必须用**同一把私钥**签；
- `versionCode` 必须比已装的大；
- 换钥匙只能让用户先卸载再重装。

模板仓库的 `package.py` 首次运行会在 `signing/plugin-ec.pem` 生成一把 P-256 私钥——**那是发布者的身份，丢了就没法发更新**。

---

## dex 插件

插件只需要实现三个接口（`:plugin:api`，编译期依赖宿主提供的那份副本）：

```kotlin
class TemplatePlugin : PluginEntry {
    override fun create(context: PluginContext): Plugin = …
}

class TemplatePage(private val ctx: PluginContext) : PluginPage() {
    override fun id() = "home"
    override fun render(): UiNode = UiColumnNode(children = listOf(…))
    override fun onAction(actionId: String, value: String?) { … }
}
```

要点：

- `render()` 在**主线程**调用，必须快——数据提前取好放字段里；
- 改完状态调 `invalidate()`，宿主会重新要一次树（任意线程都能调）；
- 用户操作只从 `onAction(actionId, value)` 回来：按钮给 `null`，输入框给整段文本，开关给 `"true"` / `"false"`，下拉给选中项的 `key`。

### 界面组件

插件交回的是**界面描述**，不是 Compose。所以深色模式、主题色、字阶、圆角、毛玻璃全都自动跟盒子一致。

| 节点 | 用途 |
|---|---|
| `UiColumnNode(children, spacingDp, alignment)` | 竖排，可设水平对齐 |
| `UiRowNode(children, spacingDp, horizontal, vertical)` | 横排，两端对齐 / 居中 / 靠右 |
| `UiGridNode(columns, children)` | 网格，1–4 列等宽 |
| `UiScrollRowNode(children, spacingDp)` | 横滑一行（子节点按内容宽度，上限 280dp） |
| `UiFlowRowNode(children, spacingDp)` | 流式换行 |
| `UiWeightNode(weight, child)` | Row 里按权重分宽度 |
| `UiCardNode(child, actionId, paddingDp)` | 卡片，可整张点 |
| `UiTextNode(text, style, secondary, maxLines, color, argb, bold, italic, underline, strikeThrough, actionId)` | 文字；给了 `actionId` 默认用主色 |
| `UiRichTextNode(spans, style)` | 混排：一段话里不同样式和链接 |
| `UiButtonNode(label, actionId, style, enabled, fillWidth)` | 按钮（Filled / Tonal / Plain）；`fillWidth = true` 撑满一行 |
| `UiListItemNode(title, subtitle, trailing, actionId)` | 设置页那种行 |
| `UiFieldNode(label, value, actionId, hint)` | 输入框 |
| `UiSelectNode(label, value, options, actionId)` | 下拉单选（≤8 项），回传 `key` |
| `UiSwitchNode(label, checked, actionId, summary, enabled)` | 开关行 |
| `UiKeyValueNode(label, value, secondary)` | 左标签右值 |
| `UiProgressNode(value, label)` | 进度条（数值带动画） |
| `UiBadgeNode(text, tone)` | 徽章（Neutral / Accent / Warning） |
| `UiIconNode(name, sizeDp)` | 图标，名字取自白名单 |
| `UiImageNode(url, heightDp)` | 网络图片（Coil） |
| `UiDividerNode()` / `UiSpacerNode(heightDp)` | 分隔线 / 留白 |
| `UiLoadingNode(text)` / `UiEmptyNode(title, subtitle)` | 加载中 / 空状态 |

> 文字对齐跟着所在 `Column` / `Row` 的水平对齐走：`alignment = End` 的竖排里，长文本也真的靠右排；卡片、网格、横滑行、流式换行内部按左起排。

**字阶**：`Display`（页面大标题）、`Title`（分区）、`Section`（卡片标题）、`Body`（正文）、`Caption`（脚注）。

**颜色**：`UiTextColor.{Default, Secondary, Primary, Success, Warning, Error}`，或者直接给 `argb`（自己适配深浅色）。

**图标白名单**（语义名，不是图标库类名；不在名单里的画灰色占位）：

```
calendar  grade  book  card  bell  school  exam  task  classroom  seat
canteen   bus    doc   download  upload  user  time  star  warning  check
```

### 页面与侧栏目录

清单里一个插件可以有多个页面（≤8），每个页面在插件里是一个 `PluginPage` 实例。宿主按 `pages[]` 生成**右侧目录**：

- 有 `group` 的归到同名的一级分组（可折叠）；
- 没有 `group` 的是顶级条目；
- 点一条 → 宿主打开对应页面（**不进返回栈**），目录自动收起；
- 只有 1 个页面时不显示目录按钮；
- 目录底部是插件信息（版本 + 包名、作者、项目地址 / 插件设置）。

深链：`plugin/<id>` 打开第一页，`plugin/<id>/<pageId>` 直接打开指定页。

### 上首页与搜索（`pages[]` 的三项）

`pages[]` 里每一项还能带三个字段，决定这一页在首页长什么样、能不能放上去：

| 字段 | 默认 | 说明 |
|---|---|---|
| `icon` | 按类型 | 图标名，从宿主白名单里挑：`calendar` `grade` `book` `card` `bell` `school` `exam` `task` `classroom` `seat` `canteen` `bus` `doc` `download` `upload` `user` `time` `star` `warning` `check`。dex 默认拼图、web 默认地球 |
| `iconColor` | 按类型 | `#RRGGBB`。dex 默认 `#F97316`（橙）、web 默认 `#3B82F6`（蓝），和列表里的类型徽章同一色 |
| `shortcut` | `true` | 允不允许放到首页当快捷方式。`false` 时这一页不出现在添加面板、也没有「加到主页」按钮，只能从侧栏进——适合「关于」「设置」 |

**每个页面默认都能上首页**。已安装且启用的插件，页面会进盒子的服务目录：

- 首页最后一组「插件」（2×1 卡片，**大字是页面标题、小字是插件名**）；
- 首页编辑态的「添加卡片」面板——**一个插件一段，段名就是插件名**，内置功能那段不加标题；
- 全局搜索、经典版首页的服务列表（那里插件页单独起一段「插件」）；
- 停用或卸载的插件不进目录；装回去又会回来。

两种加法：

- 插件页右上角，菜单按钮左边有个 **加号**：点一下确认，首页就多一格；已经有了就变成 **对号**，点一下确认移除。只是去掉首页那一格，插件本身和数据都不动。
- 首页编辑态里自己拖 / 从添加面板挑。

**自动只补一次**：第一次遇到能上首页的插件页时，宿主给改过布局的人补一组「插件」（补过记一个标记，用户删掉或搬空都不会再长回来）；之后再装插件就得自己加。

底栏候选暂时不含插件页（设置页那份候选还是静态目录）。

### 后端能力（`PluginContext`）

| API | 说明 |
|---|---|
| `schoolTool(name, argsJson)` | 只读取学校数据，19 个白名单工具（课表、成绩、考试、空教室、图书馆……）。返回 `{"ok":true,"tool":"…","text":"…","widgets":[…]}` |
| `openAsset(name)` | 读插件 APK 里的 `assets/` |
| `openPackageFile(path)` | 读插件包里的文件（相对包根目录） |
| `dataDir()` | 插件自己的可写目录 |
| `notify(title, text)` | 普通通知（不弹窗） |
| `notify(title, text, headsUp = true)` | 悬浮通知（独立的高重要级别渠道） |
| `toast` / `log` | 提示 / 打日志 |
| `isDarkTheme` / `primaryColor` / `appVersionCode` | 宿主状态 |

`schoolTool` 必须在**非主线程**调用。`widgets` 是结构化的卡片（`type` 为 `schedule` / `grades` / `rooms` / `notices` …），画表格用这个，别解析 `text`（那是给小岱看的自然语言）。个别工具不产卡片，那时 `widgets` 是空数组。

写类工具（加日程、设闹钟、改设置、存文件）**不在白名单里**，硬调返回 `not_allowed`。

---

## web 插件

```json
"type": "web",
"web": {
  "startUrl": "https://ehall.xjtu.edu.cn/",
  "origins": ["https://ehall.xjtu.edu.cn"],
  "allowSubdomains": true,
  "capabilities": ["theme"],
  "vpn": "optional_on"
}
```

- 打开时走盒子自己的内置浏览器（缓存、Cookie、夜间模式、下载都和盒子其它网页一致）；
- `origins` 是**主框架导航**白名单，跳到别的主机会被拦下并提示；子资源不受限；
- 白名单空 = 不限制。

**`vpn` 四档**（校内 WebVPN，开了校外也能访问校内站点）：

| 值 | 含义 |
|---|---|
| `required` | 必须走，用户不能关（详情页那行灰掉） |
| `optional_on` | 可选，默认开 |
| `optional_off` | 可选，默认关 |
| `off` | 必须直连，用户不能开 |

走 VPN 时先探测登录态（失效会在 App 内重登），再把地址改写成网关地址。**这时页面全在 `webvpn.xjtu.edu.cn` 下，原始域名被加密在路径里，白名单只能按网关放行**——想严格限制站点就别开 VPN。

---

## 安装与更新

- 装：插件页「在线市场」点一个插件下载；或者右上角「设置」→「从文件安装」，选 zip；
- 更新：同一个 `id`、同一把钥匙、`versionCode` 更大，直接覆盖安装，旧版本目录会被清掉；市场里会标「可更新」；
- 禁用：详情页「启用」关掉，页面与工具都不再加载，但包和设置都留着；
- 卸载：删掉包目录和 `files/plugins/data/<id>`。

工具可以单独控制：详情页「允许小岱调用」进二级页，上面一个总开关，下面每个工具一行（长按看 `definition`）。总开关关掉时单项设置会留着。

工具的 `definition.type` 填 `function`，`definition.function.name` 与清单工具名一致。宿主按插件 ID 和工具名生成模型侧标识，插件仍用清单里的原名实现 `PluginTool.name()`。安装、更新、卸载或修改开关后，小岱在下一轮请求时读取工具目录。

`definition.function.parameters` 可省略。声明时根节点必须是 `type: object`。宿主支持单一 `type`、`properties`、`required`、`additionalProperties`（布尔值或子 schema）、`items`（单一子 schema）、`enum` 和 `const`，可附 `title`、`description`、`default`、`examples` 和 `$comment`。安装时会拒绝其他关键字。调用时将空参数按空对象检查，其他参数必须是 JSON 对象，通过后才调用插件。

DEX 工具随 Android 插件发布。独立部署、需要跨客户端调用的工具可通过[小岱的 MCP 设置](agent.md#mcp)接入。

---

## 在线市场

App 从**索引地址**拉一份目录（默认 `https://dzhz.top/plugin-market.json`，源文件在 `xjtu-dzhz/dzhz-server` 的 `services/web/public/plugin-market.json`；插件设置里可以换成别的地址）。

```json
{
  "schema": 1,
  "updatedAt": "2026-11-26T21:30:00+08:00",
  "tags": ["示例", "模板"],
  "plugins": [
    {
      "id": "top.dzhz.plugin.template",
      "name": "插件模板",
      "summary": "一句话简介",
      "type": "dex",
      "version": "1.0.1",
      "versionCode": 13,
      "minAppVersionCode": 101,
      "author": "Hz6826",
      "authorUrl": "https://github.com/Hz6826",
      "projectUrl": "https://github.com/xjtu-dzhz/dzhz-plugin-template",
      "download": "https://github.com/xjtu-dzhz/dzhz-plugin-template/releases/download/v1.0.1/plugin.zip",
      "mirrors": [],
      "sha256": "…整包 zip 的 64 位小写十六进制…",
      "size": 792344,
      "featured": true,
      "tags": ["示例"],
      "iconName": ""
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `schema` | 目录格式版本，现在是 1；比 App 认识的新就整份拒绝，提示更新 App |
| `tags`（根） | 市场「标签」筛选的选项；不写就从各条目的 `tags` 汇总 |
| `id` `name` `type` `download` | 必填；`type` 只认 `dex` / `web`，`download` 与 `mirrors[]` 必须是 https，缺一条就跳过这一条 |
| `versionCode` | 和包里清单一致；大于已装版本时市场标「可更新」，否则点进去直接到详情页 |
| `minAppVersionCode` | App 版本不够时不下载，提示先更新 App |
| `sha256` | **整包 zip** 的摘要（可带 `sha256:` 前缀，就是 GitHub Release 资产的 digest）；没有就不让装 |
| `mirrors` | 同一个包的备用地址，主地址失败按顺序试 |
| `featured` | 进顶部横滑「精选」 |

安装流程：确认下载 → 下到缓存（`plugin_import_` 前缀，「清空插件缓存」会清）→ 核对整包 sha256 → 走和「从文件安装」相同的验签、`files[]` 核对与 TOFU → 再确认一次 → 落盘。目录只决定「去哪下、下的是不是这个包」，信任仍以包内签名为准。

目录先进内存，再在缓存目录留一份：开着「只在 Wi-Fi 下加载」且不在不计流量的网络上、或者拉取失败时，用上一份并提示「显示的是上次的目录」。

上架 / 更新插件：发 Release 后改 `plugin-market.json` 里那一条的 `version`、`versionCode`、`download`、`sha256`、`size`，推到 dzhz-server 的 main 即自动部署。

---

## 安全模型

- dex 插件和 App **同进程、同 uid**：权限等同 App，能读私有目录、登录会话、本机数据。`PluginContext` 那些接口是「方便」，不是「隔离」；
- 所以只装信任来源的插件，`dump` 类工具跑不出这个前提；
- web 插件不跑代码，只加载网页，风险小得多；但它仍带着登录态访问校内站点。

## 开发

```bash
# dex 插件
git clone https://github.com/xjtu-dzhz/dzhz-plugin-template
./gradlew :plugin:assembleDebug
python3 package.py plugin/build/outputs/apk/debug/plugin-debug.apk

# web 插件
git clone https://github.com/xjtu-dzhz/dzhz-plugin-web-template
python3 package.py
```

产物 zip 直接装进盒子。两个仓库都有 GitHub Actions：push 出 Artifact，手动 `Run workflow` 填版本号会发到 Release。
