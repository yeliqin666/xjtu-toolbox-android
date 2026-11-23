package com.xjtu.toolbox.termreport

import android.content.Context
import com.xjtu.toolbox.platform.keyValueStore
import com.xjtu.toolbox.update.AppUpdater
import com.xjtu.toolbox.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Instant

/**
 * 项目的公开数据（由 `.github/scripts/project_story.py` 生成）：提交、Star、issue、讨论区、发版、参与的人。
 * 时间都是北京时间。
 */
@Serializable
data class ProjectStory(
    /** 数据截至哪天。 */
    val date: String,
    /** 第一次提交的时间（"2026-02-18T23:10"）。 */
    val born: String,
    val firstCommit: String = "",
    val commits: Int = 0,
    val commitDays: Int = 0,
    /** 凌晨 0–5 点的提交数。 */
    val lateCommits: Int = 0,
    /** 每颗星的日期，按先后排。 */
    val stars: List<String> = emptyList(),
    val releases: List<Release> = emptyList(),
    val issues: Int = 0,
    val issuesClosed: Int = 0,
    val firstIssue: IssueRef? = null,
    val hottestIssue: IssueRef? = null,
    val discussions: Int = 0,
    /** 写代码的人（按提交数）、提 issue 和在 issue 里回复的人、在讨论区发帖回帖的人。 */
    val coders: List<Person> = emptyList(),
    val askers: List<Person> = emptyList(),
    val talkers: List<Person> = emptyList(),
) {
    @Serializable
    data class Release(val v: String, val d: String)

    @Serializable
    data class IssueRef(val n: Int, val title: String, val date: String = "", val closeMinutes: Int? = null, val comments: Int = 0)

    @Serializable
    data class Person(val name: String, val n: Int)

    val bornDate get() = LocalDate.parse(born.take(10))
    val people get() = (coders + askers + talkers).map { it.name.lowercase() }.distinct().size
}

private val storyStore get() = keyValueStore("project_story")

/** 线上那份发在 OSS 上，每次发正式版时由 CI 更新；国内直连快，不用 GitHub 账号。 */
private const val STORY_URL = "${AppUpdater.OSS_BASE_URL}/project_story.json"

/** 线上、上次缓存、源码快照三份里取最新的；拉线上最多等几秒，拉不到不影响生成。 */
suspend fun loadProjectStory(): ProjectStory? = withContext(Dispatchers.IO) {
    fun parse(text: String?) = text?.let { runCatching { AppJson.decodeFromString<ProjectStory>(it) }.getOrNull() }
    val online = runCatching {
        val conn = URL(STORY_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
    parse(online)?.let { storyStore.putString("json", online!!) }
    listOfNotNull(parse(online), parse(storyStore.getString("json")), parse(PROJECT_STORY_SNAPSHOT)).maxByOrNull { it.date }
}

/** 本机第一次装上的日期。 */
fun installDate(context: Context): LocalDate? = runCatching {
    val t = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    Instant.fromEpochMilliseconds(t).toLocalDateTime(com.xjtu.toolbox.util.CampusTimeZone).date
}.getOrNull()
