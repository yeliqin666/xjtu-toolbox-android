# 主页可调整面板：交互与材质规格

这份文档回答需求「补充 50 条」里每一条「需明确」——**先定死行为，再谈实现**。
每条的「状态」只有两种取值：

- `已实现`：代码里就是这么干的，落点写在同一行；
- `待实现`：决定已经定下，但这一批没写（排期见文末），验收时按本文档判定。

参数（格数、dp、毫秒）都不写死在文档里当圣旨，而是「由内容模型推出来的」，改卡片内容模型时要连带复核。

---

## 一、材质与视觉

| # | 决定 | 落点 | 状态 |
|---|---|---|---|
| 1 | 液态玻璃**只用** App 内现成实现：`com.kyant.backdrop` 的 `drawBackdrop`（底栏、玻璃顶栏、头卡同一套），三要素齐全——`blur + vibrancy` 之外还有 `lens` 折射（边缘弯光 + 色散）、`Highlight` 轮廓高光、`floatingGlassShadow` 环境光投影；毛玻璃是另一条独立路径：`squircleClip + background + squircleBorder + appCardShadow`。两者靠 `liquidCard` 开关切换，**不允许**用毛玻璃调参去"模拟"液态玻璃 | `HomeTileBoard.kt` · `HomeTileFaceBox` | 已实现 |
| 2 | 背景图**铺满全屏含顶栏**：顶栏模糊带与按钮改采首页**背景层**（之前采滚动内容层，不透背景）；非玻璃风格顶栏透明。顶栏按钮（编辑态按钮 + 宿主的扫码 / 搜索 / 通知）经 `LocalHomeTileBackdrop` 采背景层做液态玻璃，无白描边 | `HomeScreen.kt` · `HomeCollapsingBar`、`app/main/MainScreen.kt` · `HomeRoundAction` | 已实现（本批修） |
| 3 | 背景图**固定** `ContentScale.Crop` 居中铺满（不变形、不重复），**不开放**用户缩放 / 移动取景——它和页面滚动、双击手势会抢指针，收益远小于冲突；可调项只有两个滑条：模糊 0–10dp、不透明度 20–100% | `HomeSettingsScreen.kt` · `HomeSettingsBody` | 已实现（取景按此决定不开放） |
| 4 | 背景变化后**不做**卡片对比度联动补偿。理由：壁纸太花是用户的审美选择，自动压暗会让"液态玻璃透出壁纸"这个卖点消失。保底只做两件事：文字恒用主题 `onSurface`、液态玻璃内部 blur 恒为 8dp 保证可读 | `HomeTileBoard.kt` · `HomeTileFaceBox` | 已实现 |
| 5 | 组标题颜色默认 = `colorHex` 为空 → 主题 `onSurface`；可选范围 = 课表同一套 8 色板（`GROUP_TITLE_COLORS`）；深色模式把颜色向白 `lerp 0.25` 提亮，浅色原样使用 | `HomeTileBoard.kt` · `GroupHeader` | 已实现 |
| 6 | 标题为空 → 标题行**整行不画**，不留占位高度；组与组之间仍有 `GROUP_GAP` 的固定间距 | `HomeTileBoard.kt` · `HomeGroupSection` | 已实现 |
| 7 | 编辑态顶栏为纯图标 ✕ / ⚙ / ✓，视觉与常规态同一 `HomeBarIconButton`（玻璃圆钮、无边框）。语义见 §35/§36。**没有禁用态**：编辑态里这三个动作恒可用 | `HomeScreen.kt` · `HomeCollapsingBar` | 已实现 |

## 二、尺寸与内容自适应

| # | 决定 | 落点 | 状态 |
|---|---|---|---|
| 8 | 尺寸边界：最小 **1×1**（纯图标方块），最大 **4×4**。网格开 → 整数步进（`snap` 四舍五入）；网格关 → 0.5 步进 | `HomeGridMath` · `MIN_SPAN`/`MAX_SPAN`/`clampSpan` | 已实现 |
| 9 | 排版规则按格数分三档（表见下），长条形横排、方块以上竖向堆叠；大尺寸靠"多一行信息"提密度，**不是**等比放大 | `HomeTileBoard.kt` · `HomeTileFaceBox` | 已实现 |
| 10 | 形态由**实时**格数决定（`animW`/`animH`，缩放过程中逐帧判断），切档时 140ms `Crossfade` 过渡，不等松手。**尺寸也走弹簧**（缩放不再 snap）：数据是一格一格跳的，snap 把这些跳变原样画出来会一顿一顿 | 同上 | 已实现（缩放动画按 2026-11 复核加） |
| 11 | 阈值由内容模型推：单元 62dp − 竖向内边距 12dp = 50dp 可用高，放不下"图标 + 两行字"→ 1 格高只能是纯图标；竖排两行字至少要多 1 行 ≈ 0.6 格 → 1.6 格起竖排。**不写死全局像素** | `HomeTileBoard.kt` · `mode` 的 `when` | 已实现 |
| 12 | 「网格下留出文字空间」是**网格基础单元**的开关：开（默认）单元 **88dp**——1×1 是高长方形，装得下「图标 + 名称」；关 → 单元 46dp，趋近紧凑方块。存 `home_text_space` | `HomeLayoutStore.leaveTextSpace`、`HomeSettingsScreen.kt` | 已实现 |
| 13 | 与形态阈值联动：高单元格时 1 格就装得下「图标 + 名称」，1×1 竖排不落进纯图标；矮单元格时 1 格只剩图标。切换后**数据不动**——槽位存的是格数，重算的是"格 → 像素"的比例，所以按新语义重排是自动的 | `HomeTileBoard.kt` · `HomeTileFaceBox` 的 `mode` | 已实现 |

**形态阈值表（§9/§11）**

| 档 | 条件 | 排版 | 信息 |
|---|---|---|---|
| 0 纯图标 | `w < 1.2` 或 `h < 1.2` | 图标居中，24–34dp | 只有图标（热区 = 整块，见 §45） |
| 1 横排 | `h < 1.6` | 图标左（30dp）+ 右侧两行 | 名称 + 状态/说明 |
| 2 竖排 | 其余 | 图标 / 名称 / 状态 | 名称 + 状态/说明 |

## 三、拖拽、缩放与排序

| # | 决定 | 落点 | 状态 |
|---|---|---|---|
| 14 | 跟手：**所有拖拽手势只挂在页面根 Box 自己的一个修饰符上**（`Modifier.homeDragLayer`，同 AOSP Launcher3 的 `DragLayer`）。**不能做成盖在上面的兄弟覆盖层**——兄弟层一旦带 `pointerInput` 就会独占整条命中路径，把下面的滚动列 / 顶栏 / 卡片全挡掉（实测：除挂在它上层的悬浮条，其余什么都点不动）；挂成根 Box 的修饰符则是「祖先」，子节点照样先收 Main pass。它给的 `PointerInputChange.position` 直接就是**页面根局部坐标**，板内坐标 = 它 − `boardOrigin`（组区容器左上角）。`boardOrigin` **必须现读活坐标**（`rootCoords.localPositionOf(boardCoords)`），且在拖拽期间**每帧**刷（每帧维护循环 + 每次 move + 落盘前）。位置**一律不许用 `boundsInRoot()`**：它默认按**裁剪边界**取框，网格区一滚出视口，拿到的就是「视口顶」——原点从此冻在那一刻（`positionInRoot()` 读的是布局位置，不受裁剪影响）。回调 `onGloballyPositioned` 只当兜底，页面滚起来之后它不保证每帧都到，值一停住落点就整体偏「滚过的距离」——卡片是 Column 排的照样滚得对，所以现场看着就是「卡片对、网格不跟滚动」（2026-11 修）。全程只有这一处减法——**不回读任何块的 `boundsInRoot`、不逐块登记位置、不做差分累加、也不手工掺 `scrollY`**，所以弹簧动画的中间帧污染不到坐标（旧实现正是拿动画中间帧当基准才落点飘）。拖拽全程 **live preview**：每帧 `layout = f(base 快照, 手指内容坐标)` 幂等重算，被拖块画成落点占位框、真身由顶层幽灵块跟手、其它块弹簧让位；松手才把结果写进草稿一次。**拖动中从不写草稿**，所以手势被抢走时不需要「回退临时状态」——草稿根本没被碰过 | `HomeDragLayer.kt`、`HomeDragSession.kt` · `refreshBoardFromCoordinates`/`peekLiveBoardOrigin`、`HomeScreen.kt` · `liveLayout`/`settleTileDrop`、`HomeTileBoard.kt` · `DropSlot` | 已实现（2026-11 拖拽二次重构；原点跟滚动按 2026-11 复核改） |
| 15 | 每个卡片右下角恒有缩放把手——**把右下角那段圆弧染成主题色**当把手，热区 30dp。把手只画记号、不接手势：命中与拖动全在 `HomeDragLayer` 按快照几何判定（`HomeBoardGeometry.hit` 区分卡片体 / 缩放把手 / 移除按钮 / 组排序把手 / 放行）。缩放尺寸按 **AOSP `AppWidgetResizeFrame` 的绝对差值**算：`尺寸 = 起手尺寸 + (手指当前内容坐标 − 起手内容坐标) / 单元`，每帧从 baseline 重算、**不累积增量**，所以把手自己随尺寸移动也不会漂。**缩放过程就是实时预览**（其它块同步让位），松手固化；顶到屏幕边缘自动滚动时，块左上角在内容坐标里不动而手指内容坐标继续变大，尺寸自然跟着变大——不需要任何补算特例 | `HomeDragLayer.kt` · `hit`、`HomeScreen.kt` · `resizeCells`、`HomeTileBoard.kt` · `HomeTileItem`（只画把手） | 已实现（2026-11 拖拽二次重构） |
| 16 | 位置变化用 `spring(0.62, 520)`，尺寸变化用同一参数，禁止瞬移。组上下排序：**被拖组跟手浮起、其它组按「越过中线」纯函数让位**（`HomeGridMath.reorderTargetIndex/reorderShifts`），拖动中**不改数据**，列表始终堆叠不留空；松手一次性 `moveGroupWithin` 落盘（可见组序列 → 全量序列映射），被拖组补「手指位置 − 新槽位」的差值弹簧归位——三步（落盘 / 切结算态 / 值就位）放在协程同一个同步段里，中间不插帧，无重组风暴、无动画状态重建 | `HomeTileSpring`、`HomeGridMath` · 组排序几何、`HomeScreen.kt` · `settleGroupDrop`/`cancelGroupDrop` | 已实现（2026-11 拖拽二次重构） |
| 17 | **拖拽不需要长按**：`HomeDragLayer` 在 **`PointerEventPass.Initial`** 上自己收 down（这一层是滚动列的祖先，Initial 又是祖先先看到），down 那一刻按快照几何定死归属——落点是拖拽目标就 `consume`（后到的 `verticalScroll` 在 Main pass 根本看不到它），落点是 `HomeHit.Pass`（移除按钮 / 删除组 / 组名 / 空白 / 悬浮条）就**不消费**，down 原样留给下层点击节点与滚动。**不用官方 `detectDragGestures`**：它的 slop 判定「发现变更已被别人消费过就取消」，而滚动列正好在这条路径上，所以这里自己判 slop（按下到超过 slop 之间什么都不做，点一下不算拖）。编辑态要滚页面就从 Pass 区域起手（组间空隙、网格空格、组标题、刊头 / 头卡、页面空白），拖到边缘还有自动滚动兜底。**进编辑态不跳、也不闪**：编辑态会给每组补标题行、把空组与「组件都打不开」的组显示出来，这些都在视口之上，光记住 `scroll.value` 补不回来。两步：①**布局之前**就用同一套几何函数（`buildBoardGeometry(headerlessIds=…)`）算出「编辑前 → 编辑后」锚点组的顶边差，在 `SideEffect` 里 `dispatchRawDelta` 打进滚动——等布局量完再补，那一帧的错位已经被画出来（闪一下）；②布局之后按实测的**锚点组**（`groupTops`/`idleTops`，进编辑前「视口里最靠上的那一组」）逐帧把残差补回原处，逐帧是因为组高带弹簧动画，残差归零收工（兜底，正常情况①已经补准了） | `HomeDragLayer.kt` · Initial pass、`HomeScreen.kt` · `predictEnterFix`/锚点 effect | 已实现（2026-11 拖拽二次重构；进编辑锚点按 2026-11 复核改，补「布局前预测」） |
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
| 32 | 网格开关**立即**影响下一次拖动 / 缩放的吸附步进；已经摆好的组件**不重排**（避免开关一按满屏跳位），只在新操作时按新步进吸附 | `HomeScreen.kt` · `grid` | 已实现 |
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
| 40 | 标题上限 20 字（`take(20)`）；纯空格 = 存进去但渲染时 `isBlank` → 不显示标题；**无实时预览**，确定后生效 | `GroupEditDialog` | 已实现 |
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
| 45 | 纯图标模式热区 = 整块卡片（1×1 ≈ 62×62dp ≥ 48dp），不额外缩小 | `HomeTileItem` 的 tap 手势 | 已实现 |
| 46 | 字体放大降级：文字一律 `maxLines = 1` + `Ellipsis`，行高固定 dp 而非随字体无限涨；极端放大时先省略名称、再隐去状态行 | `HomeTileFaceBox` | 已实现 |
| 47 | 深浅色参数**分开**：填充 浅 62% / 深 10% 白；细边 浅 0.7 / 深 0.1；标题色深色提亮 0.25；液态玻璃 blur 两档相同 | `HomeTileFaceBox`、`GroupHeader` | 已实现 |
| 48 | 性能降级：抖动只在编辑态挂动画；掉帧交给 Compose 的帧调度，不额外做降级开关。液态玻璃的 `drawBackdrop` 用 `remember` 按圆角 / 填充 / 阴影缓存（只有圆角随尺寸动画变时才重建），静止与位置动画不重复采样；纯色背景下折射不可见但高光仍在，故不做「无背景图退化」 | `HomeTileItem`、`HomeTileFaceBox` | 已实现（本批修） |
| 49 | 基准来源：材质 = App 内 `drawBackdrop` + 底栏玻璃；抽屉 = 设置页既有 `OverlayBottomSheet` 范式；动画 = Compose 官方 spring；手势 = `detectDragGestures` 系统语义。**不自创** | 全文 | 按此决定 |
| 50 | 参数不写死：格数阈值由内容模型推（§11），dp 由单元高度推（§9），时长沿用 App 既有弹簧参数（§43） | 全文 | 按此决定 |

## 八、本轮（2026-11 复核）新增交互

| # | 决定 | 落点 | 状态 |
|---|---|---|---|
| A1 | 空行压缩：网格模式下 `moveTile` / `resizeTile` / `addTileAt` / `removeTile` **每次都收空行**——拖动中与松手都收（`HomeLayout.finishRows`），所以预览与落盘逐格一致；从前「只在松手那一次收」导致预览与落盘差一行，16278fd7 那轮为了对齐两者反过来取消松手压缩，空行就永远留着了。收的是**整行全空**：行里只要还有一块就不动。**一块也要压**（以前 `compactEmptyRows` 有个 `size < 2` 短路，「两块一组拖走上面那块」之后剩下的那块会永远停在第二行）；末行下标按 `contentBottom − 1` 算，别再引入一行幽灵行。组高在拖动中由行数预算钉住（渲染那一层），收空行只是块在组内上移，不会让下面的组跟着跳；松手后组高跟着数据收缩，下面的组按弹簧上移。**读盘时也补一遍**（`HomeLayoutStore.load` → `HomeLayout.compactGaps`）：老存档里的空行不会因为「用户没再拖一次」就一直留着。自由模式允许留空隙，不压 | `HomeGridMath.compactEmptyRows`、`HomeLayout.finishRows/compactGaps`、`HomeLayoutStore.load` | 已实现（2026-11 复核改） |
| A2 | 添加卡片用**右侧悬浮球**：进编辑态时球自动展开成液态玻璃长条（连续动画，不停顿）；把手（圆形，示意可拖拽）升到长条最上方，按住它整条收成球跟手，松手自动吸回最近的侧边；长条内是圆形功能图标（液态玻璃、可滚动），**长按拖出来**放到组里 | `HomeAddTileOrb.kt` | 已实现 |
| A3 | 从悬浮球拖出的新块落到**指定格**（`addTileAt`）：2×1 进组、照样挤开、拖动中吃行数预算 / 松手收空行；拖动全程走同一份 live preview（落点占位框 + 让位），松手固化、幽灵吸附。新块 id 由 `HomeLayout.nextTileId` 分配——全局 `#N` 尾号只涨不回退，删了再加不撞号（撞号会让 `key` 与 `locate` 错乱）；旧快照里已撞号的块读盘时重编（`HomeLayoutCodec`）。悬浮球长按拖出走它**自己节点**上的 `detectDragGesturesAfterLongPress`（`HomeDragLayer` 对悬浮球矩形 `paletteRect` 放行），拖出后只写 `session.pointer`，落点仍由首页按快照派生 | `HomeLayout.addTileAt/nextTileId`、`HomeLayoutCodec`、`HomeAddTileOrb.kt` | 已实现（2026-11 拖拽二次重构） |
| A7 | 拖拽手势**集中在 `HomeDragLayer` 一个常驻覆盖层**（同 AOSP Launcher3 的 `DragLayer`）：用 `awaitEachGesture` + `awaitFirstDown(pass = Initial)` 自己收 down，因为要①比 `verticalScroll` 早一步决定抢不抢、②按快照几何把落点分流成「卡片体 / 缩放把手 / 组排序把手 / 放行」。`try/finally` 兜底：协程被取消（系统抢走 / 离开组合）也清场，不会卡死编辑态。悬浮球长按拖出仍用官方 `detectDragGesturesAfterLongPress`（自带 `onDragCancel`）。多指互斥：每次起手先 `finish()` 上一场；松手结算动画带代次（`ghostEpoch`/`reorderEpoch`）守卫，被新拖拽顶掉就不清场 | `HomeDragLayer.kt`、`HomeDragSession.kt`、`HomeScreen.kt`、`HomeAddTileOrb.kt` | 已实现（2026-11 拖拽二次重构） |
| A4 | 组标题行：右侧两个圆钮（改名 / 加功能）改成**一个液态玻璃删除按钮**（点出二次确认，默认组不给删）；**点组名**打开「分组设置」（不再走三点）；组名放大到 18sp；**删除按钮恒贴右**——组名那一格必须 `weight(1f)`（不能 `fill = false`，否则它那格剩下的空白不会还给行尾，按钮就被顶到半个剩余宽度处） | `HomeTileBoard.kt` · `GroupHeader/GroupDeleteButton`、`HomeScreen.kt` · `DeleteGroupDialog` | 已实现（贴右按 2026-11 复核修） |
| A5 | 「分组设置」的标题颜色改用全应用同一套 `AppColorPicker`（色板 + Hex），「跟随主题」存空串；分组标题输入框括号写明「留空则不显示标题」 | `HomeScreen.kt` · `GroupEditDialog` | 已实现 |
| A6 | 组里「加功能」的入口统一收进悬浮球（不再有每组一个 ＋），圆形功能图标可滚动、长按拖出 | `HomeAddTileOrb.kt` | 已实现 |
| A9 | **空卡组保存即删**：`HomeLayoutStore.save` 落盘前过一遍 `withoutEmptyGroups()`——编辑态里被搬空的组在常规态本来就是隐藏的，留着只是存档垃圾。**「恢复默认分组和布局」立即生效**：二次确认后抽屉自己合上（`HomeSettingsBody(onDismiss)`），首页编辑态**不退出**，但手上的草稿换成新的默认布局（只听 `HomeLayoutResetRevision`，不听普通 revision——后者在改背景 / 开关网格时也会动，跟着换会把正摆着的草稿冲掉），所以卡片当场重排、不需要再点保存 | `HomeLayout.withoutEmptyGroups`、`HomeLayoutStore.save/reset`、`HomeScreen.kt` · `resetRevision`、`HomeSettingsScreen.kt` | 已实现（2026-11 复核加） |
| A8 | **拖到组底端可以再起一行**：幽灵块的底边探出某一组的行数预算时（拖到最后一行之外、或落进组间空隙），给**这一组**加行接住它——网格模式下**取整到整行**（空位随网格一格一格扩展，拖着继续往下就继续长），自由模式按实际差值。加的行是**实时布局的幂等函数**：真被占住的行留着，没被占住（让位 / 收空行把卡片提回原行）的当帧就收掉，所以不会留一条空行等到松手才塌。加的行写在 `extraRows` 里，同时喂给几何表（`buildBoardGeometry(extraRows = …)`）与渲染侧的行数预算，两边一起长，所以「命中 == 画出来的」仍然成立；**只增不减**（本场不回退）是防振荡的关键——手指探出组底长一行、长完手指回到组内，若这时收回去就成了每帧来回摆。多出来的行没被占住时，松手由收空行（§A1）压掉 | `HomeScreen.kt` · `extraRows`/`growRowUnderGhost`、`HomeDragSession.kt` · `buildBoardGeometry` | 已实现（2026-11 复核加） |

---

## 排期

- 本批（`feat/270-homepage-ui`）：§14、§15、§18、§19、§29–§33、§12、§13、§8、§10
- 拖拽重构（2026-11，同分支）：§14–§16、§19、§20、§22 改写 + A3、A7；预览驱动、流式重排、绝对坐标合成
- **拖拽二次重构（2026-11，同分支）**：§14–§20、§22、§22b、§25 改写 + A1、A3、A7 改写。把上一版「每块登记 `boundsInRoot`、拖拽中不改数据、松手固化」整套换成 **DragLayer 单一手势 + live preview + 行数预算**：手势从卡片节点上移到常驻覆盖层（块可自由换组不断手势）、坐标改成「视口 + scrollY」一处合成（不再被弹簧动画中间帧污染）、拖动中每帧从快照幂等重算布局、行数预算把组高钉死使命中与渲染每帧严格相等（消除振荡回路）
- **原点跟滚动（2026-11 复核，同分支）**：`boardOrigin` 原来由组区容器的 `onGloballyPositioned` 回填，而回填用的是 `boundsInRoot().topLeft`——**默认按裁剪边界取框**，网格区顶一滚出视口就被夹到「视口顶」，原点从此冻住（这正是「滚过大卡片之后网格不再随滚动变化」）。两处一起修：①位置改用 `positionInRoot()`；②拖拽期间**每帧**从活坐标现读（每帧维护循环 + 每次 `move` + 落盘前），不再依赖回调是否触发。诊断层同时给出「缓存 O vs 实测 live」+ `scroll` 三个值，一眼能看出原点有没有过期
- **落位手感与空行（2026-11 复核，同分支）**：§A1 收空行改回「每次落位都收」（含 `removeTile`，预览与落盘一致）；新增 §A8「拖到组底端再起一行」；§17 进编辑态改用锚点组补位（不再只记 `scroll.value`）；幽灵块在结算动画里把 94%/88% 补回 1.0/1.0，消掉落位末尾「略小一点突变成正常大小」的那一跳（`HomeDragGhost` · `settleToFull`）；落点占位框在结算期渐隐（`DropSlot(settling = …)`），不再等 `finish()` 硬消失
- **第二轮真机反馈（2026-11 复核，同分支）**：①空行还漏两处——`compactEmptyRows` 的 `size < 2` 短路（单块组永远停在第二行）、末行多算一行幽灵行；再补「读盘时收一遍」（`HomeLayout.compactGaps`），老存档里的空行不用等用户再拖一次；②进编辑态不再闪：改成**布局前**预测补偿（`predictEnterFix` + `SideEffect` 里 `dispatchRawDelta`），布局后的锚点循环只兜底残差
- **第三轮真机反馈（2026-11 复核，同分支）**：§17 改「按下即拖、不需要长按」（Initial pass 自己收 down 并定归属，自己判 slop；编辑态滚页面走 Pass 区域）；§10 缩放尺寸改走弹簧；§A8 改成网格整行扩展 + 幂等回收（真占住的行留、没占住的当帧收）；新增 §A9（空组保存即删、恢复默认立即生效且不退出编辑态）；进 / 出编辑态都做「布局前预测（`predictAnchorFix` + `SideEffect`）+ 布局后兜底」的滚动锚定
- 下一批：§42 触感、§44 底栏联动、§9 大尺寸信息密度扩展（4×4 目前只多一行状态，还能放进度 / 次要指标）

## 已知取舍

1. §7 要求"编辑态顶栏只有三个图标"，实际保留了正中的「岱宗盒子」标题——常规态标题是绝对居中的，编辑态去掉标题会让顶栏元素整体跑位，读者感知到的不是"更干净"而是"顶栏跳了"。这里按"视觉稳定优先"处理。
2. §30 的"同一抽屉实例"是两个挂载点（设置页 / 首页跳转后落到设置页）共享同一个 composable 与同一份 store，不是同一个 Composition 节点——跨页面的单实例在 Compose 里等于全局 overlay，会带来主题 / 返回栈的额外耦合。
