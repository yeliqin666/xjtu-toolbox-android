# 岱宗盒子

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-green?logo=android" />
  <img src="https://img.shields.io/badge/minSdk-31_(Android_12)-blue" />
  <img src="https://img.shields.io/badge/version-4.0-orange" />
  <img src="https://img.shields.io/badge/license-MIT-lightgrey" />
</p>

基于 AI 的西安交通大学校园工具箱。Kotlin + Jetpack Compose 原生实现，直接调用教务系统、图书馆、校园卡、思源学堂等学校系统接口，不依赖自建中转服务器。

内置校园助手「**屁岱**」（~~当然可以改名~~），授权与配置后调用本地校园工具，调用课表、成绩、考试、空教室、校园卡、通知、LMS、体测、图书馆等信息组织成可读答案与卡片，并可使用“交晓智”作为免费的校内知识subAgent。推荐配合DeepSeek官方API使用。

本版起接入“**移动交通大学**”功能，可快速访问智慧体育、一网通办提醒等移动交大子功能（暂仅限非校内服务）。
---

## 4.0 亮点

| 能力 | 说明 |
|------|------|
| 🤖 屁岱 Agent | 支持多会话、Markdown、流式回复、能力开关、联网搜索、网页阅读与校园工具调用 |
| 🧠 校园工具调用 | 可查询课表、考试、成绩/GPA、空闲教室、考勤、校园卡流水、通知公告、黄页、教材、LMS 作业课件、体测成绩等 |
| 🤝 交晓智接入 | 新增原生交晓智独立对话；也可作为屁岱的校内知识子代理，用于政策、办事流程、校内知识问答 |
| 📅 多学期日程 | 日程页支持切换学期；课表、考试、教材跟随学期查看；手动添加日程与教务课表并行合并，不会被刷新覆盖 |
| 📱 移动交大 | 支持 App 内置访问、子服务登录接力与网页定位授权桥接，减少反复跳登录 |
| 🌐 WebVPN / 浏览器 | 内置浏览器支持登录态继承，WebVPN 链路用于校外访问学校内部资源 |

> 使用屁岱时建议选择可靠 API 来源并妥善保管 API Key。对话内容可能包含个人校园数据，请勿把密钥交给不可信服务。

---

## 功能

| 模块 | 功能 |
|------|------|
| 🔐 统一认证 | CAS 登录、MFA 手机验证码、WebVPN 自动切换、SessionManager 统一会话与失败退避 |
| 📅 日程 | 多学期课表、考试安排、教材信息、手动日程、桌面小组件、ICS/CSV/图片导出 |
| 📊 成绩 | JWAPP 正式成绩、FineReport 成绩单、GPA 计算 |
| ✅ 考勤 | 本科/研究生考勤查询，出勤状态统计 |
| 🏫 空闲教室 | CDN/直查双模式，支持今天/明天、楼栋、节次筛选 |
| 💳 校园卡 | 余额、状态、账单流水与消费汇总 |
| 🎫 加餐券 | 电子券余额、有效期、状态筛选 |
| 📚 图书馆 | 当前预约、座位状态、空闲座位推荐、预约/换座/取消/签到 |
| 📖 思源学堂 | 课程、活动、作业、评分、课件、附件读取与下载链接 |
| 🎓 课程回放 | TronClass 多机位视频播放与课件下载 |
| 🔍 全校课程 | 按课程名、教师、班级、院系、校区、节次、校公选类别等字段检索 |
| 📢 通知公告 | 教务处与学院通知多源聚合 |
| 🏃 体测查询 | 学年切换、总分/等级与项目成绩 |
| 📱 移动交大 | 内置 WebView 访问移动交大及部分子服务 |
| 🧠 交晓智 | 官方校园智能问答，支持多会话、模型切换、流式响应 |
| ✏️ 评教 | GSTE + 常规评教 |
| 📖 电子教材 | 教材中心搜索 |
| ⚡ 更多功能 | 请自由探索 |

---

## 技术栈

纯 Kotlin 编写，UI 层使用 Jetpack Compose + MIUIX ，网络层使用 OkHttp + Brotli，HTML 解析使用 Jsoup，本地持久化使用 Room。构建工具链为 AGP 9 + Gradle 9。

最低支持 Android 12（API 31），目标 Android 16。

---

## 构建

```bash
# Debug 包
./gradlew assembleDebug

# Release 包
./gradlew assembleRelease
```

项目配置了 GitHub Actions：push/PR 到 `main` 自动编译 Debug，推送 `v*` tag 自动打包 Release。

```bash
git tag v4.0
git push origin v4.0
```

---

## 注意事项

- 本项目仅供学习与个人校园信息管理使用。
- 考勤、图书馆、部分移动交大子服务等可能依赖校内网络或 WebVPN。
- 学校系统接口可能随时调整，功能可用性以实际系统为准。
- 使用 AI 功能时，请自行选择可信 API 服务商并保护 API Key。

---

## 友情项目

- [XJTUToolBox](https://github.com/yan-xiaoo/XJTUToolBox.git)
- [XJTU-Course-Genius](https://github.com/Hz162/XJTU-Course-Genius)

---

**License**：MIT
