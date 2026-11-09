# 主页可调整面板：交互与材质规格

这份文档回答需求「补充 50 条」里每一条「需明确」——**先定死行为，再谈实现**。
每条的「状态」只有两种取值：

- `已实现`：代码里就是这么干的，落点写在同一行；
- `待实现`：决定已经定下，但这一批没写（排期见文末），验收时按本文档判定。

参数（格数、dp、毫秒）都不写死在文档里当圣旨，而是「由内容模型推出来的」，改卡片内容模型时要连带复核。

## 零、两套布局：经典分类卡（回落）+ 卡片组

首页只有两套实现，由 `SettingsStore.homeLayout`（`classic` / `cards`）在
`feature/home/.../HomeScreen.kt` 这个薄分派里选：

| 布局 | 入口 | 说明 |
|---|---|---|
| 经典分类卡 `classic` | `HomeClassicScreen` | 刊头 + 头卡 + 分类卡，只做回落 |
| 卡片组 `cards` | `HomeCardGroupScreen` | **本文档描述的这一套**：可编辑的卡片组网格 |

dev 上原来那套「小组件」**不是第三个选项**：它和卡片组本来就是同一个功能的两代，已经并进
卡片组——位置模型、拖拽、分组用卡片组的，材质、一镜到底接线、付款码、内容分档这些能力从
小组件那边搬了过来。老存档里的 `home_layout = widgets` 读出来按 `cards` 处理；没设置过的也
是 `cards`。

**背景 / 液态玻璃 / 顶栏排法三组设置两套共用**（落 `SettingsStore`）；只有卡片组才有的
（网格开关、卡片圆角、背景不透明度、导出 / 导入布局、恢复默认分组和布局）落在
`HomeLayoutStore`，切到经典分类卡时那几块不画。本文档其余各节只描述 `cards` 这一套。

---

## 一、材质与视觉

| # | 决定 | 落点 | 状态 |
|---|---|---|---|
| 1 | 液态玻璃**只用** App 内现成实现：`com.kyant.backdrop` 的 `drawBackdrop`（底栏、玻璃顶栏、头卡同一套），三要素齐全——`blur + vibrancy` 之外还有 `lens` 折射（边缘弯光）、`Highlight` 轮廓高光、`floatingGlassShadow` 环境光投影；毛玻璃是另一条独立路径：`squircleClip + background + squircleBorder + appCardShadow`。两者靠 `liquidCard` 开关切换（**2026-11 起默认液态玻璃**：`home_liquid_card` 缺省为真），**不允许**用毛玻璃调参去"模拟"液态玻璃；**没有折射开关**（2026-11 维护者把那一项整个删了）：浮在内容上的（顶栏圆钮 / 悬浮球 / 组删除钮）恒采样恒折射，卡片按「背景上有没有细节」自动决定要不要这一整套，判断交给渲染侧。**纯色 / 渐变背景上没有壁纸可采**（或壁纸已被模糊糊平）：那时**不采样**，改成同一个 `drawBackdrop` 加 `sampling = false`（vendored `:thirdparty:backdrop` 加的开关）：不录那层采样结果、`effects` 一概不跑、内容也不再压进 `CompositingStrategy.Offscreen`，而 `onDrawSurface` / 内容 / 高光 / 投影仍由库按原顺序画——**光影一条不少**（与采样版逐像素同一份代码），省掉的是采样、blur / lens / vibrancy 三趟 render effect 与那层离屏；**这一档不铺底色**：卡片完全透明，只剩轮廓高光（`Highlight`）与外面那圈 `floatingGlassShadow`；需要染色的是角标（`CountBadge` 走主题色半透明底），卡片不染。2026-11 这块底色连续踩了三次：深色 `Black 26%` 整块沉下去（比周围暗）、浅色 `White 52%` 比采样版多蒙 10% 白（雾蒙蒙）、改成与采样档同一层（`liquidFill`）仍然「像蒙了一层白」——结论就是**玻璃片本身该是透的**，暗部只出现在外面那圈投影里。有壁纸细节时照旧 `sampling = true`，模糊与折射都在（要不要这一整套只看这个 `glassHasDetail`，没有用户开关）。顶栏那条带子已经不吃采样层了（§48c）。**换形状 / 换材质必须换节点**：`drawBackdrop` 的离屏层是「建层那一刻」按 shape 建好的，之后只改 shape 不会重建它——症状是模糊层还是旧圆角、而高光外框（逐帧描边）已经是新圆角，杀进程重启才对齐。卡片那层因此用 `key(radius, liquid, blurGlass, refraction)` 把整块卡面换新节点（`HomeTileBoard.kt` · `HomeTileItem`），圆角滑条现在松手就通知首页重读（`HomeSettingsSheet.kt` · `SettingsSliderRow.onValueChangeFinished`） | `HomeTileBoard.kt` · `HomeTileFaceBox` / `HomeTileItem` / `GroupDeleteButton`、`HomeAddTileOrb.kt` · `orbGlass`、`HomeSettingsSheet.kt` | 已实现（2026-11 修「改圆角要重启才同步」） |
| 2 | 背景图**铺满全屏含顶栏**；非玻璃风格顶栏透明。**顶栏上还剩的玻璃只有圆钮**：它糊 / 折射的是「此刻盖住的东西」——圆钮采页面**内容层**（`pageBackdrop`＝背景层＋滚动内容一起录，顶栏本身在层外，否则会采到自己形成回环），所以刊头、卡片滚过来会被糊进去、也会在圆钮里被扭一下，而不是糊一张固定不动的壁纸（2026-11 按两次实测要求改；圆钮的采样源走 `LocalHomeBarBackdrop`，编辑态三个图标与宿主给的扫码 / 搜索 / 通知一起生效）。内容层在 `recordPageLayer = (collapsible && liquid) \|\| (liquid && editing.on)` 时才录——只剩液态玻璃的顶栏圆钮与编辑态悬浮条要采它；**顶栏那条带子从 2026-11 起不再采样**（改画黑 / 白渐变遮罩，见 §48c），所以这一层不再因为「玻璃风格顶栏」而被录。**卡片 / 悬浮球**仍采背景层（`tileBackdrop`）：它们贴在壁纸上，采整页会把自己和邻居也采进去——但这一层只在**液态玻璃开着且背景上真有壁纸**时才录（纯色 / 渐变没有信息可采，那时卡片走「不采不糊 + 薄色」，见 §1）。顶栏按钮无白描边 | `HomeScreen.kt` · `HomeCollapsingBar` / `pageBackdrop` / `tileBackdrop` / `LocalHomeBarBackdrop`、`app/main/MainScreen.kt` · `HomeRoundAction` | 已实现（本批修） |
| 3 | 背景图**固定** `ContentScale.Crop` 居中铺满（不变形、不重复），**不开放**用户缩放 / 移动取景——它和页面滚动、双击手势会抢指针，收益远小于冲突；可调项只有「模糊程度」0–10dp 一条（图片档），卡片组另有「背景不透明度」20–100%。**「亮度」滑条连同功能一起删了**（2026-11）：它和不透明度调的是同一件事（壁纸配主题底色），两条一起拨只会互相抵消；键 `home_wallpaper_brightness` 不再读写，渲染侧也不再传（壁纸按原图铺） | `HomeSettingsSheet.kt` · `HomeSettingsBody` / `SettingsSliderRow`、`HomeBackgroundPreference.kt`、`HomeWallpaper.kt` | 已实现（2026-11 删亮度） |
| 4 | 背景变化后**不做**卡片对比度联动补偿。理由：壁纸太花是用户的审美选择，自动压暗会让"液态玻璃透出壁纸"这个卖点消失。保底只做两件事：文字恒用主题 `onSurface`、液态玻璃内部 blur 恒为 8dp 保证可读 | `HomeTileBoard.kt` · `HomeTileFaceBox` | 已实现 |
| 5 | 组标题颜色默认 = `colorHex` 为空 → 主题 `onSurface`；可选范围 = 全应用同一套 `AppColorPicker`（色板 + **Hex 输入框**），对话框里那个开关叫「添加颜色」（关 = 不设色、跟随主题，存空串）；深色模式把颜色向白 `lerp 0.25` 提亮，浅色原样使用。**标题文本本身不再由用户设**（见 §40），所以「分组设置」这个弹窗里只剩颜色一项 | `HomeTileBoard.kt` · `GroupHeader`、`HomeScreen.kt` · `GroupEditDialog` | 已实现 |
| 6 | 标题为空 → 标题行**整行不画**，不留占位高度；组与组之间仍有 `GROUP_GAP` 的固定间距 | `HomeTileBoard.kt` · `HomeGroupSection` | 已实现 |
| 7 | 编辑态顶栏为纯图标 ✕ / ⚙ / ✓，视觉与常规态同一 `HomeBarIconButton`（玻璃圆钮、无边框）。语义见 §35/§36。**没有禁用态**：编辑态里这三个动作恒可用 | `HomeScreen.kt` · `HomeCollapsingBar` | 已实现 |

## 二、尺寸与内容自适应

| # | 决定 | 落点 | 状态 |
|---|---|---|---|
| 8 | 尺寸边界：最小 **1×1**（正方格：图标 + 名称），最大 **4×4**。网格开 → 整数步进（`snap` 四舍五入）；网格关 → 0.5 步进 | `HomeGridMath` · `MIN_SPAN`/`MAX_SPAN`/`clampSpan` | 已实现 |
| 9 | 排版按格数分**四档**（表见下，档位与「小组件模式」那套 1×1 / 2×1 / 1×2 / 2×2 一一对应）：1×1 图标 + 名称、横条图标 + 名称 + 大字数据、竖条名称 + 大字数据 + 说明、大方块再压水印图标并放大字号；大尺寸靠"多一行信息"提密度，**不是**等比放大 | `HomeTileBoard.kt` · `HomeTileFaceBox` | 已实现 |
| 10 | 形态由**实时**格数决定（`animW`/`animH`，缩放过程中逐帧判断），切档时 160ms `Crossfade` 过渡，不等松手。**尺寸也走弹簧**（缩放不再 snap）：数据是一格一格跳的，snap 把这些跳变原样画出来会一顿一顿 | 同上 | 已实现（缩放动画按 2026-11 复核加） |
| 11 | 档位阈值取**半格（1.5）**：`w、h` 都 < 1.5 → 1×1；`h < 1.5` → 横条；`w < 1.5` → 竖条；其余 → 大方块。判据只有"几格宽 × 几格高"，不再从像素可用高度反推——网格统一成正方格之后，格数本身就是内容的容量单位 | `HomeTileBoard.kt` · `HomeTileShape` 的 `when` | 已实现（2026-11 统一正方格） |
| 12 | 网格基础单元是**正方格**：一格的高恒等于一格的宽（= `(组宽 + 缝) / 列数`），一组的高 = `格数 × 边长 − 缝`。原先的「网格下留出文字空间」开关（88dp / 46dp 两档单元高度，键 `home_text_space`）**已删除**：同一页不该有两套尺寸设计 | `HomeTileBoard.kt` · `HomeGroupSection`、`HomeDragSession.kt` · `buildBoardGeometry` | 已实现（2026-11 统一正方格） |
| 13 | 切换尺寸只换排版、**数据不动**：槽位存的是格数，重算的只是"格 → 像素"的比例；四档外观的差别全部由 `face.stat` / `statDetail` 与那条「大字 / 一句话」判定派生，所以同一个功能在任何尺寸下强调的都是同一件事 | `HomeTileBoard.kt` · `HomeTileFaceBox` | 已实现 |

**形态档位表（§9/§11）**

| 档 | 条件 | 排版 | 信息 |
|---|---|---|---|
| 1×1 | `w < 1.5` 且 `h < 1.5` | 图标（34dp）在上、名称在下，居中 | 名称（**不**用状态数字顶替图标） |
| 横条 | `h < 1.5`（`w ≥ 1.5`） | 图标左（30dp）+ 右侧三行 | 小号名称 + 大字数据/说明 + 一行细节 |
| 竖条 | `w < 1.5`（`h ≥ 1.5`） | 图标（25dp）/ 小号名称 / 大字数据 / 细节 | 同上；没有状态的功能改成「图标 + 名称 + 说明」居中 |
| 大方块 | 其余 | 右上角淡水印图标 + 左列三行 | 小号名称 + 更大一档的数据（26sp）+ 细节 |

## 三、拖拽、缩放与排序

| # | 决定 | 落点 | 状态 |
|---|---|---|---|
| 14 | 跟手：**所有拖拽手势只挂在页面根 Box 自己的一个修饰符上**（`Modifier.homeDragLayer`，同 AOSP Launcher3 的 `DragLayer`）。**不能做成盖在上面的兄弟覆盖层**——兄弟层一旦带 `pointerInput` 就会独占整条命中路径，把下面的滚动列 / 顶栏 / 卡片全挡掉（实测：除挂在它上层的悬浮条，其余什么都点不动）；挂成根 Box 的修饰符则是「祖先」，子节点照样先收 Main pass。它给的 `PointerInputChange.position` 直接就是**页面根局部坐标**，板内坐标 = 它 − `boardOrigin`（组区容器左上角）。`boardOrigin` **必须现读活坐标**（`rootCoords.localPositionOf(boardCoords)`），且在拖拽期间**每帧**刷（每帧维护循环 + 每次 move + 落盘前）。位置**一律不许用 `boundsInRoot()`**：它默认按**裁剪边界**取框，网格区一滚出视口，拿到的就是「视口顶」——原点从此冻在那一刻（`positionInRoot()` 读的是布局位置，不受裁剪影响）。回调 `onGloballyPositioned` 只当兜底，页面滚起来之后它不保证每帧都到，值一停住落点就整体偏「滚过的距离」——卡片是 Column 排的照样滚得对，所以现场看着就是「卡片对、网格不跟滚动」（2026-11 修）。全程只有这一处减法——**不回读任何块的 `boundsInRoot`、不逐块登记位置、不做差分累加、也不手工掺 `scrollY`**，所以弹簧动画的中间帧污染不到坐标（旧实现正是拿动画中间帧当基准才落点飘）。拖拽全程 **live preview**：每帧 `layout = f(base 快照, 手指内容坐标)` 幂等重算，被拖块画成落点占位框、真身由顶层幽灵块跟手、其它块弹簧让位；松手才把结果写进草稿一次。**拖动中从不写草稿**，所以手势被抢走时不需要「回退临时状态」——草稿根本没被碰过 | `HomeDragLayer.kt`、`HomeDragSession.kt` · `refreshBoardFromCoordinates`/`peekLiveBoardOrigin`、`HomeScreen.kt` · `liveLayout`/`settleTileDrop`、`HomeTileBoard.kt` · `DropSlot` | 已实现（2026-11 拖拽二次重构；原点跟滚动按 2026-11 复核改） |
| 15 | 每个卡片右下角恒有缩放把手——图示是**主题色的直角括线（L 形，尖角带一点圆角美化）**，热区 30dp。括线标的是**这一格的右下角**，跟卡片形状无关：**不吃用户的「卡片圆角」滑条**，臂长/内缩/尖角圆角都是固定常量（`RESIZE_GRIP_ARM` = 14dp、`RESIZE_GRIP_INSET` = 2dp、`RESIZE_GRIP_CORNER` = 4dp，内缩只为让 2.5dp 描边不被裁），也不要求它与卡片「内外重叠」——圆角拖到圆饼时压在透明角上也算正常（2026-11 维护者明确）。早先画的是「把右下角那段圆弧染成主题色」，圆角一大就跟着跑、看着像卡片的圆角上了色。把手只画记号、不接手势：命中与拖动全在 `HomeDragLayer` 按快照几何判定（`HomeBoardGeometry.hit` 区分卡片体 / 缩放把手 / 移除按钮 / 组排序把手 / 放行）。缩放尺寸按 **AOSP `AppWidgetResizeFrame` 的绝对差值**算：`尺寸 = 起手尺寸 + (手指当前内容坐标 − 起手内容坐标) / 单元`，每帧从 baseline 重算、**不累积增量**，所以把手自己随尺寸移动也不会漂。**缩放过程就是实时预览**（其它块同步让位），松手固化；顶到屏幕边缘自动滚动时，块左上角在内容坐标里不动而手指内容坐标继续变大，尺寸自然跟着变大——不需要任何补算特例 | `HomeDragLayer.kt` · `hit`、`HomeScreen.kt` · `resizeCells`、`HomeTileBoard.kt` · `HomeTileItem`（只画把手） | 已实现（2026-11 拖拽二次重构） |
| 16 | 位置变化用 `spring(0.62, 520)`，尺寸变化用同一参数，禁止瞬移。组上下排序：**被拖组跟手浮起、其它组按「越过中线」纯函数让位**（`HomeGridMath.reorderTargetIndex/reorderShifts`），拖动中**不改数据**，列表始终堆叠不留空；松手一次性 `moveGroupWithin` 落盘（可见组序列 → 全量序列映射），被拖组补「手指位置 − 新槽位」的差值弹簧归位——三步（落盘 / 切结算态 / 值就位）放在协程同一个同步段里，中间不插帧，无重组风暴、无动画状态重建 | `HomeTileSpring`、`HomeGridMath` · 组排序几何、`HomeScreen.kt` · `settleGroupDrop`/`cancelGroupDrop` | 已实现（2026-11 拖拽二次重构） |
| 17 | **缩放「按住就能拖」，移动与组排序「先长按拿起」**（长按成立之前一个事件都不消费，所以从卡片上普通上下滑照样滚页面；拿起那一刻给一次 `HapticFeedbackType.LongPress`，走主题里被振动开关包过的 `LocalHapticFeedback`）：`HomeDragLayer` 在 **`PointerEventPass.Initial`** 上自己收 down（这一层是滚动列的祖先，Initial 又是祖先先看到），down 那一刻按快照几何定死归属——落点是拖拽目标就 `consume`（后到的 `verticalScroll` 在 Main pass 根本看不到它），落点是 `HomeHit.Pass`（移除按钮 / 删除组 / 组名 / 空白 / 悬浮条）就**不消费**，down 原样留给下层点击节点与滚动。**不用官方 `detectDragGestures`**：它的 slop 判定「发现变更已被别人消费过就取消」，而滚动列正好在这条路径上，所以这里自己判 slop（按下到超过 slop 之间什么都不做，点一下不算拖）。编辑态要滚页面就从 Pass 区域起手（组间空隙、网格空格、组标题、刊头 / 头卡、页面空白），拖到边缘还有自动滚动兜底。**进编辑态不跳、也不闪**：编辑态会给每组补标题行、把空组与「组件都打不开」的组显示出来，这些都在视口之上，光记住 `scroll.value` 补不回来。两步：①**布局之前**就用同一套几何函数（`buildBoardGeometry(headerlessIds=…)`）算出「编辑前 → 编辑后」锚点组的顶边差，在 `SideEffect` 里 `dispatchRawDelta` 打进滚动——等布局量完再补，那一帧的错位已经被画出来（闪一下）；②布局之后按实测的**锚点组**（`groupTops`/`idleTops`，进编辑前「视口里最靠上的那一组」）逐帧把残差补回原处，逐帧是因为组高带弹簧动画，残差归零收工（兜底，正常情况①已经补准了） | `HomeDragLayer.kt` · Initial pass、`HomeScreen.kt` · `predictEnterFix`/锚点 effect | 已实现（2026-11 拖拽二次重构；进编辑锚点按 2026-11 复核改，补「布局前预测」） |
| 18 | 编辑态**不做抖动**（按 2026-11 复核决定去掉了）：只有被拖那块画成落点占位框、被缩那块跟手，其余静止（让位是 live preview 驱动的弹簧位移，不是抖动）。**被拖块不再有「源组残影」**——手势不在块自己的节点上，块可以自由换组，无需钉死 | `HomeTileBoard.kt` · `HomeTileItem`/`DropSlot` | 已实现（2026-11 拖拽二次重构） |
| 19 | 跨组接收指示：手指落进某个**非源组**的矩形时，该组画一圈 1.5dp、55% 的 primary 圆角环。目标组判定是纯几何（组高 = 数据的纯函数），**组间空隙对半分**：上一组向下延半个间距、下一组向上延半个间距，两段区间首尾相接不重叠，所以这是位置的纯函数——**边界上不来回切，也不需要「记住当前组」的滞回** | `HomeDragSession.kt` · `HomeBoardGeometry.groupAt`、`HomeTileBoard.kt` · `HomeGroupSection` | 已实现（2026-11 拖拽二次重构改判定） |
| 20 | 边缘自动滚动：上下各 104dp 触发区，速度从 0 线性升到 14dp/帧（越靠边越快），只做纵向。自动滚动**只改 `scroll.value`**——手指板坐标是 `(pointer − boardOrigin)` 的派生量，而这条每帧维护循环**同时**把 `boardOrigin` 刷成实测值，于是 `scroll.value` 一变，目标组 / 落点格 / 缩放尺寸 / 组排序目标全部自动重算，不需要任何「按预估位置补算」的特例 | `HomeScreen.kt` · 每帧维护 effect、`pointerBoard` | 已实现（2026-11 拖拽二次重构；原点刷新按 2026-11 复核并入同一循环） |
| 21 | 碰撞只挤开**占据目标矩形**的块，无关块一律不动（模型层 `placeInGroup` 只遍历重叠者） | `HomeGridMath.placeInGroup` | 已实现，单测覆盖 |
| 22 | 被挤去向（网格模式）= **流式重排**：被压到的块按原阅读顺序依次摘除，各自重插到**离自己最近**的空位（`firstFreeSlot` 在**行数预算内**扫，扫不到才落最下方兜底）。「交换」是它的特例：被挤走的块重插时，拖拽块留下的原槽位恰好是最近空位。没有任何块会被无条件扔到组底，每一步位移最小、可预期，同一落点反复计算结果不变（预览幂等） | `HomeGridMath.placeInGroup/reflow/firstFreeSlot` | 已实现（2026-11 拖拽二次重构改语义），单测覆盖 |
| 22b | **行数预算**是「落点不飘」的物理保证：拖动中每帧把落位夹进「各组起手快照的行数（+ 本场按 §A8 明确多开的行）」（`HomeLayout.moveTile/addTileAt` 的 `rowBudget`，`HomeGridMath.placeInGroup` 的 `maxRows`），于是整场拖拽里**除了 §A8 那几行以外没有任何一个组会变高**，`dragGeometry`（按快照算的命中区间）与渲染出来的组位置**每一帧严格相等**；组一变高，下面的组被推下去而命中还按快照旧位置算，落点 → 布局 → 高度 → 落点就成了振荡回路（旧实现的病根）。§A8 开的行同时进几何表与渲染预算，所以那条不变量仍然成立 | `HomeGridMath.placeInGroup/maxRows`、`HomeLayout.moveTile/finishRows`、`HomeScreen.kt` · `rowBudget` | 已实现（2026-11 拖拽二次重构），单测覆盖 |
| 23 | 组件之间恒留标准缝隙：格坐标上贴边，渲染时每块四面各内缩半个 `HomeTileGap`(8dp)，所以肉眼看永远是 8dp | `HomeTileBoard.kt` · 宽高计算 | 已实现 |
| 24 | 跨组拖拽**永不**交换：被挤的块只能在本组找空位或落本组最下方，绝不跨组转移 | `HomeGridMath.placeAcross` | 已实现，单测覆盖 |
| 25 | 空位搜索顺序：按阅读顺序逐行扫（先上后下、先左后右），步进 = 网格 1 / 自由 0.5；拖动中在**行数预算内**扫（`maxRows`），扫不到就落最下方兜底；松手放开预算后**最下方仍无空位 → 追加一行**，永不拒绝放置 | `HomeGridMath.firstFreeSlot/bottomSlot` | 已实现（2026-11 加行数上界） |
| 26 | 自由拖拽（网格关闭）的碰撞处理是**联动压缩**而不是挤走：被压到的邻居往某一侧收拢边界让出空间（四种缩法里挑面积损失最小的），**收拢到最小尺寸还不够才挤到本组最下方**；落点前的边界吸附（`snapToNeighbors`，容差 0.28 格、不递归）保持不变 | `HomeGridMath.placeInGroup`（自由分支）、`compressToAvoid` | 已实现（本批修），单测覆盖 |
| 27 | 吸附阈值 0.28 格（≈34dp）**大于**正常缝隙 8dp，所以"进入阈值"不会和"贴边"混淆；吸附后继续拖，两侧边界一起走，中间保持 8dp | 同上 | 已实现 |
| 28 | 联动只平移不改尺寸，因此不会让任何块超 4×4 或小于 1×1；松手后各自独立 | 同上 | 已实现 |

## 四、设置与路由

| # | 决定 | 落点 | 状态 |
|---|---|---|---|
| 29 | 「主页设置」是**底部抽屉**（`OverlayBottomSheet`），复用设置页里"更新日志 / 用户协议"那一套抽屉范式，不再是二级页 | `HomeSettingsScreen.kt` · `HomeSettingsSheet` | 已实现（本批改） |
| 30 | 全局设置里的那一行和编辑态齿轮打开的是**同一个** `HomeSettingsSheet`（同一份 `HomeLayoutStore`、同一套内容）。齿轮**直接在原地打开抽屉**：不跳设置页、不走一次性旗标（`home_settings_pending` 机制已随本批删除） | `SettingsScreen.kt`、`HomeTab.kt`、`AppHomeHost.kt` | 已实现（本批修） |
| 31 | 编辑态打开抽屉**不丢改动、也不关编辑态**：齿轮只是把抽屉盖上来，手上的草稿原样留着，关掉抽屉接着摆；返回键优先关抽屉（`BackHandler(enabled = show)`） | `HomeScreen.kt`、`HomeSettingsSheet` | 已实现（本批修） |
| 32 | **关**网格：立即影响下一次拖动 / 缩放的吸附步进，已摆好的组件**不重排**。**开**网格：先二次确认（`WindowDialog`，文案与「恢复默认」同款），确认后 `HomeLayoutStore.enableGrid()` 把存档里那份自由摆位取整落盘（`HomeLayout.snapToGrid()`：宽高取整、逐块按阅读顺序找空位、收空行），并 bump `HomeLayoutGridRevision` 让编辑态把手上草稿也取整一次（只取整、不换内容）。**不可撤销**——之后再关掉网格也不会恢复原来的位置与大小 | `HomeScreen.kt` · `grid`、`HomeSettingsScreen.kt` · `confirmGrid`、`HomeLayoutStore.enableGrid`、`HomeLayout.snapToGrid` | 已实现（2026-11 加二次确认） |
| 33 | 「恢复默认分组和布局」：二次确认弹窗；重置范围 = 分组结构、顺序、标题、标题颜色、组件尺寸 / 位置、网格开关。**不含**背景图与材质开关——按钮文案写的是"分组和布局"，顺手清掉壁纸属于超出授权的破坏 | `HomeLayoutStore.reset`、`HomeSettingsScreen.kt` | 已实现（范围按此决定） |
| 34 | 液态玻璃开关与背景模糊**独立生效**：前者管卡片材质，后者管壁纸；液态玻璃内部的 blur（8dp）**不读**背景模糊值，否则两层模糊叠起来会糊成一片 | `HomeTileBoard.kt` · `HomeTileFaceBox` | 已实现 |

## 五、编辑态状态与反馈

| # | 决定 | 落点 | 状态 |
|---|---|---|---|
| 35 | ✕ = **丢弃改动并退出**，不弹确认（即使什么都没改也直接退） | `HomeScreen.kt` · `editing.cancel` | 已实现 |
| 36 | ✓ = **显式保存**草稿。本版**不提供**编辑态撤销 / 重做（撤销需要一个命令栈，和"点对勾才落盘"的模型叠起来容易出错） | 同上 | 已实现（无撤销） |
| 37 | 草稿**不**自动保存，App 被杀后回到上次保存的布局。理由：自动存草稿等于替用户拍板，下次打开会看到一个自己没确认过的桌面 | `HomeEditState` | 按此决定 |
| 38 | 新建组入口 = 编辑态底部的「＋ 新建分组」，插到末尾；**允许空组**，空组显示占位提示；空组的删除在组设置弹窗里（默认组与「常用功能」不可删） | `HomeScreen.kt` · `AddGroupRow`、`GroupEditDialog` | 已实现 |
| 39 | 删除非空组：组设置弹窗里直接删，**组内组件一起删**，不自动搬移（搬到哪都是猜）；默认组不给删 | 同上 | 已实现 |
| 40 | **分组标题可重命名**（2026-11 按需求加回）：编辑态**点组名**打开「分组设置」，最上面是 `分组名称` 输入框（最长 12 字、落盘前 trim），下面才是标题颜色；默认组（常用功能 / 各分类）与自建组都能改，空串 = 常规态整行不画、编辑态显示「未命名分组」占位。组名存进整页快照（`HomeLayoutCodec` 的组头一行），导出 / 导入布局时原样带走。「＋ 新建分组」仍然不预填名字（摆的是格子，认人靠位置） | `HomeCardGroupScreen.kt` · `GroupEditDialog`、`HomeTileBoard.kt` · `GroupHeader`、`HomeLayout.kt` · `HomeGroup.title` / `renameGroup` | 已实现（2026-11 加回改名） |
| 41 | 极值反馈：尺寸夹在 1–4，拖过极值后继续拖不再变化（把手不闪、不弹回） | `HomeTileBoard.kt` · 把手 `coerceIn` | 已实现 |
| 42 | 触感节点（计划）：进 / 出编辑态、放下落位、发生挤开、到达极值各一次轻震；强度沿用 App 现有 `SinkFeedback()` | `:shared:designsystem` · `SinkFeedback` | 待实现 |

## 六、动效与技术约束

| # | 决定 | 落点 | 状态 |
|---|---|---|---|
| 43 | 全部动效只用 Compose 官方 API：`spring()` / `animateFloatAsState` / `Animatable` / `Crossfade` / `infiniteRepeatable`。**没有**手写物理积分 | `HomeTileBoard.kt` | 已实现 |
| 44 | 底栏联动范围 = 弹簧参数调优 + 随滚动隐藏 / 显现 + 与主页材质一致。现有 `GlassBottomTabs` 已经是阻尼 + 玻璃材质，本批**不动**（无真机验证前改它风险大于收益） | `GlassBottomTabs` | 待实现 |

## 七、可访问性与性能

| # | 决定 | 落点 | 状态 |
|---|---|---|---|
| 45 | 1×1 的热区 = 整块卡片（正方格边长 ≈ 76dp ≥ 48dp），不额外缩小 | `HomeTileItem` 的 tap 手势 | 已实现 |
| 46 | 字体放大降级：文字一律 `maxLines = 1` + `Ellipsis`，行高固定 dp 而非随字体无限涨；极端放大时先省略名称、再隐去状态行 | `HomeTileFaceBox` | 已实现 |
| 47 | 深浅色参数**分开**：填充 浅 62% / 深 10% 白；细边 浅 0.7 / 深 0.1；标题色深色提亮 0.25；液态玻璃 blur 两档相同 | `HomeTileFaceBox`、`GroupHeader` | 已实现 |
| 48 | 性能降级：抖动只在编辑态挂动画；掉帧交给 Compose 的帧调度，**没有**用户可拨的降级开关（液态玻璃下原来那个「开启折射」子项 2026-11 已整项删除：真机上模糊与折射一样贵，留一半省不下什么；要省就换背景）。顶栏那条带子 **曾经用回** useplanes 的 progressive blur（七层不同半径叠着跑，滚动时每帧七次离屏采样 + 七次高斯模糊，**这是首页滚动最贵的两处之一**；之后一路压到两级阶梯、单层模糊）——**2026-11 最后一次改成不采样**：只画一条「暗色黑 / 亮色白 → 透明」的渐变遮罩，模糊、离屏层、采样源整个不要（见 §48c）；壁纸的 base64 解码从组合期同步改为 `Dispatchers.Default` 后台解码，并按数据串哈希缓存最后一张（未命中时先画占位渐变、图到后 180ms 淡入，命中缓存则首帧即图）。液态玻璃的 `drawBackdrop` 用 `remember` 按圆角 / 填充 / 阴影缓存（只有圆角随尺寸动画变时才重建），静止与位置动画不重复采样；纯色 / 渐变背景下**卡片与幽灵块那一层不再跑 lens**（`glassHasDetail`：没有细节就整档不采样，看不出差别就别付这份离屏采样）；「自定义图片 + 模糊程度 ≥ 5dp」也归进同一档（`SettingsStore.HOME_WALLPAPER_BLUR_HIDES_GLASS`，壁纸已被糊成色块，见 §A16）。顶栏圆钮 / 悬浮球 / 删除钮采的是内容层，永远有细节可糊，所以恒采样恒折射（2026-11，见 §A15 / §A16） | `HomeTileItem`、`HomeTileFaceBox`、`HomeScreen.kt` · `HomeBarBlurBand`、`HeroCustom.kt` · `HeroCustomBackground` | 已实现（本批修） |
| 48b | **第二轮性能优化（2026-11）：真机上什么有效、什么无效**。先说结论：**「缩着录再放大」这条路在 Android 上不成立，别走**。①**内容只渲染一遍**（`LayerBackdropNode` 由「上屏 + 再画一遍进采样层」改成「录进采样层 + 同一层上屏」，采样与上屏共用一次渲染，不额外占显存）——**有效，保留**；这条是**按嵌套层数相乘**的：壳的内容层里套着首页的内容层、首页里又有每张卡片的采样层，上游那样画首页要跑 4 遍。②~~采样层降采样 / 液体背景半分辨率渲染~~——**试过，真机上反而爆炸，已全部撤除**：`RenderEffect` 与 AGSL runtime shader 是在**设备空间**求值的，把采样层录成 1/n 大小、再靠画布 `scale(n)` 放大画回去，并不会让特效少算（硬件可能按设备分辨率重算一遍，再多付一次重采样）。所以 `drawBackdrop` 上不要再加 `downsample` 这类参数，液体背景也不要用离屏层缩半（`LiquidBackground.android.kt` 已回到直画）。**要在 Android 上省模糊，只能从「层数 / 半径 / 面积 / 出帧频率」四个维度省。**③`Highlight` / `Shadow` 的 `BlurMaskFilter` 只在半径真变了时才重建（`Paint.blur` 每次都 new 一个原生 MaskFilter）——有效，保留。④**顶栏那条 96dp 的带子最后干脆不采样了**：只画一条黑 / 白渐变遮罩（见下节 §48c）——层数、半径、面积怎么调都比不过它，因为真正的开销是「采样 + 高斯」本身。⑤**折射开关先升级成采样总开关、随后整项删掉**：真机上模糊与折射几乎一样贵，「留模糊」省不下什么；维护者最后拍板不要这个开关——浮在内容上的恒采样恒折射，卡片按「背景上有没有细节」自动决定。⑥**摘掉没人要的整屏采样层**：二级页的 `glassSource` 不再录（顶栏不采样了，40 来个页面每帧省一趟）、首页 `recordTileLayer` 不再为编辑态那个 30dp 删除圆钮强行录、`recordPageLayer` 去掉 `glass \|\|`——编辑态因此明显变轻。 | `thirdparty/backdrop`（`backdrops/LayerBackdropModifier.kt`、`HighlightModifier.kt`、`ShadowModifier.kt`，见该目录 `README.md`）、`shared/designsystem/.../LiquidBackground.android.kt` | 已实现（降采样那条已撤回） |
| 48c | **顶栏那条 96dp 的带子：七层模糊 → 两层 → 一层 → 最后不采样，只留一条渐变遮罩（2026-11 第六次真机反馈后定的）**。走过的路，别再走一遍：①useplanes 的 progressive blur，七层半径 0.75→18dp、每层一条渐变遮罩只留一段——每帧七次整屏离屏采样 + 七次高斯，首页最贵的两处之一；②一层 12dp + 折线遮罩——大面积大半径并不便宜，真机更慢；③两级阶梯 10dp / 3dp——开销 2/7，但两段半径的交界看得出硬边；④单层 8dp + 整带线性淡出——1/7，硬边没了，可它仍然是「采样 + 高斯」。中途一度改成不采样的黑 / 白渐变遮罩（`topBarScrim`），**2026-11-09 维护者认为质感不够，恢复为①的七层渐进模糊**（`Modifier.progressiveBlurBar`）。七层的开销只在画面变化的帧里付（滚动、动画）：静止时不重绘就不重算，真正的常驻开销来自持续出帧的源（见 §48b 记的底栏屁岱 ticker），要省先去掉那种源，而不是砍模糊。首页 96dp（`HomeCollapsingBar` 的 `bandBackdrop`）与二级页 / 宿主顶栏 / 校园卡（`glassBarSurface`）同一套；`glassSource` 重新录采样层。 | `shared/designsystem/.../glass/ProgressiveBlur.kt` · `progressiveBlurBar`、`GlassTopBar.kt` · `glassTopBar` / `glassSource`、`HomeCardGroupScreen.kt` · `HomeCollapsingBar`、`HomeClassicScreen.kt`、`MainScreen.kt` · `MainTopBar`、`CampusCardScreen.kt` | 已实现（待真机验收） |
| 49 | 基准来源：材质 = App 内 `drawBackdrop` + 底栏玻璃；抽屉 = 设置页既有 `OverlayBottomSheet` 范式；动画 = Compose 官方 spring；手势 = `detectDragGestures` 系统语义。**不自创** | 全文 | 按此决定 |
| 50 | 参数不写死：档位阈值取半格（§11），dp 由一格边长（正方格，量到的组宽推）决定（§9），时长沿用 App 既有弹簧参数（§43） | 全文 | 按此决定 |
| 51 | 顶栏排法做成主页设置里的一行开关「功能按钮统一右对齐」（`HomeLayoutStore.topActionsRight`，**默认开**），它换的是**整套改造前顶栏**：开 = 左侧「交小西」头像（随上滑淡入放大，停在顶部时看不见）+ 扫码 / 搜索 / 通知三个圆钮都在右侧 + **不画居中标题** + 左侧内边距 20dp；关 = 扫码单独站到最左边、标题绝对居中、右侧只留搜索与通知（§1.1 的排法）。**编辑态例外**：两种排法进了编辑态都是 ✕ / ⚙ / ✓ 三个纯图标，否则老排法下进了编辑态就出不来。宿主侧只换传进 `HomeScreen` 的 `topLeading` / `topActions` 两个槽位，头像与标题由 `HomeScreen` 自己按同一个键决定（`devTopBar`）。抽屉与落盘三端共用，但桌面 / web 的首页顶栏在外壳里，那一组靠 `SettingsRow.HOME_TOP_BAR` 不画 | `shared/navigation/.../HomeLayoutStore.kt` · `topActionsRight`、`feature/settings/.../HomeSettingsScreen.kt` · `HomeSettingsSheet`、`feature/home/.../HomeScreen.kt` · `HomeCollapsingBar` / `HomeBarAvatar`、`app/main/MainScreen.kt` · `rememberTopActionsRight` / `HomeTopActions`、`app/home/HomeTab.kt` | 已实现 |
| 52 | 主页背景三选一（`HomeLayoutStore.backgroundMode`：`solid` / `gradient` / `image`，抽屉里叫「主页背景」下拉）：**主题底色** = 主题 `surface`；**主题色渐变** = 主题色按 22% → 10% → 4%（深色 30% → 18% → 8%）融进底色、左上铺到右下、最后一档就是底色本身——**不铺纯主题色**（整屏浓色压掉卡片与正文，上半屏还像换了套配色）；**自定义图片** = 走原来的壁纸（模糊 / 透明度 / 移除只在图片模式下出现）。选图片会直接拉相册，选完才把样式切过去；清掉图片时样式退回主题底色。老存档没有这个键时按「有图=图片、没图=主题底色」推断，不做迁移。**换图立刻生效**：`HeroCustomBackground` 的解码状态按数据串哈希重建（`remember(哈希)` + `LaunchedEffect(哈希)`）——早前用 `produceState(key1 = 哈希)`，它内部那层 state 不随 key 重建、旧图一直留在 value 里，`value != null` 的守卫于是让新图永远不解码，表现就是「换完壁纸没反应、重启应用才生效」（2026-11 修） | `shared/navigation/.../HomeLayoutStore.kt` · `backgroundMode`、`feature/home/.../HomeScreen.kt` · `themeBackgroundGradient`、`feature/settings/.../HomeSettingsScreen.kt` · `BACKGROUND_MODE_LABELS`、`shared/designsystem/.../HeroCustom.kt` · `HeroCustomBackground` | 已实现 |

## 八、本轮（2026-11 复核）新增交互

| # | 决定 | 落点 | 状态 |
|---|---|---|---|
| A1 | 空行压缩：网格模式下 `moveTile` / `resizeTile` / `addTileAt` / `removeTile` **每次都收空行**——拖动中与松手都收（`HomeLayout.finishRows`），所以预览与落盘逐格一致；从前「只在松手那一次收」导致预览与落盘差一行，16278fd7 那轮为了对齐两者反过来取消松手压缩，空行就永远留着了。收的是**整行全空**：行里只要还有一块就不动。**一块也要压**（以前 `compactEmptyRows` 有个 `size < 2` 短路，「两块一组拖走上面那块」之后剩下的那块会永远停在第二行）；末行下标按 `contentBottom − 1` 算，别再引入一行幽灵行。组高在拖动中由行数预算钉住（渲染那一层），收空行只是块在组内上移，不会让下面的组跟着跳；松手后组高跟着数据收缩，下面的组按弹簧上移。**读盘时也补一遍**（`HomeLayoutStore.load` → `HomeLayout.compactGaps`）：老存档里的空行不会因为「用户没再拖一次」就一直留着。自由模式允许留空隙，不压 | `HomeGridMath.compactEmptyRows`、`HomeLayout.finishRows/compactGaps`、`HomeLayoutStore.load` | 已实现（2026-11 复核改） |
| A2 | 添加卡片用**右侧悬浮球**：进编辑态时**直接就是液态玻璃长条**（不再先播一段「球→长条」的展开动画，编辑态一进来就要能拖图标）；把手（圆形，示意可拖拽）在最上方，**单击它把长条收回成球**（再点球展开回来）、按住它整条跟手（松手自动吸回最近的侧边）；长条内是圆形功能图标（液态玻璃、可滚动），**长按拖出来**放到组里。**采样源恒为内容层**（`pageBackdrop` = 背景 + 滚动内容那一整层，和顶栏圆钮同一层）：悬浮条浮在所有东西之上，该糊的是「此刻压在它下面的那一片」——卡片、文字、壁纸全算在内。**不再按背景档位切**：早先有壁纸时改采背景层（`tileBackdrop`），透出来的永远是那张壁纸，卡片从它身下滚过去毫无变化（2026-11 按维护者要求改成一律采内容层，参照顶栏圆钮）。卡片本身仍走 §1 的「纯色 / 渐变不模糊 + 薄色」规则 | `HomeAddTileOrb.kt`、`HomeCardGroupScreen.kt` · `recordPageLayer` | 已实现 |
| A3 | 从悬浮球拖出的新块落到**指定格**（`addTileAt`）：2×1 进组、照样挤开、拖动中吃行数预算 / 松手收空行；拖动全程走同一份 live preview（落点占位框 + 让位），松手固化、幽灵吸附。新块 id 由 `HomeLayout.nextTileId` 分配——全局 `#N` 尾号只涨不回退，删了再加不撞号（撞号会让 `key` 与 `locate` 错乱）；旧快照里已撞号的块读盘时重编（`HomeLayoutCodec`）。悬浮球长按拖出走它**自己节点**上的 `detectDragGesturesAfterLongPress`（`HomeDragLayer` 对悬浮球矩形 `paletteRect` 放行），拖出后只写 `session.pointer`，落点仍由首页按快照派生 | `HomeLayout.addTileAt/nextTileId`、`HomeLayoutCodec`、`HomeAddTileOrb.kt` | 已实现（2026-11 拖拽二次重构） |
| A7 | 拖拽手势**集中在 `HomeDragLayer` 一个常驻覆盖层**（同 AOSP Launcher3 的 `DragLayer`）：用 `awaitEachGesture` + `awaitFirstDown(pass = Initial)` 自己收 down，因为要①比 `verticalScroll` 早一步决定抢不抢、②按快照几何把落点分流成「卡片体 / 缩放把手 / 组排序把手 / 放行」。`try/finally` 兜底：协程被取消（系统抢走 / 离开组合）也清场，不会卡死编辑态。悬浮球长按拖出仍用官方 `detectDragGesturesAfterLongPress`（自带 `onDragCancel`）。多指互斥：每次起手先 `finish()` 上一场；松手结算动画带代次（`ghostEpoch`/`reorderEpoch`）守卫，被新拖拽顶掉就不清场 | `HomeDragLayer.kt`、`HomeDragSession.kt`、`HomeScreen.kt`、`HomeAddTileOrb.kt` | 已实现（2026-11 拖拽二次重构） |
| A4 | 组标题行：右侧两个圆钮（改名 / 加功能）改成**一个液态玻璃删除按钮**（点出二次确认，默认组不给删）；**点组名**打开「分组设置」（不再走三点）；组名放大到 18sp；**删除按钮恒贴右**——组名那一格必须 `weight(1f)`（不能 `fill = false`，否则它那格剩下的空白不会还给行尾，按钮就被顶到半个剩余宽度处） | `HomeTileBoard.kt` · `GroupHeader/GroupDeleteButton`、`HomeScreen.kt` · `DeleteGroupDialog` | 已实现（贴右按 2026-11 复核修） |
| A5 | 「分组设置」的标题颜色用全应用同一套 `AppColorPicker`（色板 + **Hex 输入框**，两条路互相回填），开关是「添加颜色」——关着存空串（跟随主题），打开才出调色盘与 Hex，Hex 敲到一半解析不出来时「确定」禁用 | `HomeScreen.kt` · `GroupEditDialog` | 已实现 |
| A6 | 组里「加功能」的入口统一收进悬浮球（不再有每组一个 ＋），圆形功能图标可滚动、长按拖出 | `HomeAddTileOrb.kt` | 已实现 |
| A9 | **空卡组保存即删**：`HomeLayoutStore.save` 落盘前过一遍 `withoutEmptyGroups()`——编辑态里被搬空的组在常规态本来就是隐藏的，留着只是存档垃圾。**「恢复默认分组和布局」立即生效**：二次确认后抽屉自己合上（`HomeSettingsBody(onDismiss)`），首页编辑态**不退出**，但手上的草稿换成新的默认布局（只听 `HomeLayoutResetRevision`，不听普通 revision——后者在改背景 / 开关网格时也会动，跟着换会把正摆着的草稿冲掉），所以卡片当场重排、不需要再点保存 | `HomeLayout.withoutEmptyGroups`、`HomeLayoutStore.save/reset`、`HomeScreen.kt` · `resetRevision`、`HomeSettingsScreen.kt` | 已实现（2026-11 复核加） |
| A8 | **拖到组底端可以再起一行**：幽灵块的底边探出某一组的行数预算时（拖到最后一行之外、或落进组间空隙），给**这一组**加行接住它——网格模式下**取整到整行**（空位随网格一格一格扩展，拖着继续往下就继续长），自由模式按实际差值。加的行是**实时布局的幂等函数**：真被占住的行留着，没被占住（让位 / 收空行把卡片提回原行）的当帧就收掉，所以不会留一条空行等到松手才塌。加的行写在 `extraRows` 里，同时喂给几何表（`buildBoardGeometry(extraRows = …)`）与渲染侧的行数预算，两边一起长，所以「命中 == 画出来的」仍然成立；**只增不减**（本场不回退）是防振荡的关键——手指探出组底长一行、长完手指回到组内，若这时收回去就成了每帧来回摆。多出来的行没被占住时，松手由收空行（§A1）压掉 | `HomeScreen.kt` · `extraRows`/`growRowUnderGhost`、`HomeDragSession.kt` · `buildBoardGeometry` | 已实现（2026-11 复核加） |
| A10 | **退出编辑态即保存**：三处入口统一走 `saveAndQuitEdit()`——顶栏「对勾」、**系统返回键**（编辑态下返回键只退出编辑，不退出页面）、**切到别的页签 / 页面被销毁**（`LocalPageVisible` 变 false 或离开组合时补一次落盘）。理由：不落盘就退出会把用户摆的东西静悄悄丢掉；每次退出都问一遍「要不要保存」又太烦 | `HomeCardGroupScreen.kt` · `saveAndQuitEdit` / `BackHandler` / `LaunchedEffect(pageVisible)` / `DisposableEffect` | 已实现（2026-11 加） |
| A11 | **卡片状态数据按源 TTL 后台刷新**（`HomeStatsRefresher` 的 `Source`，不阻塞 UI、不逐帧算）：校车 `CampusBus` = **10 分钟**（下一班 + 余座，公开接口不登录）；学业进度 `DegreeProgress` = **1 天**（已修学分 / 要求学分，需教务登录；同一门课重修只算一次、只算通过的）。取不到就留空，卡片退回居中样式，不弹错 | `app/.../home/HomeStatsRefresher.kt` | 已实现（2026-11 加） |
| A12 | **卡片圆角可调**：主页设置「卡片」组一条滑条（`HomeLayoutStore.cardCorner`，键 `home_card_corner`，`0..40` dp，默认 **22**），**排在「液态玻璃」开关上方**（先定形状再定材质，2026-11 调序）。**卡片尺寸一点不变，只有形状变**：实际半径 = `min(设置值, 短边的一半)`，所以滑条拖到顶（40dp）时任何尺寸的卡片都**圆成饼**（常见 1×1 约 74dp，半径 37dp 即正圆），显示成「圆饼」；0 = 直角。占位框、拖拽幽灵、一镜到底转场都吃同一个半径，松手/转场时形状不跳 | `HomeLayoutStore.kt`、`HomeSettingsSheet.kt`、`HomeTileBoard.kt` · `HomeTileFaceBox`/`DropSlot`/`HomeTileItem`、`HomeCardGroupScreen.kt` · `HomeDragGhost` | 已实现（2026-11 加） |
| A13 | **悬浮条（编辑态右侧「添加卡片」）** = **一个外框的两个尺寸**，不是两棵树：长条 64dp 宽、球态是同一块玻璃缩成 46dp 正圆（200ms 过渡，只动外框：**把手中心是同一个点**，所以切换时它不跳、拖拽跟手也不受影响）。**长条恒为全圆**：圆角半径固定取半宽 32dp（两端半圆＝胶囊），**不吃**设置里那条「卡片圆角」——那条滑条只管卡片自己（2026-11 维护者定）。把手因此按常量让位：把手是 46dp 圆、两侧各留 9dp，半圆把直角吃掉后离顶 p 处的边界内缩 `r−√(r²−(r−p)²)`，要求 ≤ 9dp 解出 `p ≥ r−√(2rs−s²)`（`knobTopPadding`，r=32 时定值 9.75dp），所以把手永远整颗落在半圆里。**贴边一律按长条那 64dp 算**（`railPx/2`），球态因此内缩 9dp（与把手在长条里离边一致）。把手外观是 **582 那一版**：**平涂主题色圆钮**（`knobAlpha`，长条态 1、球态 0）+ 主题色拖拽图示，**不自带 `orbGlass`**——外框本身就是玻璃，再叠一层会在圆角处跟外框咬在一起、并且每帧多一次离屏采样。**名字栏**只在**滚动图标列**时横向拉出（**104dp**，往远离贴边那侧长）：图标列绝对位置不动，字像拉帘子带出来（`requiredWidth` 固定排版 + `clipToBounds`，不重排、不闪省略号）。104 是按最宽一行算的（2026-11 从 84dp 加宽：塞进计数圆标之后四个汉字的名字就要省略了）：外沿 10dp 安全边 + 15dp 圆标 + 5dp 间隙 + 名字（最长约 52dp）+ 图标侧 8dp 呼吸位 ≈ 90，再留一点给五六字的名字；**外沿那侧 10dp 是硬要求**——那里正是胶囊的圆弧，字与圆标贴上去会被弧边啃掉一角。**列表窗口一直铺到外框底边**（`listH = boxH − pad − ORB_BALL − ORB_GAP`，按 `shape` 现算而不是常量）：从前只铺到「行数 × 行高」，于是内容在外框底边上方约 10dp 处自己截断，屏幕上是「图标在中间被切一刀、下面还空着一条」——切的位置不在框的边缘；铺到底之后由外框那层 `clip(glassShape)` 裁，切的就是框本身，球 ↔ 长条过渡的每一帧也都贴着框底。滚到底时 `contentPadding(bottom = pad)` 给最后一行留一圈呼吸位（底边正是胶囊收成一点的圆弧）。收起只在三个「确认了别的意图」的时刻：**开始拖拽**（整条 / 拖出图标）、**点把手**、**别处按下**（`homeDragLayer` 的 `onDownOutsidePalette` = 失焦）；**滚动绝不收起**。名字栏露着时点把手 = 两段动画同时跑，等于直接从大矩形收成球。**名字栏里带一个计数**（`orbLabels` 那张帘子拉出来才画）：这张卡在**整页**上已经放了几块（跨组一起数，`HomeCardGroupScreen.tileCounts`），排在**图标与文字中间**，0 块不画——组内允许重复之后，光看名字认不出「是不是已经摆过了、摆了几个」。画法是一枚**主题色小圆标**（`shared:designsystem` 的 `CountBadge`：**不采样**，但半透明染色 + 白细高光边 + 同色柔光投影一个不少（且不开离屏层），直接叠在悬浮条那块玻璃上，见 §A14）。

**层次分两种拖拽**（2026-11 维护者定）：拖**已有卡片**时幽灵块在悬浮条**下面**（`GHOST_Z_INDEX = 20f` < `ORB_Z_INDEX = 24f`），卡片从悬浮条底下穿过去，图标列与名字栏不被整块盖住；从悬浮条**拖出新块**时反过来画在悬浮条**上面**（`PALETTE_GHOST_Z_INDEX = 28f`）——那一块正在找地方落，被挡住就不知道自己在放哪儿。**新块落回悬浮条自己那块玻璃里 = 不放置**：`paletteOverOrb`（手指是否落在 `paletteRect` 内）一为真就把 `targetGroup` 判成 null，于是拖动中不画落点占位框、松手不写草稿，幽灵飞回图标原位 | `HomeAddTileOrb.kt` · `OrbHandle` / `OrbTileList` / `OrbLabel` / `knobTopPadding`、`shared/designsystem/.../CountBadge.kt`、`HomeDragLayer.kt`、`HomeCardGroupScreen.kt` · `tileCounts` / `paletteOverOrb` / `GHOST_Z_INDEX` / `ORB_Z_INDEX` / `PALETTE_GHOST_Z_INDEX`、`HomeSettingsSheet.kt`（卡片圆角滑条） | 已实现（2026-11 第九版，待真机验收） |
| A14 | **角标（铃铛未读 / 悬浮条「已添加几块」）是同一枚玻璃小圆标**：`:shared:designsystem` 的 `CountBadge`——**不采样，也不用离屏层**（2026-11 维护者定）：它走 `staticGlassSurface` 直接画——高光是卡片同一份 `Highlight.Default.copy(alpha = 0.65f)`、投影是卡片同一份 `floatingGlassShadow(...)`（走 `drawBackdrop(sampling = false)`，高光与投影都由库自己画，参数全部从这两个库对象上读，所以和卡片不会漂成两套材质，见 §A16 ⑦），底色是半透明染色面，透出下面那块玻璃（悬浮条 / 圆钮）本身的颜色。不调 `drawBackdrop` 的理由是性能：它的离屏层跟有没有内容可采无关，而 15dp 的小圆标下面本来没东西可采（见 §A16 ⑦）。这与「纯色背景下的卡片」是同一条路。铃铛那枚传品牌红（`UnreadAccent`），悬浮条那枚吃主题色。位置钉在圆钮右上角**内侧**、与铃铛图形拉开，不再压在图形/玻璃圆弧上被削掉一块 | `shared/designsystem/.../CountBadge.kt` / `.../glass/StaticGlass.kt`、`feature/notice/.../InboxScreen.kt` · `InboxBell` / `InboxUnreadBadge`、`feature/home/.../HomeAddTileOrb.kt` · `OrbLabel` | 已实现（2026-11 不采样、也不开离屏层） |
| A15 | 主页设置抽屉这一轮（issue #372）：① 抽屉里三条滑条（背景不透明度 / 卡片圆角 / 模糊程度）统一用 MIUIX 的 `SliderPreference`——与上下 `SwitchPreference` / `OverlayDropdownPreference` 共用同一个 `BasicComponent`，内边距、左侧图标位、标题字号、末位数值全对齐；从前是手搓一行（标题贴 16dp、开关行的标题在 60dp），同一张卡里两套缩进。② 亮度滑条删除（理由见 §3）。③ 卡片圆角上移到液态玻璃开关上方（先形状后材质）。④ 纯色 / 渐变背景（= 没有壁纸可折）时「开启折射」**置灰并显示成关**，说明文字写清「折射与模糊在这种背景上看不出区别，为省性能已强制关闭」；卡片与拖拽幽灵那一层确实不再跑 `lens`（`cardRefraction = refraction && hasWallpaper`），顶栏圆钮 / 悬浮球采的是内容层、照旧吃开关。存盘里的值不丢，换回图片背景即恢复。（**2026-11 第六轮：这个开关已整项删除**——现在没有置灰、没有存盘值，行为就是「有细节整套上、没细节整档不采样」。）⑤ 原来那组「重置」改名**「备份与重置」**，里面三行：`导出布局到文件`（`HomeLayoutStore.saved` 按 `HomeLayoutCodec` 落盘格式写成一个固定名的 `.txt`）/ `从文件导入布局`（选文件 → 解不出来就只提示、不覆盖；解出来先问一次，确认后 `importLayout` 落盘并收起抽屉）/ `恢复默认分组和布局`。文件通道三端各一份 actual（`rememberSaveTextFile` / `rememberPickTextFile`：Android = SAF「新建文档 / 打开文档」，桌面 = `JFileChooser`，Web = 浏览器下载 + `<input type=file>`），同 `rememberHeroImagePicker` 的路子。连续值一律「拖的时候只写盘、松手通知首页重读」 | `HomeSettingsSheet.kt` · `SettingsSliderRow` / `HomeSettingsBody`、`HomeBackgroundPreference.kt`、`SettingsStore.kt` · `homeWallpaperBlur` / `homeBackground`、`shared/navigation/.../HomeLayoutStore.kt` · `saved` / `importLayout`、`shared/designsystem/.../platform/TextFile*.kt`、`HomeCardGroupScreen.kt` · `cardRefraction` | 已实现（待真机验收） |
| A16 | **主页第六轮（2026-11，同分支）**：① **组内允许重复**：`HomeLayout.addTile` / `addTileAt` 删掉「同组已有同路由就不加」的守卫，悬浮条拖出来、添加面板里都能把同一张卡再放一块（`HomeLayout` 的 `isConsistent` 仍保证不重叠；id 由 `nextTileId` 全局发号，不撞号）。**同功能的块与其他卡片没有任何特殊待遇**：落点上是哪一块就挤哪一块，走的是同一套 `placeInGroup` 挤开规则——2026-11 维护者明确「只删限制，不加条件」，别为「同功能」再发明特判。② **玻璃「没有细节」的第二档**：自定义图片但「模糊程度」≥ `HOME_WALLPAPER_BLUR_HIDES_GLASS`（= 5dp）时，卡片那层也不模糊、不折射——壁纸已经被糊成一片色块，卡片再采一遍与薄色底分不出，而这两层是全页最贵的。判据收敛成一个 `glassHasDetail = hasWallpaper && blur < 5`，渲染（`cardRefraction` / `blurGlass`）与设置面板共用。③ **置灰 ≠ 强制关**：抽屉里「开启折射」在没有细节时仍然显示**用户自己的那一档**（`checked = refraction`），只把 `enabled` 置灰；说明文字只有一句「当前背景设置下折射/模糊无明显效果，为节省资源已关闭」，**不加括号解释成因**（纯色 / 渐变与「壁纸糊了」两种成因共用这一句）。存盘值一个字不动。（**第六轮：开关已整项删除**，这一条只剩历史意义。）④ **默认液态玻璃 + 默认开折射**：`home_liquid_card` 缺省改为真（`SettingsStore.homeLiquidGlass` 与同键的 `HomeLayoutStore.liquidCard` 一起改，两处默认必须一致）；`home_liquid_refraction` 原本缺省真，**第六轮随开关一起删掉了这个键**。⑤ **从悬浮条拖出时不再顶掉已有卡片**：新块 id 由 `nextTileId` 发号后会再核一遍「有没有被占」（尾号解析不出来的老存档、导入撞号，都可能让「最大尾号 + 1」正好等于某一块的 id），同时 `HomeTileBoard` 里「把这一块画成落点占位框」的判定在**从悬浮条拖出且撞上真块**时直接放弃。症状是「手指一按住悬浮条上的图标，页面上对应的那张卡就消失了」——根因就是撞号的真块被当成被拖块，画成了空占位框。⑥ **从悬浮条拖出时不再顶掉已有卡片（两个根因都治了）**：㈠ 新块 id 由 `nextTileId` 发号后再核一遍「有没有被占」，撞了就往后挪（尾号解析不出来的老存档、导入撞号，都可能让「最大尾号 + 1」正好等于某一块的 id），`HomeTileBoard` 里「把这一块画成落点占位框」的判定同时在撞上真块时放弃——这是「手指一按住悬浮条图标，页面上对应的那张卡就消失了」这个症状。㈡ **同一轮编辑里刚放下的那张，再放同一张还是会消失**：`OrbTileIcon` 的手势在 `pointerInput(face.key)` 里只启动一次，回调 lambda 被闭包捕获在**很久以前那次组合**，里面带着当时的 `layout` 草稿——那份快照里没有本轮刚放下的卡，于是 `beginPalette` 拿旧快照当 base、id 也按旧快照重算，落盘时整页覆盖就把刚放下的那张抹了。三个回调现在都经 `rememberUpdatedState` 读最新值（`HomeDragLayer` 早就是这个写法）。⑦ **没有可糊可折的细节时不采样，而且要真的省下离屏层**（2026-11 维护者定，并要求「注意你现在正在用的方案有没有真的起到省性能的作用」）：`blurGlass == false && !refraction`（纯色 / 渐变，或壁纸已被模糊程度糊平）时 `HomeTileFaceBox` **连 `drawBackdrop` 都不进**——不能只把采样源换成空源：`DrawBackdropNode` 的离屏层跟有没有内容可采无关，源给成 `emptyBackdrop()` 也每帧 `record` + `draw` 一层卡片大小的层，卡片内容还一直被压在 `CompositingStrategy.Offscreen` 里，那样只省掉采样那一趟与 `blur` / `lens`，而这一档存在的理由就是省性能。所以把上游 `backdrop` 2.0.1 **vendor 进仓库**（`:thirdparty:backdrop`，Apache-2.0，根 `build.gradle.kts` 用 `dependencySubstitution` 顶替 Maven 坐标，调用点不用改），并给它加一个 `sampling: Boolean = true` 开关：`false` 时**不录那层离屏、不跑 `effects`、内容不再压进 `CompositingStrategy.Offscreen`**，其余顺序照旧（onDrawBehind → onDrawSurface → 内容 → onDrawFront），高光（`Highlight`）与投影（`Shadow`）用的还是上游那两份绘制代码，所以观感与采样版逐像素一致。**参数只有一处来源**：卡片两个分支共用同一个 `Highlight.Default.copy(alpha = 0.65f)` 与同一个 `floatingGlassShadow(...)` 对象。这一档第一次做错的方式是「自己拿公式重画一圈描边」，丢了 `MaskFilter` 柔边、runtime shader 的方向光与 layer 的合成顺序——**别再退回那条路**：光影只能由库画。`recordTileLayer` 因此收窄成 `liquid && (glassHasDetail || editing.on)`。**角标同理**：`CountBadge` 走同一个 `staticGlassSurface`（同一份 `Highlight` 与同一份 `floatingGlassShadow`），15dp 的小圆标下面本来没东西可采，也不必为它开一层离屏层。⑧ 组头重命名见 §40，悬浮条计数与层次见 §A13 | `HomeLayout.kt` · `addTile` / `addTileAt`、`SettingsStore.kt` · `HOME_WALLPAPER_BLUR_HIDES_GLASS` / `homeLiquidGlass`、`HomeLayoutStore.kt` · `liquidCard`、`HomeCardGroupScreen.kt` · `glassHasDetail` / `cardRefraction` / `tileCounts` / `paletteOverOrb`、`HomeSettingsSheet.kt` · 液态玻璃开关、`HomeAddTileOrb.kt` · `OrbLabel`、`HomeTileBoard.kt` · `dragId` / `rim`、`shared/designsystem/.../StaticGlass.kt` · `staticGlassSurface`、`shared/designsystem/.../CountBadge.kt`、`shared/model/src/commonTest/.../HomeGridMathTest.kt` | 已实现（待真机验收） |

---

## 排期

- 本批（`feat/270-homepage-ui`）：§14、§15、§18、§19、§29–§33、§12、§13、§8、§10
- 拖拽重构（2026-11，同分支）：§14–§16、§19、§20、§22 改写 + A3、A7；预览驱动、流式重排、绝对坐标合成
- **拖拽二次重构（2026-11，同分支）**：§14–§20、§22、§22b、§25 改写 + A1、A3、A7 改写。把上一版「每块登记 `boundsInRoot`、拖拽中不改数据、松手固化」整套换成 **DragLayer 单一手势 + live preview + 行数预算**：手势从卡片节点上移到常驻覆盖层（块可自由换组不断手势）、坐标改成「视口 + scrollY」一处合成（不再被弹簧动画中间帧污染）、拖动中每帧从快照幂等重算布局、行数预算把组高钉死使命中与渲染每帧严格相等（消除振荡回路）
- **原点跟滚动（2026-11 复核，同分支）**：`boardOrigin` 原来由组区容器的 `onGloballyPositioned` 回填，而回填用的是 `boundsInRoot().topLeft`——**默认按裁剪边界取框**，网格区顶一滚出视口就被夹到「视口顶」，原点从此冻住（这正是「滚过大卡片之后网格不再随滚动变化」）。两处一起修：①位置改用 `positionInRoot()`；②拖拽期间**每帧**从活坐标现读（每帧维护循环 + 每次 `move` + 落盘前），不再依赖回调是否触发。诊断层同时给出「缓存 O vs 实测 live」+ `scroll` 三个值，一眼能看出原点有没有过期
- **落位手感与空行（2026-11 复核，同分支）**：§A1 收空行改回「每次落位都收」（含 `removeTile`，预览与落盘一致）；新增 §A8「拖到组底端再起一行」；§17 进编辑态改用锚点组补位（不再只记 `scroll.value`）；幽灵块在结算动画里把 94%/88% 补回 1.0/1.0，消掉落位末尾「略小一点突变成正常大小」的那一跳（`HomeDragGhost` · `settleToFull`）；落点占位框在结算期渐隐（`DropSlot(settling = …)`），不再等 `finish()` 硬消失
- **第二轮真机反馈（2026-11 复核，同分支）**：①空行还漏两处——`compactEmptyRows` 的 `size < 2` 短路（单块组永远停在第二行）、末行多算一行幽灵行；再补「读盘时收一遍」（`HomeLayout.compactGaps`），老存档里的空行不用等用户再拖一次；②进编辑态不再闪：改成**布局前**预测补偿（`predictEnterFix` + `SideEffect` 里 `dispatchRawDelta`），布局后的锚点循环只兜底残差
- **第三轮真机反馈（2026-11 复核，同分支）**：§17 改「按下即拖、不需要长按」（Initial pass 自己收 down 并定归属，自己判 slop；编辑态滚页面走 Pass 区域）；§10 缩放尺寸改走弹簧；§A8 改成网格整行扩展 + 幂等回收（真占住的行留、没占住的当帧收）；新增 §A9（空组保存即删、恢复默认立即生效且不退出编辑态）；进 / 出编辑态都做「布局前预测（`predictAnchorFix` + `SideEffect`）+ 布局后兜底」的滚动锚定
- **第六轮（真机反馈 + 编辑态体验，2026-11）**：组头可重命名（§40 加回输入框）、组内允许同一功能重复（去掉限制即可，同功能与其他卡片同待遇，§A16 ①）、图片背景糊到 5dp 以上也停掉卡片的模糊 / 折射并改成「置灰但不改设置」（§A16 ②③）、悬浮条名字栏加宽到 104dp 并补外沿安全边、列表一直铺到框底才被裁（§A13）、角标改成**不采样但保留全部玻璃光影**的小圆标（铃铛与「已添加几块」共用 CountBadge，§A14）、悬浮条与幽灵块两种层次 + 落进悬浮条不放置（§A13）、默认液态玻璃 + 默认开折射（§A16 ④；折射开关第六轮已整项删除）、从悬浮条拖出不再顶掉已有卡片（两个根因，§A16 ⑥）、没有可糊可折的细节时卡片不采样、但光影改用纯绘制补齐（§A16 ⑦）
- **第六轮第二轮（2026-11 真机反馈）**：上一版「不采样」是「自己拿公式重画一圈描边」，观感不对（丢了 `MaskFilter` 柔边、runtime shader 的方向光与 layer 合成顺序）。最终方案是 **vendor 上游 `backdrop` 2.0.1 并加 `sampling` 开关**：不采样时只跳过「录采样结果＋跑 effects＋离屏层」那一段，高光与投影仍由库自己画，所以既省资源又逐像素一致（§A16 ⑦ / §1 / §A14，改动见 `thirdparty/backdrop/README.md`）
- 下一批：§42 触感、§44 底栏联动、§9 大尺寸信息密度扩展（4×4 目前只多一行状态，还能放进度 / 次要指标）

## 已知取舍

1. §7 要求"编辑态顶栏只有三个图标"，实际保留了正中的「岱宗盒子」标题——常规态标题是绝对居中的，编辑态去掉标题会让顶栏元素整体跑位，读者感知到的不是"更干净"而是"顶栏跳了"。这里按"视觉稳定优先"处理。
2. §30 的"同一抽屉实例"是两个挂载点（设置页 / 首页跳转后落到设置页）共享同一个 composable 与同一份 store，不是同一个 Composition 节点——跨页面的单实例在 Compose 里等于全局 overlay，会带来主题 / 返回栈的额外耦合。
