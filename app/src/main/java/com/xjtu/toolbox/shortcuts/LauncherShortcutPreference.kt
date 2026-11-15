package com.xjtu.toolbox.shortcuts

import androidx.compose.foundation.layout.*
import com.xjtu.toolbox.ui.components.ArrowPreference
import com.xjtu.toolbox.ui.components.SwitchPreference
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PushPin
import com.xjtu.toolbox.settings.SettingsColors
import com.xjtu.toolbox.settings.SettingsIcon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xjtu.toolbox.home.HomeIcons
import com.xjtu.toolbox.platform.showBriefMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LauncherShortcutPreference() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val candidates = remember { LauncherShortcuts.candidates() }
    val limit = remember { LauncherShortcuts.limit(context) }
    var selected by remember { mutableStateOf(LauncherShortcuts.selected(context)) }
    var show by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // 去系统权限页改完回来要重新查一次
    var pinDenied by remember { mutableStateOf(LauncherShortcuts.pinPermissionDenied(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) pinDenied = LauncherShortcuts.pinPermissionDenied(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    ArrowPreference(title = "桌面快捷方式", summary = "长按桌面图标展开快捷方式",
        startAction = { SettingsIcon(Icons.Default.Apps, SettingsColors.Blue) },
        onClick = { show = true })
    if (show) OverlayBottomSheet(show = true, title = "桌面快捷方式", onDismissRequest = { show = false }) {
        Column {
            Text("长按图标最多展示 $limit 项，按勾选顺序排列；图钉可将任意功能单独放到桌面。",
                style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (pinDenied) {
                ArrowPreference(
                    title = "未开启「桌面快捷方式」权限",
                    summary = "开启后才能把功能放到桌面，点此前往",
                    onClick = { LauncherShortcuts.openPermissionSettings(context) },
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding()) {
                items(candidates, key = { it.route.id }) { service ->
                    val canSelect = !saving && (service.route.id in selected || selected.size < limit)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SwitchPreference(title = service.title, checked = service.route.id in selected,
                            enabled = canSelect,
                            modifier = Modifier.weight(1f),
                            startAction = { Icon(HomeIcons.of(service.route), null, modifier = Modifier.size(22.dp),
                                tint = MiuixTheme.colorScheme.onSurface.copy(alpha = if (canSelect) 1f else .3f)) },
                            onCheckedChange = { checked ->
                                val next = if (checked) selected + service.route.id else selected - service.route.id
                                selected = next
                                LauncherShortcuts.save(context, next)
                                saving = true
                                scope.launch {
                                    try {
                                        if (!withContext(Dispatchers.IO) { LauncherShortcuts.sync(context) })
                                            showBriefMessage("系统暂未更新快捷方式，下次打开应用时会重试")
                                    } finally { saving = false }
                                }
                            })
                        IconButton(onClick = {
                            when (LauncherShortcuts.pin(context, service)) {
                                LauncherShortcuts.PinResult.Requested -> Unit
                                LauncherShortcuts.PinResult.Unsupported -> showBriefMessage("当前桌面暂不支持添加快捷方式")
                                LauncherShortcuts.PinResult.NeedPermission -> {
                                    pinDenied = true
                                    showBriefMessage("请先开启「桌面快捷方式」权限")
                                    LauncherShortcuts.openPermissionSettings(context)
                                }
                            }
                        }, modifier = Modifier.padding(end = 12.dp)) {
                            Icon(Icons.Default.PushPin, "将${service.title}添加到桌面", tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}
