package com.xjtu.toolbox.dzpz

/**
 * dzpz.xjtu.edu.cn 是一个通用的工作流引擎，成绩单只是其中的一个 `workflowId`。
 * 同一份文件在不同身份下走不同的 workflowId（在校生 / 校友的流程不一样），
 * 所以按「文件 × 身份」建一张表，而不是像原来那样把 workflowId 写死成一个默认值
 * （那样会导致所有人都用在校本科生 29 的流程，见 TranscriptScreen 的 P1 修复）。
 *
 * 目前只接了成绩单这一种文件；P2（等仓库主抓包）会往 [DzpzDocuments.all] 里加
 * 别的证明文件。
 */
enum class DzpzIdentity(val label: String) {
    UNDERGRAD("在校本科生"),
    POSTGRAD("研究生"),
    UNDERGRAD_ALUMNI("已毕业本科（校友）"),
    POSTGRAD_ALUMNI("研究生校友"),
}

data class DzpzDocument(
    val id: String,
    val title: String,
    val workflowIds: Map<DzpzIdentity, Int>,
)

object DzpzDocuments {
    val TRANSCRIPT = DzpzDocument(
        id = "transcript",
        title = "成绩单",
        workflowIds = mapOf(
            DzpzIdentity.UNDERGRAD to 29,
            DzpzIdentity.POSTGRAD to 34,
            DzpzIdentity.UNDERGRAD_ALUMNI to 46,
            DzpzIdentity.POSTGRAD_ALUMNI to 49,
        ),
    )

    val all = listOf(TRANSCRIPT)
}
