@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.xjtu.toolbox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.xjtu.toolbox.agent.*
import com.xjtu.toolbox.community.*
import com.xjtu.toolbox.emptyroom.*
import com.xjtu.toolbox.score.ReportedGrade
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import java.io.File
import javax.swing.SwingUtilities
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.system.exitProcess

/** 真实组件的离屏验收：过程卡尺寸、教室两种语义、成绩汇总口径与楼中楼展开。 */
fun main(args: Array<String>) {
    val output = File(args.firstOrNull() ?: "build/interface-preview").apply { mkdirs() }
    System.setProperty("xjtu.toolbox.keyValueRoot", File(output, "preferences").absolutePath)
    fun find(node: SemanticsNode, predicate: (SemanticsNode) -> Boolean): SemanticsNode? =
        if (predicate(node)) node else node.children.firstNotNullOfOrNull { find(it, predicate) }
    fun textNode(scene: ImageComposeScene, text: String, clickable: Boolean = false): SemanticsNode =
        requireNotNull(scene.semanticsOwners.firstNotNullOfOrNull { owner -> find(owner.rootSemanticsNode) {
            (!clickable || it.config.getOrNull(SemanticsActions.OnClick) != null) &&
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == text }
        } }) { "Missing control: $text" }
    var time = 0L
    fun settle(scene: ImageComposeScene) = repeat(6) {
        Thread.sleep(40)
        SwingUtilities.invokeAndWait { time += 100_000_000L; scene.render(time) }
    }
    fun save(scene: ImageComposeScene, name: String) = SwingUtilities.invokeAndWait {
        time += 100_000_000L
        File(output, name).writeBytes(requireNotNull(scene.render(time).encodeToData()).bytes)
    }
    fun scene(content: @Composable () -> Unit): ImageComposeScene {
        lateinit var result: ImageComposeScene
        SwingUtilities.invokeAndWait {
            result = ImageComposeScene(412, 915, Density(1f)) {
                XJTUToolBoxTheme(darkModeOverride = "light") {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) { content() }
                }
            }
            result.render(0)
        }
        settle(result)
        return result
    }
    val host = object : AgentHost by AgentHost.None {
        override fun loadConfig() = AgentConfig(apiKey = "offline-fixture", showReasoning = true)
    }
    val sentPrompts = mutableListOf<String>()
    val sentImages = mutableListOf<List<String>>()
    var newSessions = 0
    val chat = object : AgentChatSession {
        override val messages = mutableStateListOf(ChatMessage("user", "帮我查一下", timestamp = 1), ChatMessage("assistant", "", reasoningContent = "想", timestamp = 2))
        override var isLoading by mutableStateOf(true)
        override var errorMessage: String? by mutableStateOf(null)
        override var draft: String? by mutableStateOf(null)
        override val contextExhausted = false
        override val contextExhaustedJustTriggered = false
        override val sessions = mutableStateListOf<AgentSession>()
        override val currentSessionId = "offline"
        override fun consumeContextExhaustedTrigger() = false
        override fun bind(store: AgentSessionPersistence) {}
        override fun newSession() { newSessions++; messages.clear() }
        override fun switchSession(id: String) {}
        override fun deleteSession(id: String) {}
        override fun renameSession(id: String, title: String) {}
        override fun stop() { isLoading = false }
        override fun replaceLastUserAndSend(newText: String, config: AgentConfig) {}
        override fun sendMessage(userText: String, config: AgentConfig, llmAnnex: String?, images: List<String>) {
            sentPrompts += userText
            sentImages += images
            messages += ChatMessage("user", userText)
            isLoading = false
        }
    }
    val trace = scene { AgentScreen(host, session = chat) }
    var reference: androidx.compose.ui.geometry.Rect? = null
    listOf("想", "想一下", "正在分析课程和日期。", "正在分析课程和日期。".repeat(100)).forEach { chunk ->
        SwingUtilities.invokeAndWait { chat.messages[1] = chat.messages[1].copy(reasoningContent = chunk) }
        settle(trace)
        SwingUtilities.invokeAndWait {
            val bounds = textNode(trace, "思考", clickable = true).boundsInRoot
            reference?.let { check(bounds.width == it.width && bounds.height == it.height) { "Trace resized while streaming: $it -> $bounds" } }
            reference = bounds
        }
    }
    save(trace, "reasoning-collapsed.png")
    SwingUtilities.invokeAndWait { textNode(trace, "思考", true).config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke() }
    settle(trace)
    save(trace, "reasoning-expanded.png")
    SwingUtilities.invokeAndWait { trace.close() }

    val articlePrompt = "请阅读这篇文章，概括主要内容和要点：\nhttps://example.com/article"
    SwingUtilities.invokeAndWait { AgentPendingPrompt.set(articlePrompt) }
    val article = scene { AgentScreen(host, session = chat) }
    settle(article)
    check(sentPrompts == listOf(articlePrompt) && newSessions == 1)
    save(article, "article-read-request.png")
    settle(article)
    check(sentPrompts.size == 1)
    SwingUtilities.invokeAndWait { article.close(); AgentPendingPrompt.set("普通深链草稿", draftOnly = true) }
    val draft = scene { AgentScreen(host, session = chat) }
    check(sentPrompts.size == 1 && newSessions == 2)
    SwingUtilities.invokeAndWait { draft.close() }
    SwingUtilities.invokeAndWait { AgentPendingPrompt.set("看看图片", images = listOf("offline-photo-1.jpg", "offline-photo-2.jpg")) }
    val imageShare = scene { AgentScreen(host, session = chat) }
    check(sentPrompts.last() == "看看图片" && sentImages.last() == listOf("offline-photo-1.jpg", "offline-photo-2.jpg") && newSessions == 3)
    SwingUtilities.invokeAndWait { imageShare.close() }

    val widgets = scene {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AgentWidgetView(GradeWidget(listOf(ReportedGrade("高等数学", 5.0, "92", 4.12), ReportedGrade("大学物理", 4.0, "88", 3.88)),
                gpa = 4.013, totalPoints = 9.0, display = WidgetDisplay(summary = "selected")), host)
            AgentWidgetView(RoomWidget("兴庆 · 东1东 · 2026-10-23 · 12:50 实时", listOf(
                RoomInfo("东1东-303", 60, listOf(0, 0, 1, 1, 0, 0, 0, 0, 1, 1, 0)),
                RoomInfo("东1东-305", 90, listOf(1, 1, 0, 0, 0, 0, 1, 1, 0, 0, 0)),
            ), currentPeriod = 4, display = WidgetDisplay(view = "both", sections = listOf(5, 6)), liveRooms = listOf(
                LiveRoom("东1东-303", status = 2), LiveRoom("东1东-305", status = 1, people = 3),
            )), host)
        }
    }
    save(widgets, "selected-cards.png")
    SwingUtilities.invokeAndWait { widgets.close() }

    var opened: com.xjtu.toolbox.nav.AppRoute? = null
    val notices = scene {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AgentWidgetView(NoticeWidget(listOf(
                NoticeWidgetItem("关于2026年秋季体质健康标准测试的通知", "教务处", "2026-10-21", "https://school.example/fitness", "查看本学期测试时间与安排", 3),
                NoticeWidgetItem("2026年大学生新文科实践创新大赛校内赛获奖结果", "教务处", "2026-10-22", "https://school.example/contest", "参赛同学可核对获奖名单", 2),
                NoticeWidgetItem("书院本周讲座与活动安排", "仲英书院", "2026-10-23", "https://school.example/events"),
            ), caption = "先看体测，再核对参赛结果"), host, onNavigate = { opened = it })
            AgentWidgetView(LiveRoomWidget("兴庆校区 · 13:27 实时", listOf(
                LiveRoom("主楼A-402", status = 2, seats = 96),
                LiveRoom("主楼C-205", status = 1, people = 3, seats = 250),
            )), host)
        }
    }
    save(notices, "notice-picks-and-live.png")
    SwingUtilities.invokeAndWait {
        val row = notices.semanticsOwners.firstNotNullOfOrNull { owner -> find(owner.rootSemanticsNode) {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "查看通知原文"
        } }!!
        row.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke()
        check(opened == com.xjtu.toolbox.nav.AppRoute.Browser("https://school.example/fitness"))
    }
    SwingUtilities.invokeAndWait { notices.close() }

    val replies = (1..6).map { GithubDiscussionComment("r$it", "这是第 $it 条回复。", "同学$it", false) }
    val parent = GithubDiscussionComment("parent", "", "同学", false, replyCount = 6, previewReplies = replies.take(2))
    val community = scene {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("楼中楼回复", style = MiuixTheme.textStyles.title3)
            DiscussionReplies(parent, null, 0,
                load = { Result.success(GithubDiscussionComments(replies, null)) }, canReply = false,
                onReact = { _, _, _ -> }, onReply = {}, menu = { emptyList() }, patch = null)
        }
    }
    save(community, "community-replies.png")
    SwingUtilities.invokeAndWait { textNode(community, "展开剩余 4 条回复", true).config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke() }
    settle(community)
    SwingUtilities.invokeAndWait { textNode(community, "同学6") }
    save(community, "community-expanded.png")
    SwingUtilities.invokeAndWait { community.close() }
    println("Rendered interface refinements; streaming trace geometry and reply expansion passed.")
    exitProcess(0)
}
