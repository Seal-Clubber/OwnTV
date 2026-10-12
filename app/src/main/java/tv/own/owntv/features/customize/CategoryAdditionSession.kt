@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package tv.own.owntv.features.customize

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import tv.own.owntv.core.customize.CustomCategory
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.model.MediaType

/** Captures the owner and selected catalog rows before opening a modal preview. */
data class CategoryAddition(
    val profileId: Long,
    val sourceIds: List<Long>,
    val groupIds: List<Long>,
    val itemIds: List<Long>,
    val labels: List<String>,
    val targets: List<MoveTarget>,
    val targetId: String? = null,
    val newName: String? = null,
    val count: Int? = null,
    val exclusions: Set<Long> = emptySet(),
    val saving: Boolean = false,
    val failed: Boolean = false,
)

/** Both TV lists use this session; draft edits do not touch persistence until Save. */
class CategoryAdditionSession(
    private val scope: CoroutineScope,
    private val customize: CustomizationStore,
    private val dao: CustomCategoryDao,
) {
    private val _state = MutableStateFlow<CategoryAddition?>(null)
    val state = _state.asStateFlow()
    private var previewJob: Job? = null
    private val previewContext = state.map { s -> s?.takeIf { it.targetId != null }?.let {
        listOf(it.profileId, it.sourceIds, it.groupIds, it.itemIds, it.targetId)
    } }.distinctUntilChanged()
    val channels: Flow<PagingData<ChannelEntity>> = previewContext.flatMapLatest {
        val s = state.value
        if (s?.targetId == null) flowOf(PagingData.empty())
        else Pager(PagingConfig(pageSize = 50)) {
            dao.pagingLiveAddition(s.profileId, s.targetId, s.sourceIds.ifEmpty { listOf(-1L) },
                s.groupIds.ifEmpty { listOf(-1L) }, s.itemIds.ifEmpty { listOf(-1L) })
        }.flow
    }.cachedIn(scope)

    fun open(profileId: Long, sources: List<Long>, groups: List<Long>, items: List<Long>, labels: List<String>, targets: List<MoveTarget>) {
        if (profileId < 0 || groups.isEmpty() && items.isEmpty()) return
        previewJob?.cancel()
        _state.value = CategoryAddition(profileId, sources, groups.distinct(), items.distinct(), labels, targets)
    }

    fun dismiss() { if (state.value?.saving != true) { previewJob?.cancel(); _state.value = null } }
    fun selectTarget(id: String) = select(id, null)
    fun newCategory(name: String) {
        if (name.isBlank()) return
        select("custom:${UUID.randomUUID()}", name.trim())
    }
    private fun select(id: String, name: String?) {
        val s = state.value ?: return
        if (s.saving) return
        previewJob?.cancel()
        _state.value = s.copy(targetId = id, newName = name, count = null, exclusions = emptySet(), failed = false)
        previewJob = scope.launch {
            try {
                state.map { it?.takeIf { current -> current.targetId == id }?.exclusions }
                    .filterNotNull().distinctUntilChanged().flatMapLatest { excluded ->
                        dao.observeLiveAdditionCount(s.profileId, id, s.sourceIds.ifEmpty { listOf(-1L) },
                            s.groupIds.ifEmpty { listOf(-1L) }, s.itemIds.ifEmpty { listOf(-1L) }, excluded.toList().ifEmpty { listOf(-1L) })
                    }.collect { count ->
                    _state.update { current -> current?.takeIf { it.targetId == id }?.copy(count = count) ?: current }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) { _state.update { it?.copy(failed = true) } }
        }
    }
    fun toggleChannel(id: Long) {
        _state.update { s -> s?.takeIf { !it.saving }?.let {
            it.copy(exclusions = if (id in it.exclusions) it.exclusions - id else it.exclusions + id)
        } ?: s }
    }
    fun save(onSaved: () -> Unit = {}) {
        val s = state.value?.takeIf { it.targetId != null && it.count != null && !it.saving } ?: return
        _state.value = s.copy(saving = true, failed = false)
        scope.launch {
            runCatching {
                val cust = customize.observe(s.profileId, MediaType.LIVE).first()
                require(s.newName != null || cust.customCategories.any { it.id == s.targetId })
                dao.addLiveSelection(s.profileId, s.targetId!!, s.groupIds, s.itemIds, s.exclusions.toList())
                if (s.newName != null) customize.update(s.profileId, MediaType.LIVE) {
                    it.copy(customCategories = it.customCategories + CustomCategory(s.targetId, s.newName))
                }
            }.onSuccess { _state.value = null; onSaved() }
                .onFailure { _state.value = s.copy(failed = true) }
        }
    }
}
