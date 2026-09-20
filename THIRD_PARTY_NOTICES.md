# 第三方代码与素材声明

本文件记录 XJTUApp 中并入或参考的第三方代码、素材及其许可证。
按来源分节，每节写清「用了什么、改了什么、许可证是什么」。

目前的第三方内容集中在小游戏模块：

| 小游戏 | 第三方来源 | 许可证 |
| --- | --- | --- |
| 合成西交大 | moonfloof/suika-game、Matter.js | Unlicense、MIT |
| 象棋 | zfdang/chinese-chess-fish-android | MIT |
| 五子棋 | haslam/blackstone（仅参考思路，未并入代码） | MIT |
| 围棋 | 无 | — |
| GPA 2048 | 无 | — |

C9 校徽素材的说明见文末。

---

## 合成西交大

本游戏（`app/src/main/assets/games/merge/`）在
[moonfloof/suika-game](https://github.com/moonfloof/suika-game) 的基础上改写而成，
玩法沿用自「合成大西瓜 / Suika Game」。

### moonfloof/suika-game —— Unlicense

`index.html`、`index.js` 的游戏逻辑与结构参考自该仓库（本项目对其做了较大改写：
11 级水果改为 9 级 C9 高校校徽、投放与失败判定逻辑重写、加入分数回传等），
其原始许可证为 **Unlicense**（公共领域）。GitHub 把该仓库的许可证标记成
NOASSERTION，是因为仓库根目录的 LICENSE 文件在标准 Unlicense 正文前加了一句
「本许可证适用于 index.js / index.html / assets 目录，Matter.js 单独使用 MIT
许可证」的说明，导致自动识别失败；LICENSE 文件本体就是标准 Unlicense 全文，
原文如下：

```
This license applies to index.js, index.html, and all files in the
assets folder. Matter.js, also included in this project, uses the MIT
license, with a license notice at the top of its file.

This is free and unencumbered software released into the public domain.

Anyone is free to copy, modify, publish, use, compile, sell, or
distribute this software, either in source code form or as a compiled
binary, for any purpose, commercial or non-commercial, and by any
means.

In jurisdictions that recognize copyright laws, the author or authors
of this software dedicate any and all copyright interest in the
software to the public domain. We make this dedication for the benefit
of the public at large and to the detriment of our heirs and
successors. We intend this dedication to be an overt act of
relinquishment in perpetuity of all present and future rights to this
software under copyright law.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.

For more information, please refer to <https://unlicense.org>
```

### Matter.js —— MIT License

`app/src/main/assets/games/merge/matter.min.js` 是 Matter.js 物理引擎的压缩版
（v0.19.0），单独使用 **MIT License**：

```
The MIT License (MIT)

Copyright (c) Liam Brummitt and contributors.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

项目主页：<https://brm.io/matter-js/>，仓库：<https://github.com/liabru/matter-js>。

---

## 象棋

象棋是本项目中唯一大段并入上游源码的小游戏——规则引擎直接取自上游，只做了包名与去 Android 依赖的改动。

### 来源

- 项目：zfdang/chinese-chess-fish-android
- 地址：https://github.com/zfdang/chinese-chess-fish-android
- 许可证：MIT

### 并入的文件

从上游 `app/src/main/java/com/zfdang/chess/gamelogic/` 取了 6 个规则引擎源文件，
放到 `app/src/main/java/com/xjtu/toolbox/game/xiangqi/rules/`：

| 文件 | 作用 |
| --- | --- |
| `Board.java` | 棋盘状态、FEN 读写、Zobrist 入口 |
| `Move.java` | 一步走子、UCCI / 中文记谱 |
| `Piece.java` | 棋子 id 编码与红黑判定 |
| `Position.java` | 坐标 |
| `Rule.java` | 走法生成、将军 / 将死判定 |
| `Zobrist.java` | 局面哈希表 |

上游同目录下的 `Game.java`、`GameStatus.java`、`PvInfo.java` **没有并入**：
`Game.java` 依赖 `android.content.Context` 做文件存档，另两个是给 AI 引擎用的，
本项目只做同屏双人、不接 AI，都用不上。`Game.java` 的职责（走子、悔棋历史、终局判定）
由纯 Kotlin 的 `game/xiangqi/engine/XiangqiGame.kt` 重写，与上游代码无关。

### 所做的改动

1. **包名**：6 个文件的 `package` 从 `com.zfdang.chess.gamelogic` 改为
   `com.xjtu.toolbox.game.xiangqi.rules`。
2. **去掉 `android.util.Log` 依赖**（规则引擎因此可以在纯 JVM 单测里跑）：
   - `Board.java`：删除文件顶部 `import android.util.Log;`；
     删除 `restoreFromFEN()` 中三处 `Log.e` 调用 —— 分别在「FEN 段数不对」
     「走子方标记无法识别」「回合数不是数字」三个分支里，原来的 `return false`
     保留不动，只是不再打日志。改动处均有中文注释。
   - `Rule.java`：删除文件顶部 `import android.util.Log;`。该文件里 3 处 `Log`
     调用在上游就已经被注释掉了，删 import 不影响任何行为。
3. **`Rule.java` 末尾新增一组静态方法**，上游没有实现困毙规则，按中国象棋规则补：
   `isKingFaceToFace`、`isInCheck`、`isLegalMove`、`hasAnyLegalMove`、
   `isStalemate`、`isCheckmate`。其中 `isLegalMove` 顺带补上了上游漏掉的
   「挪开挡子导致将帅照面」这一类送将走法。新增方法均有中文注释。
4. 其余逻辑一律未改动。

### 许可证全文

```
MIT License

Copyright (c) 2024 Zhengfa Dang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### 待确认

`Piece.java` 文件头保留着上游从 DroidFish 抄来的 GPLv3 版权声明
（Copyright (C) 2011 Peter Österlund / 2012 Leo Mayer）。上游仓库整体挂 MIT，
但这个文件头与之冲突。该文件实际内容只是棋子常量表和红黑判定，
与 DroidFish 的国际象棋代码没有可辨识的关联，不过保险起见此处记录一笔，
后续可考虑改写这个文件以彻底切断关系。

---

## 五子棋

### 未并入源码，仅参考设计思路

调研阶段选定的目标是并入 [haslam/blackstone](https://github.com/haslam22/blackstone)
（MIT 许可证，见下）的 negamax AI，该 AI 位于其仓库的
`src/main/java/haslam/blackstone/players/negamax/` 目录下，共五个文件：
`Evaluator.java`、`Field.java`、`NegamaxPlayer.java`、`State.java`、`ThreatPattern.java`。

实际取源码核对后发现：`Evaluator.java` / `Field.java` / `State.java` /
`ThreatPattern.java` 确实自成一体，可以独立编译；但真正做搜索决策的
`NegamaxPlayer.java` 依赖同仓库另一个类 `ThreatUtils`（威胁棋型识别，用于
「先手应对棋盘上的冲四/活三」这类剪枝）以及 `haslam.blackstone.core.Move`，
这两者都不在调研时列出的五个文件之内，也没有在 blackstone 仓库里找到独立、
零耦合的版本。把 `NegamaxPlayer` 剥出来意味着要连带搬运/重写它依赖的威胁识别
逻辑，剥离成本不低于照着它的思路重写一份——因此按任务里给出的退路，
**本次没有并入 blackstone 的任何源码**，`app/src/main/java/com/xjtu/toolbox/game/gomoku/`
下的 `GomokuBoard.kt`、`GomokuAi.kt` 是重新实现的。

`GomokuAi.kt` 里的静态评估函数（按「一条线上还差几步能连成五」给活四/冲四/
活三/眠三/活二打分）在设计思路上参考了 blackstone `Evaluator.java` 的窗口扫描
方式，但代码是独立实现，不是逐行翻译或复制。

### blackstone 许可证（附录，供设计思路引用溯源，非代码引入声明）

blackstone 项目地址：<https://github.com/haslam22/blackstone>

```
MIT License

Copyright (c) 2017 Hasan Aslam

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## C9 校徽素材

`app/src/main/assets/games/merge/img/game_c9_*.webp` 与
`app/src/main/res/drawable-nodpi/game_c9_{xjtu,sjtu}.webp` 共九所 C9 高校的校徽，
由维基百科（zh.wikipedia.org）官方 thumb 服务渲染的 PNG 裁边、缩放到 256×256
后转为 WebP 得到，单张约 30KB。

这些校徽是各高校的注册商标，维基上多数标注为 non-free / fair use，并非自由许可。
本项目将其用于非商业的校园工具类应用中的小游戏，属于识别性使用。
如收到任何一所高校的异议，应立即从仓库与发行版本中移除对应素材——
游戏本身在素材缺失时会自动退回到 `drawPlaceholder()` 画的纯色圆加校名，仍可正常运行。
