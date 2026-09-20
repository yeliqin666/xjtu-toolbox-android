# 「合成西交大」小游戏 —— 第三方版权声明

本游戏（`app/src/main/assets/games/merge/`）在
[moonfloof/suika-game](https://github.com/moonfloof/suika-game) 的基础上改写而成，
玩法沿用自「合成大西瓜 / Suika Game」。

## moonfloof/suika-game —— Unlicense

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

## Matter.js —— MIT License

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

## 校徽占位素材

C9 高校校徽图片当前为占位状态（运行时用 canvas 现画，见
`app/src/main/assets/games/merge/img/README.md`），不涉及任何第三方素材版权；
待仓库主提供真实校徽图后，需要在此另行补充对应的版权声明。
