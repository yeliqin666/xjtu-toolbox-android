package com.xjtu.toolbox.jiaocai

/**
 * 教材 ISBN 归一化：去掉短横线和空格，转大写（ISBN-10 的校验位可能是 X）。
 *
 * 用途：把「本学期我的教材」缓存里格式不一的 ISBN（有的带横杠、有的带空格）
 * 和全文库检索的 ISBN 字段对齐，否则同一本书因为写法不同而搜不到。
 */
fun normalizeIsbn(raw: String): String =
    raw.replace("-", "").replace(" ", "").trim().uppercase()
