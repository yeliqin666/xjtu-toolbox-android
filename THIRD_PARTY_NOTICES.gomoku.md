# 五子棋（gomoku）第三方引用说明

## 未并入源码，仅参考设计思路

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

## blackstone 许可证（附录，供设计思路引用溯源，非代码引入声明）

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
