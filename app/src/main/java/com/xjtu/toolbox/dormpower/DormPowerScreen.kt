package com.xjtu.toolbox.dormpower

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.auth.AuthExpiredException
import com.xjtu.toolbox.auth.LocalAppLoginState
import com.xjtu.toolbox.auth.SiteSession
import com.xjtu.toolbox.auth.handleAuthExpired
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.ui.components.AppPullToRefresh
import com.xjtu.toolbox.ui.components.EmptyState
import com.xjtu.toolbox.ui.components.ErrorState
import com.xjtu.toolbox.ui.components.FullPageState
import com.xjtu.toolbox.ui.components.LoadingState
import com.xjtu.toolbox.ui.glass.GlassTopAppBar
import com.xjtu.toolbox.ui.glass.glassSource
import com.xjtu.toolbox.ui.glass.glassTop
import com.xjtu.toolbox.ui.glass.rememberPageGlass
import com.xjtu.toolbox.ui.glass.withoutTop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

/** 宿舍电费：查剩余电量、绑定/解绑宿舍；充值交给学校自己的页面。 */
@Composable
fun DormPowerScreen(site: SiteSession, onOpenBrowser: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val appLoginState = LocalAppLoginState.current
    val scope = rememberCoroutineScope()
    val api = remember(site) { DormPowerApi(site, context.applicationContext) }

    var readings by remember { mutableStateOf(DormPowerStore.readings(context)) }
    var loading by remember { mutableStateOf(readings.isEmpty()) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var unbinding by remember { mutableStateOf<DormReading?>(null) }

    /** 跑一段网络操作；登录过期走统一的重登，其他错误按 [onFail] 处理。 */
    fun launchGuarded(onFail: (String) -> Unit, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: AuthExpiredException) {
                appLoginState.handleAuthExpired(AppRoute.DormPower, onBack)
            } catch (e: Exception) {
                onFail(e.message ?: "请求失败")
            }
        }
    }

    fun refresh(silent: Boolean) = launchGuarded(onFail = { msg ->
        loading = false; refreshing = false
        if (readings.isEmpty()) error = msg else context.toast(msg)
    }) {
        if (silent) refreshing = true else loading = readings.isEmpty()
        error = null
        readings = DormPowerStore.refresh(context, api)
        loading = false; refreshing = false
    }
    LaunchedEffect(api) { refresh(silent = false) }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val glass = rememberPageGlass()
    Scaffold(
        topBar = { GlassTopAppBar(title = "宿舍电费", glass = glass, scrollBehavior = scrollBehavior, onBack = onBack) },
    ) { padding ->
        val glassTop = padding.glassTop(glass)
        AppPullToRefresh(
            isRefreshing = refreshing,
            onRefresh = { refresh(silent = true) },
            scrollBehavior = scrollBehavior,
            topPadding = glassTop,
            modifier = Modifier.padding(padding.withoutTop(glass)).glassSource(glass).fillMaxSize(),
        ) {
            val pageModifier = Modifier.fillMaxSize().padding(top = glassTop)
            when {
                loading -> FullPageState(pageModifier) { LoadingState("正在查询电量...", Modifier.fillMaxSize()) }
                error != null -> FullPageState(pageModifier) {
                    ErrorState(error.orEmpty(), onRetry = { refresh(silent = false) }, modifier = Modifier.fillMaxSize())
                }
                readings.isEmpty() -> FullPageState(pageModifier) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        EmptyState("还没有绑定宿舍", "绑定后就能在这里和首页看到剩余电量，低于 ${LOW_KWH.toInt()} 度会提醒", Icons.Default.Bolt)
                        TextButton(
                            text = "添加宿舍",
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = { picking = true },
                            modifier = Modifier.padding(horizontal = 48.dp),
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = glassTop, bottom = 24.dp)) {
                    item { SmallTitle("我的宿舍") }
                    items(readings, key = { it.room.id }) { r ->
                        RoomCard(
                            r,
                            onRecharge = { onOpenBrowser(com.xjtu.toolbox.auth.SsnLogin.SSN_OAUTH_URL) },
                            onUnbind = { unbinding = r },
                        )
                    }
                    item {
                        TextButton(
                            text = "添加宿舍",
                            onClick = { picking = true },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    item {
                        Text(
                            "电量来自学校公寓用电管理系统，数据可能有延迟；充值会打开学校的缴费页。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }

    unbinding?.let { r ->
        OverlayDialog(show = true, title = "解绑宿舍", onDismissRequest = { unbinding = null }) {
            Text(r.room.name, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = { unbinding = null }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "解绑",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        unbinding = null
                        launchGuarded(onFail = { context.toast(it) }) {
                            api.unbind(r.room.id)
                            readings = DormPowerStore.refresh(context, api)
                        }
                    },
                )
            }
        }
    }

    if (picking) {
        RoomPickerDialog(
            api = api,
            onDismiss = { picking = false },
            onExpired = { picking = false; appLoginState.handleAuthExpired(AppRoute.DormPower, onBack) },
            onPick = { room ->
                picking = false
                launchGuarded(onFail = { context.toast(it) }) {
                    api.bind(room)
                    readings = DormPowerStore.refresh(context, api)
                }
            },
        )
    }
}

@Composable
private fun RoomCard(r: DormReading, onRecharge: () -> Unit, onUnbind: () -> Unit) {
    val low = r.kwh != null && r.kwh < LOW_KWH
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(r.room.shortName(), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                r.room.name,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    r.kwh?.let { "%.1f".format(it) } ?: "--",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (low) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    " 度",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            val status = when {
                r.kwh == null -> "系统暂时查不到这间宿舍的电表"
                low -> "电量不足，尽快充值"
                else -> null
            }
            status?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = if (low) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            if (r.at > 0) {
                Text(
                    "更新于 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(r.at)),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(text = "充值", onClick = onRecharge, colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            TextButton(text = "解绑", onClick = onUnbind, modifier = Modifier.weight(1f))
        }
    }
}

/** 从学校房间树一层层点到房间：进入子级，点到叶子就是要绑的宿舍。 */
@Composable
private fun RoomPickerDialog(api: DormPowerApi, onDismiss: () -> Unit, onExpired: () -> Unit, onPick: (DormRoom) -> Unit) {
    var tree by remember { mutableStateOf<List<RoomNode>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf(listOf<RoomNode>()) }

    LaunchedEffect(api) {
        try {
            tree = api.tree()
        } catch (e: AuthExpiredException) {
            onExpired()
        } catch (e: Exception) {
            error = e.message ?: "房间列表加载失败"
        }
    }
    BackHandler(path.isNotEmpty()) { path = path.dropLast(1) }

    OverlayDialog(show = true, title = "选择宿舍", onDismissRequest = onDismiss) {
        val options = if (path.isEmpty()) tree.orEmpty() else path.last().children
        when {
            error != null -> Text(error.orEmpty(), color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2)
            tree == null -> LoadingState("正在加载房间列表...")
            else -> Column {
                Text(
                    if (path.isEmpty()) "从园区开始逐级选择" else path.joinToString(" / ") { it.name },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                    if (path.isNotEmpty()) {
                        item { PickerRow("← 返回上一级") { path = path.dropLast(1) } }
                    }
                    items(options, key = { it.id }) { node ->
                        PickerRow(if (node.isLeaf) node.name else node.name + "  ›") {
                            if (node.isLeaf) {
                                onPick(DormRoom(node.id, (path + node).joinToString("/") { it.name }))
                            } else {
                                path = path + node
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PickerRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MiuixTheme.textStyles.body1,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
    )
}
