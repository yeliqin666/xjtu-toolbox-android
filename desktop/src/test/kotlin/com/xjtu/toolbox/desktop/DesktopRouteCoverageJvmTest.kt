package com.xjtu.toolbox.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「`AppRoute` 的每一条声明在 `DesktopPage` 的顶层 `when` 里都有分支」这件事的机器可验版本。
 *
 * ## 为什么要有它（这一段是怎么来的）
 *
 * Stage C 收口时，「43/43 顶层分支 + 兜底的 `else -> NotPortedScreen` 不可命中」是**人工**把两边各自
 * 提取再求差得出的（`docs/desktop-port-plan.md` §12.3 / §14.5）。人工那一份会腐坏：删掉一条分支、
 * 或新加一条 `AppRoute` 声明而不加分支，**42 张证据图与 85 例测试里一张都不会红** —— 那种回归只有
 * 真进那一屏才会被发现，而且看起来像「屏坏了」而不是「这条路由没搬」。
 *
 * 所以这里改成断言。判据取源码（不靠反射：`AppRoute` 是 sealed interface 加一堆 `data object` /
 * `data class`，没有一份「全部声明」的清单可枚举）：
 *
 * - 声明侧：`core/.../nav/AppRoute.kt` 里的 `@Serializable data object|class Xxx`；
 * - 分支侧：`DesktopPage(...)` 里那个 `when (route) {` 的**顶层**分支（缩进比 `when` 深一级的那些行；
 *   嵌在分支体里的 `when`，例如 `AppRoute.Notification` 里那个 `when (r) { is AppRoute.Browser -> … }`，
 *   不算顶层分支）。
 *
 * ⚠️ 口径与 §14.5 一致：这是**分支覆盖**，不是「43 条都是真实现」。`GameMerge` 那一支仍是如实说明页
 *（`HostUnavailableScreen`：卡的是 WebView 这个载体），它也**不在** `DESKTOP_SUPPORTED_ROUTES` 里。
 *
 * ## 每条失败信息里都有那句话
 *
 * 文案统一带 `criterion`（「这条判据是 docs/desktop-port-plan.md §12.3/§14.5 的验收项」）并说清
 * **哪一边**变了（少了分支 / 多了声明 / 兜底又能命中了），好让下一个人一眼知道该改哪儿。
 */
class DesktopRouteCoverageJvmTest {

    /** 见文件头：每条断言都带上它。 */
    private val criterion = "这条判据是 docs/desktop-port-plan.md §12.3/§14.5 的验收项" +
        "（43 条 AppRoute 声明 ↔ 43 条 DesktopPage 顶层分支，兜底的 NotPortedScreen 不可命中）"

    // ── 读源码 ──

    /**
     * 仓库根目录：从工作目录往上找 `settings.gradle.kts`。
     * Gradle 的 Test 任务以**模块目录**（`desktop/`）为工作目录，所以这里要往上走一层。
     */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录（settings.gradle.kts）：工作目录是 ${File(".").absolutePath}")
    }

    private val routeSource: String by lazy {
        File(repoRoot(), "core/src/commonMain/kotlin/com/xjtu/toolbox/nav/AppRoute.kt").readText()
    }

    private val desktopAppSource: String by lazy {
        File(repoRoot(), "desktop/src/main/kotlin/com/xjtu/toolbox/desktop/ToolboxDesktopApp.kt").readText()
    }

    /** `AppRoute` 的一级声明（`@Serializable data object|class Xxx`），按源码顺序。 */
    private fun declaredRoutes(): List<String> =
        Regex("""@Serializable\s+data\s+(?:object|class)\s+(\w+)""")
            .findAll(routeSource)
            .map { it.groupValues[1] }
            .toList()

    /** `DesktopPage(...)` 里 `when (route) {` 的顶层分支，按源码顺序（只认分支名，不认 `else`）。 */
    private fun desktopPageBranches(): List<String> {
        val body = desktopAppSource.substring(desktopAppSource.indexOf("private fun DesktopPage("))
        val lines = body.lines()
        val whenLine = lines.indexOfFirst { it.trimEnd().endsWith("when (route) {") }
        require(whenLine >= 0) { "DesktopPage 里找不到 `when (route) {` —— 这段解析要跟着改：$criterion" }
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

    /** 兜底那一支的原文（`else -> NotPortedScreen(route, onNavigate)`）。 */
    private fun hasNotPortedElse(): Boolean =
        Regex("""^\s*else\s*->\s*NotPortedScreen\(""", RegexOption.MULTILINE).containsMatchIn(desktopAppSource)

    // ── 断言 ──

    @Test
    fun `43 条声明在 DesktopPage 的顶层 when 里各有分支`() {
        val declared = declaredRoutes()
        val branches = desktopPageBranches()

        assertEquals(43, declared.size, "AppRoute 的声明数不是 43 了（现在 ${declared.size}）：$criterion")
        assertEquals(
            43,
            branches.size,
            "DesktopPage 的顶层分支数不是 43 了（现在 ${branches.size}）：$criterion；" +
                "分支是这些：$branches",
        )
        assertEquals(branches.size, branches.toSet().size, "有重复的顶层分支：$branches —— $criterion")

        val missing = declared.filterNot { it in branches.toSet() }
        assertTrue(
            missing.isEmpty(),
            "这些 AppRoute 声明没有顶层分支（会落回 NotPortedScreen）：$missing —— $criterion；" +
                "加一条声明就要加一条分支（`when (route)` 里与其余分支同级那一行）",
        )
        val unknown = branches.filterNot { it in declared.toSet() }
        assertTrue(
            unknown.isEmpty(),
            "这些顶层分支不是 AppRoute 的声明（改过名字？还是声明被误删了？）：$unknown —— $criterion",
        )
    }

    @Test
    fun `兜底的 NotPortedScreen 不可命中`() {
        // 两个方向都为空（上面那条已经断言过），所以 `else` 这一支今天一条都接不到 ——
        // 留着它是因为它是「加一条路由忘了加分支」的最后一道可见退路（比白屏好）。
        assertTrue(
            hasNotPortedElse(),
            "DesktopPage 的 when 里没有 `else -> NotPortedScreen(...)` 了：它是「新路由忘了加分支」" +
                "那一条的最后退路，删掉它会让漏掉的分支变成编译错误（或者更糟：白屏）—— $criterion",
        )
        val declared = declaredRoutes()
        val branches = desktopPageBranches().toSet()
        val uncovered = declared.filterNot { it in branches }
        assertTrue(
            uncovered.isEmpty(),
            "还有声明没有自己的分支（⇒ else 仍可命中）：$uncovered —— $criterion",
        )
    }

    @Test
    fun `索引页上列的每一条都有真分支（DESKTOP_SUPPORTED_ROUTES 不许挂空）`() {
        val branches = desktopPageBranches().toSet()
        DESKTOP_SUPPORTED_ROUTES.forEach { (route, label) ->
            val name = route::class.simpleName
            assertTrue(
                name != null && name in branches,
                "索引页里的「$label」（${route.id}）在 DesktopPage 的分支里没有 $name —— " +
                    "「真能用」那一栏只放得下真能画的：$criterion",
            )
        }
    }
}
