package com.xjtu.toolbox.schedule

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.xjtu.toolbox.ui.components.AppSegmentedTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 课表壁纸取景：按本机屏幕比例实时预览，上下拖动摆位置、双指缩放，叠一层锁屏时钟与指纹区的示意好避开。
 * 选好底色、天数、节次后设锁屏、设桌面或存相册；出图按屏幕原始分辨率。
 */
@Composable
internal fun ScheduleWallpaperEditor(courses: List<CourseItem>, week: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = MiuixTheme.colorScheme.primary.toArgb()
    var theme by rememberSaveable { mutableStateOf(WallpaperTheme.LIGHT) }
    var weekdaysOnly by rememberSaveable { mutableStateOf(courses.none { it.isInWeek(week) && it.dayOfWeek >= 6 }) }
    var daytimeOnly by rememberSaveable { mutableStateOf(false) }
    var top by rememberSaveable { mutableStateOf(WallpaperOptions().top) }
    var scale by rememberSaveable { mutableStateOf(1f) }
    var busy by remember { mutableStateOf(false) }
    val options = WallpaperOptions(theme, weekdaysOnly, daytimeOnly, top, scale)

    val metrics = context.resources.displayMetrics
    val screenW = metrics.widthPixels
    // 壁纸按整屏（含状态栏、导航栏）出图
    val screenH = (context.getSystemService(android.view.WindowManager::class.java)?.maximumWindowMetrics?.bounds?.height())
        ?: metrics.heightPixels

    fun export(action: (android.graphics.Bitmap) -> Boolean, done: String) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.Default) {
                val bitmap = ScheduleWallpaper.render(courses, week, screenW, screenH, options, accent)
                try { action(bitmap) } finally { bitmap.recycle() }
            }
            busy = false
            Toast.makeText(context, if (ok) done else "没成功，请稍后再试", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // 弹窗自己的窗口也要沉浸：铺到状态栏、小白条下面，不压暗、不加导航栏衬底，图标深浅跟页面
        val view = LocalView.current
        val lightBars = MiuixTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                window.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "关闭") }
                Text("课表壁纸", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Medium)
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                val ratio = screenW.toFloat() / screenH
                val frame = if (maxWidth / maxHeight > ratio) Modifier.fillMaxSize().aspectRatio(ratio, matchHeightConstraintsFirst = true)
                else Modifier.fillMaxWidth(0.9f).aspectRatio(ratio)
                Box(
                    frame
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                top = (top + pan.y / size.height).coerceIn(0f, 0.9f)
                                scale = (scale * zoom).coerceIn(0.7f, 1.3f)
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas {
                            ScheduleWallpaper.draw(it.nativeCanvas, courses, week, size.width.toInt(), size.height.toInt(), options, accent)
                        }
                    }
                    LockScreenHints(dark = theme != WallpaperTheme.LIGHT)
                }
            }
            Text(
                "拖动调位置，双指缩放；时钟和指纹仅作示意",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp),
            )

            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OptionRow("底色") {
                    AppSegmentedTabs(WallpaperTheme.entries.map { it.label }, theme.ordinal, { theme = WallpaperTheme.entries[it] }, compact = true)
                }
                OptionRow("天数") {
                    AppSegmentedTabs(listOf("整周", "工作日"), if (weekdaysOnly) 1 else 0, { weekdaysOnly = it == 1 }, compact = true)
                }
                OptionRow("节次") {
                    AppSegmentedTabs(listOf("全天", "只要白天"), if (daytimeOnly) 1 else 0, { daytimeOnly = it == 1 }, compact = true)
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = "存相册",
                    enabled = !busy,
                    onClick = { export({ ScheduleWallpaper.save(context, it) }, "已保存到相册") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "设为桌面",
                    enabled = !busy,
                    onClick = { export({ ScheduleWallpaper.apply(context, it, lock = false) }, "已设为桌面壁纸") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "设为锁屏",
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { export({ ScheduleWallpaper.apply(context, it, lock = true) }, "已设为锁屏壁纸") },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MiuixTheme.textStyles.body2, modifier = Modifier.width(48.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** 锁屏时钟、日期与屏下指纹的位置示意（按屏高比例摆，和多数机型的锁屏差不多）。 */
@Composable
private fun LockScreenHints(dark: Boolean) {
    val ink = (if (dark) Color.White else Color.Black).copy(alpha = 0.35f)
    val now = remember { java.time.LocalDateTime.now(com.xjtu.toolbox.util.CampusZoneId) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        Column(Modifier.align(Alignment.TopCenter).padding(top = h * 0.1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%d:%02d".format(now.hour, now.minute), color = ink, fontSize = (w.value * 0.17f).sp, fontWeight = FontWeight.Light)
            Text(
                "${now.monthValue}月${now.dayOfMonth}日 星期${"一二三四五六日"[now.dayOfWeek.value - 1]}",
                color = ink,
                fontSize = (w.value * 0.04f).sp,
            )
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = h * 0.84f)
                .size(w * 0.13f)
                .border(1.dp, ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = ink, modifier = Modifier.size(w * 0.08f))
        }
    }
}
