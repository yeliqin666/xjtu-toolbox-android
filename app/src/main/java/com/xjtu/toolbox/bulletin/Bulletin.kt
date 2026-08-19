package com.xjtu.toolbox.bulletin

import com.google.gson.JsonParser
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class BulletinLevel {
    INFO, WARN, UPDATE, CRITICAL, FORCE_UPDATE;

    val rank: Int
        get() = when (this) {
            FORCE_UPDATE -> 5
            CRITICAL -> 4
            UPDATE -> 3
            WARN -> 2
            INFO -> 1
        }

    companion object {
        fun parse(raw: String?): BulletinLevel = when (raw?.trim()?.lowercase()) {
            "warn", "warning" -> WARN
            "update", "upgrade" -> UPDATE
            "critical" -> CRITICAL
            "force_update", "force-update", "forceupdate" -> FORCE_UPDATE
            else -> INFO
        }
    }
}

data class Bulletin(
    val id: String,
    val level: BulletinLevel,
    val title: String,
    val body: String,
    val url: String? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val minVersion: String? = null,
    val maxVersion: String? = null,
    /** 当前版本 >= 此值则不展示。用来发「请升到 4.7.3」时让已更新的人自动消失。 */
    val targetVersion: String? = null,
    /** 当前版本 < 此值时，把 update 升级成 force_update。 */
    val forceBelow: String? = null,
    val mustAck: Boolean,
    val block: Boolean,
    val synthesized: Boolean = false,
)

object BulletinRules {
    private val beijing = ZoneId.of("Asia/Shanghai")

    fun parsePayload(json: String): List<Bulletin> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = runCatching { JsonParser.parseString(trimmed) }.getOrNull() ?: return emptyList()
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.has("bulletins") ->
                root.asJsonObject.getAsJsonArray("bulletins")
            else -> return emptyList()
        }
        return array.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val obj = el.asJsonObject
            val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val title = obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            val level = BulletinLevel.parse(obj.get("level")?.takeIf { it.isJsonPrimitive }?.asString)
            val mustAck = when {
                obj.has("mustAck") && obj.get("mustAck").isJsonPrimitive -> obj.get("mustAck").asBoolean
                else -> level == BulletinLevel.CRITICAL
            }
            Bulletin(
                id = id,
                level = level,
                title = title,
                body = obj.get("body")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                url = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
                startsAt = parseInstant(obj.get("startsAt")?.takeIf { it.isJsonPrimitive }?.asString),
                endsAt = parseInstant(obj.get("endsAt")?.takeIf { it.isJsonPrimitive }?.asString),
                minVersion = obj.get("minVersion")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
                maxVersion = obj.get("maxVersion")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
                targetVersion = obj.get("targetVersion")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
                forceBelow = obj.get("forceBelow")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null },
                mustAck = mustAck,
                block = obj.get("block")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            )
        }
    }

    fun isActive(bulletin: Bulletin, now: Instant, currentVersion: String): Boolean {
        if (bulletin.startsAt != null && now.isBefore(bulletin.startsAt)) return false
        if (bulletin.endsAt != null && !now.isBefore(bulletin.endsAt)) return false
        val min = bulletin.minVersion
        if (!min.isNullOrBlank() && compareVersions(currentVersion, min) < 0) return false
        val max = bulletin.maxVersion
        if (!max.isNullOrBlank() && compareVersions(currentVersion, max) > 0) return false
        val target = bulletin.targetVersion
        if (!target.isNullOrBlank() && compareVersions(currentVersion, target) >= 0) return false
        return true
    }

    fun resolveForVersion(bulletin: Bulletin, currentVersion: String): Bulletin {
        val floor = bulletin.forceBelow
        if (!floor.isNullOrBlank() && compareVersions(currentVersion, floor) < 0) {
            return bulletin.copy(level = BulletinLevel.FORCE_UPDATE)
        }
        return bulletin
    }

    fun isHidden(
        bulletin: Bulletin,
        dismissedIds: Set<String>,
        ackedIds: Set<String>,
        snoozedIds: Set<String>,
    ): Boolean {
        if (bulletin.id in snoozedIds) return true
        if (bulletin.id in dismissedIds) return true
        if (bulletin.level == BulletinLevel.CRITICAL && bulletin.mustAck && bulletin.id in ackedIds) {
            return true
        }
        if (bulletin.level == BulletinLevel.CRITICAL && !bulletin.mustAck && bulletin.id in ackedIds) {
            return true
        }
        return false
    }

    fun visible(
        items: List<Bulletin>,
        now: Instant,
        currentVersion: String,
        dismissedIds: Set<String>,
        ackedIds: Set<String>,
        snoozedIds: Set<String>,
    ): List<Bulletin> {
        return items
            .filter { isActive(it, now, currentVersion) }
            .filterNot { isHidden(it, dismissedIds, ackedIds, snoozedIds) }
            .map { resolveForVersion(it, currentVersion) }
            .sortedWith(
                compareByDescending<Bulletin> { it.level.rank }
                    .thenByDescending { it.id },
            )
    }

    fun pick(
        items: List<Bulletin>,
        now: Instant,
        currentVersion: String,
        dismissedIds: Set<String>,
        ackedIds: Set<String>,
        snoozedIds: Set<String>,
    ): Bulletin? = visible(
        items,
        now,
        currentVersion,
        dismissedIds,
        ackedIds,
        snoozedIds,
    ).firstOrNull()

    fun shouldShowLaunchDialog(bulletin: Bulletin): Boolean =
        bulletin.block ||
            bulletin.level == BulletinLevel.CRITICAL ||
            bulletin.level == BulletinLevel.FORCE_UPDATE

    /** 冷启动只弹一条：已按等级排好的列表里，取第一条需要弹窗的。 */
    fun pickLaunchDialog(items: List<Bulletin>): Bulletin? =
        items.firstOrNull { !it.synthesized && shouldShowLaunchDialog(it) }

    fun parseInstant(raw: String?): Instant? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
        runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()?.let { return it }
        runCatching {
            LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(beijing).toInstant()
        }.getOrNull()?.let { return it }
        runCatching {
            LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(beijing).toInstant()
        }.getOrNull()?.let { return it }
        return null
    }

    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = versionParts(v1)
        val parts2 = versionParts(v2)
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    /**
     * 兼容旧写法：`4.72` / `4.71` / `4.61` 分别当成 `4.7.2` / `4.7.1` / `4.6.1`。
     * 已经带第三段的（如 `4.7.3`、`4.5.3`）原样比较。
     */
    private fun versionParts(raw: String): List<Int> {
        val parts = raw.split(".", "-").mapNotNull { it.toIntOrNull() }
        if (parts.size == 2 && parts[1] >= 10) {
            return listOf(parts[0], parts[1] / 10, parts[1] % 10)
        }
        return parts
    }

    fun syntheticUpdate(version: String, channel: String): Bulletin = Bulletin(
        id = "auto_${channel}_$version",
        level = BulletinLevel.UPDATE,
        title = "发现新版本 v$version",
        body = "可在首页这条提示或设置里下载更新。",
        mustAck = false,
        block = false,
        synthesized = true,
    )
}
