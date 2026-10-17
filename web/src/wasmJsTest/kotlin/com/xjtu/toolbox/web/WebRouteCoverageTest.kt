package com.xjtu.toolbox.web

import com.xjtu.toolbox.web.testsources.WebSourceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 「`WebSupport.openableRouteIds` = `AppPage` 的分支」这件事的机器可验版本。 */
class WebRouteCoverageTest {

    /** 见文件头：每条失败信息都带上它。 */
    private val criterion = "这条判据是 docs/desktop-port-plan.md §16.2 第 1 条" +
        "（路由全等：openableRouteIds 与 AppRoute 的差集只允许剩「这一端永远做不到」的条目，且每条都有如实说明）"

    // ── 读源码（构建期生成的快照，见文件头） ──

    /** `AppRoute` 的一条一级声明：类名 + 它 `id` getter 里出现的字符串字面量。 */
    private data class DeclaredRoute(val name: String, val idLiterals: List<String>)

    private val declaredRoutes: List<DeclaredRoute> by lazy {
        val source = WebSourceSnapshot.APP_ROUTE
        val starts = Regex("""@Serializable\s+data\s+(?:object|class)\s+(\w+)""").findAll(source).toList()
        starts.mapIndexed { index, match ->
            val name = match.groupValues[1]
            val block = source.substring(match.range.first, starts.getOrNull(index + 1)?.range?.first ?: source.length)
            val idLine = block.lineSequence().firstOrNull { it.contains("override val id") }
                ?: error("AppRoute.$name 里找不到 `override val id` —— 这段解析要跟着改：$criterion")
            DeclaredRoute(name, Regex("\"([^\"]*)\"").findAll(idLine).map { it.groupValues[1] }.toList())
        }
    }

    /** 运行时那份清单（`internal`，与 `:web` 的 main 是同一模块）。 */
    private val openable: Set<String> = WebSupport.openableRouteIds

    /** 清单里那些路由的**声明名**：`id` getter 的任一字面量落在清单里就算它在。 */
    private val openableNames: List<String> by lazy {
        declaredRoutes.filter { route -> route.idLiterals.any { it in openable } }.map { it.name }
    }

    /** 差集（声明有、清单没有），按 `AppRoute.kt` 的源码顺序。 */
    private val notOpenableNames: List<String> by lazy {
        declaredRoutes.map { it.name }.filterNot { it in openableNames.toSet() }
    }

    /** `AppPage(...)` 里 `when (route) {` 的顶层分支，按源码顺序（只认分支名，不认 `else`）。 */
    private fun appPageBranches(): List<String> {
        val source = WebSourceSnapshot.TOOLBOX_WEB_APP
        val body = source.substring(source.indexOf("private fun AppPage("))
        val lines = body.lines()
        val whenLine = lines.indexOfFirst { it.trimEnd().endsWith("when (route) {") }
        require(whenLine >= 0) { "AppPage 里找不到 `when (route) {` —— 这段解析要跟着改：$criterion" }
        val whenIndent = lines[whenLine].takeWhile { it == ' ' }.length
        val branchIndent = whenIndent + 4
        val branchPrefix = " ".repeat(branchIndent)
        val branches = mutableListOf<String>()
        for (line in lines.drop(whenLine + 1)) {
            if (line.isBlank()) continue
            val indent = line.takeWhile { it == ' ' }.length
            if (indent <= whenIndent) break // when 块结束
            if (indent != branchIndent) continue // 分支体内部（含嵌在里面的 when）
            Regex("""^(?:is\s+)?AppRoute\.(\w+)""")
                .find(line.removePrefix(branchPrefix))
                ?.let { branches += it.groupValues[1] }
        }
        return branches
    }

    // ── 断言 ──

    @Test
    fun `路由差集只剩那 4 条，且每条都在 WebSupport 的文件头里有如实说明`() {
        assertEquals(
            43,
            declaredRoutes.size,
            "AppRoute 的声明数不是 43 了（现在 ${declaredRoutes.size}）：$criterion；" +
                "加/删一条声明要同时看这里与 desktop 那份 DesktopRouteCoverageJvmTest",
        )
        assertEquals(
            listOf("Browser", "DownloadManager", "Jiaocai1", "Jiaocai1Reader"),
            notOpenableNames.sorted(),
            "差集变了（现在 ${notOpenableNames}）：$criterion；" +
                "新补上一屏就把它从差集里拿掉（同时改 AppPage 的分支与 WebSupport 的文件头说明），" +
                "差集里多出新的一条就要在文件头里写清「为什么这一端做不到」",
        )

        val explained = WebSourceSnapshot.WEB_SUPPORT
            .substringAfter("## 什么不在清单里", missingDelimiterValue = "")
            .substringBefore("internal object WebSupport")
        assertTrue(
            explained.isNotBlank(),
            "WebSupport 的文件头里没有「什么不在清单里」那一段（差集条目就没有出处了）：$criterion",
        )
        notOpenableNames.forEach { name ->
            assertTrue(
                name in explained,
                "差集里的 $name 在 WebSupport 文件头那一段里没有说明：$criterion；" +
                    "清单拒收一条路由就要写一句为什么（照 Agent / Browser / DownloadManager 那几行的写法）",
            )
        }
    }

    @Test
    fun `openableRouteIds 的每一条在 AppPage 的顶层 when 里都真有分支（清单不许挂空）`() {
        assertEquals(
            declaredRoutes.size - notOpenableNames.size,
            openableNames.size,
            "清单里的路由在 AppRoute 的声明里配不上对：$criterion（解析要跟着改？）",
        )
        val branches = appPageBranches().toSet()
        assertTrue(branches.isNotEmpty(), "AppPage 里一个顶层分支都没解析出来：$criterion（解析要跟着改？）")

        val missing = openableNames.filterNot { it in branches }
        assertTrue(
            missing.isEmpty(),
            "这些路由在清单里（首页宫格与学辅目录会画出门），而 AppPage 的 when 里没有它们的顶层分支 ——" +
                "点进去只能落 NotPortedScreen（「门在、屏不在」正是最难看的那种漏）：$missing —— $criterion；" +
                "要么补分支，要么把它们从 openableRouteIds 里拿掉并在文件头里写清为什么",
        )
    }

    @Test
    fun `AppPage 多出来的分支只有 Browser 一条（清单与分支必须同时改）`() {
        val extra = appPageBranches().filterNot { it in openableNames.toSet() }.sorted()
        assertEquals(
            listOf("Browser"),
            extra,
            "AppPage 里多出了清单之外的分支（现在 $extra）：$criterion；" +
                "Browser 不是一屏（`navigateSameTab`，在 WebSupport 文件头里写着）——" +
                "除此之外的分支必须同时进 openableRouteIds，否则那一屏在首页宫格与学辅目录上都没有门",
        )

        // 兜底那一支还在（清单说有、这里却没有分支时的报错页）。
        assertTrue(
            Regex("""^\s*else\s*->""", RegexOption.MULTILINE).containsMatchIn(WebSourceSnapshot.TOOLBOX_WEB_APP),
            "AppPage 的 when 里没有 `else ->` 兜底了：$criterion；删掉它会让漏掉的分支静默落一张" +
                "\"还没搬到 Web\"（而清单上明明有它）",
        )
    }
}
