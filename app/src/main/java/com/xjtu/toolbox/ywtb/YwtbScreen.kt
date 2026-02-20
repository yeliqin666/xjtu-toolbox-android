package com.xjtu.toolbox.ywtb

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.auth.YwtbLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YwtbScreen(login: YwtbLogin, onBack: () -> Unit) {
    val api = remember { YwtbApi(login) }
    var userInfo by remember { mutableStateOf<UserInfo?>(null) }
    var currentTerm by remember { mutableStateOf<String?>(null) }
    var currentWeek by remember { mutableStateOf<Int?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) {
                userInfo = api.getUserInfo()
                try {
                    val weekInfo = api.getCurrentWeekOfTeaching()
                    if (weekInfo != null) {
                        val (week, semesterName, semesterId) = weekInfo
                        currentWeek = week
                        // semesterId 格式: "2024-2025", semesterName: "第一学期"/"第二学期"
                        val termNo = if (semesterName.contains("二")) "2" else "1"
                        currentTerm = "$semesterId-$termNo"
                    } else {
                        // 假期：服务端返回无教学周，客户端估算学期名用于显示
                        currentWeek = null
                        val now = LocalDate.now()
                        val year = now.year; val month = now.monthValue
                        currentTerm = if (month in 2..7) "${year - 1}-$year-2" else "$year-${year + 1}-1"
                    }
                } catch (_: Exception) { }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = "加载失败: ${e.message}"
        } finally { isLoading = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("一网通办") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } })
        }
    ) { padding ->
        when {
            isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(8.dp)); Text("正在加载...", style = MaterialTheme.typography.bodyMedium) }
            }
            errorMessage != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { errorMessage = null; isLoading = true; scope.launch { try { withContext(Dispatchers.IO) { userInfo = api.getUserInfo() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { errorMessage = "加载失败: ${e.message}" } finally { isLoading = false } } }) { Text("重试") }
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.height(8.dp))
                userInfo?.let { info ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("个人信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            InfoRow(Icons.Default.Person, "姓名", info.userName)
                            Spacer(Modifier.height(12.dp))
                            InfoRow(Icons.Default.Badge, "学号", info.userUid)
                            Spacer(Modifier.height(12.dp))
                            InfoRow(Icons.Default.Person, "身份", info.identityTypeName)
                            Spacer(Modifier.height(12.dp))
                            InfoRow(Icons.Default.Business, "部门", info.organizationName)
                        }
                    }
                }
                if (currentTerm != null) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("学期信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            // 学期名显示：将 "2024-2025-2" 格式化为 "2024-2025 第二学期"
                            val termParts = currentTerm?.split("-")
                            val termDisplay = if (termParts?.size == 3) {
                                "${termParts[0]}-${termParts[1]} 第${if (termParts[2] == "1") "一" else "二"}学期"
                            } else currentTerm ?: ""
                            InfoRow(Icons.Default.CalendarMonth, "当前学期", termDisplay)
                            Spacer(Modifier.height(12.dp))
                            val weekDisplay = if (currentWeek != null) {
                                "第${currentWeek}周"
                            } else {
                                "假期中"
                            }
                            InfoRow(Icons.Default.CalendarMonth, "当前教学周", weekDisplay)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}
