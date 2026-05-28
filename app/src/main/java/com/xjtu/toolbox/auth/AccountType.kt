package com.xjtu.toolbox.auth

enum class AccountType(val key: String, val displayName: String) {
    UNDERGRADUATE("undergraduate", "本科生"),
    POSTGRADUATE("postgraduate", "研究生");

    companion object {
        fun fromKey(key: String?): AccountType = entries.firstOrNull { it.key == key } ?: UNDERGRADUATE
    }
}
