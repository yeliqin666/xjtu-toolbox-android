package com.xjtu.toolbox.plugin.sample

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.xjtu.toolbox.plugin.api.PluginContext
import com.xjtu.toolbox.plugin.api.PluginPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 示例页：宿主给标准页面壳，这里用插件自带的 Compose 画内容区。
 *
 * 关键点是 [PageLifecycleOwner]：插件自带一份 Compose / lifecycle，不能依赖宿主的
 * `ViewTreeLifecycleOwner`（类身份不同），所以自己给 ComposeView 装一套 lifecycle。
 */
internal class SamplePage(private val pluginContext: PluginContext) : PluginPage() {

    private var lifecycleOwner: PageLifecycleOwner? = null

    override fun id(): String = "home"

    override fun createView(context: Context, argsJson: String): View {
        val owner = PageLifecycleOwner()
        lifecycleOwner = owner
        val view = ComposeView(context)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setContent { SampleContent(pluginContext) }
        // 进页就是前台；否则 ComposeView 挂上去时停在 CREATED，动画和协程作用域要等一次
        // 宿主 onResume 才起步（宿主 effect 与 AndroidView 的 attach 顺序不保证）。
        owner.moveTo(Lifecycle.State.RESUMED)
        return view
    }

    override fun onStart() {
        lifecycleOwner?.moveTo(Lifecycle.State.STARTED)
    }

    override fun onResume() {
        lifecycleOwner?.moveTo(Lifecycle.State.RESUMED)
    }

    override fun onPause() {
        lifecycleOwner?.moveTo(Lifecycle.State.STARTED)
    }

    override fun onStop() {
        lifecycleOwner?.moveTo(Lifecycle.State.CREATED)
    }

    override fun onDestroy() {
        lifecycleOwner?.destroy()
        lifecycleOwner = null
    }
}

/** 插件自己的 lifecycle/savedstate/viewModel 宿主，全部走插件自带的 androidx 副本。 */
private class PageLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun moveTo(state: Lifecycle.State) {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = state
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

@Composable
private fun SampleContent(pluginContext: PluginContext) {
    val dark = pluginContext.isDarkTheme
    val background = if (dark) Color(0xFF141414) else Color(0xFFF5F6F8)
    val foreground = if (dark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)
    val muted = if (dark) Color(0xFF9A9A9A) else Color(0xFF6B6B6B)
    val accent = Color(pluginContext.primaryColor)
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("点「读取成绩」调用宿主的只读学校接口。") }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                pluginContext.schoolTool("get_grades", "{}")
            }
            text = parseSchoolReply(result)
            loading = false
        }
    }

    // 进页自动读一次，省得用户先点一下才知道能不能用
    LaunchedEffect(Unit) { refresh() }

    Column(
        Modifier.fillMaxSize().background(background).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        BasicText(
            "示例插件",
            style = TextStyle(color = foreground, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        )
        BasicText(
            "这个页面和工具都由插件 APK 提供，宿主只给了只读学校接口。",
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
            style = TextStyle(color = muted, fontSize = 13.sp),
        )
        BasicText(
            if (loading) "读取中…" else "读取成绩",
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(accent)
                .clickable { refresh() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
        BasicText(
            text,
            modifier = Modifier.padding(top = 16.dp),
            style = TextStyle(color = foreground, fontSize = 13.sp, lineHeight = 20.sp),
        )
    }
}

private fun parseSchoolReply(raw: String): String = runCatching {
    val obj = JSONObject(raw)
    if (obj.optBoolean("ok")) {
        obj.optString("text").ifBlank { "(空结果)" }
    } else {
        "学校接口返回错误：" + obj.optString("error", "unknown")
    }
}.getOrElse { raw }
