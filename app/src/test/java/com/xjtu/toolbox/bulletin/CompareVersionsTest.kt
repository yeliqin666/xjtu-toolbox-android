package com.xjtu.toolbox.bulletin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareVersionsTest {

    private fun cmp(v1: String, v2: String): Int = BulletinRules.compareVersions(v1, v2)

    private fun assertLessThan(v1: String, v2: String) {
        val res = cmp(v1, v2)
        assertTrue("$v1 should be < $v2 but was $res", res < 0)
        val sym = cmp(v2, v1)
        assertTrue("$v2 should be > $v1 but was $sym", sym > 0)
    }

    private fun assertEqualTo(v1: String, v2: String) {
        val res = cmp(v1, v2)
        assertEquals("$v1 should == $v2", 0, res)
        val sym = cmp(v2, v1)
        assertEquals("$v2 should == $v1", 0, sym)
    }

    @Test
    fun standardVersions() {
        assertLessThan("4.9.6", "4.9.7")
        assertEqualTo("4.9.7", "4.9.7")
    }

    @Test
    fun legacyCompatibility() {
        assertEqualTo("4.72", "4.7.2")
        assertLessThan("4.72", "4.7.3")
        assertEqualTo("4.71", "4.7.1")
        assertEqualTo("4.61", "4.6.1")
    }

    @Test
    fun preReleaseNumericOrder() {
        assertLessThan("4.9.8-dev.3", "4.9.8-dev.12")
        assertLessThan("4.9.8-dev.1", "4.9.8-dev.2")
    }

    @Test
    fun preReleaseVersusFormal() {
        assertLessThan("4.9.8-dev.12", "4.9.8")
        assertLessThan("4.9.8-dev.999", "4.9.8")
        assertLessThan("4.9.7", "4.9.8-dev.1")
    }

    @Test
    fun preReleaseSegmentsAndLength() {
        assertLessThan("4.9.8-dev", "4.9.8-dev.1")
        assertLessThan("4.9.8-alpha.1", "4.9.8-beta.1")
    }

    @Test
    fun betaSchemeMigration() {
        // 迁移后的预览号：同一核心版本内序号按数字递增，正式版最高（CONTRIBUTING.md §1.3）
        assertLessThan("6.0.1-beta.1", "6.0.1-beta.2")
        assertLessThan("6.0.1-beta.2", "6.0.1-beta.10")
        assertLessThan("6.0.1-beta.10", "6.0.1")
        // 旧预览用户（-dev.N）能升到新核心版本的 beta：靠核心版本号抬升，而不是靠 -beta 字面量。
        // 同一核心版本内 dev 反而比 beta 大，所以迁移时一并抬了核心版本号（CONTRIBUTING.md §1.8）。
        assertLessThan("6.0.0-dev.384", "6.0.1-beta.1")
        assertLessThan("6.0.0-dev.999", "6.0.1-beta.1")
        assertLessThan("6.0.1-beta.1", "6.0.1-dev.1")
    }

    @Test
    fun reflexivityAndSymmetry() {
        val versions = listOf(
            "4.9.6",
            "4.9.7",
            "4.72",
            "4.7.2",
            "4.7.3",
            "4.9.8-dev.3",
            "4.9.8-dev.12",
            "4.9.8",
        )
        for (v in versions) {
            assertEquals("Reflexivity failed for $v", 0, cmp(v, v))
        }
        for (i in versions.indices) {
            for (j in i + 1 until versions.size) {
                val a = versions[i]
                val b = versions[j]
                val ab = cmp(a, b)
                val ba = cmp(b, a)
                val signA = if (ab > 0) 1 else if (ab < 0) -1 else 0
                val signB = if (ba > 0) 1 else if (ba < 0) -1 else 0
                assertEquals("Symmetry failed for $a vs $b", signA, -signB)
            }
        }
    }
}
