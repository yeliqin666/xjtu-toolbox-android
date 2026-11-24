package com.xjtu.toolbox.settings

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.xjtu.toolbox.calendar.SystemCalendar
import com.xjtu.toolbox.ui.components.CouiSettingsCard
import com.xjtu.toolbox.ui.components.SmallTitle
import com.xjtu.toolbox.ui.components.SwitchPreference

/**
 * 「日程里显示系统日历」开关（#462）。默认关；打开时才申请「读取日历」权限，拒绝了就不打开。
 * 权限之后在系统设置里被收回时开关显示为关，再点一次会重新申请。
 */
@Composable
internal fun SystemCalendarPreference() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(SystemCalendar.hasPermission(context)) }
    var enabled by remember { mutableStateOf(SystemCalendar.isEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = SystemCalendar.hasPermission(context)
        enabled = SystemCalendar.isEnabled(context)
    }
    fun commit(on: Boolean) {
        enabled = on
        SystemCalendar.setEnabled(context, on)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) {
            commit(true)
        } else {
            Toast.makeText(context, "未授予日历权限，可在系统的应用设置里允许后再打开", Toast.LENGTH_SHORT).show()
        }
    }
    SmallTitle("系统日历")
    CouiSettingsCard {
        SwitchPreference(
            title = "在日程里显示系统日历",
            summary = if (enabled && !granted) "日历权限已被收回，点一下重新授权"
                else "把手机日历各账户里的日程合进今日时间轴和「接下来」；只读取，不会改动系统日历",
            checked = enabled && granted,
            startAction = { SettingsIcon(Icons.Default.Event, SettingsColors.Teal) },
            onCheckedChange = { on ->
                if (on && !granted) permission.launch(Manifest.permission.READ_CALENDAR) else commit(on)
            },
        )
    }
}
