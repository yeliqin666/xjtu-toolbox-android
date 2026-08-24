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
