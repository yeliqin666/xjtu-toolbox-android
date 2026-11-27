<!--suppress HtmlDeprecatedAttribute -->

<div align="center">

<h1 style="margin-bottom: 2rem;"><img src="docs/icon.png" alt="" height="180px" /> 岱宗盒子 </h1>

[![GitHub License](https://img.shields.io/github/license/xjtu-dzhz/dzhz)](LICENSE)
[![GitHub Issues](https://img.shields.io/github/issues/xjtu-dzhz/dzhz)](https://github.com/xjtu-dzhz/dzhz/issues)
[![GitHub PRs](https://img.shields.io/github/issues-pr/xjtu-dzhz/dzhz)](https://github.com/xjtu-dzhz/dzhz/pulls)
[![CodeFactor](https://www.codefactor.io/repository/github/xjtu-dzhz/dzhz/badge)](https://www.codefactor.io/repository/github/xjtu-dzhz/dzhz)
[![Build & Release](https://img.shields.io/github/actions/workflow/status/xjtu-dzhz/dzhz/build.yml?label=build&logo=github)](https://github.com/xjtu-dzhz/dzhz/actions/workflows/build.yml)
![GitHub Stars](https://img.shields.io/github/stars/xjtu-dzhz/dzhz?style=social)
<br>

![android](https://img.shields.io/badge/platforms-Android-green)
![win/linux](https://img.shields.io/badge/Desktop%20%28Win%2FLinux%29-blueviolet)
![web](https://img.shields.io/badge/Web%20%28wasm%29-teal)&nbsp;&nbsp;&nbsp;
![minSdk](https://img.shields.io/badge/minSdk-31%20(Android%2012)-blue)
![version](https://img.shields.io/github/v/release/xjtu-dzhz/dzhz?label=version&color=orange)

<b>[下载](https://github.com/xjtu-dzhz/dzhz/releases/latest) | [反馈](https://github.com/xjtu-dzhz/dzhz/discussions/new/choose) | [插件开发](docs/plugins.md) | [加入我们](https://github.com/xjtu-dzhz/dzhz/discussions/new?category=join-us)</b>

</div>

> 泰山不让土壤，故能成其大。&nbsp;&nbsp;&nbsp;&nbsp;——李斯《谏逐客书》

面向**西安交通大学学生**的校园工具箱 _（KMP，Android 为主，另有桌面端与网页端）_。

Android 版使用 _Kotlin_ 与 _Jetpack Compose_ 原生开发，便捷使用教务、图书馆、校园卡、思源学堂等学校服务，**不经过任何自建业务中转服务器**——账号凭据与校园数据只在设备和学校系统之间流动。

## 下载安装

官网：<https://dzhz.top>

| 平台 | 获取方式 |
|------|------|
| Android 12 及以上 | [直接下载最新正式版](https://dzhz.zyxf.top/xjtu-toolbox/apk/latest/app-release.apk)；也可以在 [GitHub Releases](https://github.com/xjtu-dzhz/dzhz/releases/latest) 下载 `app-release.apk` |
| Windows | [GitHub Releases](https://github.com/xjtu-dzhz/dzhz/releases/latest) 下载 `xjtu-dzhz-<版本>.exe` |
| Linux | 同上，Debian / Ubuntu 用 `.deb`，其他发行版用 `linux-x64.tar.gz` |
| 网页 | 没有公共部署，需要自己运行 serve 模式，见 [`docs/api-contract.md`](docs/api-contract.md) |

安装后在「设置 → 更新」检查新版本；想提前体验开发中的功能可以开启「接收预览版更新」，预览版与正式版能互相覆盖安装。

<!--TODO: 最新正式版的下载链接需要更新-->

> [!NOTE]
> 想要在Windows上体验更原生的服务，以及 PyQt + fluent-widgets 风格？快试试 [XJTUToolBox](https://github.com/yan-xiaoo/XJTUToolBox)。岱宗盒子的很多抓取逻辑就是从那里借鉴而来，访问各个系统不卡顿！
>
> 注：XJTUToolBox 的作者与本项目无关

## ✨ 功能一览

| 模块 | 核心能力 |
|:---|:---|
| 🏠 首页 | 卡片编排 · 主题定制 · 公告投票 |
| 🎨 界面 | MIUIX · 动画交互 · 深色模式 |
| 📱 适配 | 多端支持 · 平板 · 折叠屏 |
| 🔐 账号 | CAS · MFA · 多账号隔离 |
| 📅 日程 | 课表 · 考试 · 日历 · 小组件 |
| 🎓 教学 | 成绩 · GPA · 考勤 · 评教 |
| 🏫 教学空间 | 教室查询 · 课程检索 · 教师信息 |
| 💳 生活 | 校园卡 · 场馆 · 活动 |
| 📚 学习 | 图书馆 · 思源学堂 · 仲英学辅 |
| 📰 资讯 | 校园通知 · 校历 · 体测 |
| 🎮 娱乐 | 学期总结 · 小游戏 · 联机 · 交友 |
| 💬 社区 | 论坛 · 互动 · 协作 |
| 🛠️ 反馈 | 建议 · 问题反馈 · 隐私保护 |
| 💾 数据 | 多账号隔离 · 跨端存档 |

### 🌟 特色功能

- **学期总结**：回顾课程、成绩与消费，生成专属学期称号。
- **图书馆空位推荐**：覆盖三校区，支持座位查询、预约、换座与签到。
- **思源学堂**：课程、作业、评分、课件、附件及直播回放。
- **原生社区**：基于 GitHub Discussions 构建，支持完整的讨论与互动体验。
- **多账号隔离**：不同账号的课表、成绩、对话与校园卡数据相互独立。
- **跨端存档**：支持 Android、桌面与 Web 之间的数据导入导出。

### 🤖 小岱（AI 助手）

内置可自定义名称的校园 AI 助手「**小岱**」。支持配置任意兼容 OpenAI API 的模型服务（自备 API Key，推荐 `deepseek-flash`），通过自然语言查询校园信息。

- **校园查询**：课表、成绩、教室、校园卡、通知、思源学堂、体测及仲英学辅
- **智能交互**：多会话、流式回复、数据卡片、联网搜索与网页阅读（注明出处）
- **生活助手**：设置闹钟、添加日历事件
- **本地记忆**：记住个人偏好，支持查看与删除
- **隐私控制**：外部链接仅填入输入框，不会自动发送；外链图片点击后才加载

> [!IMPORTANT]
> 使用小岱时，相关校园数据会随请求发送至你配置的模型服务。请谨慎选择服务商，并妥善保管 API Key。

## ⚠️ 使用须知

- **使用规范**：本项目仅供学习与个人信息管理，请遵守学校各系统使用规则，不要高频请求或尝试绕过验证码、访问控制等安全机制。
- **功能可用性**：学校接口可能调整，具体功能以实际运行情况为准。
- **数据与隐私**：妥善保管 API Key、账号凭据及下载的个人资料。
- **排行榜**：达到上榜条件的成绩才会上传至项目自建服务，仅包含游戏标识、分数、昵称及设备级匿名 ID（`ANDROID_ID` 哈希），不包含学号或账号信息；昵称留空即可匿名上榜。
- **AI 内容**：生成内容可能存在错误，重要信息请以学校官方渠道为准。
- **项目声明**：本项目为学生个人作品，非西安交通大学官方产品，与学校无隶属关系。

## 反馈与参与

- 使用问题、建议和交流去 [Discussions](https://github.com/xjtu-dzhz/dzhz/discussions)，App 里「我的 → 社区讨论」看到的就是这里；也可以在 App 里直接提交反馈。
- 确定是 bug 欢迎提 Issue，请说明应用版本、机型系统、涉及模块与复现步骤；**不要粘贴账号、密码、Cookie 或 API Key**。
- 学校某个系统挂了或在维护，开一个「校园系统状态」Issue，维护者核实后会发应用内公告。
- 学校系统接口变动、维护是功能失效最主要的来源，遇到问题欢迎反馈。

欢迎 PR。参与开发前请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，其中规定了版本号、分支、提交与测试要求。

> [!INFO]
> 想要参与社区审核？或是想频繁的参与开发？欢迎[加入我们](https://github.com/xjtu-dzhz/dzhz/discussions/new?category=join-us)！

## 开发

### 🛠️ 技术栈

**Kotlin** · **Jetpack Compose** · **MIUIX** · **OkHttp** · **Ktor** · **Room** · **Kotlin Coroutines** · **Coil**

### 本地构建

需要 Android Studio 或 Android SDK（API 37）、JDK 21、Git。

```bash
git clone https://github.com/xjtu-dzhz/dzhz.git
cd dzhz
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
```

Windows 下把 `./gradlew` 换成 `gradlew.bat`。

<details >
<summary>关于release构建</summary>
Release构建（`./gradlew assembleRelease`）会启用代码压缩与资源收缩。未提供签名配置时生成未签名产物；如需签名，通过环境变量 `KEYSTORE_PATH`、`STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 提供，或在本地放置被 Git 忽略的 `release.jks` 与 `keystore.properties`。
</details>

## 相关项目

- [XJTUToolBox](https://github.com/yan-xiaoo/XJTUToolBox) —— 桌面端仙交百宝箱
- [XJTU-Course-Genius](https://github.com/Hz162/XJTU-Course-Genius)
- [zyxf](https://github.com/Guochaoo/zyxf) —— 仲英学辅资料站
- [Etoile](https://github.com/JoyinJoester/Etoile) —— Android 原生 GitHub 客户端，社区功能的登录与讨论区代码改编自它

## Star 趋势

[![Star History Chart](https://api.star-history.com/svg?repos=xjtu-dzhz/dzhz&type=Date)](https://star-history.com/#xjtu-dzhz/dzhz&Date)

### 访问计数器

![Moe Counter](https://count.getloli.com/@dzhz-top-main?theme=booru-jaypee&padding=7&offset=0&align=top&scale=1&pixelated=1&darkmode=auto)

## 图标与致谢

应用图标使用「交小西」Q 版形象，**图标形象版权归交小西制作组所有**（[B 站主页](https://space.bilibili.com/3494379805215567)），已获得交小西制作组授权。

**本应用为开源项目，不由交小西制作组出品。**

该形象不适用本仓库的 GPL-3.0 许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可

[GPL-3.0](LICENSE)
