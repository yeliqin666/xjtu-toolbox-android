# 岱宗盒子 (DaiZong Box)

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-green?logo=android" />
  <img src="https://img.shields.io/badge/minSdk-31_(Android_12)-blue" />
  <img src="https://img.shields.io/badge/version-1.0--alpha-orange" />
  <img src="https://img.shields.io/badge/license-MIT-lightgrey" />
</p>

西安交通大学一站式校园工具箱，纯 Kotlin + Jetpack Compose 构建，覆盖教务、成绩、考勤、图书馆、校园卡、通知公告等核心校园服务。

## 功能一览

| 模块 | 功能 | 说明 |
|------|------|------|
| 🔐 统一认证 | CAS 登录 | RSA 加密 + MFA 手机验证码 + 本科/研究生账户 |
| 🌐 网络适配 | WebVPN | 自动检测校内/校外，校外走 webvpn.xjtu.edu.cn |
| 📅 课表 | 周视图 | 按周筛选 + 考试安排 + 教材信息 |
| 📊 成绩 | GPA 计算 | JWAPP 正式成绩 + FineReport 报表（绕过评教限制） |
| ✅ 考勤 | 流水查询 | 全 20 周视图 + 正常/迟到/缺勤统计 |
| 🏫 空闲教室 | 节次筛选 | CDN 数据，无需登录和校园网 |
| 💳 校园卡 | 消费分析 | 余额 + 账单流水 + 月度消费图表 |
| 📚 图书馆 | 座位管理 | 在座/预约状态 + 续座/签退 |
| 📢 通知公告 | 多源聚合 | 一网通办 + 各学院通知爬虫 |
| 🌐 浏览器 | Cookie 注入 | WebView 自动登录 + WebVPN 支持 |
| 📝 评教 | 一键评教 | GSTE 评教 + 常规评教 |
| 🎓 研究生 | GMIS | 研究生管理信息系统 |

## 技术栈

| 项目 | 选型 |
|------|------|
| 语言 | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 (Material You) |
| 网络 | OkHttp 4.12 + Brotli 压缩 |
| HTML 解析 | Jsoup 1.18 |
| 构建 | AGP 9.0 + Gradle 9.2 + R8 优化 |
| 最低版本 | Android 12 (API 31) |
| 目标版本 | Android 16 (API 36) |
| 启动 | SplashScreen API |
| Release 体积 | ~4.4 MB |

## 项目结构

```
app/src/main/java/com/xjtu/toolbox/
├── MainActivity.kt              # 入口、导航、登录状态、首页、关于
├── auth/                        # 认证模块
│   ├── XJTULogin.kt             # CAS 统一身份认证（RSA + MFA + SSO）
│   ├── JwappLogin.kt            # 教务新系统
│   ├── YwtbLogin.kt             # 一网通办
│   ├── AttendanceLogin.kt       # 考勤（Bearer Token）
│   ├── CampusCardLogin.kt       # 校园卡（hallticket）
│   ├── LibraryLogin.kt          # 图书馆座位系统
│   ├── GmisLogin.kt / GsteLogin.kt  # 研究生/教评
│   └── LoginScreen.kt           # 登录界面
├── schedule/                    # 课表 + 考试 + 教材
├── score/                       # 成绩报表 + GPA
├── attendance/                  # 考勤查询
├── emptyroom/                   # 空闲教室
├── card/                        # 校园卡
├── library/                     # 图书馆座位
├── notification/                # 通知公告爬虫
├── judge/                       # 评教
├── browser/                     # 应用内浏览器
├── ywtb/                        # 一网通办
├── gmis/                        # 研究生系统
├── util/                        # Cookie 持久化、WebVPN、凭据存储
└── ui/                          # 主题 + 通用组件
```

## 快速开始

### 构建

```bash
# Debug
./gradlew assembleDebug

# Release (R8 压缩，~4.4MB)
./gradlew assembleRelease
```

APK 输出位置：`app/build/outputs/apk/release/app-release.apk`

### 自动构建

项目配置了 GitHub Actions：
- **Push/PR 到 main** → 自动构建 Debug APK
- **推送 Tag `v*`** → 自动构建 Release APK 并创建 GitHub Release

发版流程：
```bash
git tag v1.0-alpha
git push origin v1.0-alpha
# → GitHub Actions 自动构建并发布 Release
```

## 注意事项

- AGP 9.0 内置 Kotlin 支持，**不要**添加 `org.jetbrains.kotlin.android` 插件
- 考勤/图书馆为校内服务，校外自动通过 WebVPN 访问
- 校园卡 `card.xjtu.edu.cn` 是公网服务，不走 WebVPN
- 空闲教室数据来自 CDN，不需要登录和校园网
- `network_security_config.xml` 允许 XJTU 域名 cleartext HTTP

## 开发计划

1. 图书馆定时抢座
2. 空闲区域分析 & 座位推荐
3. 智能抢课
4. 通知聚合订阅 & Push
5. 电子成绩单获取与签印分析
6. 思源学堂解析版

## 致谢

- [XJTUToolBox](https://github.com/yan-xiaoo/XJTUToolBox.git) — 本项目的核心算法灵感来源，包括 CAS 登录流程、WebVPN 加解密、FineReport 报表解析、空闲教室 CDN 数据、学期时间计算等均移植或参考自该项目的 Python 实现。感谢 [@yan-xiaoo](https://github.com/yan-xiaoo) 的开源贡献！

## 作者

**Yeliqin666** — [runqinliu666.cn](https://www.runqinliu666.cn/)

## License

MIT
