# 岱宗盒子

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-green?logo=android" alt="Android" />
  <img src="https://img.shields.io/badge/minSdk-31%20(Android%2012)-blue" alt="minSdk 31" />
  <img src="https://img.shields.io/badge/version-5.0.7-orange" alt="version 5.0.7" />
  <img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="GPL-3.0 License" />
</p>

面向西安交通大学学生的 Android 校园工具箱。使用 Kotlin 与 Jetpack Compose 原生开发，便捷使用教务、图书馆、校园卡、思源学堂等学校服务，**不经过任何自建业务中转服务器**——账号凭据与数据只在设备和学校系统之间流动。

应用内置可自定义名称的校园 AI 助手「**屁岱**」。配置任意兼容 OpenAI API 格式的模型服务后，可查询课程安排、成绩、空闲教室、校园卡、通知、思源学堂、体测、仲英学辅资料等信息，并以自然语言和数据卡片形式作答。

> [!IMPORTANT]
> 屁岱的请求会把相关校园数据发给你配置的模型服务。请使用可信的服务商，并妥善保管 API Key。
> 推荐使用`deepseek-flash`模型。

---

## 基础特性

| 模块 | 能力 |
|------|------|
| 界面UI | MIUIX 原生 UI，玻璃顶栏 / 底栏、整页转场与形变动画、富触感反馈；大卡片 / 彩虹图标主题、跟随系统配色、全局深色模式 |
| 大屏适配 | 平板、折叠屏与横屏全面适配：首页分栏、课表 / 屁岱 / 学辅 / 设置分屏，二级页限宽 |
| 账号与认证 | CAS 登录、MFA验证码、多账号隔离、WebVPN 自动切换、登录诊断与请求限流 |
| 日程 | 多学期课表（节次排版、空闲压缩）、调停补课、考试安排、教材信息、自定义日程、节假日、桌面小组件、自由导出 |
| 成绩与学籍 | 未评教成绩查询、GPA 计算、学籍档案 |
| 考勤 | 流水、统计，请假申请 / 审批 / 撤回 / 销假，课表考勤角标 |
| 教学空间 | 空闲教室查询、全校课程检索、教师信息 |
| 校园生活 | 校园卡、付款码、加餐券、体育场馆预订 |
| 图书馆 | 三校区座位查询、空位推荐、预约、换座、取消与签到 |
| 思源学堂 | 课程、活动、作业、评分、课件与附件查看下载；直播回放观看与下载 |
| 仲英学辅 | 资料检索、目录浏览与文件下载 |
| 校园信息 | 教务处 / 学院通知聚合与站内搜索、校园黄页、体测成绩、电子教材、校历 |
| 评教 | GSTE 与常规评教（即将更换新系统） |
| 课余 | 合成仙交大、GPA 2048 等小游戏与棋类，支持联机对弈；课表匹配交友 |
| 社区 | 基于本仓库 GitHub Discussions 的原生论坛：分区浏览、发帖、楼层与楼中楼、@ 提及、点赞、采纳答案；GitHub 设备码登录 |


多账号下，各账号的课表、成绩、对话与校园卡数据相互隔离。

---

## 屁岱（AI 助手）

屁岱查课表、成绩、余额、座位、通知等等时直接调学校接口。

支持 DeepSeek、OpenAI 或任意 OpenAI 兼容接口，需自备 API Key。

- 多会话、流式回复，工具结果卡片展示
- 联网搜索、读网页，会注明出处
- 设闹钟、加日历
- 记住你的偏好
- 每类工具都能在设置里单独关掉

---

## 技术栈

- Kotlin、Jetpack Compose、[MIUIX](https://github.com/miuix-kotlin-multiplatform/miuix)
- OkHttp、Brotli、Jsoup、Gson
- [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)、Coil（社区的 Markdown 与图片）
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

`miuix-ref` 不是子模块，需要单独 Clone ，否则 Gradle 同步会因找不到 composite build 而失败。

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

发版前须同步更新：

1. `app/build.gradle.kts` 的 `versionName` 与 `versionCode`
2. `app/src/main/java/com/xjtu/toolbox/util/AppChangelog.kt` 最前面追加对应版本条目（编译期校验）
3. 如有必要，更新本文件的版本徽章

`AppChangelog.kt` 中 `issues` 字段写的是**发版时仍然存在的问题**。

校园系统临时故障或维护公告：对 `announce` 分支的 `bulletin.json` 提 PR，或直接开一个「校园系统状态」Issue。

---

## 参与贡献

日常提问、建议和交流可以去 [Discussions](https://github.com/yeliqin666/xjtu-toolbox-android/discussions)，App 里「我的 → 社区讨论」看到的就是这里；确定是 bug 也欢迎直接提 Issue。

欢迎 Issue 和 PR。提问题时请说明应用版本、机型系统、涉及模块与复现步骤；**不要粘贴账号、密码、Cookie 或 API Key**。

学校系统接口变动、系统维护是功能失效最主要的来源。如果你发现问题，欢迎直接提 issue。

---

## 使用须知

- 本项目仅供学习与个人信息管理使用，请遵守学校各系统的使用规则。
- 学校系统接口可能调整，功能可用性以实际情况为准。
- 请勿高频请求，或尝试绕过验证码、访问控制等安全机制。
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

## 许可

[GPL-3.0](LICENSE)
