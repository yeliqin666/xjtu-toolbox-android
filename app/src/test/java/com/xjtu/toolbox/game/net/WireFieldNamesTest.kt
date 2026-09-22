package com.xjtu.toolbox.game.net

import com.google.gson.annotations.SerializedName
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联机二维码和对局消息是两台设备之间的协议。正式包经 R8 混淆，没钉 @SerializedName 的字段
 * 键名会变成 a/b/c 且每次编译都可能不同，不同版本的两台手机互扫就读不懂对方。
 * 本地单测不走 R8，测不出混淆本身，只能守住「每个字段都钉了名字，且名字等于字段名」。
 */
class WireFieldNamesTest {

    private fun assertAllFieldsPinned(type: Class<*>) {
        val fields = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        assertTrue("${type.simpleName} 没有字段？", fields.isNotEmpty())
        for (f in fields) {
            val ann = f.getAnnotation(SerializedName::class.java)
            assertTrue("${type.simpleName}.${f.name} 缺少 @SerializedName", ann != null)
            assertEquals("${type.simpleName}.${f.name} 的线上键名不能改", f.name, ann!!.value)
        }
    }

    @Test
    fun qrPayload_fieldsPinned() {
        assertAllFieldsPinned(OnlineQrPayload::class.java)
        assertAllFieldsPinned(OnlineQrPayload.BleInfo::class.java)
    }

    @Test
    fun envelope_fieldsPinned() {
        assertAllFieldsPinned(NetEnvelope::class.java)
    }
}
