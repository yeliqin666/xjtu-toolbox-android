# 第三方代码声明 · 象棋规则引擎

本文件只覆盖「象棋」小游戏并入的第三方代码。其余第三方依赖见 `THIRD_PARTY_NOTICES.md`。

## 来源

- 项目：zfdang/chinese-chess-fish-android
- 地址：https://github.com/zfdang/chinese-chess-fish-android
- 许可证：MIT

## 并入的文件

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

## 所做的改动

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

## 许可证全文

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

## 待确认

`Piece.java` 文件头保留着上游从 DroidFish 抄来的 GPLv3 版权声明
（Copyright (C) 2011 Peter Österlund / 2012 Leo Mayer）。上游仓库整体挂 MIT，
但这个文件头与之冲突。该文件实际内容只是棋子常量表和红黑判定，
与 DroidFish 的国际象棋代码没有可辨识的关联，不过保险起见此处记录一笔，
后续可考虑改写这个文件以彻底切断关系。
