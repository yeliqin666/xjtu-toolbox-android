# Issue #449 调研：动画掉帧（首页打开子应用、「我的」打开设置）

> 只读调研，未改代码。调研时间 2026-11-23，代码基于 `dev`（`1bc9e4eb`，版本号 6.0.3）。
> 来源：[issue #449](https://github.com/yeliqin666/xjtu-toolbox-android/issues/449)，由 [Discussion #447](https://github.com/yeliqin666/xjtu-toolbox-android/discussions/447) 转来。

## 0. 结论摘要

1. 「我的 → 设置」根本不走放大转场（`composeFallback = false`），却同样卡顿，说明主因**不在转场几何**，而在**新页面首帧的 COUI 材质**和**首次执行的代码**。
2. 代码里能直接确认的热点有三处：
   - **每个浮层组件在组合时于主线程编译 4 段 AGSL**，阴影首次绘制时还要再编 6 段，每次打开页面都要重新编译，没有缓存（§3.1）。
   - **浮层每个动画帧都重建 `drawWithCache`**，阴影参数一变就重建 Mesh；胶囊形（底栏就是）**每帧还要再编译一次 AGSL**，所以点底栏切页本身就会卡（§3.2）。
   - **顶栏渐进模糊全分辨率、两遍、每像素单边最多约 30 次采样，每次采样都在着色器里现算 `exp`**。页面内容一动（入场动画、骨架屏闪光）就要整块重算（§3.3）。
3. Baseline Profile 是在模拟器全新安装、停在登录页时采集的，**首页以后的路径几乎都没覆盖**：`ExpandTransition`、`NavDisplay`、各功能页、`ProfileTab` 的命中数都是 0。这能解释「第一次最卡、再开就好些」（§3.5）。
4. 「切底栏后再开又卡」目前没有能单独坐实的成因，最可能是几项叠加（§3.7）：切页本身很重（静态 CompositionLocal 让整页重组两次，外加 §3.2 的逐帧编译），回到首页后错峰弹簧动画还没跑完，以及每个访问过的页签常驻一张整屏离屏层带来的 GPU 内存压力。**需要报告者的 trace 来确认。**
5. 推荐做法：**先量化**（让报告者提供 logcat 和 Perfetto，同时补 Macrobenchmark 帧时间用例），**同时落地一批低风险修复**（共享着色器实例、阴影复用、Profile 补全、`LocalTabSwitch` 改为动态），再根据数据决定要不要做「转场期间降级」和「模糊降采样」。

---

## 1. 需求概述

报告者在小米 15 Ultra 上使用 6.0.1，反馈如下：

| 编号 | 症状 |
|---|---|
| A | 从首页**第一次**打开子应用时，动画明显卡顿 |
| B | 不切换底栏、**再次**打开时有所缓解 |
| C | **切换底栏页签再回来**，重复打开子应用，又开始卡 |
| D | 「我的」页打开设置等页面也有同样问题 |
| E | 整体感觉不如上一个版本流畅 |

讨论里，贡献者 Ckarefulon 提到：**采样**是开销的大头，只要做了采样，加不加折射模糊影响都不大。issue 被标为 P1。

这是一个「性能总帖」，目标不止修一个点，而是让首页→子页、我的→子页、底栏切页这三类高频动画在旗舰机上稳定满帧，并且回到不低于 5.2.x 的观感。

---

## 2. 打开一个子页时到底走哪条路

```
点卡片/行 → SeamlessOrigin.open()                       shared/navigation/.../SeamlessCardSource.kt:57
        → seamlessOpener()                               app/.../sharedview/SeamlessOpener.kt:24
            ├─ ROM 声明支持金标 SharedViewKit 且页面可独立托管
            │     → KitOneTake.openPage → PixelCopy 截图 → 启动 SeamlessPageActivity（新窗口）
            │                                            app/.../sharedview/KitOneTake.kt:85, :123
            └─ 否则压 Compose 路由
                  ├─ 首页格子（composeFallback = true）：expandFromOrigin 放大转场，380ms
                  │                                      shared/navigation/.../ExpandTransition.kt:490, :635
                  └─「我的」菜单行（composeFallback = false）：miuix 默认平移
                                                         app/.../profile/ProfileTab.kt:266, :905
```

- 设置在可独立托管的名单里（`app/.../nav/AppNavHost.kt:169`），所以在 ROM 支持 KIT 时也会走独立 Activity。
- **小米 15 Ultra 实际走哪条路目前不确定**。金标联盟 2026 年 10 月发布了 SharedView Kit，小米是推动方之一，但公开资料没有写明 HyperOS 哪个版本开始支持（见文末来源）。代码在首次探测时会打日志 `SharedViewKit supported, protocol=…` 或 `…unsupported on this ROM…`（`KitOneTake.kt:51`），让报告者跑一次 `adb logcat -s KitOneTake` 就能确定。
- 不管走哪条路，新页面都要**从零组合**，并**新建一整套 COUI 材质对象**。KIT 路径还要多建一个 Activity、窗口和 Compose 根。由于 D 和 A 的表现相同，后面的分析重点放在两条路径共有的部分。

---

## 3. 现状与疑点（按证据强弱排列）

### 3.1 【确定】COUI 浮层在主线程编译 AGSL，每次打开页面都重来

- Android 的 `RuntimeShader(String)` 在构造时就会调用 `SkRuntimeEffect::MakeForShader` 编译 SkSL，编译失败当场抛异常，**JNI 层不按源码缓存**（[hwui `Shader.cpp`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/hwui/jni/Shader.cpp) 里的 `RuntimeShader_createShaderBuilder`）。公开 API 也没有拷贝构造之类的复用手段（[RuntimeShader 文档](https://developer.android.com/reference/android/graphics/RuntimeShader)只有 `RuntimeShader(String)` 这一个构造器）。
- `CouiFloatingRenderer` 在 `init` 里**立即**编译 3 段：材质、焦散、destination 直通（`shared/designsystem/src/androidMain/.../coui/CouiFloatingSurfaces.kt:89-110`）。
- 它内部的 `CouiShadowRenderer` 在 `init` 里还会**编一段然后直接丢掉**，只是为了「校验」（`CouiShadowRenderer.kt:54-57`）。首次绘制时再懒加载 2 段裁剪着色器和 4 个 `MeshSpecification`，每个 Spec 都要编译顶点和片元 SkSL（`CouiShadowRenderer.kt:31-50`）。
- 这些渲染器都是在组件里 `remember { CouiFloatingRenderer.standalone() }` 出来的，所以**编译发生在组合阶段、主线程上，赶在转场的第一帧**：
  - 顶栏按钮，每个按钮一个（`CouiTopAppBarButton.kt:62`）：子页至少有一个返回键，常见的还有 1–2 个操作键；
  - 分段控件、Chip 标签（`ChipTabRow.kt:131`）：**每个 Chip 一个**；
  - 课表格子 `CouiGlassCard`（`CouiGlassCard.android.kt:75`，用在 `feature/schedule/.../ScheduleComponents.kt:543`）：**每个课格一个**。一周 20–30 节课，就是一两百次编译，出现在第一次切到日程页签时；
  - 底栏（`CouiNavigationBar.kt:116`）、搜索框等。
- 顶栏渐进模糊还会在首次绘制时编 2 段（`CouiGradientBlur.kt:123-132`）。
- GPU 侧：同一份源码生成的 GPU 程序由 Skia 缓存，**进程里第一次**用到时才付 GPU 编译的代价。这与症状 A/B 吻合：第一次 CPU 和 GPU 都要编译；再开时只剩 CPU 端的 SkSL 编译。这一点是推断，需要用 trace 确认。

> 粗算一个普通子页（返回键 + 1 个操作键 + 顶栏模糊）：组合期约 2×4 = 8 次编译，首帧绘制再加约 2×6 + 2 = 14 次。单次耗时取决于源码长度（`CouiFloatingShader.kt` 有约 23 KB 的片段拼接），**需要实测**；按 Skia 的一般经验，每次在毫秒量级。

### 3.2 【确定】浮层每个动画帧都重建缓存，阴影每帧重建 Mesh，胶囊形还要每帧编译

- `floatingSurfaces` 在 `drawWithCache` 的缓存块里调用 `surfaces()`（`CouiFloatingSurfaces.kt:242-244`）。`surfaces()` 读取了按压和跟手形变的动画状态，例如底栏的 `motion.scaleX + press.scale`（`CouiNavigationBar.kt:353-358`）。所以**按压或形变动画的每一帧都会让整块缓存重建**：重新生成轮廓和 Path，重新创建 3–4 个 `RenderEffect`，重新分配各种数组。
- 阴影按 `ShadowKey(width, height, sx, sy, …)` 缓存，每个槽位只留一份（`CouiShadowRenderer.kt:77-83`）。缩放一变，`makeDraw` 就要重新做几何剖分、新建 `Mesh`；**不是圆形且需要裁剪时，还会 `RuntimeShader(materialClipSource)` 再编译一次**（`CouiShadowRenderer.kt:118-120`）。
- 底栏用的是 `smoothCorner = true` 的胶囊形（`CouiNavigationBar.kt:166`），正好命中这条分支。也就是说，**每次点底栏切页，按压弹簧运行期间，每帧要编译 2 次 AGSL（ambient 和 spot 各一次）**。顶栏的胶囊按钮和搜索框按下时也一样（`CouiTopAppBarButtonDefaults.kt:88`）。
- 这一项和症状 C（切底栏）直接相关，而且可以 100% 从代码确认。

### 3.3 【确定】顶栏渐进模糊：全分辨率、双遍、逐采样计算 `exp`

- `CouiGradientRenderer` 按原厂实现「先横后纵，每遍保留原尺寸中间图，不用多级缩小图」（`CouiGradientBlur.kt:111`），两遍都在全分辨率的离屏层上进行。
- 核半径 `kernelRadius = ceil((r·0.577 + 0.5)·3/2)`，其中 r 为 10.8dp 换算的像素（`CouiGradientBlur.kt:120`、`CouiTopAppBarDefaults.kt:66`）。在 1080 宽、密度约 2.75 时约为 27；在 1440 宽、密度约 3.5 时约为 34。每个像素单边最多采样这么多次，每次循环都调用 `getWeight()`，里面算一次 `exp`（`CouiGradientBlur.kt:209`、`:234`）。核还会把采样区域四周外扩 `2·kernelRadius+1` 像素（`:142`）。
- 估算：顶栏表面加外扩约 1×10⁶ 像素（WQHD），两遍合计每帧约 10⁸ 次纹理采样，外加同等量级的 `exp`。这是数量级估计，不是实测值。
- 页面采样层一重录，模糊就要重算。转场期间新页面的内容正好在变：
  - 子页里的 `enterOnce` 错峰入场动画在转场期间**照常播放**。`LocalEnterAnimations` 的注释说「整页一起放大进来时关掉」（`Motion.kt:116-117`），但只有底栏切页（`ShellTabHost.kt:237`）和首页布局切换（`HomeScreen.kt:61`）关了，**导航转场进来的子页没关**。全仓有 18 个文件在用 `enterOnce`；
  - 骨架屏的闪光走 `rememberLoopPhase`，30fps（`Motion.kt:151`）；
  - 数据从加载态变成内容态。
- 仓库里已经有**降采样加 Kawase** 的模糊实现，菜单在用（`coui/menu/CouiMenuBlur.kt:35-50`），可以复用。

### 3.4 【确定】每个页面的采样源常驻整屏离屏层

- `CouiBackdrop` 的 `isolateWhenIdle` 默认为 `true`，所以**即使没有消费者**，它也一直是 `CompositingStrategy.Offscreen`（`CouiBackdrop.kt:66`、`:75`、`:102-112`）。只有菜单会把它设成 `false`。
- 每个玻璃风格的页面都有一份整屏采样源（`ShellBarPage.kt:42`、`GlassTopBar.kt:58-70`），顶栏表面还会再建一份带 parent 的（`CouiTopAppBarSurface.kt:41`）。
- 底栏页签一旦访问过就常驻组合（`ShellTabHost.kt:93-94`、`:109`，`composedIds` 只增不减），隐藏的页签只是 `alpha = 0`（`:195`）。WQHD 下一张整屏 RGBA 离屏层约 17.6 MiB，访问的页签越多，常驻的 GPU 内存越多。这一项是症状 C 的**候选成因**，内存占用可以用 `dumpsys gfxinfo` 验证。

### 3.5 【确定】Baseline Profile 几乎不覆盖首页之后的路径

- 采集脚本只覆盖冷启动和首屏滑动（`baselineprofile/src/main/java/.../BaselineProfileGenerator.kt:28-49`）。上次刷新（`91fd84c19`，2026-11-11）的提交说明里也写了：模拟器全新安装后**只走到协议和登录页**，所以手动在 `app/src/main/baseline-prof.txt` 加了 `HSPLcom/xjtu/toolbox/ui/glass/coui/Coui*;->**(**)**`。
- 在 release 版的 profile（19734 行）里统计命中数：

| 关键字 | 命中 |
|---|---|
| `ExpandTransition` / `ExpandOrigins` / `NavDisplay` | 0 / 0 / 0 |
| `ProfileTab` / `SettingsScreen` / `HomeTileBoard` / `HomeCardGroup` | 0 / 0 / 0 / 0 |
| `ShellTabHost` / `SeamlessPageActivity` | 0 / 0 |
| 本 App 的 `home` 包 | 39 |

- 后果：第一次打开某页、第一次切页时，这些代码以解释执行加 JIT 的方式运行，要等系统空闲充电时做后台 dexopt 才会被编译。这与症状 A/B 吻合，也部分解释了 E：刚升级的 6.0.1 还没有经过后台编译，而 5.2.1 在设备上已经用了很久。

### 3.6 【确定】转场第一帧挤在一起的工作

除了 §3.1 的着色器编译，同一帧（或紧接着的几帧）里还有：

- 新页面的首次组合，以及它的 ViewModel 和取数启动；
- 首页的 `LocalPageVisible` 翻转（`AppNavHost.kt:197-202`）。它是动态 CompositionLocal（`MeshBackground.kt:15`），只有读取方会重组，但首页**每个格子都在读**（`HomeTileBoard.kt:849-851`），会重启各自的 `LaunchedEffect`。液态背景、像素背景、`rememberCampusNow` 也会停下；
- `ModulateAlpha` 下的整页缩放和裁剪，以及首页底下那一层的缩放和压暗（`ExpandTransition.kt:496-518`）。

### 3.7 【推断】为什么「切底栏后再开又卡」

单靠读代码无法坐实，候选成因按可能性排列：

1. **切页本身就重，而且会和下一次点击重叠。**
   - 点底栏时，按压弹簧期间每帧编译 AGSL（§3.2）；
   - `LocalTabSwitch` 是 **`staticCompositionLocalOf`**（`Motion.kt:142`），提供的值在 `isActive` 变化时变一次，`switchGeneration++` 后又变一次（`ShellTabHost.kt:146-162`、`:239`），**静态 Local 一变，整个页签子树就要重组**，回到首页时整页重组两次；
   - 回到首页后，各格子的错峰弹簧（每档 18ms，阻尼 0.74，刚度 260，阈值 0.0015，见 `Motion.kt:120-122`、`:193-209`）还要大约 0.5–0.8 秒才能停稳。用户这时点卡片，两段动画就叠在一起；
   - 第一次切到日程页签时，课格要编译大量 AGSL（§3.1）。
2. **访问过的页签各自常驻整屏离屏层**（§3.4），GPU 内存上涨后，HWUI 和 Skia 的缓存更容易被回收，下一次打开时要重新分配。这条需要 trace 来证实。
3. **首页回到前台时重启常驻动画**：默认的液态背景按 30fps 推进（`LiquidBackground.android.kt:37-50`，`LiquidMood.kt:115`，除非用户把动效设为 still）。这会让首页采样层持续重录，底栏和顶栏的模糊也跟着重算，GPU 负载一直偏高，旗舰机也可能因此降频。

### 3.8 与 5.2.x 的差异（对应症状 E）

v5.2.1 → v6.0.1 共 243 个提交，其中与渲染相关的主线是：**删除 kyant 液态玻璃，改用 COUI 原生材质**（`14b85a145`），底栏、顶栏、菜单、课格全部换成 COUI 的 AGSL 和 Mesh 实现（`21841247d`、`16d0dfe97`、`9f9d8067a` 等）。另外加了底栏整页滑动与错峰（`2bc5ea2c6`）和 KIT 一镜到底（`9775d934f`、`58b9cabe1`、`55b05f09d`）。这些效果在 Android 15 及以上才开启（`CouiEffectSupport.kt:11`），小米 15 Ultra 正好全部命中。6.0.1 之后的 dev 上**还没有性能相关的提交**。

---

## 4. 可行方案

### 方案 A：先量化，再动手（必做，约 0.5–1 天）

- 让报告者提供以下数据（需要有人在 issue 下回复，本次调研**没有**评论）：
  - `adb logcat -s KitOneTake`，确认走 KIT 还是 Compose；
  - `adb shell dumpsys gfxinfo com.xjtu.toolbox framestats`，在复现 A/B/C 之后各取一次；
  - 能录的话，附一份 Perfetto trace（勾选 gfx、view、hwui）。
- 在 `:baselineprofile` 模块加 Macrobenchmark `FrameTimingMetric` 用例：「首页 → 子页 ×3」「我的 → 设置」「底栏轮切」。和 Profile 采集共用登录前置条件（见 §6 的问题 3）。
- 在 `CouiFloatingRenderer` 构造、`makeDraw`、渐进模糊重录处加 `androidx.tracing` 的 `trace("…")` 段（release 包里几乎没有开销），让 Perfetto 能直接看到每一段耗时。
- 现有的 `CouiRenderDiagnostics` 在 App 里没有入口启用，可以顺带接到调试设置。

**利**：结论有据，避免「改了观感却没变」。**弊**：本身不修任何问题，依赖报告者配合或自备 HyperOS 真机。**风险**：几乎没有。

### 方案 B：低风险的定点修复（约 2–3 天）

每一项都对应 §3 里能从代码确认的热点，彼此独立，可以分批合入：

| # | 改动 | 涉及文件 | 预期收益 | 风险 |
|---|---|---|---|---|
| B1 | **着色器实例进程级共享**：按源码字符串缓存 `RuntimeShader`，只在主线程使用；凡是走 `RenderEffect.createRuntimeShaderEffect` 的（材质、焦散、直通、渐进模糊、Edge、Material），在创建 effect 前设好 uniform 即可共用 | `CouiFloatingSurfaces.kt`、`CouiGradientBlur.kt`、`CouiEdge.kt`、`CouiMaterial.kt`、新增一个小的 `CouiShaderCache` | 消掉 §3.1 的大部分 CPU 编译，课表页收益最大 | 需验证 effect 和 Paint 录制时确实会给 uniform 拍快照（代码注释已有此假设，见 `CouiGradientBlur.kt:179`）；必须限定在主线程 |
| B2 | 删掉 `CouiShadowRenderer` 里只为校验而编译的那一段；`materialClipSource` 每个槽位只建一个 shader，`makeDraw` 里只更新 uniform | `CouiShadowRenderer.kt:54-57`、`:118-120` | 消掉 §3.2 里每帧的 AGSL 编译 | 低 |
| B3 | 阴影的缩放不放进 key：Mesh 按 1:1 生成，缩放交给 Canvas 变换；或者把 sx/sy 量化到 1–2%，动画期间复用 | `CouiShadowRenderer.kt:77-83` | 按压和形变时不再每帧剖分几何、新建 Mesh | 中：源码注释说「网格包含轴向缩放」，需要对比半影是否一致 |
| B4 | `drawWithCache` 只放几何和 effect，把逐帧变化的缩放、alpha 挪到绘制阶段读取 | `CouiFloatingSurfaces.kt:242-430` | 按压和形变时不再重建整块缓存 | 中：这块代码长，需要回归底栏、顶栏、搜索框、课格 |
| B5 | `LocalTabSwitch` 改成 `compositionLocalOf`，或者传一个稳定的持有对象、在绘制阶段读取 | `Motion.kt:142`、`ShellTabHost.kt:146-162` | 切页时不再整页重组两次 | 低 |
| B6 | 导航进入的子页关掉控件入场动画（落实 `LocalEnterAnimations` 注释里的意图），在 `AppNavHost` 的 entry 外层 `provides false`，或在转场 `isRunning` 期间为 false | `AppNavHost.kt`、`Motion.kt:109-117` | 转场期间页面内容不变，采样层和顶栏模糊不必逐帧重算 | 低，视觉上少了一层错峰（本来就和放大转场重复） |
| B7 | **Baseline Profile 补全**：短期在 `app/src/main/baseline-prof.txt` 手写通配规则，覆盖 `nav`、`home`、`shell`、`profile`、`settings`、各 feature 的 `*Screen`、miuix `nav`；长期让采集脚本带登录态，跑「开子页 / 切页 / 设置」 | `app/src/main/baseline-prof.txt`、`baselineprofile/.../BaselineProfileGenerator.kt` | 直接改善「第一次」 | 通配过宽会增大安装时编译量和 APK 体积，需要权衡 |

### 方案 C：转场与模糊降级（约 3–5 天，依赖 A 的数据）

- **C1 转场期间冻结采样**：页面导航或切页动画运行时（`LocalNavTransitionScope.isRunning`，或 `ShellTabZoom` 的进度），顶栏改用上一帧的模糊结果或实色底，浮层按钮跳过焦散和阴影，落定后再恢复。380ms 内肉眼几乎看不出来。
- **C2 渐进模糊降采样**：把 `CouiGradientRenderer` 换成「1/4 降采样 + Kawase + 按渐变 mask 混合」，复用 `CouiMenuBlur` 的着色器。权重改为预先算好的 uniform 数组，不再在循环里算 `exp`。GPU 开销可以降一个数量级，但和原厂 COUI 的像素级一致性会下降。
- **C3 采样源按需离屏**：默认 `isolateWhenIdle = false`，没有消费者时不离屏；隐藏页签的采样层在隐藏时释放、显示时重建。
- **C4 重内容延后**：子页的列表主体在转场结束前先画骨架（现有的 `SeamlessPageSkeleton` 和各页骨架可以复用），落定后再组合真实内容。

**利**：直接压低转场中每帧的 GPU 和 CPU 开销。**弊**：改动面大，涉及视觉取舍，需要设计确认。**风险**：中等，容易引入闪烁或接缝，需要逐页回归。

### 方案 D：产品层兜底（约 1 天，可与 B/C 并行）

- 在设置里加「减弱动态效果 / 性能模式」：关闭液态背景动画、浮层光效和阴影、顶栏模糊（退回实色），转场改为普通平移。
- 也可以按 JankStats 统计自动建议开启。但报告者用的是旗舰机，**光靠降级不能替代 B/C**。

### 对比

| 方案 | 工作量 | 对 A/B（首次卡） | 对 C（切页后卡） | 对 E（总体） | 风险 |
|---|---|---|---|---|---|
| A 量化 | 0.5–1 天 | 定位 | 定位 | 定位 | 无 |
| B 定点修复 | 2–3 天 | ★★★（B1、B7） | ★★★（B2、B3、B5） | ★★ | 低–中 |
| C 降级与降采样 | 3–5 天 | ★★ | ★★ | ★★★ | 中 |
| D 性能模式 | 1 天 | ★（需用户手动开启） | ★ | ★ | 低 |

---

## 5. 推荐方案与实施步骤

**推荐 A + B 先行，C 按数据决定，D 作为可选兜底。** 理由：B 的每一项都能直接在代码里指认，改动局部、可以单独回滚；A 用来确认 B 是否打中要害，以及 C 是否必要。

1. **建立基线**（A）：在 issue 下请报告者提供 logcat、gfxinfo 和 trace；本地补 Macrobenchmark 帧时间用例，加 trace 段，记下 P50/P90/P99 帧时间和卡顿帧数。
2. **B2 + B5 + B6**：改动最小、收益明确，一个 PR。
3. **B1 着色器共享**：先在 `CouiGradientBlur` 和 `CouiFloatingRenderer` 上做；单测加真机对比截图，确认多实例同屏时 uniform 不串（底栏三格、顶栏多按钮、课表多格）。
4. **B3 + B4 浮层缓存重构**：单独一个 PR，重点回归按压、形变、拖动和搜索展开。
5. **B7 Profile**：先上手写通配规则，下一次发版时用真实登录态重新采集。
6. **对比基线**：如果转场中仍有明显的 GPU 卡顿帧（Perfetto 里看 RenderThread 和 GPU completion），再上 C1/C2，并先给设计看效果。
7. 每批改完只编译安卓（`:app:compileDebugKotlin`），在 HyperOS 真机和一台非 KIT 的 ROM 上各走一遍「首页 → 子页 ×3、我的 → 设置、底栏轮切、日程首进」。

---

## 6. 兼容性与回归约束（任何优化都不能破坏这些）

性能改动只能动「怎么画、何时画」，不能改变功能和低版本的退路。逐项列出：

**低 SDK 分层**（`minSdk = 31`，见 `app/build.gradle.kts:27`）

| 能力 | 最低 API | 现状 | 改动时的约束 |
|---|---|---|---|
| `RenderEffect`、模糊 | 31 | 31–34 走普通 `BlurEffect` 或纯色（`CouiGradientBlur.kt:170-178`、`CouiFloatingSurfaces.kt:317-339`） | B4、C2 不能把降级分支并进 AGSL 分支，31–34 必须保持现有退路 |
| `RuntimeShader`（AGSL） | 33 | 全部由 `couiEffectsSupported`（API 35）把关 | B1 的着色器缓存**只能在 `couiEffectsSupported` 为真时创建**，类加载期不能触碰 `RuntimeShader`，否则 31–32 会 `NoClassDefFoundError` |
| `Mesh` / `MeshSpecification` | 34 | 同样受 API 35 门控 | B3 不能把 Mesh 相关字段提成非懒加载的静态成员 |
| `PixelCopy.request(Window, …)` | 26 | KIT 失败自动回落 | 不动 |

- B1 要按「源码 → `RuntimeShader`」做懒加载缓存，放在 `androidMain`，并用 `couiEffectsSupported` 守住入口；`jvmMain`（桌面）和 `wasmJsMain`（Web）的 `CouiGlassCard` 等 actual 实现不受影响，也不应该被引用到。
- 共享实例只在主线程使用。需要专门验证：同一帧里两个组件先后设置 uniform 再创建 effect 时**不串色**（底栏三格、顶栏多按钮、课表多格同屏）。验证不过就退回「每个组件一份，但去掉多余的编译」，不强上共享。

**不能丢的功能行为**

- **KIT 回落链**：`KitOneTake.openPage` 保证 `fallback` 恰好调用一次（`KitOneTake.kt:80-84`）。任何预热或改动都不能吞掉回落；不支持的 ROM、截图失败、离线、维护中这些分支保持原样（`SeamlessOpener.kt:36-50`）。
- **经典风格**：`glass = false` 时不建采样源，顶栏为实色（`ShellBarPage.kt:42`）。C1、C3 只作用于玻璃风格，经典风格不能多出离屏层，也不能少画东西。
- **`LocalPageVisible` 的语义**：付款码在页面被盖住时只停刷新、不清码（`HomeTileBoard.kt:561-575`）；首页编辑态在不可见时自动保存（`HomeCardGroupScreen.kt:283-291`）；返回键拦截依赖它（`HomeQuickActions.kt:83`、`JwappScoreScreen.kt:129`）。B6、C4 不能改变它的翻转时机。
- **`LocalTabSwitch` 改为动态（B5）**：要保证同一页签内中途才组合出来的块（数据刚回来）仍然按「已就位」开局（`Motion.kt:194-200`），并且只在绘制阶段读取，不能变成逐帧重组。
- **关掉子页入场动画（B6）**：只在「导航进入」的页面关；底栏页签和首页布局切换保持现有逻辑（`ShellTabHost.kt:237`、`HomeScreen.kt:61`）。页面里依赖 `rememberEnterProgress` 的数字滚动、骨架等不能因此停在 0。
- **转场状态机**：`ExpandOrigins` 对结束帧、残帧和反悔手势有专门处理（`ExpandTransition.kt:131-185`）。C1 的「转场期间冻结采样」只能读 `isRunning` 之类的状态，不能写入或提前结束 `ExpandOrigins` 的状态，否则会回到「原卡藏着不出来、返回闪一下」的老问题。
- **阴影改动（B3）**：要保留透明度小于 1 时的半影外扩（`CouiFloatingSurfaces.kt:311`）和光源随主体平移（`CouiShadowRenderer.kt:75-80`）；改完对比按压、拖拽、展开搜索的截图。
- **采样源按需离屏（C3）**：`CouiBackdrop` 要求消费者成对 retain/release（`CouiBackdrop.kt:84-101`），菜单退出动画期间源必须保留。改默认值前先确认所有消费者都走 `RetainWhileComposed`。
- **Baseline Profile（B7）**：只能加规则，不能删；R8 混淆后类名会变，手写规则必须写混淆前的名字，并确认 release 和 preview 两个变体都生效（参考 `91fd84c19` 的做法）。

**回归清单（每批合入前走一遍）**

- 设备：一台 Android 15 及以上（AGSL 全开，最好是 HyperOS，KIT 可能开启），一台 Android 12–13（31–33，走退路），条件允许再加一台不支持 KIT 的 ROM。
- 路径：首页卡片和常用图标 → 子页并返回（含预测式返回手势中途反悔）；我的 → 设置、账号、下载管理；公告 → 详情；底栏轮切、拖动底栏；日程首进和周视图；搜索展开收起；菜单弹出；首页编辑态切页自动保存；饭点付款码；深色模式、动态取色；玻璃风格与经典风格各一遍。
- 只编译安卓：`:app:compileDebugKotlin`。

## 7. 待用户拍板的问题

1. **视觉取舍**：转场期间顶栏和浮层按钮暂时退成「上一帧模糊 / 实色、无光效」（C1），能否接受？
2. **模糊算法**：顶栏渐进模糊改成降采样加 Kawase（C2），会和原厂 COUI 有细微差别，能否接受？还是坚持原厂一致、只做 B1 和缓存优化？
3. **Profile 采集**：用通配规则（简单，包会大一点），还是维护一个用于采集的测试账号或离线演示数据？演示数据可以参考学期总结的示例数据做法。
4. **是否加「减弱动态效果」开关**（D）？如果加，默认值是什么？
5. **谁去 issue 下请报告者提供 logcat 和 trace**？本次按要求没有评论。另外需要确认：如果 HyperOS 支持 KIT，独立 Activity 这条路径是否保留？它每次都要冷建窗口，首帧压力比 Compose 路由更大。
6. 课表页课格的 COUI 有色玻璃（每格一套渲染器）是否值得保留？保留的话，B1 必须先做。

---

## 来源

- 金标联盟发布 SharedView Kit：[凤凰科技](https://tech.ifeng.com/c/8xgmfnKTzGD)、[IT 之家](https://www.ithome.com/0/978/157.htm)（四家厂商联合推动；未写明 HyperOS 的具体支持版本）
- [Android `RuntimeShader` 文档](https://developer.android.com/reference/android/graphics/RuntimeShader)（只有 `RuntimeShader(String)` 一个构造器）
- [hwui `jni/Shader.cpp`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/hwui/jni/Shader.cpp)（构造时调用 `SkRuntimeEffect::MakeForShader` 编译，失败抛 IAE，无缓存）
- [Skia `SkRuntimeEffect.cpp`](https://skia.googlesource.com/skia/+/HEAD/src/core/SkRuntimeEffect.cpp)（部分程序懒编译）
- [Flutter 文档：Shader compilation jank](https://liudonghua123.github.io/flutter_website/perf/shader)（Skia 着色器编译导致首帧卡顿的一般说明）
