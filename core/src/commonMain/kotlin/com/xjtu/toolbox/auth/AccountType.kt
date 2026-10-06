package com.xjtu.toolbox.auth

enum class AccountType(val key: String, val displayName: String) {
    UNDERGRADUATE("undergraduate", "本科生"),
    POSTGRADUATE("postgraduate", "研究生");

    companion object {
        fun fromKey(key: String?): AccountType = entries.firstOrNull { it.key == key } ?: UNDERGRADUATE

        /**
         * 从一网通办返回的 `identityTypeName` 判断身份。
         *
         * 这个字段来自 `authx-service.xjtu.edu.cn/personal/api/v1/personal/me/user`，
         * 是**学校自己填的身份名**（"本科生" / "研究生" / "硕士研究生" / "博士研究生"…），
         * 比让用户自己在设置里猜一个靠谱得多，也比从学号首位反推靠谱——
         * 学号编码规则学校从没公开过，各年级、各类型（留学生、二学位、专项）都有例外，
         * 猜错的代价是整个 CAS 选择身份走错分支、一堆子系统登不上。
         *
         * 认不出返回 null：宁可保持用户原来的设置，也不要用一个猜测去覆盖它。
         */
        fun fromIdentityName(identityTypeName: String?): AccountType? {
            val name = identityTypeName?.trim().orEmpty()
            if (name.isEmpty()) return null
            return when {
                // 「硕士研究生」「博士研究生」都含"研究生"；单独的"硕士""博士"也兜住。
                "研究生" in name || "硕士" in name || "博士" in name -> POSTGRADUATE
                "本科" in name -> UNDERGRADUATE
                else -> null
            }
        }
    }
}
