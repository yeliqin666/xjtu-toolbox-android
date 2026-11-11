# AI 代理工作约定

本文件面向在本仓库干活的 AI 代理（Claude Code、Codex、各类 bot）。通用开发规范见 [DEVELOP.md](DEVELOP.md)。

## Issue 状态流转

代理按 `State` 标签找活、报进度。每个 issue 同一时间只挂一个 `State` 标签，换状态时先摘掉旧的。

| 时机 | 标签 | 代理要做的事 |
|---|---|---|
| 新建、还没分诊 | `🕒 State:Awaiting Triage` | 不接。需求不清或要先拍板的，留给维护者 |
| 可以开工 | `📥 State:Awaiting Assignment` | 没有任务时从这里领，优先级高的先做（P0 > P1 > P2 > P3） |
| 开始动手 | `🔧 State:In Progress` | 领到后立刻改成这个，避免两个代理撞车 |
| 改动已合入 `dev` | `🔍 State:In Review` | 在 issue 下简短说明原因和改法，等人工实测 |
| 人工实测通过 | `✅ State:Completed` | 由维护者改标签并关闭 issue，代理不要自己关 |
| 实测不理想 | `📥 State:Awaiting Assignment` | 维护者把现象或报错贴在 issue 下，打回待领；再接手时先读完这些反馈 |

领活前先看 issue 的评论和关联提交，确认没人在做、也不是已经修完只差关闭。

## 常用命令

```bash
gh issue list --label "📥 State:Awaiting Assignment" --state open
```

```bash
gh issue edit 123 --remove-label "📥 State:Awaiting Assignment" --add-label "🔧 State:In Progress"
```

```bash
gh issue edit 123 --remove-label "🔧 State:In Progress" --add-label "🔍 State:In Review"
```

## 构建与安装

改完 Android 相关代码后构建安卓版即可，不必自己开模拟器实测：

```bash
./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

用户说「帮我安装」时，安装目标是无线调试设备，地址形如 `<IP>:<端口>`——
**地址每次会变，由用户当次给出，不要写死、也不要复用旧地址**：

```bash
adb connect <用户当次给出的 IP:端口>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
