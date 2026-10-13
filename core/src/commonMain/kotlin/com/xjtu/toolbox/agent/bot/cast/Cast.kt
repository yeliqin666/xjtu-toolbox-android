package com.xjtu.toolbox.agent.bot.cast

/** 屁岱的角色名册（不含经典屁岱：那只走 BotEngine）。顺序即设置页里的顺序。 */
val CAST: List<CastCharacter> = listOf(Sleepy, Muyu, Puff, Cat, Pigeon)

private val CAST_BY_ID = CAST.associateBy { it.id }

fun castById(id: String?): CastCharacter? = CAST_BY_ID[id]
