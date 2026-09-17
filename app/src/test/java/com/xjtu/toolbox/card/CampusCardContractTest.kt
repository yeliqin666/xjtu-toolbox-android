package com.xjtu.toolbox.card

import com.xjtu.toolbox.util.safeParseJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusCardContractTest {

    @Test
    fun signedAmount_incomeExpenseRefundAndServerNegative() {
        assertEquals(500L, CampusCardContract.signedAmountCents(500, "充值", "recharge"))
        assertEquals(-300L, CampusCardContract.signedAmountCents(300, "消费", "consume"))
        assertEquals(200L, CampusCardContract.signedAmountCents(200, "退款", "refund"))
        assertEquals(150L, CampusCardContract.signedAmountCents(150, "补助", "subsidy"))
        assertEquals(-80L, CampusCardContract.signedAmountCents(-80, "未知", ""))
        assertEquals(90L, CampusCardContract.signedAmountCents(90, "qrcode", ""))
        // 食堂窗口扫码支付：抓包实测的真实分类值，之前两份关键词都对不上，被当成收入显示。
        assertEquals(-2100L, CampusCardContract.signedAmountCents(2100, "二维码支付", "qrCode-payment"))
    }

    @Test
    fun signedAmount_typeFromMatchesOfficialWebFrontendRule() {
        // 官方 ncard 账单页前端代码里的规则：typeFrom=="1" 显示 +，其余显示 -。
        // 优先级高于关键词匹配，即使文案没见过也能判对。
        assertEquals(500L, CampusCardContract.signedAmountCents(500, "充值", "recharge", typeFrom = "1"))
        assertEquals(-300L, CampusCardContract.signedAmountCents(300, "消费", "consume", typeFrom = "2"))
        // 抓包实测的真实二维码支付记录：typeFrom="2"，即便 typeName/icon 从没见过也判对。
        assertEquals(-2100L, CampusCardContract.signedAmountCents(2100, "没见过的新渠道", "mystery-icon", typeFrom = "2"))
    }

    @Test
    fun signedAmount_fallsBackToToAccountWhenMarkersDontMatch() {
        // 关键词都对不上、又给了 toAccount 时：钱转去了别的账号（商户/终端）算支出。
        assertEquals(-1200L, CampusCardContract.signedAmountCents(1200, "新支付方式", "mystery", toAccount = 1001028L))
        // toAccount=0：钱没转出去，算收入。
        assertEquals(1200L, CampusCardContract.signedAmountCents(1200, "新支付方式", "mystery", toAccount = 0L))
        // toAccount 等于自己的账号：跟没转出去等价，算收入。
        assertEquals(
            1200L,
            CampusCardContract.signedAmountCents(1200, "新支付方式", "mystery", toAccount = 255798L, fromAccount = 255798L)
        )
        // 两份关键词都对不上、也没给 toAccount：维持原样，不瞎猜方向。
        assertEquals(1200L, CampusCardContract.signedAmountCents(1200, "新支付方式", "mystery"))
    }

    @Test
    fun businessCode_acceptsIntAndString() {
        val intCode = """{"code":200,"data":{}}""".safeParseJsonObject()
        val strCode = """{"code":"200","message":"ok"}""".safeParseJsonObject()
        val quoted401 = """{"code":"401","message":"其他设备登录"}""".safeParseJsonObject()
        assertEquals("200", CampusCardContract.businessCode(intCode))
        assertEquals("200", CampusCardContract.businessCode(strCode))
        assertEquals("401", CampusCardContract.businessCode(quoted401))
        CampusCardContract.requireSuccess(intCode, "查询")
        CampusCardContract.requireSuccess(strCode, "查询")
    }

    @Test(expected = RuntimeException::class)
    fun missingCode_isNotSuccess() {
        CampusCardContract.requireSuccess("""{"message":"ok"}""".safeParseJsonObject(), "查询")
    }

    @Test
    fun authFailure_readsQuoted401AndOtherDevice() {
        assertTrue(CampusCardContract.isAuthFailureBody("""{"code":"401","message":"其他设备登录"}"""))
        assertTrue(CampusCardContract.isAuthFailureBody("""{"code":401,"message":"未登录"}"""))
        assertTrue(CampusCardContract.isAuthFailureBody("""{"message":"其他设备已登录"}"""))
        assertFalse(CampusCardContract.isAuthFailureBody("""{"code":200,"message":"ok"}"""))
    }

    @Test
    fun requiredFields_rejectBlankAndBoolean() {
        val data = """{"name":"张三","sno":"123","cardAccount":"255798"}""".safeParseJsonObject()
        assertEquals("张三", CampusCardContract.requiredText(data, "name", "资料"))
        try {
            CampusCardContract.requiredText("""{"name":true}""".safeParseJsonObject(), "name", "资料")
            throw AssertionError("expected failure")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("必要字段"))
        }
    }
}
