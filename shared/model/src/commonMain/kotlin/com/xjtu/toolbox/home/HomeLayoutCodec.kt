package com.xjtu.toolbox.home

import kotlin.math.roundToInt

/**
 * [HomeLayout] 的落盘格式。
 *
 * 存成一个字符串而不是 JSON：这份数据是「用户手动摆过没有」的整页快照，
 * 只用 `KeyValueStore` 的字符串档，不想为它引入序列化器、也不想让旧版本
 * 读到一半解析失败就崩。格式故意做成一条记录一行、字段用 `|` 分隔，
 * 出错的行直接跳过，最坏情况是回到默认布局。
 *
 * ```
 * v1
 * g|<组 id>|<标题色>|<组标题>
 * t|<组 id>|<块 id>|<路由 id>|<x>|<y>|<w>|<h>
 * ```
 *
 * 坐标与尺寸按「千分之一格」存整数：`Float.toString()` 在 JVM / wasmJs 上
 * 的输出格式并不一致（有的端 `1.0f.toString()` 是 `1`），存整数才能保证
 * 一次落盘在任何端读回来都是同一个数。
 */
object HomeLayoutCodec {

    private const val VERSION = "v1"
    private const val GROUP_TAG = "g"
    private const val TILE_TAG = "t"
    private const val FIELD = '|'
    private const val SCALE = 1000f

    fun encode(layout: HomeLayout): String = buildString {
        append(VERSION).append('\n')
        layout.groups.forEach { group ->
            append(GROUP_TAG).append(FIELD)
            append(escape(group.id)).append(FIELD)
            append(escape(group.colorHex)).append(FIELD)
            append(escape(group.title)).append('\n')
            group.tiles.forEach { tile ->
                append(TILE_TAG).append(FIELD)
                append(escape(group.id)).append(FIELD)
                append(escape(tile.id)).append(FIELD)
                append(escape(tile.routeId)).append(FIELD)
                append(milli(tile.x)).append(FIELD)
                append(milli(tile.y)).append(FIELD)
                append(milli(tile.w)).append(FIELD)
                append(milli(tile.h)).append('\n')
            }
        }
    }

    /** 解不出来（旧格式、写坏了、空串）返回 null，调用方回落到默认布局。 */
    fun decode(text: String?): HomeLayout? {
        if (text.isNullOrBlank()) return null
        val lines = text.split('\n').filter { it.isNotBlank() }
        if (lines.firstOrNull() != VERSION) return null

        val groups = mutableListOf<HomeGroup>()
        val tiles = mutableMapOf<String, MutableList<HomeTile>>()

        lines.drop(1).forEach { line ->
            val parts = line.split(FIELD)
            when (parts.firstOrNull()) {
                GROUP_TAG -> {
                    val id = if (parts.size >= 4) unescape(parts[1]) else ""
                    if (id.isNotBlank()) {
                        groups += HomeGroup(
                            id = id,
                            colorHex = unescape(parts[2]),
                            title = unescape(parts[3]),
                        )
                        tiles.getOrPut(id) { mutableListOf() }
                    }
                }
                TILE_TAG -> {
                    if (parts.size < 8) return@forEach
                    val groupId = unescape(parts[1])
                    val list = tiles[groupId] ?: return@forEach
                    list += HomeTile(
                        id = unescape(parts[2]),
                        routeId = unescape(parts[3]),
                        x = cells(parts[4]),
                        y = cells(parts[5]),
                        w = cells(parts[6]),
                        h = cells(parts[7]),
                    )
                }
            }
        }

        if (groups.isEmpty()) return null
        // 块的 id 是全页的身份标识，旧版本可能落过撞号的快照（尾号按块数生成，删了再加就撞）。
        // 读盘时把重复的 id 重编成没占用的——丢块会弄丢用户摆好的入口，重编只是换身份。
        val seen = mutableSetOf<String>()
        return HomeLayout(
            groups = groups.map { group ->
                group.copy(
                    tiles = tiles[group.id].orEmpty().map { tile ->
                        if (seen.add(tile.id)) return@map tile
                        var serial = 2
                        var renamed = "${tile.id}#dup$serial"
                        while (!seen.add(renamed)) {
                            serial++
                            renamed = "${tile.id}#dup$serial"
                        }
                        tile.copy(id = renamed)
                    },
                )
            },
        )
    }

    private fun milli(value: Float): String = (value * SCALE).roundToInt().toString()

    private fun cells(value: String): Float = (value.toIntOrNull() ?: 0) / SCALE

    /** 组名是用户自己打的，可能带 `|` 或换行，落盘前要转义。 */
    private fun escape(text: String): String = buildString {
        text.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '|' -> append("\\p")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    private fun unescape(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'p' -> append('|')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    '\\' -> append('\\')
                    else -> append(text[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
