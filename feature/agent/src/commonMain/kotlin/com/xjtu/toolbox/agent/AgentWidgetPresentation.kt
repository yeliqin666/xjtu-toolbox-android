package com.xjtu.toolbox.agent

import com.xjtu.toolbox.attendance.WaterType
import com.xjtu.toolbox.score.ScoreCalculator
import com.xjtu.toolbox.util.AppJson
import com.xjtu.toolbox.util.todayOnCampus
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*

/** 展示只筛选已有快照，不重新查询，也不接收模型编写的业务数据。 */
internal object AgentWidgetPresentation {
    const val TOOL = "present_result"

    private val kinds = mapOf(
        "get_grades" to "grades", "get_schedule" to "schedule", "add_schedule_event" to "schedule",
        "get_exam_schedule" to "exams", "get_empty_rooms" to "rooms", "get_attendance" to "attendance",
        "get_card_info" to "card", "list_zyxf" to "files", "get_library" to "library",
        "campus_map" to "map", "my_location" to "map",
        "get_notifications" to "notices",
    )

    private fun arrayProp(description: String, type: String = "string") = buildJsonObject {
        put("type", "array"); put("description", description)
        put("items", buildJsonObject { put("type", type) })
    }

    fun schema(kind: String? = null): JsonObject {
        val props = linkedMapOf(
            "rows" to arrayProp("原始结果从 1 开始的条目编号，按此顺序选取；不得编造。空数组不显示任何条目。", "integer"),
            "limit" to agentIntProp("最多展示 1–30 条，默认 12。"),
            "fields" to arrayProp("显示的可选字段；不填显示全部。各类型字段见对应选项。"),
        )
        fun forKind(k: String, block: () -> Unit) { if (kind == null || kind == k) block() }
        forKind("grades") {
            props["summary"] = agentEnumProp("GPA 口径：all=本次查询全部课程，selected=筛选后的课程（含被 limit 折叠的），none=隐藏汇总。fields: credit,gpa。", "all", "selected", "none")
            props["score_min"] = agentPropOf("number", "数值成绩下限，等级制成绩不匹配。")
            props["score_max"] = agentPropOf("number", "数值成绩上限。")
        }
        if (kind == null || kind in setOf("grades", "schedule", "exams", "attendance")) {
            props["courses"] = arrayProp("课程名或课程号关键词，匹配任意一项。")
        }
        if (kind == null || kind in setOf("exams", "attendance", "card", "notices")) {
            props["date_from"] = agentStrProp("起始日期 yyyy-MM-dd，包含当天。")
            props["date_to"] = agentStrProp("结束日期 yyyy-MM-dd，包含当天。")
        }
        forKind("schedule") {
            props["weekdays"] = arrayProp("星期 1–7。fields: time,location,teacher。", "integer")
        }
        forKind("exams") { props["include_past"] = agentBoolProp("是否显示已结束考试，默认 true。fields: time,location,seat。") }
        forKind("rooms") {
            props["view"] = agentEnumProp("slots=课表空闲条；status=此刻状态；both=两者。补查节次使用 get_empty_rooms。", "auto", "slots", "status", "both")
            props["sections"] = arrayProp("要求全部空闲并在空闲条中突出显示的节次 1–11。", "integer")
            props["min_free_sections"] = agentIntProp("至少连续空闲的节次数 1–11。")
            props["statuses"] = arrayProp("实时状态筛选：free/in_use/in_class/unknown；没有实时数据时不支持。fields: seats,slots,status。")
        }
        forKind("attendance") { props["statuses"] = arrayProp("normal/late/absence/leave/unknown，或对应中文；只看异常可传 late,absence。fields: date,location,status。") }
        forKind("card") {
            props["view"] = agentEnumProp("余额、收支汇总、流水或全部；汇总和流水须查询 days。fields: balance,summary,transactions。", "all", "balance", "summary", "transactions")
            props["direction"] = agentEnumProp("流水方向。", "all", "expense", "income")
        }
        forKind("files") { props["kind"] = agentEnumProp("只显示文件、目录或全部。fields: path,size。", "all", "files", "folders") }
        forKind("library") {
            props["view"] = agentEnumProp("平面图、区域空座汇总或本人预约。fields: booking,summary,plan。", "plan", "summary", "booking")
            props["seat_ids"] = arrayProp("在真实平面图中突出显示这些 seatId；其余座位与占用状态仍保留。")
        }
        forKind("map") {
            props["view"] = agentEnumProp("地点标记或按顺序连线。fields: here,caption。", "places", "route")
            props["place_names"] = arrayProp("仅从本次地图结果中选择地点名，按此顺序。")
        }
        forKind("notices") {
            props["sources"] = arrayProp("按真实来源名筛选。fields: source,date,why。标题与原文链接始终保留。")
            props["caption"] = agentStrProp("最多 120 字的总括，可留空；事实须来自查询结果。")
            props["annotations"] = buildJsonObject {
                put("type", "array"); put("description", "推荐理由与重要程度。标题、来源、日期、链接沿用查询结果；需要详情时先读原文。")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("row", agentIntProp("本快照原始编号，从 1 开始。"))
                        put("why", agentStrProp("为什么值得看，最多 80 字。"))
                        put("level", agentIntProp("3 必须处理、2 值得一看、1 顺便知道。"))
                    })
                    put("required", buildJsonArray { add("row") }); put("additionalProperties", false)
                })
            }
        }
        if (kind == null) props["view"] = agentEnumProp("按结果类型选择展示视图。",
            "auto", "slots", "status", "both", "all", "balance", "summary", "transactions", "plan", "booking", "places", "route")
        if (kind == null) props["statuses"] = arrayProp("教室：free/in_use/in_class/unknown；考勤：normal/late/absence/leave/unknown。")
        val sorts = when (kind) {
            "grades" -> arrayOf("original", "score_asc", "score_desc", "credit_desc")
            "rooms" -> arrayOf("original", "seats_desc", "free_desc", "people_asc", "name")
            "card" -> arrayOf("original", "date_asc", "date_desc", "amount_desc")
            "files" -> arrayOf("original", "name", "folders_first")
            "schedule", "exams", "attendance" -> arrayOf("original", "date_asc", "date_desc")
            "notices" -> arrayOf("original", "date_asc", "date_desc", "priority_desc")
            "library", "map" -> arrayOf("original")
            else -> arrayOf("original", "score_asc", "score_desc", "credit_desc", "date_asc", "date_desc", "seats_desc", "free_desc", "people_asc", "amount_desc", "name", "folders_first", "priority_desc")
        }
        props["sort"] = agentEnumProp("按所选真实数据排序。", *sorts)
        return buildJsonObject { put("type", "object"); put("properties", JsonObject(props)); put("additionalProperties", false) }
    }

    fun supportsResult(tool: String): Boolean = tool in kinds || agentNavSuggestions(listOf(tool)).isNotEmpty()

    fun queryDefinition(definition: JsonObject): JsonObject {
        val fn = definition["function"] as? JsonObject ?: return definition
        if (!supportsResult(fn["name"]?.jsonPrimitive?.content.orEmpty())) return definition
        val params = fn["parameters"] as? JsonObject ?: return definition
        val props = params["properties"] as? JsonObject ?: return definition
        val parameters = JsonObject(params + ("properties" to JsonObject(props - setOf("show", "display"))))
        return JsonObject(definition + ("function" to JsonObject(fn + ("parameters" to parameters))))
    }

    fun toolDefinition(ids: Set<String>) = agentTool(TOOL,
        "先查看查询结果，再用本轮 result_id 展示。数据结果显示卡片和页面入口；仅有页面入口的结果显示入口。display 可筛选已有数据。重复展示替换旧卡。",
        agentParams("result_id" to agentEnumProp("查询完成后返回的 result_id。", *ids.toTypedArray()),
            "display" to schema(), required = listOf("result_id")))

    fun options(args: String): JsonObject? {
        val root = AppJson.parseToJsonElement(args.ifBlank { "{}" }) as? JsonObject ?: error("参数须为对象")
        val value = root["display"] ?: return null
        require(value is JsonObject) { "display 须为对象" }
        return value
    }

    fun catalog(widgets: List<AgentWidget>): String = widgets.joinToString("\n") { w ->
        val labels = when (w) {
            is NoticeWidget -> w.items.map { "${it.title}｜${it.source}｜${it.date}" }
            is GradeWidget -> w.grades.map { "${it.courseName}｜${it.term}｜${it.score}" }
            is ScheduleWidget -> w.courses.map { "${it.courseName}｜周${it.dayOfWeek} ${it.startSection}-${it.endSection}节" }
            is ExamWidget -> w.exams.map { "${it.courseName}｜${it.examDate}" }
            is AttendanceWidget -> w.records.map { "${it.courseName}｜${it.date}｜${it.status.displayName}" }
            is RoomWidget -> w.rooms.map { "${it.name}｜课表空闲节次=${it.status.mapIndexedNotNull { i, s -> (i + 1).takeIf { s == 0 } }.joinToString(",")}" }
            is LiveRoomWidget -> w.rooms.map { "${it.name}｜此刻=${statusName(it.status)}｜无节次数据" }
            is CardWidget -> w.transactions.map { "${it.time}｜${it.displayMerchant}｜${it.amount}" }
            is ZyxfWidget -> w.items.map { "${it.name}｜id=${it.id}" }
            is LibraryWidget -> if (w.areas.isNotEmpty()) w.areas.map { "${it.name}｜${it.free}/${it.total}" } else w.seats.map { it.seatId }
            is CampusMapWidget -> w.places
        }
        val scope = when (w) {
            is LiveRoomWidget -> "snapshot: realtime_only; slots: unavailable; continuous_vacancy: unknown\n"
            is RoomWidget -> "snapshot: timetable; realtime: ${w.liveRooms.isNotEmpty()}\n"
            else -> ""
        }
        scope + labels.mapIndexed { i, s -> "[${i + 1}] ${s.take(160)}" }.joinToString("\n")
    }

    /** 回执描述最终卡片，不能把请求过的字段当作已展示的数据。 */
    fun receipt(widgets: List<AgentWidget>): String = ToolReply.CARD_SHOWN + widgets.joinToString("") { w ->
        when (w) {
            is LiveRoomWidget -> "\ncard_display: rooms; view=status; slots=unavailable; continuous_vacancy=unknown; rows=${minOf(w.rooms.size, w.display.limit)}; 只代表此刻状态"
            is RoomWidget -> {
                val slots = w.display.view in setOf("auto", "slots", "both") && w.display.shows("slots") && w.rooms.any { it.status.isNotEmpty() }
                val live = w.display.view in setOf("status", "both") && w.display.shows("status") && w.liveRooms.isNotEmpty()
                "\ncard_display: rooms; view=${w.display.view}; slots=${if (slots) "shown" else "not_shown"}; realtime=${if (live) "shown" else "not_shown"}; rows=${minOf(w.rooms.size, w.display.limit)}"
            }
            is NoticeWidget -> "\ncard_display: notices; rows=${minOf(w.items.size, w.display.limit)}; fields=${w.display.fields.ifEmpty { listOf("source", "date", "why") }.joinToString(",")}; original_links=clickable"
            else -> ""
        }
    }

    fun apply(widgets: List<AgentWidget>, options: JsonObject?): List<AgentWidget> {
        if (options == null) return widgets
        val o = Options(options)
        return widgets.map { w ->
            o.validate(when (w) {
                is NoticeWidget -> "notices"
                is GradeWidget -> "grades"; is ScheduleWidget -> "schedule"; is ExamWidget -> "exams"
                is RoomWidget, is LiveRoomWidget -> "rooms"; is AttendanceWidget -> "attendance"
                is CardWidget -> "card"; is ZyxfWidget -> "files"; is LibraryWidget -> "library"; is CampusMapWidget -> "map"
            })
            when (w) {
                is NoticeWidget -> {
                    val notes = options["annotations"]?.let { raw ->
                        require(raw is JsonArray) { "annotations 须为数组" }
                        raw.map { value ->
                            val note = value as? JsonObject ?: error("annotation 须为对象")
                            require(note.keys.all { it in setOf("row", "why", "level") }) { "annotation 仅支持 row/why/level" }
                            val row = note["row"]?.jsonPrimitive?.intOrNull ?: error("annotation.row 须为整数")
                            require(row in 1..w.items.size) { "annotation.row 不存在" }
                            val why = note["why"]?.let { require(it is JsonPrimitive && it.isString) { "why 须为字符串" }; it.content }.orEmpty()
                            require(why.length <= 80) { "why 最多 80 字" }
                            val level = note["level"]?.jsonPrimitive?.intOrNull ?: 1
                            require(level in 1..3) { "level 须为 1–3" }
                            row to w.items[row - 1].copy(why = why, level = level)
                        }.also { require(it.map { pair -> pair.first }.distinct().size == it.size) { "annotations 编号重复" } }.toMap()
                    }.orEmpty()
                    val annotated = w.items.mapIndexed { i, item -> notes[i + 1] ?: item }
                    val sources = o.strings("sources")
                    var rows = o.select(annotated).filter { o.date(it.date) && (sources == null || it.source in sources) }
                    rows = when (o.sort) {
                        "date_asc" -> rows.sortedBy { it.date }; "date_desc" -> rows.sortedByDescending { it.date }
                        "priority_desc" -> rows.sortedByDescending { it.level }; else -> rows
                    }
                    val caption = o.string("caption") ?: w.caption
                    require(caption.length <= 120) { "caption 最多 120 字" }
                    w.copy(items = rows, caption = caption, display = o.display(rows.size).let { if ("limit" in options) it else it.copy(limit = 6) })
                }
                is GradeWidget -> {
                    var selected = o.select(w.grades).filter { o.course(it.courseName) &&
                        o.numberRange(it.score.toDoubleOrNull(), "score_min", "score_max") }
                    selected = when (o.sort) {
                        "score_asc" -> selected.sortedBy { it.score.toDoubleOrNull() ?: Double.POSITIVE_INFINITY }
                        "score_desc" -> selected.sortedByDescending { it.score.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY }
                        "credit_desc" -> selected.sortedByDescending { it.coursePoint }
                        else -> selected
                    }
                    val summary = o.enum("summary", setOf("all", "selected", "none"), "all")
                    val scope = if (summary == "selected") selected else w.grades
                    val (sum, credit) = ScoreCalculator.accumulate(scope.asSequence().map {
                        ScoreCalculator.calculateOneCourseContribution(it.score, it.coursePoint, it.gpa)
                    })
                    w.copy(grades = selected, gpa = if (summary != "none" && credit > 0) sum / credit else null,
                        totalPoints = if (summary == "none") 0.0 else credit,
                        display = o.display(selected.size, summary = summary))
                }
                is ScheduleWidget -> {
                    val weekdays = o.ints("weekdays", 1..7)
                    var rows = o.select(w.courses).filter { o.course(it.courseName, it.courseCode) && (weekdays == null || it.dayOfWeek in weekdays) }
                    if (o.sort.startsWith("date_")) rows = rows.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection })).let { if (o.sort == "date_desc") it.reversed() else it }
                    w.copy(courses = rows, display = o.display(rows.size))
                }
                is ExamWidget -> {
                    var rows = o.select(w.exams).filter { o.course(it.courseName, it.courseCode) && o.date(it.examDate) &&
                        (o.bool("include_past", true) || it.examDate.take(10) >= todayOnCampus().toString()) }
                    if (o.sort.startsWith("date_")) rows = rows.sortedBy { it.examDate + it.examTime }.let { if (o.sort == "date_desc") it.reversed() else it }
                    w.copy(exams = rows, display = o.display(rows.size))
                }
                is AttendanceWidget -> {
                    val statuses = o.strings("statuses")
                    var rows = o.select(w.records).filter { o.course(it.courseName, it.courseCode) && o.date(it.date) &&
                        (statuses == null || statuses.any { s -> s.equals(it.status.name, true) || s == it.status.displayName }) }
                    if (o.sort.startsWith("date_")) rows = rows.sortedBy { it.date + it.startTime.toString().padStart(2, '0') }.let { if (o.sort == "date_desc") it.reversed() else it }
                    w.copy(records = rows, display = o.display(rows.size))
                }
                is RoomWidget -> {
                    val sections = o.ints("sections", 1..11) ?: w.display.sections
                    val statuses = o.strings("statuses")
                    require(statuses == null || w.liveRooms.isNotEmpty()) { "实时状态筛选请查询 view=status/both" }
                    require(o.sort != "people_asc" || w.liveRooms.isNotEmpty()) { "people_asc 排序请查询 view=both" }
                    val minimum = o.int("min_free_sections", 1..11, 1)
                    var rows = o.select(w.rooms).filter { r -> sections.all { r.status.getOrNull(it - 1) == 0 } &&
                        longestFree(r.status) >= minimum && (statuses == null || w.liveRooms.firstOrNull { it.name == r.name }?.let { statusName(it.status) in statuses } == true) }
                    rows = when (o.sort) {
                        "seats_desc" -> rows.sortedByDescending { it.size }
                        "free_desc" -> rows.sortedByDescending { longestFree(it.status) }
                        "people_asc" -> rows.sortedBy { r -> w.liveRooms.firstOrNull { it.name == r.name }?.people ?: Int.MAX_VALUE }
                        "name" -> rows.sortedBy { it.name }; else -> rows
                    }
                    val view = o.enum("view", setOf("auto", "slots", "status", "both"), w.display.view.takeIf { it in setOf("slots", "status", "both") } ?: "slots")
                    // 没有实时数据时始终标明课表语义，不把课表空闲伪装成此刻无人。
                    w.copy(rooms = rows, display = o.display(rows.size, view = if (view == "status" && w.liveRooms.isEmpty()) "slots" else view, sections = sections))
                }
                is LiveRoomWidget -> {
                    require(o.ints("sections", 1..11) == null && "min_free_sections" !in options) { "节次数据请查询 view=slots/both" }
                    require(o.string("view") !in setOf("slots", "both")) { "节次数据请查询 view=slots/both" }
                    require(o.strings("fields")?.contains("slots") != true) { "slots 字段请查询 view=slots/both" }
                    require(o.sort != "free_desc") { "free_desc 排序请查询 view=slots/both" }
                    val statuses = o.strings("statuses")
                    var rows = o.select(w.rooms).filter { statuses == null || statusName(it.status) in statuses }
                    rows = when (o.sort) { "seats_desc" -> rows.sortedByDescending { it.seats }; "people_asc" -> rows.sortedBy { it.people }; "name" -> rows.sortedBy { it.name }; else -> rows }
                    w.copy(rooms = rows, display = o.display(rows.size, view = "status"))
                }
                is CardWidget -> {
                    val view = o.enum("view", setOf("all", "balance", "summary", "transactions"), "all")
                    require(view !in setOf("summary", "transactions") || w.transactionsLoaded) { "尚未查询流水，请调用 get_card_info 并指定 days" }
                    val direction = o.enum("direction", setOf("all", "expense", "income"), "all")
                    var rows = o.select(w.transactions).filter { o.date(it.time) && when (direction) { "expense" -> it.amount < 0; "income" -> it.amount > 0; else -> true } }
                    rows = when (o.sort) { "date_asc" -> rows.sortedBy { it.time }; "date_desc" -> rows.sortedByDescending { it.time }; "amount_desc" -> rows.sortedByDescending { kotlin.math.abs(it.amount) }; else -> rows }
                    w.copy(transactions = rows, spend = rows.filter { it.amount < 0 }.sumOf { -it.amount }, income = rows.filter { it.amount > 0 }.sumOf { it.amount }, display = o.display(rows.size, view = view))
                }
                is ZyxfWidget -> {
                    val kind = o.enum("kind", setOf("all", "files", "folders"), "all")
                    var rows = o.select(w.items).filter { kind == "all" || it.isFolder == (kind == "folders") }
                    rows = when (o.sort) { "name" -> rows.sortedBy { it.name }; "folders_first" -> rows.sortedByDescending { it.isFolder }; else -> rows }
                    w.copy(items = rows, display = o.display(rows.size))
                }
                is LibraryWidget -> {
                    val ids = o.strings("seat_ids") ?: if (w.areas.isEmpty() && "rows" in options) o.select(w.seats).map { it.seatId } else emptyList()
                    require(ids.all { id -> w.seats.any { it.seatId == id } }) { "seat_ids 包含不存在的座位" }
                    val view = o.enum("view", setOf("plan", "summary", "booking"), w.display.view.takeIf { it in setOf("plan", "summary", "booking") } ?: "plan")
                    require(view != "booking" || w.booking != null) { "本次查询未取得预约状态" }
                    val areas = if (w.areas.isEmpty()) w.areas else o.select(w.areas)
                    w.copy(areas = areas, highlightedSeats = ids, display = o.display(w.seats.size, view = view))
                }
                is CampusMapWidget -> {
                    val names = o.strings("place_names")
                    require(names == null || names.all { it in w.places }) { "place_names 只能选择本次结果已有地点" }
                    val places = names ?: o.select(w.places)
                    val route = o.enum("view", setOf("places", "route"), if (w.route) "route" else "places") == "route"
                    w.copy(places = places.take(o.limit), route = route,
                        caption = if (places != w.places || !o.shows("caption")) "" else w.caption,
                        here = w.here.takeIf { o.shows("here") }, display = o.display(places.size))
                }
            }
        }
    }

    internal fun longestFree(slots: List<Int>): Int {
        var run = 0; var longest = 0
        slots.forEach { run = if (it == 0) run + 1 else 0; longest = maxOf(longest, run) }
        return longest
    }

    private fun statusName(status: Int) = when (status) { 2 -> "free"; 1 -> "in_use"; 3 -> "in_class"; else -> "unknown" }

    private class Options(private val json: JsonObject) {
        val limit = int("limit", 1..30, 12)
        val sort = string("sort") ?: "original"
        init {
            strings("fields")?.let { require(it.isNotEmpty()) { "fields 须至少包含一个字段；不传表示全部" } }
        }
        fun validate(kind: String) {
            val properties = schema(kind)["properties"]!!.jsonObject
            require(json.keys.all { it in properties }) { "该卡片不支持参数 ${(json.keys - properties.keys).joinToString()}" }
            val sorts = properties["sort"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
            require(sort in sorts) { "该卡片排序可选 ${sorts.joinToString()}" }
            val allowedFields = when (kind) {
                "grades" -> setOf("credit", "gpa")
                "notices" -> setOf("source", "date", "why")
                "schedule" -> setOf("time", "location", "teacher")
                "exams" -> setOf("time", "location", "seat")
                "rooms" -> setOf("seats", "slots", "status")
                "attendance" -> setOf("date", "location", "status")
                "card" -> setOf("balance", "summary", "transactions")
                "files" -> setOf("path", "size")
                "library" -> setOf("booking", "summary", "plan")
                else -> setOf("here", "caption")
            }
            strings("fields")?.let { require(it.all { field -> field in allowedFields }) { "该卡片字段可选 ${allowedFields.joinToString()}" } }
            if ("date_from" in json || "date_to" in json) date("")
            strings("statuses")?.let { statuses ->
                val allowed = if (kind == "rooms") setOf("free", "in_use", "in_class", "unknown") else
                    WaterType.entries.flatMap { listOf(it.name.lowercase(), it.displayName) }.toSet()
                require(statuses.all { it.lowercase() in allowed }) { "状态可选 ${allowed.joinToString()}" }
            }
        }
        fun string(key: String): String? = json[key]?.let { require(it is JsonPrimitive && it.isString) { "$key 须为字符串" }; it.content }
        fun bool(key: String, default: Boolean): Boolean = json[key]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: error("$key 须为布尔值") } ?: default
        fun int(key: String, range: IntRange, default: Int): Int = json[key]?.let { (it as? JsonPrimitive)?.intOrNull?.also { n -> require(n in range) { "$key 超出范围 $range" } } ?: error("$key 须为整数") } ?: default
        fun strings(key: String): List<String>? = json[key]?.let { value -> require(value is JsonArray) { "$key 须为数组" }; value.map { require(it is JsonPrimitive && it.isString) { "$key 须为字符串数组" }; it.content }.distinct() }
        fun ints(key: String, range: IntRange): List<Int>? = json[key]?.let { value -> require(value is JsonArray) { "$key 须为数组" }; value.map { (it as? JsonPrimitive)?.intOrNull?.also { n -> require(n in range) { "$key 超出范围 $range" } } ?: error("$key 须为整数数组") }.distinct() }
        fun enum(key: String, values: Set<String>, default: String): String = (string(key) ?: default).also { require(it in values) { "$key 可选 ${values.joinToString()}" } }
        fun <T> select(rows: List<T>): List<T> = ints("rows", 1..maxOf(1, rows.size))?.map { require(it <= rows.size) { "条目编号不存在" }; rows[it - 1] } ?: rows
        fun course(vararg names: String): Boolean = strings("courses")?.let { terms -> terms.any { term -> names.any { it.contains(term, ignoreCase = true) } } } ?: true
        fun shows(field: String) = strings("fields")?.let { field in it } ?: true
        fun date(raw: String): Boolean {
            val from = string("date_from")?.let { LocalDate.parse(it) }
            val to = string("date_to")?.let { LocalDate.parse(it) }
            require(from == null || to == null || from <= to) { "起始日期晚于结束日期" }
            if (from == null && to == null) return true
            val d = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() ?: return false
            return (from == null || d >= from) && (to == null || d <= to)
        }
        fun numberRange(n: Double?, min: String, max: String): Boolean {
            fun number(k: String) = json[k]?.let { (it as? JsonPrimitive)?.doubleOrNull?.takeIf { v -> v.isFinite() } ?: error("$k 须为有限数值") }
            val lo = number(min); val hi = number(max)
            require(lo == null || hi == null || lo <= hi) { "下限大于上限" }
            return (lo == null && hi == null) || (n != null && (lo == null || n >= lo) && (hi == null || n <= hi))
        }
        fun display(count: Int, view: String = "auto", summary: String = "all", sections: List<Int> = emptyList()) =
            WidgetDisplay(view, strings("fields").orEmpty(), sections, summary, count, limit)
    }
}
