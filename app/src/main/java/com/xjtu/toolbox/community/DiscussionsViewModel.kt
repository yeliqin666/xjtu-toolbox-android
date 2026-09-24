package com.xjtu.toolbox.community

// 改编自 JoyinJoester/Etoile（GPL-3.0）：github/feature/discussions/DiscussionsViewModel.kt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DiscussionsUiState(
    val items: List<GithubDiscussion> = emptyList(),
    val categories: List<GithubDiscussionCategory> = emptyList(),
    val repositoryId: String? = null,
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val loadError: Boolean = false,
    val categoriesError: Boolean = false,
    val submitting: Boolean = false,
    val submitError: Boolean = false,
    val title: String = "",
    val body: String = "",
    val categoryId: String? = null,
    /** 列表按哪个分类筛选，null 为全部。 */
    val filter: String? = null,
    /** 刚发布失败时 GitHub 给的原因（例如该分类只有维护者能发帖）。 */
    val submitMessage: String? = null,
    val composing: Boolean = false,
    val created: GithubDiscussion? = null
)

class DiscussionsViewModel(
    private val owner: String,
    private val name: String,
    private val repository: GithubDiscussionsRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    accountId: Long? = null
) : ViewModel() {
    private val draftScope = "$accountId:$owner/$name"
    private val mutable = MutableStateFlow(restoreDraft())
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var categoriesJob: Job? = null
    private var createJob: Job? = null

    fun cancelPendingRequests(clearAccountData: Boolean = true) {
        loadJob?.cancel(); categoriesJob?.cancel(); createJob?.cancel()
        if (clearAccountData) {
            mutable.value = DiscussionsUiState()
            saveDraft()
        }
        else mutable.update { it.copy(loading = false, submitting = false) }
    }

    init { load(); loadCategories() }

    private fun restoreDraft(): DiscussionsUiState {
        if (savedState.get<String>("discussion.scope") != draftScope) {
            savedState["discussion.draft"] = arrayListOf<String>()
            savedState["discussion.composing"] = false
            savedState["discussion.scope"] = draftScope
            return DiscussionsUiState()
        }
        val draft = savedState.get<ArrayList<String>>("discussion.draft").orEmpty()
        return DiscussionsUiState(
            title = draft.getOrNull(0).orEmpty().take(256),
            body = draft.getOrNull(1).orEmpty().take(65536),
            categoryId = draft.getOrNull(2)?.takeIf(String::isNotEmpty),
            composing = savedState["discussion.composing"] ?: false
        )
    }

    private fun saveDraft() {
        val current = mutable.value
        savedState["discussion.scope"] = draftScope
        savedState["discussion.draft"] = arrayListOf(current.title, current.body, current.categoryId.orEmpty())
        savedState["discussion.composing"] = current.composing
    }

    fun setComposing(value: Boolean) {
        if (mutable.value.submitting) return
        mutable.update { it.copy(composing = value) }
        saveDraft()
    }

    fun load(more: Boolean = false) {
        val current = mutable.value
        if (current.loading || (more && current.nextCursor == null)) return
        val cursor = if (more) current.nextCursor else null
        mutable.update { it.copy(loading = true, loadError = false) }
        loadJob = viewModelScope.launch {
            val result = repository.list(owner, name, cursor, current.filter)
            if (!isActive) return@launch
            result.fold(onSuccess = { page ->
                mutable.update { it.copy(loading = false, repositoryId = page.repositoryId,
                    items = (if (more) it.items + page.items else page.items).distinctBy(GithubDiscussion::id),
                    nextCursor = page.nextCursor?.takeUnless { next -> next == cursor }) }
            }, onFailure = { mutable.update { it.copy(loading = false, loadError = true) } })
        }
    }

    fun selectFilter(categoryId: String?) {
        if (mutable.value.filter == categoryId) return
        loadJob?.cancel()
        mutable.update { it.copy(filter = categoryId, items = emptyList(), nextCursor = null, loading = false) }
        load()
    }

    /** 帖子被删了：从列表里拿掉。 */
    fun remove(id: String) {
        mutable.update { state -> state.copy(items = state.items.filterNot { it.id == id }) }
    }

    /** 详情页里点赞、回复、编辑后，把最新的帖子写回列表。 */
    fun replace(discussion: GithubDiscussion) {
        mutable.update { state -> state.copy(items = state.items.map { if (it.id == discussion.id) discussion else it }) }
    }

    fun loadCategories() {
        if (categoriesJob?.isActive == true) return
        categoriesJob = viewModelScope.launch {
            val result = repository.categories(owner, name)
            if (!isActive) return@launch
            result.fold(onSuccess = { values -> mutable.update { it.copy(categories = values, categoriesError = false) } },
                onFailure = { mutable.update { it.copy(categoriesError = true) } })
        }
    }

    fun edit(title: String, body: String, categoryId: String?) {
        if (mutable.value.submitting || title.length > 256 || body.length > 65536) return
        mutable.update { it.copy(title = title, body = body, categoryId = categoryId, submitError = false, submitMessage = null) }
        saveDraft()
    }

    fun create() {
        val current = mutable.value
        if (current.submitting) return
        val repositoryId = current.repositoryId ?: return
        val category = current.categories.firstOrNull { it.id == current.categoryId }
        if (category == null || current.title.isBlank() || current.body.isBlank()) {
            mutable.update { it.copy(submitError = true) }; return
        }
        mutable.update { it.copy(submitting = true, submitError = false, submitMessage = null) }
        createJob = viewModelScope.launch {
            val result = repository.create(repositoryId, category.id, current.title, current.body)
            if (!isActive) return@launch
            result.fold(onSuccess = { created -> mutable.update { it.copy(submitting = false,
                items = (listOf(created) + it.items).distinctBy(GithubDiscussion::id), created = created,
                title = "", body = "", categoryId = null, composing = false) }; saveDraft() },
                onFailure = { error -> mutable.update { it.copy(submitting = false, submitError = true,
                    submitMessage = (error as? GithubDiscussionException)?.message) } })
        }
    }

    fun consumeCreated() { mutable.update { it.copy(created = null) } }
}
