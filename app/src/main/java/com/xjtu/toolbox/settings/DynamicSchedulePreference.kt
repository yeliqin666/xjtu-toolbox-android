package com.xjtu.toolbox.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.xjtu.toolbox.notification.DynamicScheduleScheduler
import com.xjtu.toolbox.notification.DynamicScheduleNotifier
import com.xjtu.toolbox.notification.AutoDndScheduler
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DynamicSchedulePreference() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(DynamicScheduleScheduler.isEnabled(context)) }
    var exact by remember { mutableStateOf(DynamicScheduleScheduler.canScheduleExactly(context)) }
    var canPost by remember { mutableStateOf(DynamicScheduleNotifier.canPost(context)) }
    var dndOn by remember { mutableStateOf(AutoDndScheduler.isEnabled(context)) }
    var dndGranted by remember { mutableStateOf(AutoDndScheduler.hasAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exact = DynamicScheduleScheduler.canScheduleExactly(context)
        canPost = DynamicScheduleNotifier.canPost(context)
        dndOn = AutoDndScheduler.isEnabled(context)
        dndGranted = AutoDndScheduler.hasAccess(context)
        DynamicScheduleScheduler.requestRefresh(context)
    }
    fun commit(value: Boolean) {
        enabled = value
        DynamicScheduleScheduler.setEnabled(context, value)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canPost = DynamicScheduleNotifier.canPost(context)
        if (granted) commit(true)
        else Toast.makeText(context, "未授予通知权限，动态通知未开启", Toast.LENGTH_SHORT).show()
    }
    fun openSettings(intent: Intent) {
        if (runCatching { context.startActivity(intent) }.isFailure) {
            Toast.makeText(context, "无法打开系统设置，请在应用信息中调整", Toast.LENGTH_SHORT).show()
        }
    }
    SmallTitle("动态通知")
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column {
            SwitchPreference(
                title = "动态课程与日程通知",
                summary = "显示今日当前或下一项安排与倒计时；需先同步课表，今日安排结束后自动收起",
                checked = enabled,
                startAction = { SettingsIcon(Icons.Default.EventNote, SettingsColors.Blue) },
                onCheckedChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && !canPost) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else commit(on)
                },
            )
            if (enabled) {
                var walkOn by remember { mutableStateOf(DynamicScheduleScheduler.isWalkHintOn(context)) }
                SwitchPreference(
                    title = "跨楼时提醒出发",
                    summary = "上一节与下一节不在同一栋楼时，按步行时间在开课前提醒该出发了",
                    checked = walkOn,
                    startAction = { SettingsIcon(Icons.Default.EventNote, SettingsColors.Green) },
                    onCheckedChange = { on ->
                        walkOn = on
                        DynamicScheduleScheduler.setWalkHintOn(context, on)
                    },
                )
                ArrowPreference(
                    title = "准时切换通知",
                    summary = if (exact) "已允许在课程、日程开始和结束时切换" else "未允许精确闹钟，后台切换可能延迟；倒计时仍自动更新",
                    startAction = { SettingsIcon(Icons.Default.Alarm, SettingsColors.Orange) },
                    onClick = {
                        openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    },
                )
                ArrowPreference(
                    title = "系统通知设置",
                    summary = if (canPost) "可单独关闭动态课程与日程渠道" else "系统通知未允许，当前无法展示",
                    startAction = { SettingsIcon(Icons.Default.Notifications, SettingsColors.Teal) },
                    onClick = {
                        openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    },
                )
            }
        }
    }
    SmallTitle("自动勿扰")
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
    ) {
        Column {
            SwitchPreference(
                title = "上课、在馆时自动开启勿扰",
                summary = if (dndGranted) "课前 3 分钟到课后 3 分钟、图书馆签到入座期间切为「仅优先」，结束后恢复；手动改过的不会被还原"
                    else "需要先在系统里授权「勿扰模式访问」（应用可以切换勿扰，不读取通知内容）",
                checked = dndOn,
                startAction = { SettingsIcon(Icons.Default.Notifications, SettingsColors.Purple) },
                onCheckedChange = { on ->
                    if (!dndGranted) openSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    else {
                        dndOn = on
                        AutoDndScheduler.setEnabled(context, on)
                        DynamicScheduleScheduler.requestRefresh(context)
                    }
                },
            )
        }
    }
}
