# 岱宗盒子

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-green?logo=android" alt="Android" />
  <img src="https://img.shields.io/badge/minSdk-31%20(Android%2012)-blue" alt="minSdk 31" />
  <img src="https://img.shields.io/badge/version-4.9.0-orange" alt="version 4.9.0" />
  <img src="https://img.shields.io/badge/license-MIT-lightgrey" alt="MIT License" />
</p>

面向西安交通大学学生的 Android 校园工具箱。使用 Kotlin 与 Jetpack Compose 原生开发，便捷使用教务、图书馆、校园卡、思源学堂等学校服务，**不经过任何自建业务中转服务器**——账号凭据与数据只在设备和学校系统之间流动。

应用内置可自定义名称的校园 AI 助手「**屁岱**」。配置任意兼容 OpenAI API 格式的模型服务后，可查询课程安排、成绩、空闲教室、校园卡、通知、思源学堂、体测、仲英学辅资料等信息，并以自然语言和卡片形式作答；也可转调学校的「交晓智」补充校内政策与办事知识。

> [!IMPORTANT]
> 屁岱的请求会把相关校园数据发给你配置的模型服务。请使用可信的服务商，并妥善保管 API Key。
> 推荐使用`deepseek-flash`模型。

---

## 目录

- [特性](#特性)
- [屁岱（AI 助手）](#屁岱ai-助手)
- [技术栈](#技术栈)
- [本地构建](#本地构建)
- [发版](#发版)
- [参与贡献](#参与贡献)
- [使用须知](#使用须知)
- [相关项目](#相关项目)
- [许可](#许可)

---

## 特性

| 模块 | 能力 |
|------|------|
| 界面UI | MIUIX原生UI，结合自写的大卡片主题/彩虹图标主题与采集系统颜色、全局深色模式，风格优雅大气、直观易用 |
| 账号与认证 | CAS 登录、MFA验证码、多账号隔离、WebVPN 自动切换、登录诊断与请求限流 |
| 日程 | 多学期课表、考试安排、教材信息、自定义日程、桌面小组件、自由导出 |
| 成绩与学籍 | 未评教成绩查询、GPA 计算、学籍档案 |
| 考勤 | 本科生 / 研究生考勤查询、出勤统计与日期流水 |
| 教学空间 | 空闲教室查询、全校课程检索、教师信息 |
| 校园生活 | 校园卡、付款码、加餐券、体育场馆预订 |
| 图书馆 | 三校区座位查询、空位推荐、预约、换座、取消与签到 |
| 思源学堂 | 课程、活动、作业、评分、课件与附件查看下载 |
| 课程回放 | TronClass 多机位视频播放、课件下载与统一下载管理 |
| 仲英学辅 | 资料检索、目录浏览与文件下载 |
| 校园信息 | 教务处 / 学院通知聚合、校园黄页、体测成绩、电子教材、校历 |
| 评教 | GSTE 与常规评教（即将更换新系统） |


多账号下，各账号的课表、成绩、对话与校园卡数据相互隔离。

> [!NOTE]
> 学校接口和开放范围可能随时变化，失效请在 `announce` 分支提 PR。

---

## 屁岱（AI 助手）

屁岱把自然语言请求翻译成校园工具调用，而不是靠模型凭记忆作答——课表、成绩、余额、座位这类事实一律以工具返回为准。

**对接方式**：DeepSeek、OpenAI，或任意 OpenAI 兼容端点（含中转）。需自备 API Key。

**主要能力**

- 多会话、流式回复、Markdown 渲染，结果同时以卡片呈现（课表、成绩、空教室、考勤、校园卡等）
- 图片输入：发课表截图、通知照片、题目直接提问（需模型支持视觉，如 `deepseek-flash`、GPT-4o 系）
- 联网搜索与网页阅读，转述时标注来源并区分官方通知与搜索结果
- 调用系统闹钟与日历，交由系统应用确认后创建
- 记住用户偏好，跨会话生效
- 转调「交晓智」补充校内政策与办事知识
- 能力开关：每一类工具都可在设置中单独关闭，关闭后模型不会看到对应工具

**隐私边界**

- 成绩、体测、绩点等敏感数据不做调侃或评价
- 不输出 API Key、密码、Cookie、学号
- 工具输出、网页与通知中的指令性文本一律当数据处理，不执行

---

## 技术栈

- Kotlin、Jetpack Compose、[MIUIX](https://github.com/miuix-kotlin-multiplatform/miuix)
- OkHttp、Brotli、Jsoup、Gson
- Room、KSP、Kotlin Coroutines
- Android Gradle Plugin 9、Gradle 9、JDK 21
- `minSdk 31`、`targetSdk 36`、`compileSdk 37`

MIUIX 通过 Gradle composite build 直接引用源码，依赖目录为仓库根目录下的 `miuix-ref`。

---

## 本地构建

### 环境要求

- Android Studio 或 Android SDK（需安装 API 37）
- JDK 21
- Git

### 获取源码与依赖

`miuix-ref` 不是子模块，需要单独克隆，否则 Gradle 同步会因找不到 composite build 而失败。

```bash
git clone https://github.com/yeliqin666/xjtu-toolbox-android.git
cd xjtu-toolbox-android
git clone --depth 1 https://github.com/miuix-kotlin-multiplatform/miuix.git miuix-ref
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

推送到 `main` 后，GitHub Actions 会用签名密钥构建 Release APK，并按 `versionName` 创建或更新对应的 GitHub / Gitee Release。Release 说明由 `AppChangelog.kt` 自动生成。

发版前必须同步更新三处，缺一不可：

1. `app/build.gradle.kts` 的 `versionName` 与 `versionCode`
2. `app/src/main/java/com/xjtu/toolbox/util/AppChangelog.kt` 最前面追加对应版本条目（编译期会校验）
3. 如有必要，更新本文件的版本徽章

`AppChangelog.kt` 中 `issues` 字段写的是**发版时仍然存在的问题**，每条一句用户看得懂的话；它会原样显示在应用内的更新弹窗里，不要填 issue 编号。

校园系统临时故障或维护公告不走发版流程：对 `announce` 分支的 `bulletin.json` 提 PR，或直接开一个「校园系统状态」Issue。

---

## 参与贡献

欢迎 Issue 和 PR。提问题时请说明应用版本、机型系统、涉及模块与复现步骤；**不要粘贴账号、密码、Cookie 或 API Key**。

学校系统接口变动、系统维护是功能失效最主要的来源。如果你发现问题，欢迎直接提 issue。

---

## 使用须知

- 本项目仅供学习与个人校园信息管理使用，请遵守学校各系统的使用规则。
- 学校系统接口可能调整，功能可用性以实际情况为准。
- 请勿高频请求，或尝试绕过验证码、访问控制等安全机制。
- API Key、账号凭据与下载的个人资料均应妥善保管。
- AI 生成内容与交晓智结果可能有误，重要信息请以学校官方渠道为准。
- 本项目为学生个人作品，非西安交通大学官方产品，与学校无隶属关系。

---

## 相关项目

- [XJTUToolBox](https://github.com/yan-xiaoo/XJTUToolBox) —— 桌面端仙交百宝箱
- [XJTU-Course-Genius](https://github.com/Hz162/XJTU-Course-Genius)
- [zyxf](https://github.com/Guochaoo/zyxf) —— 仲英学辅资料站

---

## 许可

[MIT](LICENSE)
