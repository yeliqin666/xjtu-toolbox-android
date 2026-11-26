package com.xjtu.toolbox.home

import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.nav.AppRoute
import com.xjtu.toolbox.platform.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 学业进度、成绩单挪出「我的」页后，改过首页的老用户要补回一次（[HomeLayoutStore.load]）。 */
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
    fun `改过首页的本科生升级后补回学业进度和成绩单，各一格`() {
        val layout = HomeLayoutStore(oldCustomizedStore()).load(AccountType.UNDERGRADUATE)
        assertEquals(1, layout.count(degree))
        assertEquals(1, layout.count(transcript))
    }

    @Test
    fun `只补一次，补完再删就不会长回来`() {
        val kv = oldCustomizedStore()
        val store = HomeLayoutStore(kv)
        val first = store.load(AccountType.UNDERGRADUATE)
        val removed = HomeLayout(first.groups.map { g -> g.copy(tiles = g.tiles.filter { it.routeId != degree && it.routeId != transcript }) })
        kv.map[KEY_LAYOUT] = HomeLayoutCodec.encode(removed)
        val second = store.load(AccountType.UNDERGRADUATE)
        assertEquals(0, second.count(degree))
        assertEquals(0, second.count(transcript))
    }

    @Test
    fun `研究生看不到这两项，不补也不置标记，换本科账号时再补`() {
        val kv = oldCustomizedStore()
        val store = HomeLayoutStore(kv)
        val grad = store.load(AccountType.POSTGRADUATE)
        assertEquals(0, grad.count(degree))
        assertFalse(kv.getBoolean(KEY_ONCE, false))
        assertEquals(1, store.load(AccountType.UNDERGRADUATE).count(degree))
    }

    @Test
    fun `默认布局的人第一次亲手保存时删掉了它们，之后不补回`() {
        val kv = FakeStore()
        val store = HomeLayoutStore(kv)
        val defaults = store.load(AccountType.UNDERGRADUATE)
        assertEquals(1, defaults.count(degree))
        assertEquals(1, defaults.count(transcript))
        store.save(HomeLayout(defaults.groups.map { g -> g.copy(tiles = g.tiles.filter { it.routeId != degree && it.routeId != transcript }) }))
        val after = store.load(AccountType.UNDERGRADUATE)
        assertEquals(0, after.count(degree))
        assertEquals(0, after.count(transcript))
    }

    private companion object {
        const val KEY_LAYOUT = "home_cards_layout"
        const val KEY_SEEN = "home_seen_routes"
        const val KEY_ONCE = "home_profile_moved_routes_v1"
    }
}
