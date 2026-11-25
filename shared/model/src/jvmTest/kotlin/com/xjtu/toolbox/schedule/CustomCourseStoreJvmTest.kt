package com.xjtu.toolbox.schedule

import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.platform.keyValueStoreRootOverride
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomCourseStoreJvmTest {
    @Test
    fun concurrentInsertsPreserveRowsAndUniqueIds() = runBlocking {
        val root = Files.createTempDirectory("custom-courses-test").toFile()
        val previous = keyValueStoreRootOverride
        keyValueStoreRootOverride = root
        try {
            val backing = keyValueStore("custom_courses_concurrency")
            val stores = listOf(KeyValueCustomCourseStore(backing), KeyValueCustomCourseStore(backing))
            val ids = (0 until 32).map { i ->
                async(Dispatchers.Default) {
                    stores[i % 2].insert(CustomCourse(
                        accountId = "account", courseName = "course-$i", weekBits = "1",
                        dayOfWeek = 1, startSection = 1, endSection = 2, termCode = "term",
                    ))
                }
            }.awaitAll()
            val rows = stores.first().getAll("account")
            assertEquals((1L..32L).toSet(), ids.toSet())
            assertEquals((0 until 32).map { "course-$it" }.toSet(), rows.map { it.courseName }.toSet())
            assertEquals(ids.toSet(), rows.map { it.id }.toSet())
        } finally {
            keyValueStoreRootOverride = previous
            root.deleteRecursively()
        }
    }
}
