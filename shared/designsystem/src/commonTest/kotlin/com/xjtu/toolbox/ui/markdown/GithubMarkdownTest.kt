package com.xjtu.toolbox.ui.markdown

import com.xjtu.toolbox.community.GithubRefKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** [GithubMarkdown] 渲染前那几步文本处理的断言。 */
class GithubMarkdownTest {

    @Test
    fun `@用户名 变成指向 GitHub 主页的链接`() {
        assertEquals("[@alice](https://github.com/alice) 你好", linkMentions("@alice 你好"))
        assertEquals("看 [@a-b](https://github.com/a-b) 的帖", linkMentions("看 @a-b 的帖"))
        assertEquals("（[@alice](https://github.com/alice)）", linkMentions("（@alice）"))
    }

    @Test
    fun `代码块与行内代码里的 @ 不动，邮箱也不动`() {
        val fenced = "```\n@alice\n```"
        assertEquals(fenced, linkMentions(fenced))
        assertEquals("`@alice`", linkMentions("`@alice`"))
        assertEquals("me@example.com", linkMentions("me@example.com"))
        assertEquals("没有 @ 就原样", linkMentions("没有 @ 就原样"))
    }

    @Test
    fun `GitHub 自动短链：仓库引用、issue 与讨论链接、标签`() {
        val repo = "https://github.com/yeliqin666/xjtu-toolbox-android"
        assertEquals("[Hz6826/CampusNetLogin#1](https://github.com/Hz6826/CampusNetLogin/issues/1)", linkGithubRefs("Hz6826/CampusNetLogin#1"))
        assertEquals("看 [#451]($repo/issues/451)。", linkGithubRefs("看 $repo/issues/451。"))
        assertEquals(
            "[#383 (comment)]($repo/discussions/383#discussioncomment-19713207)",
            linkGithubRefs("$repo/discussions/383#discussioncomment-19713207"),
        )
        assertEquals("[a/b#2](https://github.com/a/b/pull/2)", linkGithubRefs("https://github.com/a/b/pull/2"))
        assertEquals("[✨ Type:Feature]($repo/labels/%e2%9c%a8%20Type%3aFeature)", linkGithubRefs("$repo/labels/%e2%9c%a8%20Type%3aFeature"))
        // 没编码完整的标签链接、别的锚点、已经写成 Markdown 链接的都不动
        assertEquals("$repo/labels/✨", linkGithubRefs("$repo/labels/✨"))
        assertEquals("$repo/issues/1#event-2", linkGithubRefs("$repo/issues/1#event-2"))
        assertEquals("[x]($repo/issues/1)", linkGithubRefs("[x]($repo/issues/1)"))
        assertEquals("`a/b#1`", linkGithubRefs("`a/b#1`"))
    }

    @Test
    fun `GH-编号 查到后才变链接`() {
        assertEquals(setOf(451), issueNumbersIn("GH-451"))
        val kinds = mapOf(451 to GithubRefKind.ISSUE)
        assertEquals("[GH-451](https://github.com/yeliqin666/xjtu-toolbox-android/issues/451)", linkIssues("GH-451", kinds))
        assertEquals("GH-452", linkIssues("GH-452", kinds))
    }

    @Test
    fun `引用块里独占一行的图片切成引用图片块`() {
        val md = "> <img width=\"10\" height=\"20\" alt=\"a\" src=\"https://x/a.png\" />\n>\n> ![b](https://x/b.png)\n\n/attachcomment #468"
        assertEquals(
            listOf(
                PostPart.QuotedImages(listOf(PostPart.Image("https://x/a.png", "a", 10, 20), PostPart.Image("https://x/b.png", "b", null, null))),
                PostPart.Text("/attachcomment #468"),
            ),
            splitPostParts(md),
        )
        // 引用里还有文字时文字照常留给 Markdown
        assertEquals(PostPart.Text("> 原文"), splitPostParts("> 原文\n> ![b](https://x/b.png)").first())
    }

    @Test
    fun `套了链接的图片也单独成块`() {
        val svg = "https://api.star-history.com/svg?repos=a/b&type=Date"
        assertEquals(
            listOf(PostPart.Image(svg, "Star History Chart", null, null)),
            splitPostParts("[![Star History Chart]($svg)](https://star-history.com/#a/b&Date)"),
        )
        assertEquals(
            listOf(PostPart.Image("https://x/a.png", "a", null, null)),
            splitPostParts("<a href=\"https://x\"><img alt=\"a\" src=\"https://x/a.png\"></a>"),
        )
    }

    @Test
    fun `details 切成折叠块，可嵌套、可在引用里`() {
        val md = "前文\n<details open><summary>🐞 <b>有bug</b></summary>\n\n正文\n<details><summary>内层</summary>\n\nx\n</details>\n\n</details>\n后文"
        assertEquals(
            listOf(
                PostPart.Text("前文"),
                PostPart.Details("🐞 <b>有bug</b>", "正文\n<details><summary>内层</summary>\n\nx\n</details>", open = true, quoted = false),
                PostPart.Text("后文"),
            ),
            splitPostParts(md),
        )
        assertEquals(
            listOf(PostPart.Details("日志", "```\nboom\n```", open = false, quoted = true)),
            splitPostParts("> <details><summary>日志</summary>\n>\n> ```\n> boom\n> ```\n> </details>"),
        )
    }

    @Test
    fun `常见 HTML 换成 Markdown`() {
        assertEquals("`a` **b** ~~c~~ [d](https://x) ![e](https://x/e.png)",
            githubHtmlToMarkdown("<kbd>a</kbd> <strong>b</strong> <del>c</del> <a href=\"https://x\">d</a> <img alt=\"e\" src=\"https://x/e.png\">"))
        assertEquals("说明", githubHtmlToMarkdown("<sub>说明</sub><!-- poll-data:{} -->"))
        assertEquals("> 一\n> 二", githubHtmlToMarkdown("> 一<br>二"))
        assertEquals("## 标题\n***", githubHtmlToMarkdown("<h2>标题</h2>\n<hr>"))
        assertEquals("\\<details\\> 🎉 :core:", githubHtmlToMarkdown("&lt;details&gt; :tada: :core:"))
        assertEquals("`<b>x</b>`", githubHtmlToMarkdown("`<b>x</b>`"))
    }
}
