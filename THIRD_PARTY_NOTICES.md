# 第三方代码与素材声明

本文件记录 XJTUApp 中并入或参考的第三方代码、素材及其许可证。
按来源分节，每节写清「用了什么、改了什么、许可证是什么」。

## 美术素材

### 应用图标中的「交小西」形象

- **版权**：图标形象版权归交小西制作组所有（[B 站主页](https://space.bilibili.com/3494379805215567)）。
- **授权**：已获得交小西制作组授权使用该形象。
- **出品关系**：本应用为开源项目，不由交小西制作组出品。
- **授权条件**：项目保持开源、免费、无广告、无内购；项目性质发生变更时授权不再适用，须与制作组重新协商。
- **许可边界**：该形象**不属于**本仓库 GPL-3.0 的授权范围。下游 fork 或再分发不得沿用该图标，需自行取得授权或替换图标。

代码类第三方内容集中在小游戏模块：

| 小游戏 | 第三方来源 | 许可证 |
| --- | --- | --- |
| 合成西交大 | moonfloof/suika-game、Matter.js | Unlicense、MIT |
| 象棋 | zfdang/chinese-chess-fish-android | MIT |
| 五子棋 | haslam/blackstone（仅参考思路，未并入代码） | MIT |
| 围棋 | 无 | — |
| GPA 2048 | 无 | — |

界面上的液态玻璃效果：

| 用在哪 | 第三方来源 | 许可证 |
| --- | --- | --- |
| 玻璃底栏及各处玻璃 | Kyant0/AndroidLiquidGlass（backdrop 库 + 示例 App） | Apache-2.0 |
| COUI 底栏材质、浮层材质与跟手形变 | ItosEO/COUI-Kit 0.0.1-alpha10（源码并入 `androidMain/.../ui/glass/coui/`） | MPL-2.0 |

社区（GitHub Discussions）：

| 用在哪 | 第三方来源 | 许可证 |
| --- | --- | --- |
| 社区页的讨论列表、详情、楼中楼、发帖、GitHub 设备码登录 | JoyinJoester/Etoile | GPL-3.0 |
| 社区页的 Markdown 渲染 | mikepenz/multiplatform-markdown-renderer（核心模块 + coil3 模块） | Apache-2.0 |
| 社区页的头像与帖子图片 | coil-kt/coil（coil-compose、coil-network-okhttp） | Apache-2.0 |

屁岱（AI 助手）：

| 用在哪 | 第三方来源 | 许可证 |
| --- | --- | --- |
| 屁岱回答的公式排版 | huarangmeng/latex 1.6.3（latex-renderer、latex-fonts-katex） | MIT |
| 公式字体 | KaTeX 0.16.11 字体（Khan Academy，经 latex-fonts-katex 打包） | MIT |

玻璃底栏和 C9 校徽素材的说明见文末。

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

## 玻璃底栏

手机竖屏的玻璃底栏（`app/src/main/java/com/xjtu/toolbox/ui/glass/`）和其他几处玻璃效果，用到
[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（`kmp` 分支）的两部分内容，
许可证均为 **Apache License 2.0**（Copyright 2025 Kyant）：

1. **`io.github.kyant0:backdrop:2.0.1`**（及其传递依赖 `io.github.kyant0:shapes-android`）：通过 Maven Central
   引入的库，未修改，提供 `layerBackdrop` / `drawBackdrop` / `blur` / `lens` / `vibrancy` 等玻璃效果 API。
2. **示例 App 里的 5 个源文件**，改写后搬进了本项目：

   | Kyant 原路径（`app/src/commonMain/kotlin/com/kyant/backdrop/catalog/...`） | 本项目路径 |
   | --- | --- |
   | `components/LiquidBottomTabs.kt` | `ui/glass/GlassBottomTabs.kt` |
   | `components/LiquidBottomTab.kt` | `ui/glass/GlassNavTabSlot.kt` |
   | `utils/DampedDragAnimation.kt` | `ui/glass/GlassDampedDragAnimation.kt` |
   | `utils/InteractiveHighlight.kt` | `ui/glass/GlassInteractiveHighlight.kt` |
   | `utils/DragGestureInspector.kt` | `ui/glass/GlassDragGestureInspector.kt` |

   改动：强调色从写死的 iOS 蓝改成跟随本应用主题；深色模式跟随本应用的深色模式设置而不是系统设置；
   胶囊形状从 `com.kyant.shapes.Capsule` 换成 `RoundedCornerShape(50)`（不额外引入依赖，视觉相同）；
   「滑块经过时染色」改成按格可选（屁岱头像不能被复制出第二份）；选中回调改成显式参数；
   `awaitFrame()` 换成 `withFrameNanos {}`。每个文件头都保留了出处链接和改动说明。

### COUI-Kit 0.0.1-alpha10 —— MPL 2.0

底栏的 COUI 材质分支另外并入了 [ItosEO/COUI-Kit](https://github.com/ItosEO/COUI-Kit)
0.0.1-alpha10 的源码，许可证为 **Mozilla Public License 2.0**（仓库自有源码，第三方内容另见其
`NOTICE`）。这些文件位于
`shared/designsystem/src/androidMain/kotlin/com/xjtu/toolbox/ui/glass/coui/`：浮层材质、
RuntimeShader（AGSL）、边缘光/内发光/轮廓光、网格投影与焦散、融球、`CouiBackdrop` 采样层、
`CouiSpring` / `CouiTouchMotion` / `CouiPressMotion` / 跟手光、胶囊形状、主题与 FAB 预设，
以及 `navigation/NavigationBar.kt` 的移植版 `CouiNavigationBar.kt`。MPL 2.0 全文附在本文末尾。

改动：

- 包名从 `com.itos.coui.*` 扁平化为同一模块内的 `com.xjtu.toolbox.ui.glass.coui`，
  文件内容与 alpha10 原版一致（仅 `NavigationBar.kt` 有下述宿主接缝）。
- `CouiNavigationBar.kt`：允许 `selectedIndex = -1`（当前页不属于任何一格时不高亮）；
  新增 `itemContent` 槽，用于把本机每格内容（屁岱头像、徽标）整格替换原厂「图标 + 文字」；
  底栏只用得到单主体，接入 `CouiFloatingRenderer.standalone()` + `couiFloatingSurface()`；
  胶囊宽度铺满并保留本机左右各 20dp 侧距（原厂为 16dp 侧距 + 附件预留）。
  库里的三主体融球管线原样保留，供其它浮层使用。
- 深浅模式通过 `LocalCouiDarkTheme` 由宿主主题提供；页面采样源挂在外壳内容节点上，
  `ShellTabHost` 为不透明页面垫底色，满足 COUI 材质对不透明 SDR 采样源的要求。
- 非 Android 平台没有 AGSL 材质，接缝返回 null，底栏继续走上面的 Kyant 玻璃实现。

COUI-Kit 自有源码采用 MPL 2.0；ColorOS 及相关名称归 OPPO 所有，本项目与 OPPO 没有隶属、
授权或背书关系。

---

## C9 校徽素材

`app/src/main/assets/games/merge/img/game_c9_*.webp` 与
`app/src/main/res/drawable-nodpi/game_c9_{xjtu,sjtu}.webp` 共九所 C9 高校的校徽，
由维基百科（zh.wikipedia.org）官方 thumb 服务渲染的 PNG 裁边、缩放到 256×256
后转为 WebP 得到，单张约 30KB。

这些校徽是各高校的注册商标，维基上多数标注为 non-free / fair use，并非自由许可。
本项目将其用于非商业的校园工具类应用中的小游戏，属于识别性使用。
如收到任何一所高校的异议，应立即从仓库与发行版本中移除对应素材——
游戏本身在素材缺失时会自动退回到 `drawPlaceholder()` 画的纯色圆加校名，仍可正常运行。

---

## 社区（GitHub Discussions）

`app/src/main/java/com/xjtu/toolbox/community/` 下的代码大部分改编自
[JoyinJoester/Etoile](https://github.com/JoyinJoester/Etoile)（GPL-3.0），每个文件开头注明了对应的原文件。
改动：数据层（GraphQL 查询、设备码登录、评论分页加载）基本沿用，补了头像、时间、点赞、分类筛选、
楼中楼预览；界面全部用 MIUIX 按论坛样式重写（帖子卡片、楼层、楼主标记、楼中楼、底部回帖栏、编辑 / 预览），
顶栏用本 App 的玻璃栏；登录只保留设备码方式，token 不过期、不做刷新。
本项目自 2026-09-24 起以 GPL-3.0 发布，与之兼容。

---

## 屁岱公式排版

### huarangmeng/latex 1.6.3 —— MIT License

`feature/agent` 依赖 `io.github.huarangmeng:latex-renderer` 与 `latex-fonts-katex`（1.6.3）排版回答中的公式：

```
MIT License

Copyright (c) 2026 huarangmeng

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

仓库：<https://github.com/huarangmeng/latex>。

### KaTeX 0.16.11 fonts —— MIT License

`latex-fonts-katex` 打包了 KaTeX 0.16.11 的字体：

```
The MIT License (MIT)

Copyright (c) 2013-2020 Khan Academy and other contributors

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

项目主页：<https://katex.org/>，仓库：<https://github.com/KaTeX/KaTeX>。

## 附录：COUI-Kit（MPL 2.0）许可证全文

以下为 COUI-Kit 仓库 `LICENSE` 文件的全文，适用于上文并入的 MPL-2.0 源码。

```text
Mozilla Public License Version 2.0
==================================

1. Definitions
--------------

1.1. "Contributor"
    means each individual or legal entity that creates, contributes to
    the creation of, or owns Covered Software.

1.2. "Contributor Version"
    means the combination of the Contributions of others (if any) used
    by a Contributor and that particular Contributor's Contribution.

1.3. "Contribution"
    means Covered Software of a particular Contributor.

1.4. "Covered Software"
    means Source Code Form to which the initial Contributor has attached
    the notice in Exhibit A, the Executable Form of such Source Code
    Form, and Modifications of such Source Code Form, in each case
    including portions thereof.

1.5. "Incompatible With Secondary Licenses"
    means

    (a) that the initial Contributor has attached the notice described
        in Exhibit B to the Covered Software; or

    (b) that the Covered Software was made available under the terms of
        version 1.1 or earlier of the License, but not also under the
        terms of a Secondary License.

1.6. "Executable Form"
    means any form of the work other than Source Code Form.

1.7. "Larger Work"
    means a work that combines Covered Software with other material, in
    a separate file or files, that is not Covered Software.

1.8. "License"
    means this document.

1.9. "Licensable"
    means having the right to grant, to the maximum extent possible,
    whether at the time of the initial grant or subsequently, any and
    all of the rights conveyed by this License.

1.10. "Modifications"
    means any of the following:

    (a) any file in Source Code Form that results from an addition to,
        deletion from, or modification of the contents of Covered
        Software; or

    (b) any new file in Source Code Form that contains any Covered
        Software.

1.11. "Patent Claims" of a Contributor
    means any patent claim(s), including without limitation, method,
    process, and apparatus claims, in any patent Licensable by such
    Contributor that would be infringed, but for the grant of the
    License, by the making, using, selling, offering for sale, having
    made, import, or transfer of either its Contributions or its
    Contributor Version.

1.12. "Secondary License"
    means either the GNU General Public License, Version 2.0, the GNU
    Lesser General Public License, Version 2.1, the GNU Affero General
    Public License, Version 3.0, or any later versions of those
    licenses.

1.13. "Source Code Form"
    means the form of the work preferred for making modifications.

1.14. "You" (or "Your")
    means an individual or a legal entity exercising rights under this
    License. For legal entities, "You" includes any entity that
    controls, is controlled by, or is under common control with You. For
    purposes of this definition, "control" means (a) the power, direct
    or indirect, to cause the direction or management of such entity,
    whether by contract or otherwise, or (b) ownership of more than
    fifty percent (50%) of the outstanding shares or beneficial
    ownership of such entity.

2. License Grants and Conditions
--------------------------------

2.1. Grants

Each Contributor hereby grants You a world-wide, royalty-free,
non-exclusive license:

(a) under intellectual property rights (other than patent or trademark)
    Licensable by such Contributor to use, reproduce, make available,
    modify, display, perform, distribute, and otherwise exploit its
    Contributions, either on an unmodified basis, with Modifications, or
    as part of a Larger Work; and

(b) under Patent Claims of such Contributor to make, use, sell, offer
    for sale, have made, import, and otherwise transfer either its
    Contributions or its Contributor Version.

2.2. Effective Date

The licenses granted in Section 2.1 with respect to any Contribution
become effective for each Contribution on the date the Contributor first
distributes such Contribution.

2.3. Limitations on Grant Scope

The licenses granted in this Section 2 are the only rights granted under
this License. No additional rights or licenses will be implied from the
distribution or licensing of Covered Software under this License.
Notwithstanding Section 2.1(b) above, no patent license is granted by a
Contributor:

(a) for any code that a Contributor has removed from Covered Software;
    or

(b) for infringements caused by: (i) Your and any other third party's
    modifications of Covered Software, or (ii) the combination of its
    Contributions with other software (except as part of its Contributor
    Version); or

(c) under Patent Claims infringed by Covered Software in the absence of
    its Contributions.

This License does not grant any rights in the trademarks, service marks,
or logos of any Contributor (except as may be necessary to comply with
the notice requirements in Section 3.4).

2.4. Subsequent Licenses

No Contributor makes additional grants as a result of Your choice to
distribute the Covered Software under a subsequent version of this
License (see Section 10.2) or under the terms of a Secondary License (if
permitted under the terms of Section 3.3).

2.5. Representation

Each Contributor represents that the Contributor believes its
Contributions are its original creation(s) or it has sufficient rights
to grant the rights to its Contributions conveyed by this License.

2.6. Fair Use

This License is not intended to limit any rights You have under
applicable copyright doctrines of fair use, fair dealing, or other
equivalents.

2.7. Conditions

Sections 3.1, 3.2, 3.3, and 3.4 are conditions of the licenses granted
in Section 2.1.

3. Responsibilities
-------------------

3.1. Distribution of Source Form

All distribution of Covered Software in Source Code Form, including any
Modifications that You create or to which You contribute, must be under
the terms of this License. You must inform recipients that the Source
Code Form of the Covered Software is governed by the terms of this
License, and how they can obtain a copy of this License. You may not
attempt to alter or restrict the recipients' rights in the Source Code
Form.

3.2. Distribution of Executable Form

If You distribute Covered Software in Executable Form then:

(a) such Covered Software must also be made available in Source Code
    Form, as described in Section 3.1, and You must inform recipients of
    the Executable Form how they can obtain a copy of such Source Code
    Form by reasonable means in a timely manner, at a charge no more
    than the cost of distribution to the recipient; and

(b) You may distribute such Executable Form under the terms of this
    License, or sublicense it under different terms, provided that the
    license for the Executable Form does not attempt to limit or alter
    the recipients' rights in the Source Code Form under this License.

3.3. Distribution of a Larger Work

You may create and distribute a Larger Work under terms of Your choice,
provided that You also comply with the requirements of this License for
the Covered Software. If the Larger Work is a combination of Covered
Software with a work governed by one or more Secondary Licenses, and the
Covered Software is not Incompatible With Secondary Licenses, this
License permits You to additionally distribute such Covered Software
under the terms of such Secondary License(s), so that the recipient of
the Larger Work may, at their option, further distribute the Covered
Software under the terms of either this License or such Secondary
License(s).

3.4. Notices

You may not remove or alter the substance of any license notices
(including copyright notices, patent notices, disclaimers of warranty,
or limitations of liability) contained within the Source Code Form of
the Covered Software, except that You may alter any license notices to
the extent required to remedy known factual inaccuracies.

3.5. Application of Additional Terms

You may choose to offer, and to charge a fee for, warranty, support,
indemnity or liability obligations to one or more recipients of Covered
Software. However, You may do so only on Your own behalf, and not on
behalf of any Contributor. You must make it absolutely clear that any
such warranty, support, indemnity, or liability obligation is offered by
You alone, and You hereby agree to indemnify every Contributor for any
liability incurred by such Contributor as a result of warranty, support,
indemnity or liability terms You offer. You may include additional
disclaimers of warranty and limitations of liability specific to any
jurisdiction.

4. Inability to Comply Due to Statute or Regulation
---------------------------------------------------

If it is impossible for You to comply with any of the terms of this
License with respect to some or all of the Covered Software due to
statute, judicial order, or regulation then You must: (a) comply with
the terms of this License to the maximum extent possible; and (b)
describe the limitations and the code they affect. Such description must
be placed in a text file included with all distributions of the Covered
Software under this License. Except to the extent prohibited by statute
or regulation, such description must be sufficiently detailed for a
recipient of ordinary skill to be able to understand it.

5. Termination
--------------

5.1. The rights granted under this License will terminate automatically
if You fail to comply with any of its terms. However, if You become
compliant, then the rights granted under this License from a particular
Contributor are reinstated (a) provisionally, unless and until such
Contributor explicitly and finally terminates Your grants, and (b) on an
ongoing basis, if such Contributor fails to notify You of the
non-compliance by some reasonable means prior to 60 days after You have
come back into compliance. Moreover, Your grants from a particular
Contributor are reinstated on an ongoing basis if such Contributor
notifies You of the non-compliance by some reasonable means, this is the
first time You have received notice of non-compliance with this License
from such Contributor, and You become compliant prior to 30 days after
Your receipt of the notice.

5.2. If You initiate litigation against any entity by asserting a patent
infringement claim (excluding declaratory judgment actions,
counter-claims, and cross-claims) alleging that a Contributor Version
directly or indirectly infringes any patent, then the rights granted to
You by any and all Contributors for the Covered Software under Section
2.1 of this License shall terminate.

5.3. In the event of termination under Sections 5.1 or 5.2 above, all
end user license agreements (excluding distributors and resellers) which
have been validly granted by You or Your distributors under this License
prior to termination shall survive termination.

************************************************************************
*                                                                      *
*  6. Disclaimer of Warranty                                           *
*  -------------------------                                           *
*                                                                      *
*  Covered Software is provided under this License on an "as is"       *
*  basis, without warranty of any kind, either expressed, implied, or  *
*  statutory, including, without limitation, warranties that the       *
*  Covered Software is free of defects, merchantable, fit for a        *
*  particular purpose or non-infringing. The entire risk as to the     *
*  quality and performance of the Covered Software is with You.        *
*  Should any Covered Software prove defective in any respect, You     *
*  (not any Contributor) assume the cost of any necessary servicing,   *
*  repair, or correction. This disclaimer of warranty constitutes an   *
*  essential part of this License. No use of any Covered Software is   *
*  authorized under this License except under this disclaimer.         *
*                                                                      *
************************************************************************

************************************************************************
*                                                                      *
*  7. Limitation of Liability                                          *
*  --------------------------                                          *
*                                                                      *
*  Under no circumstances and under no legal theory, whether tort      *
*  (including negligence), contract, or otherwise, shall any           *
*  Contributor, or anyone who distributes Covered Software as          *
*  permitted above, be liable to You for any direct, indirect,         *
*  special, incidental, or consequential damages of any character      *
*  including, without limitation, damages for lost profits, loss of    *
*  goodwill, work stoppage, computer failure or malfunction, or any    *
*  and all other commercial damages or losses, even if such party      *
*  shall have been informed of the possibility of such damages. This   *
*  limitation of liability shall not apply to liability for death or   *
*  personal injury resulting from such party's negligence to the       *
*  extent applicable law prohibits such limitation. Some               *
*  jurisdictions do not allow the exclusion or limitation of           *
*  incidental or consequential damages, so this exclusion and          *
*  limitation may not apply to You.                                    *
*                                                                      *
************************************************************************

8. Litigation
-------------

Any litigation relating to this License may be brought only in the
courts of a jurisdiction where the defendant maintains its principal
place of business and such litigation shall be governed by laws of that
jurisdiction, without reference to its conflict-of-law provisions.
Nothing in this Section shall prevent a party's ability to bring
cross-claims or counter-claims.

9. Miscellaneous
----------------

This License represents the complete agreement concerning the subject
matter hereof. If any provision of this License is held to be
unenforceable, such provision shall be reformed only to the extent
necessary to make it enforceable. Any law or regulation which provides
that the language of a contract shall be construed against the drafter
shall not be used to construe this License against a Contributor.

10. Versions of the License
---------------------------

10.1. New Versions

Mozilla Foundation is the license steward. Except as provided in Section
10.3, no one other than the license steward has the right to modify or
publish new versions of this License. Each version will be given a
distinguishing version number.

10.2. Effect of New Versions

You may distribute the Covered Software under the terms of the version
of the License under which You originally received the Covered Software,
or under the terms of any subsequent version published by the license
steward.

10.3. Modified Versions

If you create software not governed by this License, and you want to
create a new license for such software, you may create and use a
modified version of this License if you rename the license and remove
any references to the name of the license steward (except to note that
such modified license differs from this License).

10.4. Distributing Source Code Form that is Incompatible With Secondary
Licenses

If You choose to distribute Source Code Form that is Incompatible With
Secondary Licenses under the terms of this version of the License, the
notice described in Exhibit B of this License must be attached.

Exhibit A - Source Code Form License Notice
-------------------------------------------

  This Source Code Form is subject to the terms of the Mozilla Public
  License, v. 2.0. If a copy of the MPL was not distributed with this
  file, You can obtain one at https://mozilla.org/MPL/2.0/.

If it is not possible or desirable to put the notice in a particular
file, then You may include the notice in a location (such as a LICENSE
file in a relevant directory) where a recipient would be likely to look
for such a notice.

You may add additional accurate notices of copyright ownership.

Exhibit B - "Incompatible With Secondary Licenses" Notice
---------------------------------------------------------

  This Source Code Form is "Incompatible With Secondary Licenses", as
  defined by the Mozilla Public License, v. 2.0.
```
