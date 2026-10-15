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

/**
 * 屁岱（`AppRoute.Agent`）在浏览器上的那一屏（D10 的 S-d；设计真源 `docs/agent-web-spec.md` §4）。
 *
 * ## 「同一屏、两种驱动」在这里的落法
 *
 * 屏还是 `:core` 那一屏（[AgentScreen]），这一端只交三样：
 *
 * 1. **驱动** —— `session = WebAgentSession(…)`（循环在 serve，这个类只做「事件 → 状态」）；
 * 2. **宿主面** —— [WebAgentHost]：配置走 `/api/agent/config`（Key 那一格**只读**，见下）、
 *    其余槽位全是「这一端没有」（`honored` 空集、没有相册、没有落盘、没有工具表、没有传输 ——
 *    与桌面同值，逐条见 [WebAgentHost] 的 KDoc）；
 * 3. **顶栏** —— 共享壳在这端的 `topBar` 是 null（「这一端每一屏自己画头部」，见 `AppShell` 的签名注释），
 *    而 [AgentScreen] 自己**不画**顶栏（它把标题与两个按钮交给宿主）⇒ 这里画一条最简的表头：
 *    会话抽屉按钮 + 标题 + 设置按钮。按钮本体还是屏给的那两个 composable（一个字没重写）。
 *
 * ## 面板两槽位按端上传（§4.3）
 *
 * - `apiKeySection`：这一端**只读徽标** —— 「Key 存在运行 serve 的那台机器上」。不画输入框的理由是
 *   最强口径那一条：D11 之前 serve 默认明文 HTTP，在这里输入等于把 Key 明文传一次；而且「输入也是持有」；
 * - `memorySection`：**模型用的那一份记忆在 serve**（`/api/agent/memory*`）。不收口的话面板显示的记忆
 *   与模型读到的不是一份（§4.3 第 3 条，那一处是必改项）—— 所以那一块整个由本端画。
 *
 * ## 保存之后 `keyConfigured` 不许丢
 *
 * 面板的「即改即存」走的是 `AgentHost.saveConfig`，而它拼出来的那份 [AgentConfig] **不带**
 * `keyConfigured`（那一格是宿主提供的非密文信道）⇒ 保存一次之后页面就会当「没配 Key」显示。
 * 收口方式：写完之后**重新读一次** `/api/agent/config`（[WebAgentHost.reload]），并把这一格
 * 顺着 `AgentScreen` 的保存路径透传回去（见那个屏里 `onSave` 那一行）。
 */
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

    // 还没读到 serve 上的配置之前**不画屏**：那一份默认值（deepseek / 空 key）一碰面板就会被写回 serve，
    // 等于把人家机器上的配置覆盖成默认值。读失败就如实说读不到 + 给一次重试。
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

/**
 * 浏览器这一端的**宿主面**（设计稿 §4.1 的逐槽位表；与 `DesktopAgentHost` 同形）。
 *
 * | 槽位 | 这一端 |
 * |---|---|
 * | `honored` | **空集**（系统闹钟 / 应用设置 / 自建日程写 / 相册 / 存下载，这一端一件都没有 —— 同桌面） |
 * | `loadConfig` / `saveConfig` | HTTP（`GET` / `POST /api/agent/config`）；**Key 那一格只读且不回写** |
 * | `fetchModels` | HTTP（`POST /api/agent/models`，那一枪在 serve 上打） |
 * | `sessions` | `AgentSessionPersistence.None` —— 会话落在 serve，本端不落盘（屏会把这一格交给驱动，
 *   而 [WebAgentSession.bind] 用不上它） |
 * | `tools` / `transport` | `AgentTools.None` / `AgentTransport.None` —— 工具层与模型那一枪都在 serve |
 * | `accountId` / `greetingName` / `welcomeFacts` / `images` / 附件四件 | 默认值（空 / 不接）：学号与姓名是红线，
 *   首屏卡片与相册这一端没有（读不到就不出，与桌面同形） |
 *
 * 唯一**不是**默认值的三格就是 config 那一组，理由在下面每个成员上。
 */
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

    /** 本端收不收 `apiKey`：serve 如实报 `canEditKey`；这一端**不画**输入框、也不发那一格。 */
    var canEditKey by mutableStateOf(true)
        private set

    /** 读 / 写配置失败时那句中文短句（面板上那一格显示它 —— 不吞掉，与「如实」同一条纪律）。 */
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

    /**
     * 存配置（面板的「即改即存」）。两条如实：
     *
     * 1. **不带 `apiKey`**：本端永远拿不到它（红线），而服务端的语义是「给了这一格就按给的写」
     *    ⇒ 带一个空串就等于把 serve 上的 Key 清掉；
     * 2. 写完**重读一次**（[reload]）：`reasoningEffort` 这类会被服务端归一的取值，页面上要跟着变，
     *    而 `keyConfigured` 也靠这一读回来（见 [WebAgentScreen] 的 KDoc）。
     */
    override fun saveConfig(config: AgentConfig) {
        scope.launch {
            session.saveConfig(config)
                .onSuccess { reload() }
                .onFailure { configError = it.message }
        }
    }

    /** 拉模型列表（`POST /api/agent/models`）：失败抛，面板那一格显示 `e.message`（与 Android 同一条路）。 */
    override suspend fun fetchModels(config: AgentConfig): List<String> = session.models(config)

    /** 会话落在 serve：这一格如实报「本端不落盘」（屏把这一格交给驱动，而驱动用不上它）。 */
    override fun sessions(): AgentSessionPersistence = AgentSessionPersistence.None
}

/**
 * API Key 那一行（`ConfigPanel` 的 `apiKeySection`）：**只读徽标 + 一句指路**。
 *
 * 为什么不画输入框（设计稿 §9 未决 #1 的口径，v1 就这么定）：D11 之前 serve 默认明文 HTTP，
 * 在浏览器里输入等于把 Key 明文传一次；而且「输入也是持有」，会破坏「一个字都不拿到」的最强口径。
 * 这一格因此只回答「配好了没」—— 真源是 serve 投影过来的 `keyConfigured`（不是掩码、不是前后缀）。
 */
@Composable
private fun WebAgentKeySection(host: WebAgentHost) {
    val cs = MiuixTheme.colorScheme
    val configured = host.config?.keyConfigured == true
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("API Key", style = MiuixTheme.textStyles.body1)
        Text(
            if (configured) "已在运行 serve 的那台机器上配好" else "还没配：请在运行 serve 的那台机器上设置",
            style = MiuixTheme.textStyles.footnote1,
            color = if (configured) cs.primary else cs.error,
        )
        Text(
            "Key 存在运行 serve 的那台机器上（`agent_config_secure` 那份存储里）：浏览器这一端一个字都拿不到，" +
                "也不保存 —— 所以这里没有输入框。要换 Key 就在那台机器上改。" +
                (if (host.canEditKey) "" else "（这一份部署连那台机器上的 `POST /api/agent/config` 都不收 Key。）"),
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

/**
 * 「记住的偏好」那一块（`ConfigPanel` 的 `memorySection`）：读的是 **serve 上那一份**
 * （`GET /api/agent/memory`），删 / 清空也走 serve（`/api/agent/memory/forget`、`/clear`）。
 *
 * 为什么必须换成这一块（§4.3 第 3 条，**必改项**）：模型读的记忆在 serve，若这里画本端
 * `AgentMemory`（浏览器上就是 localStorage 那一份），面板显示的东西与模型实际记住的东西**不是一份** ——
 * 那既骗用户也骗自己。没有条目时整块不出（与内置那一块同形：不画一个空的「记住的偏好」）。
 */
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
                "存在运行 serve 的那台机器上，与模型用的是同一份。最多 ${AgentMemory.MAX_ITEMS} 条，" +
                    "增删从下一个新对话起生效。",
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

/**
 * 「这一份部署的屁岱能做什么」——**页面说明与白名单同源**。
 *
 * [allowlist] 就是 `GET /api/agent/config` 的 `toolAllowlist`，也**就是** serve 真下发给模型的那批工具名
 *（同一份定义两个消费者，见 `ServeAgent.toolAllowlist` 的 KDoc）⇒ 这一段不可能与「模型真能调什么」漂开。
 * 本文件刻意**不另写一份**「能干什么」的清单：那种第二份真相正是漏的来源（设计稿 §6 末段）。
 *
 * 顺带把三件**如实**写在用户面前（不写就成了「看起来跟 App 一样」）：
 * 不开的那几档为什么不开、App 里加的自建日程这一端看不见、「改上一条重发」在这一端只是再发一次。
 */
internal fun agentToolNote(allowlist: List<String>): String = buildString {
    append("这一份部署的屁岱：模型调用、工具取数、会话与记忆、API Key 都在**运行 serve 的那台机器**上，")
    append("浏览器这一端只发消息、只画屏（所以另一台设备改了会话，这一页刷新后看到的是同一份）。")
    if (allowlist.isEmpty()) {
        append("这一份部署**现在一件工具都没给模型**（toolAllowlist 是空的）：能聊天，但查不到校园数据。")
    } else {
        append("它能查：")
        append(allowlist.joinToString("、") { agentToolLine(it) })
        append("。")
    }
    append("不开放的几档，逐条理由：")
    append(
        AGENT_CLOSED_TOOL_GROUPS.joinToString("；") { group ->
            "${group.title} —— ${group.names.joinToString(" / ")}（${group.why}）"
        },
    )
    append("。另外两处如实：① 你在 App 里加的自建日程，浏览器这一端的屁岱**看不到**（它读的是桌面/")
    append("serve 那份 `custom_courses`，Android 的存在 Room 里）；② 「改上一条重发」这一端只能做到")
    append("「屏幕上换掉 + 再发一次」—— serve 契约里没有「替换上一轮」的入口。")
}

/**
 * 一行工具：名字 + `:core` 那份文案表里的话（`agentToolLabel`）。
 *
 * 认不出的名字**照原样报认不出**，不编一句功能描述 —— 契约将来加了工具而这一端还没跟上时，
 * 页面说的是「我没有它的说明」，而不是一句假话。
 */
internal fun agentToolLine(name: String): String {
    val label = agentToolLabel(name)
    return if (label == "调用工具 $name…") "`$name`（这一端没有它的说明）" else "`$name`（${label.trimEnd('…')}）"
}

/** 「为什么不开」的一组工具（设计稿 §6.2 的逐条理由，这里只留一句话级）。 */
internal class AgentClosedToolGroup(val title: String, val why: String, val names: List<String>)

/**
 * 白名单**之外**的那几档，按「为什么不开」分组（`docs/agent-web-spec.md` §6.2）。
 *
 * 名字必须是 `:core` 那份目录里真有的工具名 —— 否则这一段就成了编的名字（`WebAgentNoteTest` 钉这一条）。
 */
internal val AGENT_CLOSED_TOOL_GROUPS: List<AgentClosedToolGroup> = listOf(
    AgentClosedToolGroup(
        title = "写路径",
        why = "写的是你的闹钟 / 应用设置 / 自建日程，浏览器这一端没有那几样落点",
        names = listOf("set_alarm", "app_setting", "add_schedule_event"),
    ),
    AgentClosedToolGroup(
        title = "钱",
        why = "余额 / 流水 / 券都是钱的读数，不该由浏览器上的模型代读代领",
        names = listOf("get_card_info", "get_coupons"),
    ),
    AgentClosedToolGroup(
        title = "本人敏感",
        why = "成绩 / 体测 / 考勤（考勤记录里带上课教师名）",
        names = listOf("get_grades", "get_fitness_score", "get_attendance"),
    ),
    AgentClosedToolGroup(
        title = "要站点会话或没有落点",
        why = "图书馆 / 学辅 / 教材 / 思源学堂：前者要站点会话，后者的「读全文」「存进下载」在这一端没有落点",
        names = listOf("get_library", "list_zyxf", "read_zyxf_file", "get_textbooks", "get_lms", "get_lms_activity"),
    ),
    AgentClosedToolGroup(
        title = "联网搜索",
        why = "联网的是 serve 那台机器（不是你的网络），搜索与抓取的边界还没收口",
        names = listOf("web_search", "web_fetch"),
    ),
    AgentClosedToolGroup(
        title = "独立一大块",
        why = "全校课表检索要上百行拼装 + 十三个参数；通知要 29 个源（其中 23 源有动态挑战）",
        names = listOf("search_school_courses", "get_notifications"),
    ),
)
