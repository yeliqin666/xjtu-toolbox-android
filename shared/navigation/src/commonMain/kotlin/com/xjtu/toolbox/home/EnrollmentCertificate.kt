package com.xjtu.toolbox.home

enum class EnrollmentCertificate(val label: String, private val workflowId: Int) {
    CHINESE("中文", 30),
    BILINGUAL("中英文", 31);

    val url: String get() = "https://dzpz.xjtu.edu.cn/spa/workflow/static4form/index.html#/main/workflow/req?iscreate=1&workflowid=$workflowId&isagent=0&beagenter=0"
}
