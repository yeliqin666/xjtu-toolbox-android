package com.xjtu.toolbox.library

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 定时抢座设置对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeatGrabDialog(
    currentFavorites: Set<String>,
    selectedArea: String,
    onDismiss: () -> Unit,
    onConfirm: (SeatGrabConfig) -> Unit
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(SeatGrabConfigStore.load(context)) }
    var manualSeatId by remember { mutableStateOf("") }
    var manualAreaName by remember { mutableStateOf(selectedArea) }

    // 时间选择
    var hourStr by remember { mutableStateOf(config.triggerTimeStr.substringBefore(":")) }
    var minuteStr by remember { mutableStateOf(config.triggerTimeStr.split(":").getOrElse(1) { "00" }) }
    var secondStr by remember { mutableStateOf(config.triggerTimeStr.split(":").getOrElse(2) { "00" }) }

    // 权限状态
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var hasExactAlarmPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
            } else true
        )
    }

    // 通知权限请求
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasNotificationPermission = granted }

    // 从系统设置返回后刷新权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else true
                hasExactAlarmPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
                } else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("定时抢座", style = MaterialTheme.typography.headlineSmall)
            }
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
            ) {
                // ── 权限检查 ──
                if (!hasNotificationPermission || !hasExactAlarmPermission) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "⚠ 需要授权",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(4.dp))
                                if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    TextButton(onClick = {
                                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }) { Text("授予通知权限") }
                                }
                                if (!hasExactAlarmPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    TextButton(onClick = {
                                        context.startActivity(
                                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                        )
                                    }) { Text("授予精确闹钟权限") }
                                }
                            }
                        }
                    }
                }

                // ── 进程保活提醒 ──
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "⚡ 进程保活提醒",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "为确保定时抢座成功，请：\n" +
                                    "1. 将本应用加入电池优化白名单\n" +
                                    "2. 允许后台运行（设置→应用→特殊权限）\n" +
                                    "3. 锁屏后不要手动清理后台\n" +
                                    "4. 部分厂商 ROM 需在安全中心/手机管家中设置自启动",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                // ── 触发时间 ──
                item {
                    Text("触发时间", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = hourStr,
                            onValueChange = { if (it.length <= 2 && it.all { c -> c.isDigit() }) hourStr = it },
                            modifier = Modifier.width(64.dp),
                            label = { Text("时") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Text(":", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        OutlinedTextField(
                            value = minuteStr,
                            onValueChange = { if (it.length <= 2 && it.all { c -> c.isDigit() }) minuteStr = it },
                            modifier = Modifier.width(64.dp),
                            label = { Text("分") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Text(":", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        OutlinedTextField(
                            value = secondStr,
                            onValueChange = { if (it.length <= 2 && it.all { c -> c.isDigit() }) secondStr = it },
                            modifier = Modifier.width(64.dp),
                            label = { Text("秒") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }

                // ── 目标座位列表 ──
                item {
                    Text("目标座位（按优先级排列）", style = MaterialTheme.typography.titleSmall)
                }

                // 已添加的座位
                itemsIndexed(config.targetSeats) { index, seat ->
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(seat.seatId, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    seat.areaName.ifEmpty { seat.areaCode },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // 上移
                            if (index > 0) {
                                IconButton(
                                    onClick = {
                                        val list = config.targetSeats.toMutableList()
                                        val temp = list[index]
                                        list[index] = list[index - 1]
                                        list[index - 1] = temp
                                        config = config.copy(targetSeats = list.mapIndexed { i, s -> s.copy(priority = i) })
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) { Icon(Icons.Default.KeyboardArrowUp, "上移", Modifier.size(18.dp)) }
                            }
                            // 下移
                            if (index < config.targetSeats.size - 1) {
                                IconButton(
                                    onClick = {
                                        val list = config.targetSeats.toMutableList()
                                        val temp = list[index]
                                        list[index] = list[index + 1]
                                        list[index + 1] = temp
                                        config = config.copy(targetSeats = list.mapIndexed { i, s -> s.copy(priority = i) })
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) { Icon(Icons.Default.KeyboardArrowDown, "下移", Modifier.size(18.dp)) }
                            }
                            // 删除
                            IconButton(
                                onClick = {
                                    config = config.copy(
                                        targetSeats = config.targetSeats.filterIndexed { i, _ -> i != index }
                                            .mapIndexed { i, s -> s.copy(priority = i) }
                                    )
                                },
                                modifier = Modifier.size(32.dp)
                            ) { Icon(Icons.Default.Close, "删除", Modifier.size(18.dp)) }
                        }
                    }
                }

                // 从收藏添加按钮
                if (currentFavorites.isNotEmpty()) {
                    item {
                        val existingIds = config.targetSeats.map { it.seatId }.toSet()
                        val addable = currentFavorites.filter { it !in existingIds }
                        if (addable.isNotEmpty()) {
                            var expanded by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = { expanded = !expanded },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Star, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("从收藏添加 (${addable.size})")
                            }
                            AnimatedVisibility(expanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    addable.forEach { seatId ->
                                        val guessedArea = LibraryApi.guessAreaCode(seatId)
                                        val areaName = guessedArea?.let { code ->
                                            LibraryApi.AREA_MAP.entries.find { it.value == code }?.key
                                        } ?: selectedArea
                                        val areaCode = guessedArea ?: LibraryApi.AREA_MAP[selectedArea] ?: ""

                                        TextButton(
                                            onClick = {
                                                val newSeat = TargetSeat(
                                                    seatId = seatId,
                                                    areaCode = areaCode,
                                                    areaName = areaName,
                                                    priority = config.targetSeats.size
                                                )
                                                config = config.copy(
                                                    targetSeats = config.targetSeats + newSeat
                                                )
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("★ $seatId ($areaName)")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 手动输入
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = manualSeatId,
                            onValueChange = { manualSeatId = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("座位号") },
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        FilledTonalButton(
                            onClick = {
                                if (manualSeatId.isNotBlank()) {
                                    val guessedArea = LibraryApi.guessAreaCode(manualSeatId.trim())
                                    val areaCode = guessedArea
                                        ?: LibraryApi.AREA_MAP[manualAreaName]
                                        ?: LibraryApi.AREA_MAP[selectedArea]
                                        ?: ""
                                    val areaName = if (guessedArea != null) {
                                        LibraryApi.AREA_MAP.entries.find { it.value == guessedArea }?.key ?: manualAreaName
                                    } else manualAreaName
                                    val newSeat = TargetSeat(
                                        seatId = manualSeatId.trim(),
                                        areaCode = areaCode,
                                        areaName = areaName,
                                        priority = config.targetSeats.size
                                    )
                                    config = config.copy(targetSeats = config.targetSeats + newSeat)
                                    manualSeatId = ""
                                }
                            },
                            enabled = manualSeatId.isNotBlank()
                        ) { Text("添加") }
                    }
                }

                // ── 高级设置 ──
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    Text("高级设置", style = MaterialTheme.typography.titleSmall)
                }

                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = config.maxRetries.toString(),
                            onValueChange = {
                                it.toIntOrNull()?.let { n ->
                                    config = config.copy(maxRetries = n.coerceIn(1, 20))
                                }
                            },
                            modifier = Modifier.width(100.dp),
                            label = { Text("重试次数") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = (config.retryIntervalMs / 1000).toString(),
                            onValueChange = {
                                it.toLongOrNull()?.let { sec ->
                                    config = config.copy(retryIntervalMs = (sec * 1000).coerceIn(500, 30000))
                                }
                            },
                            modifier = Modifier.width(100.dp),
                            label = { Text("间隔(秒)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }

                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("已有预约时自动换座", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = config.autoSwap,
                            onCheckedChange = { config = config.copy(autoSwap = it) }
                        )
                    }
                }

                // 底部留白
                item { Spacer(Modifier.height(8.dp)) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = hourStr.toIntOrNull()?.coerceIn(0, 23) ?: 22
                    val m = minuteStr.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    val s = secondStr.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    val timeStr = "%02d:%02d:%02d".format(h, m, s)
                    val finalConfig = config.copy(
                        enabled = true,
                        triggerTimeStr = timeStr
                    )
                    onConfirm(finalConfig)
                },
                enabled = config.targetSeats.isNotEmpty()
            ) {
                Icon(Icons.Default.Schedule, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("设定闹钟")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
