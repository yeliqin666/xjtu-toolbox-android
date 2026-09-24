package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/feature/discussions/DiscussionSummary.kt

import org.intellij.markdown.MarkdownElementTypes as Elements
import org.intellij.markdown.MarkdownTokenTypes as Tokens
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** A bounded list preview. The original body is always used by the detail reader. */
internal fun discussionSummary(markdown: String): String {
    val source = markdown.take(8_192)
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
    val plain = StringBuilder()
    fun visit(node: ASTNode) {
        when (node.type) {
            Elements.LINK_DEFINITION, Elements.LINK_DESTINATION, Elements.LINK_TITLE,
            Elements.HTML_BLOCK, Tokens.HTML_TAG -> return
            Elements.LINK_TEXT, Elements.LINK_LABEL -> {
                node.children.filter { it.type != Tokens.LBRACKET && it.type != Tokens.RBRACKET }
                    .forEach(::visit)
                return
            }
            Elements.IMAGE -> {
                node.children.filter { it.type != Tokens.EXCLAMATION_MARK }.forEach(::visit)
                return
            }
            Elements.SHORT_REFERENCE_LINK -> {
                // The parser also classifies literal array indices as possible reference links.
                plain.append(source.substring(node.startOffset, node.endOffset))
                return
            }
            Elements.INLINE_LINK, Elements.FULL_REFERENCE_LINK -> {
                node.children.firstOrNull { it.type == Elements.LINK_TEXT || it.type == Elements.LINK_LABEL }
                    ?.let(::visit)
                return
            }
            Elements.CODE_SPAN -> {
                node.children.filter { it.type != Tokens.BACKTICK }.forEach {
                    plain.append(source.substring(it.startOffset, it.endOffset))
                }
                return
            }
        }
        if (node.children.isNotEmpty()) {
            node.children.forEach(::visit)
        } else when (node.type) {
            Tokens.EOL, Tokens.WHITE_SPACE, Tokens.HARD_LINE_BREAK -> plain.append(' ')
            Tokens.ATX_HEADER, Tokens.SETEXT_1, Tokens.SETEXT_2, Tokens.EMPH,
            Tokens.BACKTICK, Tokens.LIST_BULLET, Tokens.LIST_NUMBER, Tokens.BLOCK_QUOTE,
            Tokens.HORIZONTAL_RULE, Tokens.FENCE_LANG, Tokens.CODE_FENCE_START,
            Tokens.CODE_FENCE_END -> Unit
            else -> plain.append(source.substring(node.startOffset, node.endOffset))
        }
    }
    visit(tree)
    val text = plain.toString().replace(Regex("\\s+"), " ").trim()
    if (text.length <= 240) return text
    // Do not split a surrogate pair (for example an emoji) at the preview boundary.
    val end = if (text[239].isHighSurrogate()) 239 else 240
    return text.take(end).trimEnd() + "…"
}
