package com.xjtu.toolbox.judge

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.xjtu.toolbox.auth.JwxtLogin
import com.xjtu.toolbox.util.safeString
import com.xjtu.toolbox.util.safeStringOrNull
import com.xjtu.toolbox.util.safeInt
import okhttp3.FormBody
import okhttp3.Request

// ==================== 数据类 ====================

/**
 * 评教问卷基本信息（教务系统字段名为拼音首字母大写，保持原样）
 */
data class Questionnaire(
    val BPJS: String,   // 被评教师
    val BPR: String,    // 被评人
    val DBRS: Int,      // 代笔人数
    val JSSJ: String,   // 结束时间
    val JXBID: String,  // 教学班 ID
    val KCH: String,    // 课程号
    val KCM: String,    // 课程名
    val KSSJ: String,   // 开始时间
    val PCDM: String,   // 批次代码
    val PGLXDM: String, // 评估类型代码 "01"=期末 "05"=过程
    val PGNR: String,   // 评估内容
    val WJDM: String,   // 问卷代码
    val WJMC: String,   // 问卷名称
    val XNXQDM: String  // 学年学期代码
)

/**
 * 问卷内的具体题目信息
 */
data class QuestionnaireData(
    val WJDM: String,   // 问卷代码
    val CPR: String,    // 参评人
    val BPR: String,    // 被评人
    val PGNR: String,   // 评估内容
    val ZBDM: String,   // 指标代码（题目代码）
    val PCDM: String,   // 批次代码
    val TXDM: String,   // 题型代码: "01"=客观题, "02"=主观题, "03"=分值题
    val JXBID: String,  // 教学班 ID
    var DA: String,     // 答案（答案编号）
    val ZBMC: String,   // 指标名称（题目名称）
    val DADM: String,   // 答案代码
    var ZGDA: String = "",  // 主观题答案
    val SFBT: String = "1", // 是否必填
    val DAXH: String = "1", // 答案序号
    val FZ: String? = null  // 分值（分值题最大分）
) {
    /** 获取分值题的最大分值 */
    fun getMaxScore(): Int {
        require(TXDM == "03") { "此题目不是分值题" }
        requireNotNull(FZ) { "此题目的分值信息不可用" }
        return FZ.toInt()
    }

    /** 为分值题设置分值 */
    fun setScore(score: Int) {
        require(TXDM == "03") { "此题目不是分值题" }
        requireNotNull(FZ) { "此题目的分值信息不可用" }
        val maxScore = FZ.toInt()
        require(score in 0..maxScore) { "分值必须在 0 到 $maxScore 之间" }
        DA = score.toString()
    }

    /**
     * 为客观题设置选项
     * @param options 选项字典（ZBDM -> 选项列表）
     * @param score 选项排序值，"1"=100分最优，"5"=20分最低
     */
    fun setOption(options: Map<String, List<QuestionnaireOptionData>>, score: String = "1") {
        require(TXDM == "01") { "此题目不是客观题" }
        val optionList = options[ZBDM]
            ?: throw IllegalArgumentException("无法在输入的答案选项中找到此题目")
        require(optionList.isNotEmpty()) { "此题目没有可选的选项" }

        // 精确匹配 DAPX
        for (opt in optionList) {
            if (opt.DAPX == score) {
                DA = opt.DA
                return
            }
        }
        // 找不到精确匹配时，选择最接近的
        val scoreNum = score.toFloatOrNull() ?: 1f
        var minDiff = 100f
        for (opt in optionList) {
            val diff = kotlin.math.abs((opt.DAPX.toFloatOrNull() ?: 0f) - scoreNum)
            if (diff < minDiff) {
                minDiff = diff
                DA = opt.DA
            }
        }
    }

    /** 为主观题设置答案 */
    fun setSubjectiveAnswer(data: String) {
        require(TXDM == "02") { "此题目不是主观题" }
        DA = ""
        ZGDA = data
    }

    /** 转换为提交用 JSON Map */
    fun toJsonMap(): Map<String, String?> = mapOf(
        "WJDM" to WJDM,
        "CPR" to CPR,
        "BPR" to BPR,
        "PGNR" to PGNR,
        "ZBDM" to ZBDM,
        "PCDM" to PCDM,
        "TXDM" to TXDM,
        "JXBID" to JXBID,
        "DA" to DA,
        "ZBMC" to ZBMC,
        "DADM" to DADM,
        "ZGDA" to ZGDA,
        "SFBT" to SFBT,
        "DAXH" to DAXH,
        "FZ" to FZ,
        "SFXYTJFJXX" to "",
        "FJXXSFBT" to "",
        "FJXX" to ""
    )
}

/**
 * 问卷选项信息
 */
data class QuestionnaireOptionData(
    val ZBDM: String,  // 指标（题目）代码
    val ZBMC: String,  // 指标（题目）名称
    val DADM: String,  // 答案代码
    val DA: String,    // 答案选项编号（填入 QuestionnaireData.DA）
    val TXDM: String,  // 选项所属题目的类型
    val DAPX: String,  // 选项排序（"1"~"5"，对应 100/80/60/40/20 分）
    val FZ: String     // 选项所附属题目的分值
)

// ==================== API 类 ====================

/**
 * 教务评教 API
 * 封装了教务系统评教相关的所有请求接口
 */
class JudgeApi(private val login: JwxtLogin) {

    private val gson = Gson()
    private var cachedTerm: String? = null

    /**
     * 获取当前学期的字符串表示，如 "2024-2025-1"
     */
    fun getCurrentTerm(): String {
        val formBody = FormBody.Builder()
            .add(
                "setting",
                "[{\"name\":\"CSDM\",\"value\":\"PJGLPJSJ\",\"builder\":\"equal\",\"linkOpt\":\"AND\"}" +
                        ",{\"name\":\"ZCSDM\",\"value\":\"PJXNXQ\",\"builder\":\"m_value_equal\",\"linkOpt\":\"AND\"}]"
            )
            .build()

        val request = Request.Builder()
            .url("https://jwxt.xjtu.edu.cn/jwapp/sys/wspjyyapp/modules/xspj/cxxtcs.do")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(formBody)
            .build()

        val responseBody = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val root = JsonParser.parseString(responseBody).asJsonObject
        return root.getAsJsonObject("datas")
            .getAsJsonObject("cxxtcs")
            .getAsJsonArray("rows")
            .get(0).asJsonObject
            .get("CSZA").safeString()
    }

    /**
     * 获取评教问卷列表
     * @param type 评估类型: "01"=期末, "05"=过程
     * @param term 学年学期代码，如 "2024-2025-1"
     * @param finished true=已评, false=未评
     */
    fun getQuestionnaires(type: String, term: String, finished: Boolean): List<Questionnaire> {
        val formBody = FormBody.Builder()
            .add("PGLXDM", type)
            .add("SFPG", if (finished) "1" else "0")
            .add("SFKF", "1")
            .add("SFFB", "1")
            .add("XNXQDM", term)
            .build()

        val request = Request.Builder()
            .url("https://jwxt.xjtu.edu.cn/jwapp/sys/wspjyyapp/modules/xspj/cxdwpj.do")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(formBody)
            .build()

        val responseBody = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val root = JsonParser.parseString(responseBody).asJsonObject
        val rows = root.getAsJsonObject("datas")
            .getAsJsonObject("cxdwpj")
            .getAsJsonArray("rows")

        return rows.map { el ->
            val obj = el.asJsonObject
            Questionnaire(
                BPJS = obj.get("BPJS").safeString(),
                BPR = obj.get("BPR").safeString(),
                DBRS = obj.get("DBRS").safeInt(),
                JSSJ = obj.get("JSSJ").safeString(),
                JXBID = obj.get("JXBID").safeString(),
                KCH = obj.get("KCH").safeString(),
                KCM = obj.get("KCM").safeString(),
                KSSJ = obj.get("KSSJ").safeString(),
                PCDM = obj.get("PCDM").safeString(),
                PGLXDM = obj.get("PGLXDM").safeString(),
                PGNR = obj.get("PGNR").safeString(),
                WJDM = obj.get("WJDM").safeString(),
                WJMC = obj.get("WJMC").safeString(),
                XNXQDM = obj.get("XNXQDM").safeString()
            )
        }
    }

    /**
     * 获取所有未完成的评教问卷（期末 + 过程）
     */
    fun unfinishedQuestionnaires(term: String? = null): List<Questionnaire> {
        val t = term ?: run {
            if (cachedTerm == null) cachedTerm = getCurrentTerm()
            cachedTerm!!
        }
        val mid = getQuestionnaires("05", t, finished = false)
        val end = getQuestionnaires("01", t, finished = false)
        return mid + end
    }

    /**
     * 获取所有已完成的评教问卷（期末 + 过程）
     */
    fun finishedQuestionnaires(term: String? = null): List<Questionnaire> {
        val t = term ?: run {
            if (cachedTerm == null) cachedTerm = getCurrentTerm()
            cachedTerm!!
        }
        val mid = getQuestionnaires("05", t, finished = true)
        val end = getQuestionnaires("01", t, finished = true)
        return mid + end
    }

    /**
     * 获取某问卷的题目信息
     */
    fun getQuestionnaireData(q: Questionnaire, username: String): List<QuestionnaireData> {
        val formBody = FormBody.Builder()
            .add("WJDM", q.WJDM)
            .add("JXBID", q.JXBID)
            .build()

        val request = Request.Builder()
            .url("https://jwxt.xjtu.edu.cn/jwapp/sys/wspjyyapp/modules/wj/cxwjzb.do")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(formBody)
            .build()

        val responseBody = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val root = JsonParser.parseString(responseBody).asJsonObject
        val rows = root.getAsJsonObject("datas")
            .getAsJsonObject("cxwjzb")
            .getAsJsonArray("rows")

        return rows.map { el ->
            val obj = el.asJsonObject
            QuestionnaireData(
                WJDM = obj.get("WJDM").safeString(),
                CPR = username,
                BPR = q.BPR,
                PGNR = q.PGNR,
                ZBDM = obj.get("ZBDM").safeString(),
                PCDM = q.PCDM,
                TXDM = obj.get("TXDM").safeString(),
                JXBID = q.JXBID,
                DA = "",
                ZBMC = obj.get("ZBMC").safeString(),
                DADM = obj.get("DADM").safeString(),
                SFBT = obj.get("SFBT").safeString("1"),
                FZ = obj.get("FZ").safeStringOrNull()
            )
        }
    }

    /**
     * 获取某问卷所有题目的选项
     * @return Map: ZBDM -> 该题目的选项列表
     */
    fun getQuestionnaireOptions(
        q: Questionnaire,
        username: String,
        finished: Boolean = false
    ): Map<String, List<QuestionnaireOptionData>> {
        val querySetting = gson.toJson(
            listOf(
                mapOf("name" to "BPR", "value" to q.BPR, "linkOpt" to "AND", "builder" to "equal"),
                mapOf("name" to "CPR", "value" to username, "linkOpt" to "AND", "builder" to "equal"),
                mapOf("name" to "JXBID", "value" to q.JXBID, "linkOpt" to "AND", "builder" to "equal"),
                mapOf("name" to "PGNR", "value" to q.PGNR, "linkOpt" to "AND", "builder" to "equal"),
                mapOf("name" to "WJDM", "value" to q.WJDM, "linkOpt" to "AND", "builder" to "equal"),
                mapOf("name" to "PCDM", "value" to q.PCDM, "linkOpt" to "AND", "builder" to "equal")
            )
        )

        val formBody = FormBody.Builder()
            .add("WJDM", q.WJDM)
            .add("CPR", username)
            .add("PCDM", q.PCDM)
            .add("SFPG", if (finished) "1" else "0")
            .add("BPR", q.BPR)
            .add("PGNR", q.PGNR)
            .add("querySetting", querySetting)
            .build()

        val request = Request.Builder()
            .url("https://jwxt.xjtu.edu.cn/jwapp/sys/wspjyyapp/modules/wj/cxxswjzbxq.do")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .post(formBody)
            .build()

        val responseBody = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val root = JsonParser.parseString(responseBody).asJsonObject
        val rows = root.getAsJsonObject("datas")
            .getAsJsonObject("cxxswjzbxq")
            .getAsJsonArray("rows")

        val result = mutableMapOf<String, MutableList<QuestionnaireOptionData>>()
        for (el in rows) {
            val obj = el.asJsonObject
            val zbdm = obj.get("ZBDM").safeString()
            val optionData = QuestionnaireOptionData(
                ZBDM = zbdm,
                ZBMC = obj.get("ZBMC").safeString(),
                DADM = obj.get("DADM").safeString(),
                DA = obj.get("DAFXDM").safeString(),
                TXDM = obj.get("TXDM").safeString(),
                DAPX = obj.get("DAPX").safeString(),
                FZ = obj.get("FZ").safeString()
            )
            result.getOrPut(zbdm) { mutableListOf() }.add(optionData)
        }
        return result
    }

    /**
     * 提交已完成的问卷
     * @return Pair<是否成功, 服务器消息>
     */
    fun submitQuestionnaire(q: Questionnaire, data: List<QuestionnaireData>): Pair<Boolean, String> {
        val wjysjgJson = gson.toJson(data.map { it.toJsonMap() })
        val requestParamStr = gson.toJson(
            mapOf(
                "WJDM" to q.WJDM,
                "PCDM" to q.PCDM,
                "PGLY" to "1",
                "SFTJ" to "1",
                "WJYSJG" to wjysjgJson
            )
        )

        val formBody = FormBody.Builder()
            .add("requestParamStr", requestParamStr)
            .build()

        val request = Request.Builder()
            .url("https://jwxt.xjtu.edu.cn/jwapp/sys/wspjyyapp/WspjwjController/addXsPgysjg.do")
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .post(formBody)
            .build()

        val responseBody = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val root = JsonParser.parseString(responseBody).asJsonObject
        val code = root.get("code").safeString("-1")
        val datasObj = root.getAsJsonObject("datas")
        val datasCode = datasObj?.get("code").safeString("-1")
        val msg = datasObj?.get("msg").safeString("未知错误")

        val success = code == "0" && datasCode == "0"
        return Pair(success, msg)
    }

    /**
     * 撤销/编辑已提交的问卷（将"已评"改回"待评"）
     * 移植自 Python 的 editQuestionnaire()
     * @return Pair<是否成功, 服务器消息>
     */
    fun editQuestionnaire(q: Questionnaire, username: String): Pair<Boolean, String> {
        val requestParamStr = gson.toJson(
            mapOf(
                "WJDM" to q.WJDM,
                "PCDM" to q.PCDM,
                "CPR" to username,
                "PGLXDM" to q.PGLXDM,
                "BPR" to q.BPR,
                "JXBID" to q.JXBID,
                "PGNR" to q.PGNR
            )
        )

        val formBody = FormBody.Builder()
            .add("requestParamStr", requestParamStr)
            .build()

        val request = Request.Builder()
            .url("https://jwxt.xjtu.edu.cn/jwapp/sys/wspjyyapp/WspjwjController/updateCprZt.do")
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .post(formBody)
            .build()

        val responseBody = login.client.newCall(request).execute().use { response ->
            response.body?.string() ?: throw RuntimeException("空响应")
        }
        val root = JsonParser.parseString(responseBody).asJsonObject
        val code = root.get("code").safeString("-1")
        val datasObj = root.getAsJsonObject("datas")
        val datasCode = datasObj?.get("code").safeString("-1")
        val msg = datasObj?.get("msg").safeString("未知错误")

        val success = code == "0" && datasCode == "0"
        return Pair(success, msg)
    }

    /**
     * 自动填写问卷（为所有题目自动选择最优答案）
     * @param q 问卷信息
     * @param username 学号
     * @param score 选择题分值排序值，"1"=最优(100分)
     * @return 已填好答案的题目列表，可直接用于 submitQuestionnaire
     */
    fun autoFillQuestionnaire(
        q: Questionnaire,
        username: String,
        score: String = "1"
    ): List<QuestionnaireData> {
        // 获取所有题目
        val dataList = getQuestionnaireData(q, username)
        // 获取所有选项
        val options = getQuestionnaireOptions(q, username, finished = false)

        for (item in dataList) {
            when (item.TXDM) {
                "01" -> {
                    // 客观题：选择最优选项
                    item.setOption(options, score)
                }
                "02" -> {
                    // 主观题：设置默认文字
                    item.setSubjectiveAnswer("老师授课认真，课程收益良多。")
                }
                "03" -> {
                    // 分值题：设置最高分（如果获取不到，默认用 100）
                    try {
                        val maxScore = item.getMaxScore()
                        item.setScore(maxScore)
                    } catch (_: Exception) {
                        item.setScore(100)
                    }
                }
            }
        }

        return dataList
    }
}
