import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class Score(val course: String, val credit: Double, val score: Int, val gpa: Double)

private val mockScores = listOf(
    Score("高等数学（上）", 5.0, 92, 4.5),
    Score("大学英语（一）", 4.0, 88, 4.0),
    Score("思想道德与法治", 3.0, 95, 5.0),
    Score("大学物理（上）", 4.0, 85, 3.7),
    Score("程序设计基础", 3.5, 78, 3.0),
    Score("线性代数", 3.0, 91, 4.5),
)

private val scoreInfoList = mockScores.map { s ->
    s.credit.toString() + " 学分 · GPA " + s.gpa.toString()
}

@Composable
fun ScoreDemo() {
    val scrollBehavior = MiuixScrollBehavior()
    var selectedTerm by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "成绩",
                largeTitle = "成绩查询",
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
                // GPA 卡片
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        GpaItem("综合 GPA", "3.92", Color(0xFF5B8DEF))
                        GpaItem("学期 GPA", "4.10", Color(0xFF4CAF8A))
                        GpaItem("已修学分", "22.5", MiuixTheme.colorScheme.onBackground)
                    }
                }
            }
            item {
                TabRow(
                    tabs = listOf("大一上", "大一下", "大二上"),
                    selectedTabIndex = selectedTerm,
                    onTabSelected = { selectedTerm = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(mockScores.size) { i ->
                val s = mockScores[i]
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                            Text(s.course, style = MiuixTheme.textStyles.headline1)
                            Text(scoreInfoList[i],
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                        }
                        Text(
                            s.score.toString(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                s.score >= 90 -> Color(0xFF4CAF8A)
                                s.score >= 80 -> Color(0xFF5B8DEF)
                                s.score >= 70 -> Color(0xFFFFB74D)
                                else -> Color(0xFFEF5350)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GpaItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions)
    }
}

