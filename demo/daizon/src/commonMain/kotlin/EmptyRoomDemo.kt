import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class EmptyRoom(val room: String, val building: String, val capacity: Int)

private val mockRooms = listOf(
    EmptyRoom("东二302", "东二教学楼", 108),
    EmptyRoom("东二401", "东二教学楼", 72),
    EmptyRoom("理学院201", "理学院", 120),
    EmptyRoom("西一203", "西一教学楼", 60),
    EmptyRoom("文化大厦301", "文化大厦", 90),
    EmptyRoom("逸夫馆102", "逸夫科学馆", 50),
)

private val roomInfoList = mockRooms.map { r ->
    r.building + " · 容纳 " + r.capacity.toString() + " 人"
}

@Composable
fun EmptyRoomDemo() {
    var selectedIdx by remember { mutableIntStateOf(0) }
    val buildings = listOf("全部", "东二楼", "理学院", "西一楼", "文化大厦")

    Scaffold(
        topBar = { SmallTopAppBar(title = "空教室") }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            TabRow(
                tabs = buildings,
                selectedTabIndex = selectedIdx,
                onTabSelected = { selectedIdx = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "当前时段可用 " + mockRooms.size.toString() + " 间",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(mockRooms.size) { i ->
                    RoomItem(name = mockRooms[i].room, info = roomInfoList[i])
                }
            }
        }
    }
}

@Composable
private fun RoomItem(name: String, info: String) {
    Card {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MiuixTheme.textStyles.headline1)
                Text(info, style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions)
            }
            Text(
                "空闲",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF4CAF8A),
            )
        }
    }
}
