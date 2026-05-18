# 岱宗盒子

> [!NOTE]
> 
> 推荐大家使用[XJTUToolBox](https://github.com/yan-xiaoo/XJTUToolBox.git)，更新修复更快，实现也更稳健；
> 
> 作者最近忙炸，开发暂缓...欢迎大家PR！
> 

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-green?logo=android" />
  <img src="https://img.shields.io/badge/minSdk-31_(Android_12)-blue" />
  <img src="https://img.shields.io/badge/version-3.3.0-orange" />
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
| 🏫 空闲教室 | 节次筛选 |
| 💳 校园卡 | 余额 + 账单流水 + 智能洞察 |
| 🎫 加餐券 | 电子加餐券余额、有效期、状态筛选与自动登录 |
| 📚 图书馆 | 在座/预约状态 + 空闲座位推荐/签退 |
| 🔍 全校课表 | 按课程名、教师、院系、校区、节次等多维度检索全校开课信息 |
| 📢 通知公告 | 一网通办 + 各学院通知多源聚合 |
| 🎓 课程回放 | TronClass 多机位视频播放 + 课件下载 |
| 📖 思源学堂 | 活动详情、作业与评分、课件、直播流 |
| ✏️ 评教 | GSTE + 常规评教一键完成 |
| � 电子教材 | 教材中心搜索 |
| ⭐ NeoSchool | 拔尖计划课程、章节、课件与资源下载 |

---

## 开发计划

- 个人/教务通知订阅 & Push
- 方案管理

---

## 技术栈

纯 Kotlin 2.0 编写，UI 层使用 Jetpack Compose + MIUIX（HyperOS 设计语言），网络层是 OkHttp 4.12，全程启用 Brotli 解压（服务端支持 `Content-Encoding: br` 时生效，降低接口流量而非 APK 体积）。HTML 解析用 Jsoup，本地数据持久化走 Room。构建工具链 AGP 9.0 + Gradle 9.3.1，Release 包经 R8 全量混淆后约 **10 MB**，其中 ~9 MB 为 `classes.dex`（Compose runtime + Media3 + MIUIX 等库的编译产物，无冗余资源）。APK 签名为 v2+v3 双方案。

最低支持 Android 12（API 31），目标 Android 16（API 36.1）。

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
git tag v3.3.0
git push origin v3.3.0
# Actions 自动构建并发布
```

---

## 注意事项

- 考勤、图书馆等服务仅限校内网络，应用会自动识别并切换到 WebVPN

---

## 致谢

部分核心算法来自 [XJTUToolBox](https://github.com/yan-xiaoo/XJTUToolBox.git)，CAS 登录流程、WebVPN 加解密、FineReport 报表解析、空闲教室 CDN 数据、学期时间计算等均参考或移植自该项目的 Python 实现。感谢 [@yan-xiaoo](https://github.com/yan-xiaoo) 的开源贡献。

---
 
**License**：MIT
