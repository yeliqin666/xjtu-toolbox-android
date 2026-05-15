import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class AttendRecord(val course: String, val date: String, val status: String, val statusColor: Color)

private val mockRecords = listOf(
    AttendRecord("高等数学", "2025-01-08  08:00", "正常", Color(0xFF4CAF8A)),
    AttendRecord("大学英语", "2025-01-08  10:00", "正常", Color(0xFF4CAF8A)),
    AttendRecord("物理实验", "2025-01-07  14:00", "迟到", Color(0xFFFFB74D)),
    AttendRecord("线性代数", "2025-01-07  08:00", "正常", Color(0xFF4CAF8A)),
    AttendRecord("程序设计", "2025-01-06  08:00", "缺勤", Color(0xFFEF5350)),
    AttendRecord("大学物理", "2025-01-06  10:00", "正常", Color(0xFF4CAF8A)),
    AttendRecord("体育课", "2025-01-05  14:00", "正常", Color(0xFF4CAF8A)),
    AttendRecord("思想政治", "2025-01-05  08:00", "正常", Color(0xFF4CAF8A)),
)

@Composable
fun AttendanceDemo() {
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "考勤",
                largeTitle = "考勤记录",
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // 统计卡片
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatItem("出勤率", "93.2%", Color(0xFF4CAF8A))
                        StatItem("总课次", "54", MiuixTheme.colorScheme.onBackground)
                        StatItem("迟到", "2", Color(0xFFFFB74D))
                        StatItem("缺勤", "1", Color(0xFFEF5350))
                    }
                }
            }
            item {
                Text(
                    "最近记录",
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            items(mockRecords.size) { i ->
                val record = mockRecords[i]
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(record.course, style = MiuixTheme.textStyles.headline1)
                            Text(record.date, style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = record.statusColor.copy(alpha = 0.15f),
                        ) {
                            Text(
                                record.status,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                color = record.statusColor,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions)
    }
}

