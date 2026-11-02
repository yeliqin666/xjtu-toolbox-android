# 首页「一镜到底」：金标联盟 SharedViewKit + Compose 转场回落

本文记录 `feat/one-take-seamless` 分支上「首页公告卡 → 公告详情」这条无缝转场的实现与真机验证方法。
范围只有这一条英雄流程；其它页面仍然走仓库原有的 Compose 放大转场（`shared/navigation/.../ExpandTransition.kt`）。

## 1. 目标与分支行为

点首页公告堆叠条里的「查看详情」时：

| 运行时条件 | 走哪条路 | 效果 |
|---|---|---|
| ROM 声明支持 SharedViewKit（`isViewAnimationSupported() > 0`）且截图成功 | `KitOneTake` → 独立 `NoticeDetailActivity`（`ActivityOptions`） | ROM 系统级一镜到底：公告卡快照长成详情页，返回缩回卡片 |
| 不支持 / 截图失败 / `makeViewAnimationOptions()` 返回 null / 已有一个启动在进行中 | 压 `AppRoute.NoticeDetail` 路由 | 仓库现有 Compose 转场：窗口从公告卡矩形长到全屏，返回叠回卡片快照 |

判定在点击处完成；KIT 失败路径**恰好回落一次**（主线程），不会出现「点不动」或两套动画叠加。

## 2. 关键文件

| 层 | 文件 |
|---|---|
| KIT 桥（只 `:app`） | `app/src/main/java/com/xjtu/toolbox/sharedview/KitOneTake.kt` |
| KIT 宿主 Activity | `app/src/main/java/com/xjtu/toolbox/sharedview/NoticeDetailActivity.kt` |
| 路由 | `shared/navigation/src/commonMain/kotlin/com/xjtu/toolbox/nav/AppRoute.kt`（`AppRoute.NoticeDetail`） |
| 起点信息 | `shared/navigation/src/commonMain/kotlin/com/xjtu/toolbox/nav/SeamlessCardSource.kt` |
| 公告详情屏（两端共用） | `feature/home/src/commonMain/kotlin/com/xjtu/toolbox/bulletin/NoticeDetailScreen.kt` |
| 首页入口 | `feature/home/.../bulletin/BulletinUi.kt`、`home/HomeScreen.kt`、`home/HomeHost.kt`、`app/.../home/AppHomeHost.kt` |
| 路由注册 / 深链 | `app/.../nav/AppNavHost.kt`、`app/.../nav/DeepLinkRouter.kt` |
| 构建 | `settings.gradle.kts`、`gradle/libs.versions.toml`、`app/build.gradle.kts` |

## 3. KIT 侧的硬约束（实现时已满足）

- 目标必须是**独立 Activity**、同一 task；关闭动画绑在该 Activity 的 `finish()` 上。
- 目标页首帧必须不透明全屏，不能有自定义 window enter/exit 转场。
- 源是 Compose 页面，没有单卡 View，所以用 **Bitmap 源**：`PixelCopy` 截取公告卡窗口矩形（保留玻璃/底色等真实像素）。
- 单位：`radius/bounds` 都是 **px**（`18.dp × density`）。
- 坐标系：读 `getViewAnimationExtras()[EXTRA_BOUNDS]` 的 `rect|WINDOW|...` 元数据决定 SCREEN/WINDOW，不按机型硬编码。
- extras：只写 ROM 声明支持的 key（当前只用 `EXTRA_BLUR`）；未声明的写了不生效。
- 该 Activity 关掉预测式返回（manifest `android:enableOnBackInvokedCallback="false"`），避免系统跨 Activity 预测返回与 ROM 关闭动画打架。

## 4. 真机验证清单

```bash
# 构建
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 看 KIT 探测与启动（支持 ROM 应出现 protocol>0 与 start KIT 日志）
adb logcat -c && adb logcat -s KitOneTake SharedViewKit

# 1) 首页公告条出现后点「查看详情」
#    支持 ROM 期望：
#      SharedViewKit supported, protocol=100, extras=...
#      start KIT: protocol=100, windowRect=..., kitBounds=..., radiusPx=..., blur=...
#      KIT animation start, mode=0
#    不支持期望：
#      SharedViewKit unsupported on this ROM, fall back to Compose transition

# 2) 返回（工具栏返回 / 系统返回手势 / 快速返回）
#    期望 KIT animation end/start mode=1，页面缩回公告卡；无「返回动画一帧消失」

# 3) 不经过动画直达详情（验证路由/深链，不验证 KIT）
adb shell am start -a android.intent.action.VIEW \
  -d "xjtu://notice_detail?id=test&level=info&title=%E6%B5%8B%E8%AF%95" -p com.xjtu.toolbox

# 4) release 混淆后 KIT 契约仍在（必须通过）
./gradlew :app:minifyReleaseWithR8
grep 'com.itgsa.animation.sharedview' app/build/intermediates/mapping/release/minifyReleaseWithR8/mapping.txt
```

回归关注点：打开起点圆角/位置是否对齐、返回落点是否回到公告卡、深色/动态取色、旋转/分屏、快速连点、切后台再回、release 包（preview 包不混淆，问题只会在 release 暴露）。

## 5. 已知限制 / 风险

1. **覆盖率**：仅声明支持且 `isFeatureEnabled()` 打开的 ROM 走 KIT；模拟器、旧机型全部走 Compose 回落（回落路径已在模拟器验证）。
2. **半遮挡卡片**：公告卡被列表视口裁切时 `PixelCopy` 可能失败 → 自动回落。尚未做 demo 里「返回前滚动入视」的兜底。
3. **效果不统一**：各 ROM 支持的 extras 不同，模糊等效果按 ROM 声明来；KIT 的时长/曲线也由 ROM 决定。
4. **产品语义变化**：公告「查看详情」从跳系统浏览器改为站内详情页（页内保留「在浏览器中打开」）。
5. **依赖与许可**：`gsai.sdk:sharedview-kit` 来自联盟内测 Nexus（HTTP、`allowInsecureProtocol`），版本已钉死 `1.0.0.5`；闭源 AAR + GPL-3.0 发行需维护者/法务确认，CI 需能访问该仓库。
6. **回滚**：本流程独立，`git revert` 对应提交即可整体退回（路由/Activity/依赖/首页入口一起撤销），不影响其它页面。

## 6. 后续扩展（未做）

要把 KIT 扩到成绩/图书馆/思源等需要登录的页面，需要先把登录态从 Activity 级 `AppLoginStateViewModel` 提到进程级（第二个 Activity 会复制 SessionManager 并覆盖全局 `active`），再做通用 Activity 宿主；公告详情是本项目里唯一**自包含、不依赖登录态**的入口，所以先拿它做试点。
