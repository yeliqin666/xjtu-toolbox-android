# 岱宗盒子

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-green?logo=android" alt="Android" />
  <img src="https://img.shields.io/badge/platform-Desktop%20%28Win%2FLinux%29-blueviolet" alt="Desktop" />
  <img src="https://img.shields.io/badge/platform-Web%20%28wasm%29-teal" alt="Web" />
  <img src="https://img.shields.io/badge/minSdk-31%20(Android%2012)-blue" alt="minSdk 31" />
  <img src="https://img.shields.io/github/v/release/yeliqin666/xjtu-toolbox-android?label=version&color=orange" alt="version" />
  <img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="GPL-3.0 License" />
</p>

面向西安交通大学学生的校园工具箱（Kotlin Multiplatform：Android 为主，另有桌面端与网页端）。Android 版使用 Kotlin 与 Jetpack Compose 原生开发，便捷使用教务、图书馆、校园卡、思源学堂等学校服务，**不经过任何自建业务中转服务器**——账号凭据与校园数据只在设备和学校系统之间流动。

> [!NOTE]
> 唯一的例外是**小游戏排行榜**这一自建服务：上榜时会把游戏标识、分数、昵称与设备级匿名 ID 传出设备，不涉校园数据，详见文末「使用须知」；服务端源码在 [`server/leaderboard/`](server/leaderboard/)，零第三方依赖。

## 屁岱（AI 助手）

应用内置可自定义名称的校园 AI 助手「**屁岱**」。配置任意兼容 OpenAI API 格式的模型服务（自备 API Key，推荐 `deepseek-flash`）后，可查询课程安排、成绩、空闲教室、校园卡、通知、思源学堂、体测、仲英学辅资料等信息，以自然语言和数据卡片作答。

- 多会话、流式回复，工具结果以卡片展示；查课表、成绩、余额、座位、通知时直接调学校接口
- 联网搜索、读网页，会注明出处；可设闹钟、加日历
- 记住你的偏好（存在本机，聊天时随请求发给模型；设置里可查看和删除）
- 外部链接唤起屁岱只填进输入框，由你决定发不发；回复里的外链图片点了才加载

> [!IMPORTANT]
> 屁岱的请求会把相关校园数据发给你配置的模型服务，请使用可信的服务商，并妥善保管 API Key。

---

## 基础特性

| 模块 | 能力 |
|------|------|
| 界面 | MIUIX 原生 UI：玻璃顶栏 / 底栏、整页转场与形变动画、触感反馈；多套主题与全局深色模式 |
| 大屏适配 | 平板、折叠屏与横屏：首页分栏、多页分屏、二级页限宽 |
| 账号与认证 | CAS 登录、MFA 验证码、多账号隔离、WebVPN 自动切换、登录诊断 |
| 日程 | 多学期课表（节次排版、空闲压缩）、调停补课、考试安排、教材信息、自定义日程、桌面小组件、导出 |
| 成绩与考勤 | 未评教成绩查询、GPA 计算、学籍档案；考勤流水与统计、请假申请 / 审批 / 销假 |
| 教学空间 | 空闲教室查询、全校课程检索、教师信息 |
| 校园生活 | 校园卡、付款码、加餐券、体育场馆预订 |
| 图书馆 | 三校区座位查询、空位推荐、预约、换座与签到 |
| 思源学堂 | 课程、活动、作业、评分、课件与附件下载；直播回放观看与下载 |
| 仲英学辅 | 资料检索、目录浏览与文件下载 |
| 校园信息 | 教务处 / 学院通知聚合与站内搜索、校园黄页、体测成绩、电子教材、校历 |
| 评教 | GSTE 与常规评教（即将更换新系统） |
| 课余 | 合成仙交大、GPA 2048 等小游戏与棋类，支持联机对弈，分数类小游戏有全服排行榜；课表匹配交友 |
| 社区 | 基于本仓库 GitHub Discussions 的原生论坛：分区浏览、楼层与楼中楼、@ 提及、点赞、采纳答案 |

多账号下，各账号的课表、成绩、对话与校园卡数据相互隔离。

---

## 技术栈

- Kotlin、Jetpack Compose、[MIUIX](https://github.com/compose-miuix-ui/miuix)（[版本策略](docs/MIUIX-reference.md)）
- OkHttp、Ktor、Brotli、Jsoup、kotlinx.serialization
- [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)、Coil（社区的 Markdown 与图片）
- Room、KSP、Kotlin Coroutines
- Android Gradle Plugin 9、Gradle 9、JDK 21
- `minSdk 31`、`targetSdk 36`、`compileSdk 37`

### 模块划分（Kotlin Multiplatform）

完整清单与文件数见 [`docs/kmp-module-map.md`](docs/kmp-module-map.md)：

| 层 | 模块 | 目标 | 职责 |
|---|---|---|---|
| 平台壳 | `:app` | Android | 宿主：`Context` / Room / 相机 / BLE / 小组件 / 通知；把宿主机能适配成共享层的端口 |
| | `:desktop` | jvm（Compose Desktop） | 桌面窗口壳：jpackage 出包；自己登录（`:shared:data` 会话内核 + 凭据文件），不依赖 campus-api |
| | `:web` | wasmJs（浏览器） | 浏览器外壳，不是独立客户端：渲染共享层的屏，数据经同源 `/api/*` 取自 campus-api 或 serve 模式 |
| | `:server` | jvm | serve 模式：托管 web 页面并提供 `/api/*`（契约见 `docs/api-contract.md`） |
| 共享层 | `:shared:*`（5 个） | 三端（`:shared:data` 仅 Android + jvm） | 模型与 `*Source` 数据接口、路由、MIUIX 主题与通用组件、Ktor 客户端与各 `Campus*Api`、会话内核与各站取数解析 |
| 功能 | `:feature:*`（15 个） | 三端 | 各业务界面独立成模块，互不依赖 |
| 过渡残留 | `:core` / `:data` | — | 拆分后的壳（应用外壳、场馆滑块验证码），聚合转发依赖，待并入共享层后移除 |
| 测试 | `:testkit` | jvm（仅测试） | 假的校园上游（座位系统 + 统一认证 + 校历门户），不进任何交付物 |

分层规矩：`:feature:*` 只依赖 `:shared:{model,navigation,designsystem,network}`；数据接口（`*Source`、`AgentTransport`）放 `:shared:model`，实现由各平台壳注入。serve 模式联调与 wasmJs 便携性检查见 [`docs/kmp-module-map.md`](docs/kmp-module-map.md)「备注」。

---

## 本地构建

### 环境要求

- Android Studio 或 Android SDK（需安装 API 37）
- JDK 21
- Git

### 获取源码

```bash
git clone https://github.com/yeliqin666/xjtu-toolbox-android.git
cd xjtu-toolbox-android
```

### 编译

```bash
./gradlew assembleDebug
```

Windows 下使用 `gradlew.bat assembleDebug`。产物位于 `app/build/outputs/apk/debug/`。

### Release 构建

Release 会启用代码压缩与资源收缩。未提供签名配置时生成未签名产物；如需签名，通过环境变量 `KEYSTORE_PATH`、`STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 提供，或在本地放置被 Git 忽略的 `release.jks` 与 `keystore.properties`。

```bash
./gradlew assembleRelease
```

### 测试

```bash
./gradlew testDebugUnitTest
```

---

## 发版

版本号、分支与发布流程见 [DEVELOP.md](DEVELOP.md)。校园系统临时故障或维护公告：对 `announce` 分支的 `bulletin.json` 提 PR，或开一个「校园系统状态」Issue。

---

## 参与贡献

日常提问、建议和交流可以去 [Discussions](https://github.com/yeliqin666/xjtu-toolbox-android/discussions)，App 里「我的 → 社区讨论」看到的就是这里；确定是 bug 也欢迎直接提 Issue。

欢迎 Issue 和 PR。提问题时请说明应用版本、机型系统、涉及模块与复现步骤；**不要粘贴账号、密码、Cookie 或 API Key**。

参与开发前请先阅读 [DEVELOP.md](DEVELOP.md)，其中规定了版本号、分支、提交与测试要求。学校系统接口变动、维护是功能失效最主要的来源，遇到问题欢迎反馈。

---

## 使用须知

- 本项目仅供学习与个人信息管理使用，请遵守学校各系统的使用规则。
- 学校系统接口可能调整，功能可用性以实际情况为准。
- 请勿高频请求，或尝试绕过验证码、访问控制等安全机制。
- 小游戏排行榜会把够上榜的成绩上传到本项目的自建服务：只有游戏标识、分数、昵称与设备级匿名 ID（`ANDROID_ID` 的哈希，不含学号账号）会离开设备；够不上榜则不会上传，昵称留空即匿名上榜。
- API Key、账号凭据与下载的个人资料均应妥善保管。
- AI 生成内容可能有误，重要信息请以学校官方渠道为准。
- 本项目为学生个人作品，非西安交通大学官方产品，与学校无隶属关系。

---

## 相关项目

- [XJTUToolBox](https://github.com/yan-xiaoo/XJTUToolBox) —— 桌面端仙交百宝箱
- [XJTU-Course-Genius](https://github.com/Hz162/XJTU-Course-Genius)
- [zyxf](https://github.com/Guochaoo/zyxf) —— 仲英学辅资料站
- [Etoile](https://github.com/JoyinJoester/Etoile) —— Android 原生 GitHub 客户端，社区功能的登录与讨论区代码改编自它

---

## 图标与致谢

应用图标使用「交小西」Q 版形象，**图标形象版权归交小西制作组所有**（[B 站主页](https://space.bilibili.com/3494379805215567)），已获得交小西制作组授权。

**本应用为开源项目，不由交小西制作组出品。**

该形象不适用本仓库的 GPL-3.0 许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

## 许可

[GPL-3.0](LICENSE)
