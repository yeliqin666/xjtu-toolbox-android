package com.xjtu.toolbox.home

import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals

/** 每个看得到的功能在首页至少一张卡：删光过的读出来会补回（[HomeLayoutStore.load]）。 */
class HomeLayoutStoreTest {

    private class FakeStore : KeyValueStore {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String): String? = map[key] as? String
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) {
            map[key] = value
        }
        override fun getInt(key: String, default: Int): Int = map[key] as? Int ?: default
        override fun putInt(key: String, value: Int) {
            map[key] = value
        }
        override fun getStringSet(key: String): Set<String>? = null
        override fun putStringSet(key: String, value: Set<String>) = Unit
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() = map.clear()
    }

    private val degree = AppRoute.DegreeProgress.id
    private val transcript = AppRoute.Transcript.id

    /** 老版本存下的布局：只摆了课表，学业进度被删过，成绩单当时还不在首页目录里。 */
    private fun oldCustomizedStore(): FakeStore = FakeStore().apply {
        val layout = HomeLayout(
            listOf(
                HomeGroup(id = HomeLayoutDefaults.QUICK_GROUP, title = "常用功能"),
                HomeGroup(
                    id = ServiceCategory.CLASS.name,
                    title = ServiceCategory.CLASS.title,
                    tiles = listOf(HomeTile("CLASS/schedule#0", AppRoute.Schedule.id, 0f, 0f, 2f, 1f)),
                ),
            ),
        )
        map[KEY_LAYOUT] = HomeLayoutCodec.encode(layout)
        // 当时目录里的功能都见过（成绩单那时 showOnHome = false，不在里面）
        map[KEY_SEEN] = (AppServices.home.map { it.route.id } - transcript).joinToString(",")
    }

    private fun HomeLayout.count(routeId: String) = groups.sumOf { g -> g.tiles.count { it.routeId == routeId } }

    @Test
    fun `改过首页、删光过某些功能的人，读出来每个看得到的功能都至少一张卡`() {
        val layout = HomeLayoutStore(oldCustomizedStore()).load(AccountType.UNDERGRADUATE)
        AppServices.homeFor(AccountType.UNDERGRADUATE).forEach { assertEquals(true, layout.count(it.route.id) >= 1, it.title) }
        assertEquals(1, layout.count(degree))
        assertEquals(1, layout.count(transcript))
    }

    @Test
    fun `删光了再读还会补回，已有的不重复加`() {
        val kv = oldCustomizedStore()
        val store = HomeLayoutStore(kv)
        val first = store.load(AccountType.UNDERGRADUATE)
        val removed = HomeLayout(first.groups.map { g -> g.copy(tiles = g.tiles.filter { it.routeId != degree }) })
        store.save(removed)
        val second = store.load(AccountType.UNDERGRADUATE)
        assertEquals(1, second.count(degree))
        assertEquals(1, second.count(AppRoute.Schedule.id))
    }

    @Test
    fun `研究生看不到本科专属的功能，不补`() {
        val grad = HomeLayoutStore(oldCustomizedStore()).load(AccountType.POSTGRADUATE)
        assertEquals(0, grad.count(degree))
    }

    private companion object {
        const val KEY_LAYOUT = "home_cards_layout"
        const val KEY_SEEN = "home_seen_routes"
    }
}
