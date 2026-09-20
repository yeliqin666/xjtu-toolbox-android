# 校徽素材

本目录放「合成西交大」用的九所 C9 高校校徽，当前是 256×256 的 WebP，单张约 30KB。
文件名与 `index.js` 里的 `LEVELS` 数组一一对应，顺序就是合成链（从小到大）：

| 文件名 | 高校 |
| --- | --- |
| `game_c9_hit.webp` | 哈工大 |
| `game_c9_ustc.webp` | 中科大 |
| `game_c9_nju.webp` | 南大 |
| `game_c9_zju.webp` | 浙大 |
| `game_c9_fudan.webp` | 复旦 |
| `game_c9_sjtu.webp` | 上交 |
| `game_c9_pku.webp` | 北大 |
| `game_c9_thu.webp` | 清华 |
| `game_c9_xjtu.webp` | 西交大 |

## 换素材

同名覆盖即可，不用改代码：

- **格式**：`.webp` 优先于同名 `.png`，两者都没有就自动退回 `index.js` 里的
  `drawPlaceholder()`——画一个纯色圆加校名，缺素材也能正常玩。
- **尺寸**：正方形、透明底，边长不限。贴图缩放按图片的 `naturalWidth` 实时算，
  换成 512 或 128 都不会变形，只是清晰度不同。
- **合成链顺序**：改 `index.js` 里 `LEVELS` 数组的顺序，其余逻辑不用动。

素材来源与版权说明见仓库根目录的 `THIRD_PARTY_NOTICES.md`。
