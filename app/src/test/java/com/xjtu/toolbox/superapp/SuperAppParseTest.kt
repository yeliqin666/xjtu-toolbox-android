package com.xjtu.toolbox.superapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SuperAppParseTest {

    private val raw = """
        {"code":0,"message":null,"data":[
          {"label":"教育教学","services":[
            {"id":"a1","serviceName":"本科生在读证明（异地护照申请）","serviceUrl":"https://workflow.xjtu.edu.cn/fe/taskCenter/one/application?app_id=278",
             "iconUrl":"https://ywtb.xjtu.edu.cn/portal-minio/service/1.png","serviceDepartmentName":"教务处","serviceSource":"教务处",
             "serviceDesc":"本流程仅适用于异地户籍申请护照。","contactInformation":"82666406/82668305",
             "servicePinYin":"benkeshengzaiduzhengming","clickNum":14994,"aggregationServices":{"aggregationServiceList":[]}},
            {"id":"a2","serviceName":"空入口","serviceUrl":null,"aggregationServices":{"aggregationServiceList":[]}}
          ]},
          {"label":"校园生活","services":[
            {"id":"z1","serviceName":"体检专区","serviceUrl":null,"servicePicUrl":"https://ywtb.xjtu.edu.cn/portal-minio/service/z.png",
             "serviceSource":"校医院","clickNum":null,"aggregationServices":{"aggregationServiceList":[
               {"id":"c1","serviceId":"s1","service_name":"体检预约","service_url":"https://health.xjtu.edu.cn/homePage/index1","service_icon":"https://ywtb.xjtu.edu.cn/portal-minio/service/c.png"},
               {"id":"c2","serviceId":"s2","service_name":"检验报告查询","service_url":" https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1615 "},
               {"id":"c3","serviceId":"s3","service_name":"重复","service_url":"https://health.xjtu.edu.cn/homePage/index1"}
             ]}}
          ]},
          {"label":"空分类","services":[]}
        ]}
    """.trimIndent()

    @Test fun parsesCategoriesServicesAndZones() {
        val categories = SuperAppApi.parse(raw)
        assertEquals(listOf("教育教学", "校园生活"), categories.map { it.name })

        val cert = categories[0].services.single()
        assertEquals("本科生在读证明（异地护照申请）", cert.name)
        assertEquals("教务处", cert.department)
        assertEquals(14994, cert.clicks)
        assertFalse(cert.isZone)

        val zone = categories[1].services.single()
        assertTrue(zone.isZone)
        assertEquals("", zone.url)
        assertEquals("校医院", zone.department)
        assertEquals("https://ywtb.xjtu.edu.cn/portal-minio/service/z.png", zone.icon)
        // 地址去首尾空白、同一地址只留一个
        assertEquals(
            listOf("https://health.xjtu.edu.cn/homePage/index1", "https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1615"),
            zone.children.map { it.url },
        )
        assertNull(zone.children[1].icon)
    }

    @Test fun searchMatchesNamePinyinAndDepartment() {
        val cert = SuperAppApi.parse(raw)[0].services.single()
        assertTrue(cert.matches("在读证明"))
        assertTrue(cert.matches("zaidu zheng"))
        assertTrue(cert.matches("教务"))
        assertFalse(cert.matches("网费"))
    }

    @Test(expected = IOException::class) fun serverErrorIsThrown() {
        SuperAppApi.parse("""{"code":-1,"message":"服务异常","data":null}""")
    }
}
