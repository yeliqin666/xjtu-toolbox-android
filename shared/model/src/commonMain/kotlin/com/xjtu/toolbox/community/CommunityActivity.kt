package com.xjtu.toolbox.community

/** 社区里要告诉用户的两件事（#236）。表情回应不算。 */
enum class CommunityActivityKind { REPLY, MENTION }

/**
 * 别人的一条回复或一次 @。
 *
 * [id] 是 GitHub 节点 id，去重靠它；帖子正文里的 @ 用帖子 id。[title] 是所在帖子的标题。
 * [number] 是所在帖子的编号（`.../discussions/42` 里的 42），用来在 App 内直达那一帖；
 * 取不到时为 0，调用方退回网页。
 */
data class CommunityActivity(
    val id: String, val kind: CommunityActivityKind, val author: String,
    val title: String, val body: String, val url: String, val createdAt: String,
    val number: Int = 0,
)

/** 帖子地址里的编号：`https://github.com/o/n/discussions/42` → 42。认不出返回 null。 */
fun discussionNumberOf(url: String): Int? =
    Regex("""/discussions/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

/**
 * 评论地址里的锚点：`.../discussions/42#discussioncomment-123` → `discussioncomment-123`。
 *
 * 只认 GitHub 评论锚点这一种写法；帖子本身（没有 `#`）返回 null。
 */
fun discussionCommentAnchor(url: String): String? =
    Regex("""#(discussioncomment-\d+)""").find(url)?.groupValues?.get(1)

/** [fresh] 要推的新条目；[seen] 每项这一轮看到的全部 id，交给存储记为已见；[baselined] 已建过基线的项。 */
data class CommunityDelta(
    val fresh: List<CommunityActivity>,
    val seen: Map<CommunityActivityKind, List<String>>,
    val baselined: Set<CommunityActivityKind>,
)

/**
 * 与 [com.xjtu.toolbox.notification.noticeDelta] 同一口径：两项开关各自建基线、各自去重。
 * 刚打开的那项只把眼下看得到的记为已见，不推历史；关着的项不推也不动它的记录。
 * 同一条评论既回复了我的帖子又 @ 了我，只推一次。
 */
fun communityDelta(
    items: List<CommunityActivity>,
    enabled: Set<CommunityActivityKind>,
    seen: Map<CommunityActivityKind, Collection<String>>,
    baselined: Set<CommunityActivityKind>,
): CommunityDelta {
    val fresh = items
        .filter { it.kind in enabled && it.kind in baselined && it.id !in seen[it.kind].orEmpty() }
        .distinctBy { it.id }
        .sortedByDescending { it.createdAt }
    val visible = enabled.associateWith { kind -> items.filter { it.kind == kind }.map { it.id }.distinct() }
    return CommunityDelta(fresh, visible, baselined + enabled)
}

/** 正文里是否 @ 了 [login]：前后不能紧挨用户名字符，`@abc` 不算 @ 了 `ab`。GitHub 用户名不分大小写。 */
fun mentions(body: String, login: String): Boolean =
    Regex("""(^|[^\w-])@${Regex.escape(login)}(?![\w-])""", RegexOption.IGNORE_CASE).containsMatchIn(body)
