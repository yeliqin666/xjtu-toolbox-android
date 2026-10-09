package com.xjtu.toolbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton

/**
 * 顶栏左上角的返回按钮。
 *
 * ⚠️ 图标是**共享代码里手搓的 `ImageVector`**，而不是 `Icons.AutoMirrored.Filled.ArrowBack`：
 * `androidx.compose.material:material-icons-*` 实测**没有 wasmJs 变体**（Maven Central 上
 * `material-icons-core-wasm-js` / `material-icons-extended-wasm-js` 都是 404），一旦 import 它，
 * `:core:compileKotlinWasmJs` 这条门禁立刻红 —— 这也是「UI 基础设施必须先搬」的一个具体原因。
 *
 * 所以这里把 Material 的那条路径数据原样搬进来：几何逐点相同，`Icon` 的 tint 覆盖 fill
 * （与 Material 资产的做法一致），**Android 端渲染结果不变**。
 *
 * 唯一差异：Material 的 AutoMirrored 版在 RTL 语言下会水平翻转，这里的固定路径不翻。
 * 本 App 只有 zh/en（LTR），没有可见影响；将来真要支持 RTL 时把这里换成 expect/actual
 * （androidMain 用 `Icons.AutoMirrored`）即可，调用点不用动。
 */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(BackArrow, contentDescription = "返回")
    }
}

/** Material `arrow_back`（24dp）的原始路径，逐点照抄。 */
private val BackArrow: ImageVector by lazy {
    ImageVector.Builder(
        name = "ArrowBack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 11f)
            horizontalLineTo(7.83f)
            lineToRelative(5.59f, -5.59f)
            lineTo(12f, 4f)
            lineToRelative(-8f, 8f)
            lineToRelative(8f, 8f)
            lineToRelative(1.41f, -1.41f)
            lineTo(7.83f, 13f)
            horizontalLineTo(20f)
            verticalLineToRelative(-2f)
            close()
        }
    }.build()
}
