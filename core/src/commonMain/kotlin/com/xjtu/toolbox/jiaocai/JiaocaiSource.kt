package com.xjtu.toolbox.jiaocai

/** 教材中心的取数端口。 */
interface JiaocaiSource {

    /** 按关键词检索教材（全校范围）。 */
    suspend fun search(keyword: String): List<JiaocaiBook>

    /** 取一本教材的全文库书目号（进详情页时补一次；`search-list` 的 `content` 里没有链接）。 */
    suspend fun fetchSsno(book: JiaocaiBook): String?
}

/** 「本学期我的教材」那一段的数据端口（它在教务的缓存里，不在教材中心）。 */
interface JiaocaiTermTextbooks {

    /** 本学期课程选用的教材；读不到（没有缓存 / 没有学期）返回空表。 */
    suspend fun currentTerm(): List<JiaocaiTermTextbook>
}
