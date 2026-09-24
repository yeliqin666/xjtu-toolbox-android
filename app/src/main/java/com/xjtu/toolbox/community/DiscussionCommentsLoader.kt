package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/feature/discussions/DiscussionCommentsLoader.kt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class DiscussionCommentsState(
    val items: List<GithubDiscussionComment> = emptyList(),
    val cursor: String? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val failed: Boolean = false
)

/** Main-thread owner of a comment connection, including mutations during an in-flight read. */
internal class DiscussionCommentsLoader(
    private val scope: CoroutineScope,
    private val load: suspend (String?) -> Result<GithubDiscussionComments>
) {
    private val mutable = MutableStateFlow(DiscussionCommentsState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var request = 0L
    private var revision = 0L
    private val edits = mutableMapOf<String, Pair<Long, GithubDiscussionComment?>>()

    fun edited(comment: GithubDiscussionComment) {
        edits[comment.id] = ++revision to comment
        mutable.update { it.copy(items = it.items.map { old -> if (old.id == comment.id) comment else old }) }
    }

    fun deleted(id: String) {
        edits[id] = ++revision to null
        mutable.update { it.copy(items = it.items.filterNot { old -> old.id == id }) }
    }

    fun fetch(reset: Boolean) {
        val before = mutable.value
        if (!reset && (before.loading || before.cursor == null)) return
        job?.cancel()
        val ticket = ++request
        val startedAt = revision
        val cursor = if (reset) null else before.cursor
        mutable.update { it.copy(loading = true, failed = false) }
        job = scope.launch {
            try {
                val result = load(cursor)
                if (!isActive || ticket != request) return@launch
                result.fold(onSuccess = { page ->
                    val fresh = page.items.mapNotNull { comment ->
                        val change = edits[comment.id]
                        if (change != null && change.first > startedAt) change.second else comment
                    }
                    mutable.update {
                        it.copy(items = (if (reset) fresh else it.items + fresh).distinctBy { item -> item.id },
                            cursor = page.nextCursor?.takeUnless { next -> next == cursor }, loaded = true)
                    }
                    edits.entries.removeAll { it.value.first <= startedAt }
                }, onFailure = { mutable.update { it.copy(failed = true) } })
            } finally {
                if (ticket == request) mutable.update { it.copy(loading = false) }
            }
        }
    }
}
