# 岱宗盒子
> 作者最近忙炸，App不在近期计划内...上等的PR会接受！
<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-green?logo=android" />
  <img src="https://img.shields.io/badge/minSdk-31_(Android_12)-blue" />
  <img src="https://img.shields.io/badge/version-3.0-orange" />
  <img src="https://img.shields.io/badge/license-MIT-lightgrey" />
</p>

西安交通大学校园工具箱。Kotlin + Jetpack Compose 原生实现，直接调用教务系统、图书馆、校园卡等官方接口，不依赖任何三方服务器。

---

## 功能

| 模块 | 功能 |
|------|------|
| 🔐 统一认证 | CAS 登录（RSA + MFA 手机验证码）+ WebVPN 自动切换 |
| 📅 课表 | 周视图 + 考试安排 + 教材信息 + 桌面小组件（2×2 / 4×2）|
| 📊 成绩 | JWAPP 正式成绩 + FineReport 报表 + GPA 计算 |
| ✅ 考勤 | 全 20 周出勤流水，正常/迟到/缺勤统计 |
| 🏫 空闲教室 | 节次筛选，CDN 公开数据，无需登录和校园网 |
| 💳 校园卡 | 余额 + 账单流水 + 智能洞察 |
| 📚 图书馆 | 在座/预约状态 + 空闲座位推荐/签退 |
| 🔍 全校课表 | 按课程名、教师、院系、校区、节次等多维度检索全校开课信息 |
| 📢 通知公告 | 一网通办 + 各学院通知多源聚合 |
| 🎓 课程回放 | TronClass 多机位视频播放 + 课件下载 |
| 📖 思源学堂 | 活动详情、作业与评分、课件、直播流 |
| 👤 个人信息 | NSA OAuth2，包含 16 项详细信息 |
| ✏️ 评教 | GSTE + 常规评教一键完成 |

---

## 开发计划

- 校园卡功能修复
- 钱院拔尖计划NeoSchool集成
- 图书馆座位智能推荐
- 个人/教务通知订阅 & Push
- 方案管理
- 电子教材

---

## 技术栈

纯 Kotlin 2.0 编写，UI 层使用 Jetpack Compose + MIUIX（HyperOS 设计语言），网络层是 OkHttp 4.12，全程启用 Brotli 解压（服务端支持 `Content-Encoding: br` 时生效，降低接口流量而非 APK 体积）。HTML 解析用 Jsoup，本地数据持久化走 Room。构建工具链 AGP 9.0 + Gradle 9.2，Release 包经 R8 全量混淆后约 **10 MB**，其中 ~9 MB 为 `classes.dex`（Compose runtime + Media3 + MIUIX 等库的编译产物，无冗余资源）。APK 签名为 v2+v3 双方案。

最低支持 Android 12（API 31），目标 Android 16（API 36.1）。

---

## 项目结构

```
app/src/main/java/com/xjtu/toolbox/
├── MainActivity.kt              # 入口、导航、登录状态、首页
├── auth/                        # CAS 统一认证、WebVPN、各系统 Token
├── schedule/                    # 课表、考试、教材、桌面小组件
├── score/                       # 成绩报表、GPA 计算
├── attendance/                  # 出勤记录
├── emptyroom/                   # 空闲教室
├── card/                        # 校园卡
├── library/                     # 图书馆座位
├── notification/                # 通知公告爬虫
├── nsa/                         # 个人信息（OAuth2 + 动态表单）
├── judge/                       # 评教
├── browser/                     # 应用内浏览器
├── ywtb/                        # 一网通办
├── gmis/                        # 研究生系统
├── lms/                         # 思源学堂 + 课程回放
└── util/                        # Cookie 持久化、WebVPN 工具、凭据管理
```

---

## 构建与发版

```bash
# Debug 包
./gradlew assembleDebug

# Release 包（R8 压缩混淆）
./gradlew assembleRelease
```

项目配置了 GitHub Actions：push/PR 到 `main` 自动编译 Debug，推送 `v*` tag 自动打包 Release 并发布到 GitHub Releases。

```bash
git tag v3.0
git push origin v3.0
# Actions 自动构建并发布
```

---

## 注意事项

- 考勤、图书馆等服务仅限校内网络，应用会自动识别并切换到 WebVPN
- 空闲教室数据来自公开 CDN，不需要登录
- `network_security_config.xml` 允许 XJTU 子域名的 cleartext HTTP

---

## 致谢

部分核心算法来自 [XJTUToolBox](https://github.com/yan-xiaoo/XJTUToolBox.git)，CAS 登录流程、WebVPN 加解密、FineReport 报表解析、空闲教室 CDN 数据、学期时间计算等均参考或移植自该项目的 Python 实现。感谢 [@yan-xiaoo](https://github.com/yan-xiaoo) 的开源贡献。

---
 
**License**：MIT
