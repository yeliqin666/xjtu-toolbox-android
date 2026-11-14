package com.xjtu.toolbox.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
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
import com.xjtu.toolbox.ui.components.SmallTitle
import com.xjtu.toolbox.ui.components.ArrowPreference
import com.xjtu.toolbox.ui.components.CouiSettingsCard
import com.xjtu.toolbox.ui.components.SwitchPreference
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
    CouiSettingsCard {
            SwitchPreference(
                title = "动态课程与日程通知",
                summary = "显示今天正在上的或下一节安排和倒计时，要先同步课表，安排结束后自动收起",
                checked = enabled,
                startAction = { SettingsIcon(Icons.Default.EventNote, SettingsColors.Blue) },
                onCheckedChange = { on ->
                    if (on && !canPost) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else commit(on)
                },
            )
            if (enabled) {
                var walkOn by remember { mutableStateOf(DynamicScheduleScheduler.isWalkHintOn(context)) }
                SwitchPreference(
                    title = "跨楼时提醒出发",
                    summary = "上一节和下一节不在一栋楼时，按步行时间提醒你该出发了",
                    checked = walkOn,
                    startAction = { SettingsIcon(Icons.Default.EventNote, SettingsColors.Green) },
                    onCheckedChange = { on ->
                        walkOn = on
                        DynamicScheduleScheduler.setWalkHintOn(context, on)
                    },
                )
                ArrowPreference(
                    title = "准时切换通知",
                    summary = if (exact) "已经允许在课程和日程开始时切换" else "还没允许精确闹钟，后台切换可能晚一点，倒计时照常更新",
                    startAction = { SettingsIcon(Icons.Default.Alarm, SettingsColors.Orange) },
                    onClick = {
                        openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    },
                )
                ArrowPreference(
                    title = "系统通知设置",
                    summary = if (canPost) "可以单独关掉动态课程和日程的通知" else "系统通知还没允许，现在没法展示",
                    startAction = { SettingsIcon(Icons.Default.Notifications, SettingsColors.Teal) },
                    onClick = {
                        openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    },
                )
            }
    }
    SmallTitle("自动勿扰")
    CouiSettingsCard {
            SwitchPreference(
                title = "上课、在馆时自动开启勿扰",
                summary = if (dndGranted) "课前 3 分钟到课后 3 分钟和图书馆入座期间自动切到仅优先，结束后恢复，手动改过的不会还原"
                    else "要先在系统里允许勿扰模式访问，应用只会切换勿扰，不读取通知内容",
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
