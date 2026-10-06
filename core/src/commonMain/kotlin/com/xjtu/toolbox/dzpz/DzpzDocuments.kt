package com.xjtu.toolbox.dzpz

/**
 * dzpz.xjtu.edu.cn 是一个通用的工作流引擎，成绩单只是其中的一个 `workflowId`。
 * 目前只开放在校本科生的成绩单（29）：研究生、校友的流程不同，入口也不给他们显示。
 */
data class DzpzDocument(
    val id: String,
    val title: String,
    val workflowId: Int,
)

object DzpzDocuments {
    val TRANSCRIPT = DzpzDocument(id = "transcript", title = "成绩单", workflowId = 29)

    val all = listOf(TRANSCRIPT)
}
