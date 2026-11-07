# :thirdparty:backdrop（vendored）

上游：`io.github.kyant0:backdrop:2.0.1`（仓库 [Kyant0/backdrop](https://github.com/Kyant0/backdrop)，
作者 Kyant，Apache License 2.0）。源码取自 Maven Central 的 `backdrop-2.0.1-sources.jar`、
`backdrop-android-2.0.1-sources.jar`，包名保持 `com.kyant.backdrop` 不变。

## 为什么放进仓库

岱宗盒子的液态玻璃用它。首页有一档需要「**不采样、但光影一条不少**」——纯色 / 渐变背景，或者壁纸
已经被模糊程度糊平、卡片再从背景上采一遍什么都看不出来。上游的 `drawBackdrop` 没有这个开关：

- `DrawBackdropNode` 不管采样源里有没有内容，每帧都会 `record` + `draw` 一层卡片大小的离屏层；
- 卡片内容还一直被放在 `CompositingStrategy.Offscreen` 里；
- `effects { }` 里配的 `blur` / `lens` / `vibrancy` 即使采样源是空的也照样建 renderEffect。

也就是说「把采样源换成 `emptyBackdrop()`」只省掉采样那一趟，离屏层一分没省。所以 fork 一份，
加一个 `sampling` 开关，把这一档的离屏层与 renderEffect 一起关掉，而高光（`Highlight`）与投影
（`Shadow`）的绘制代码**一个字不改**——观感与采样版逐像素一致。

顶替方式（不需要改任何调用点）：根 `build.gradle.kts` 里

```kotlin
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.kyant0:backdrop")).using(project(":thirdparty:backdrop"))
    }
}
```

## 相对上游 2.0.1 改了什么

到目前为止**只有为了让构建跑起来的三处**，绘制逻辑一行未动：

1. 去掉 `@Language("AGSL")` 注解（`RuntimeShader.kt`、`RuntimeShaderCache.kt`、`effects/RenderEffect.kt`、
   `internal/Shaders.kt`、两个 `setFloatUniform` 无所谓）。那个注解来自 `org.jetbrains:annotations`，
   是 JVM-only 的 jar，放在 commonMain 会拖一条不兼容的依赖进来；注解只影响 IDE 高亮，删除无行为影响。
2. 去掉 androidMain `Platform.kt` 上的 `@ChecksSdkIntAtLeast`（只给 lint 用），免得为它引入
   `androidx.annotation:annotation-experimental`。两个函数的判等逻辑与上游一致。
3. 上游把 jvm 与 wasmJs 共用的实现放在 `skikoMain` 这个中间源集，而本仓库的约定插件只建
   `jvmMain` / `wasmJsMain`，所以 `build.gradle.kts` 里补一个同名中间源集让两者 `dependsOn` 它。

后续计划（第二步）：`DrawBackdropModifier.kt` 加 `sampling: Boolean = true`，`false` 时跳过
`drawBackdropLayer()`、不设 `CompositingStrategy.Offscreen`、不执行 `effects`。

已知上游遗留（**未改动**，按最小改动原则）：`highlight/HighlightModifier.kt` 的 `prevStyle`
只写不读（SearchDeadCode 会报一条 DC002），上游 2.0.1 就是这样。

## 升级上游

重新拉两个 sources jar 覆盖 `src/`，然后把上面三处改动重放一遍。**不要**直接改绘制代码而不同步
到这里，否则下次升级会丢。

## 许可

Apache License 2.0（见同目录 `LICENSE`）。版权归上游作者 Kyant。
