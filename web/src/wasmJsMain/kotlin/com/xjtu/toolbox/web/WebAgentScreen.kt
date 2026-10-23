package com.xjtu.toolbox.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjtu.toolbox.agent.AgentConfig
import com.xjtu.toolbox.agent.AgentHost
import com.xjtu.toolbox.agent.AgentMemory
import com.xjtu.toolbox.agent.AgentScreen
import com.xjtu.toolbox.agent.AgentSessionPersistence
import com.xjtu.toolbox.agent.agentToolLabel
import com.xjtu.toolbox.shell.ShellPageInsets
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 屁岱（`AppRoute.Agent`）在浏览器上的那一屏（设计真源 `docs/agent-web-spec.md` §4）。 */
@Composable
internal fun WebAgentScreen(
    client: HttpClient,
    insets: ShellPageInsets,
    onNavigate: (WebTarget) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val session = remember(client) { WebAgentSession(client, scope) }
    val host = remember(session) { WebAgentHost(session, scope) }
    var reloadNonce by remember { mutableStateOf(0) }
    LaunchedEffect(session, reloadNonce) { host.reload() }

    // 读到 serve 上的配置之前不画屏：否则默认值会被写回 serve 覆盖掉真实配置。
    if (host.config == null) {
        WebUnavailableScreen(
            title = "屁岱（Web 这一端）",
            why = host.configError
                ?: "正在读取运行 serve 的那台机器上的屁岱配置（GET /api/agent/config）…",
            missing = listOf(
                "这一屏的模型调用 / 工具取数 / 会话与记忆 / API Key 全在 serve 那一侧",
                "配置没读回来之前不画屏：拿默认值画出来的表单一保存就会把 serve 上的配置覆盖掉",
            ),
        ) {
            TextButton(
                text = "重新读一次",
                onClick = { reloadNonce++ },
                minWidth = 128.dp,
            )
        }
        return
    }

    // 顶栏那三样由屏反向送上来（见文件头第 3 条）；标题 / 按钮的本体都是屏给的 composable。
    var title by remember { mutableStateOf("屁岱") }
    var navIcon by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var actions by remember { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navIcon?.invoke()
            Text(
                title,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            actions?.invoke(this)
        }
        // 「这一份部署能做什么」——**按 toolAllowlist 现生成**（白名单与页面说明同源，见 agentToolNote）
        WebGapNote(agentToolNote(host.toolAllowlist))
        Box(Modifier.weight(1f)) {
            AgentScreen(
                host = host,
                session = session,
                extraBottomPadding = insets.bottomBarReserve,
                // 跳转建议给的是 `AppRoute.id`（`:core` 的同一份表算出来的）；本端按路由转回 shell。
                onNavigate = { route -> onNavigate(WebTarget.App(route)) },
                onTitleChange = { title = it },
                onActionsChange = { actions = it },
                onNavIconChange = { navIcon = it },
                apiKeySection = { WebAgentKeySection(host) },
                memorySection = { WebAgentMemorySection(session) },
            )
        }
    }
}

/** 浏览器这一端的宿主面（设计稿 §4.1 的逐槽位表；与 `DesktopAgentHost` 同形）。 */
internal class WebAgentHost(
    private val session: WebAgentSession,
    private val scope: CoroutineScope,
) : AgentHost {

    /** `GET /api/agent/config` 读到的那一份（`null` = 还没读到 ⇒ 屏还不画，见 [WebAgentScreen]）。 */
    var config by mutableStateOf<AgentConfig?>(null)
        private set

    /** 这一份部署**真给模型**的工具名（页面说明那一段的真源，与 serve 的工具表同一份定义）。 */
    var toolAllowlist by mutableStateOf<List<String>>(emptyList())
        private set

    /** 本端收不收 `apiKey`：serve 报 `canEditKey`；这一端不画输入框、也不发那一格。 */
    var canEditKey by mutableStateOf(true)
        private set

    /** 读 / 写配置失败时那句中文短句。 */
    var configError by mutableStateOf<String?>(null)
        private set

    /** 读（或重读）serve 上的配置：屏进来时一次、写完配置再一次（保存后不丢 `keyConfigured`）。 */
    suspend fun reload() {
        session.configView()
            .onSuccess { view ->
                config = view.config
                toolAllowlist = view.toolAllowlist
                canEditKey = view.canEditKey
                configError = null
            }
            .onFailure { configError = it.message }
    }

    override fun loadConfig(): AgentConfig = config ?: AgentConfig()

    /** 存配置（面板的「即改即存」）。 */
    override fun saveConfig(config: AgentConfig) {
        scope.launch {
            session.saveConfig(config)
                .onSuccess { reload() }
                .onFailure { configError = it.message }
        }
    }

    /** 拉模型列表（`POST /api/agent/models`）：失败抛，面板那一格显示 `e.message`（与 Android 同一条路）。 */
    override suspend fun fetchModels(config: AgentConfig): List<String> = session.models(config)

    /** 会话落在 serve：本端不落盘。 */
    override fun sessions(): AgentSessionPersistence = AgentSessionPersistence.None

    /**
     * 「先填一下模型」那张卡的副标题：**Key 不在这一端**——它存在运行 serve 的那台机器上。
     * 「配好就能聊」在浏览器里会把话说到一半（这一边没有输入框，用户也无从判断该去哪填）⇒ 说全。
     */
    override val configHint: String
        get() = "API Key 配在运行 serve 的那台机器上，那边配好这边就能聊"

    /**
     * 「改上一条」：这一端改得动**页面上**那一轮（输入框回填 + 重发是一个真的动作），但 serve 那一侧
     * 没有「替换上一轮」的入口（会话在服务端、契约里只有追加）⇒ 下一轮请求里上一轮仍然在，别让那三个字
     * 暗示它被换掉了。
     */
    override val editLastLabel: String
        get() = "改上一条（本端是重发：serve 那边上一轮还在）"
}

/** API Key 那一行（`ConfigPanel` 的 `apiKeySection`）：只读徽标 + 一句指路。 */
@Composable
private fun WebAgentKeySection(host: WebAgentHost) {
    val cs = MiuixTheme.colorScheme
    val configured = host.config?.keyConfigured == true
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("API Key", style = MiuixTheme.textStyles.body1)
        Text(
            if (configured) "已配置" else "未配置，请在服务端设置",
            style = MiuixTheme.textStyles.footnote1,
            color = if (configured) cs.primary else cs.error,
        )
        Text(
            "API Key 保存在服务端，浏览器不保存。请在服务端修改。",
            style = MiuixTheme.textStyles.footnote2,
            color = cs.onSurfaceVariantSummary,
        )
        host.configError?.let {
            Text(
                "上一次配置没存上：$it",
                style = MiuixTheme.textStyles.footnote1,
                color = cs.error,
            )
        }
    }
}

/** 「记住的偏好」那一块（`ConfigPanel` 的 `memorySection`） */
@Composable
private fun WebAgentMemorySection(session: WebAgentSession) {
    val cs = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) {
        session.memory()
            .onSuccess { items = it }
            .onFailure { note = it.message }
    }
    val memories = items ?: return
    if (memories.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("记住的偏好", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Text(
                "存在 serve 主机上，最多 ${AgentMemory.MAX_ITEMS} 条，新对话起生效。",
                style = MiuixTheme.textStyles.footnote1,
                color = cs.onSurfaceVariantSummary,
            )
            memories.forEach { (key, value) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(key, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
                        Text(value, style = MiuixTheme.textStyles.footnote1, color = cs.onSurfaceVariantSummary)
                    }
                    TextButton(
                        text = "删除",
                        onClick = {
                            scope.launch {
                                session.forgetMemory(key)
                                    .onSuccess { items = memories.filterNot { it.first == key } }
                                    .onFailure { note = it.message }
                            }
                        },
                    )
                }
            }
            TextButton(
                text = "全部清空",
                onClick = {
                    scope.launch {
                        session.clearMemory()
                            .onSuccess { items = emptyList() }
                            .onFailure { note = it.message }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            note?.let {
                Text(it, style = MiuixTheme.textStyles.footnote1, color = cs.error)
            }
        }
    }
}

// 记忆条数上限用 `:core` 的 `AgentMemory.MAX_ITEMS`（serve 侧落盘的那一份说了算）—— 本文件不另写一个数

/** 「这一份部署的屁岱能做什么」——页面说明与白名单同源。 */
internal fun agentToolNote(allowlist: List<String>): String = buildString {
    append("对话、记忆和 API Key 保存在服务端。")
    if (allowlist.isEmpty()) append("当前仅支持聊天，校园查询暂未开放。")
    else append("可用功能：").append(allowlist.joinToString("、") { agentToolLine(it) }).append("。")
    append("暂不支持：").append(AGENT_CLOSED_TOOL_GROUPS.joinToString("、") { it.title }).append("。")
    append("App 的自建日程不会同步到这里；修改已发送的消息会再发一次。")
}

/** 一行工具：名字 + `:core` 那份文案表里的话（`agentToolLabel`）。 */
internal fun agentToolLine(name: String): String {
    val label = agentToolLabel(name)
    return if (label == "调用工具 $name…") "未知工具（$name）" else label.trimEnd('…')
}

/** 「为什么不开」的一组工具（设计稿 §6.2 的逐条理由，这里只留一句话级）。 */
internal class AgentClosedToolGroup(val title: String, val why: String, val names: List<String>)

/** 白名单之外的那几档，按「为什么不开」分组（`docs/agent-web-spec.md` §6.2）。 */
internal val AGENT_CLOSED_TOOL_GROUPS: List<AgentClosedToolGroup> = listOf(
    AgentClosedToolGroup(
        title = "闹钟和日程写入",
        why = "写的是你的闹钟 / 应用设置 / 自建日程，浏览器这一端没有那几样落点",
        names = listOf("set_alarm", "app_setting", "add_schedule_event"),
    ),
    AgentClosedToolGroup(
        title = "校园卡与加餐券",
        why = "余额 / 流水 / 券都是钱的读数，不该由浏览器上的模型代读代领",
        names = listOf("get_card_info", "get_coupons"),
    ),
    AgentClosedToolGroup(
        title = "成绩、体测和考勤",
        why = "成绩 / 体测 / 考勤（考勤记录里带上课教师名）",
        names = listOf("get_grades", "get_fitness_score", "get_attendance"),
    ),
    AgentClosedToolGroup(
        title = "图书馆和课程资料",
        why = "图书馆 / 学辅 / 教材 / 思源学堂：前者要站点会话，后者的「读全文」「存进下载」在这一端没有落点",
        names = listOf("get_library", "list_zyxf", "read_zyxf_file", "get_textbooks", "get_lms", "get_lms_activity"),
    ),
    AgentClosedToolGroup(
        title = "联网搜索",
        why = "联网的是 serve 那台机器（不是你的网络），搜索与抓取的边界还没收口",
        names = listOf("web_search", "web_fetch"),
    ),
    AgentClosedToolGroup(
        title = "全校课程与通知",
        why = "全校课表检索要上百行拼装 + 十三个参数；通知要 29 个源（其中 23 源有动态挑战）",
        names = listOf("search_school_courses", "get_notifications"),
    ),
)
