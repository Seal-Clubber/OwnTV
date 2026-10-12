@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package tv.own.owntv.features.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository

data class SportsUiState(
    val profileId: Long = -1,
    val preferences: SportsPreferences = SportsPreferences(),
    val snapshot: SportsSnapshot = SportsSnapshot(),
    val matches: List<SportsMatch> = emptyList(),
    val loading: Boolean = true,
    val feedUnavailable: Boolean = false,
    val stale: Boolean = false,
    val guideUnavailable: Boolean = false,
    val guideReady: Boolean = false,
    val badges: Map<String, String> = emptyMap(),
    val broadcastUnavailable: Boolean = false,
)
data class SportsMatchDetails(val match: SportsMatch, val broadcasts: List<SportsBroadcast> = emptyList(), val results: List<SportsChannelResult> = emptyList(), val loading: Boolean = true, val unavailable: Boolean = false, val searchStation: String? = null, val broadcasterSearch: Boolean = false)

class SportsViewModel(
    sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    settings: SettingsRepository,
    customization: CustomizationStore,
    private val preferencesStore: SportsPreferencesStore,
    private val reader: SportsGuideReader,
    private val api: SportsApi,
) : ViewModel() {
    private val refresh = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    private var scopeIdentity: List<Any?>? = null
    private var previousSnapshot: SportsSnapshot? = null
    private var guideReadMs = 0L
    private val updates = merge(refresh, flow {
        emit(false)
        while (true) { delay(60_000L); if (!state.value.loading) emit(false) }
    })
    val state: StateFlow<SportsUiState> = combine(activeProfileSources(settings, sourceDao), updates) { ctx, force -> ctx to force }
        .flatMapLatest { (ctx, force) ->
            combine(preferencesStore.observe(ctx.profileId), customization.observe(ctx.profileId, MediaType.LIVE), channelDao.observeListIndex(ctx.liveSourceIds)) { prefs, cust, index -> Triple(prefs, cust, index) }
                .transformLatest { (prefs, cust, index) ->
                    val identity = listOf(ctx.profileId, ctx.liveSourceIds, prefs, cust, index)
                    val changed = identity != scopeIdentity
                    if (changed) { closeMatch(); previousSnapshot = null; guideReadMs = 0L }
                    scopeIdentity = identity
                    if (changed) emit(SportsUiState(ctx.profileId, prefs))
                    if (ctx.profileId < 0) return@transformLatest
                    var guideError = false
                    val snapshot = try {
                        if (!changed && previousSnapshot != null && System.currentTimeMillis() - guideReadMs < 10 * 60_000L) previousSnapshot!!
                        else reader.read(ctx, prefs, cust, index).also { previousSnapshot = it; guideReadMs = System.currentTimeMillis() }
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { guideError = true; SportsSnapshot() }
                    var value = if (!changed && state.value.guideReady) state.value.copy(snapshot = snapshot, loading = true, guideUnavailable = guideError)
                        else SportsUiState(ctx.profileId, prefs, snapshot, snapshot.guideMatches, guideUnavailable = guideError, guideReady = true)
                    emit(value)
                    val fixtures = mutableListOf<SportsMatch>()
                    var failed = false
                    var stale = false
                    for (team in prefs.teams.take(24)) {
                        try {
                            val feed = api.next(team, force)
                            stale = stale || feed.stale
                            fixtures += feed.matches.filter { match ->
                                match.kickoffMs?.let { it > System.currentTimeMillis() - 3 * 60 * 60_000L }
                                    ?: match.date?.let { runCatching { java.time.LocalDate.parse(it) >= java.time.LocalDate.now(java.time.ZoneOffset.UTC) }.getOrDefault(false) } ?: false
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failed = true }
                    }
                    var dailyMatches = emptyList<SportsMatch>()
                    var closedIds = emptySet<String>()
                    try {
                        val daily = api.today(force)
                        dailyMatches = daily.matches
                        closedIds = daily.closedIds
                        stale = stale || daily.stale
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
                    val unique = SportsSchedule.merge(fixtures, dailyMatches, closedIds)
                    var broadcastFailed = false
                    val broadcasts = try { api.broadcasts(unique, force).also { broadcastFailed = it.unavailable }.listings }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { broadcastFailed = true; emptyMap() }
                    val (external, guideOnly) = withContext(Dispatchers.Default) {
                        val external = FotMobJson.attach(unique, listOf(broadcasts)).map { match ->
                            match.copy(guideChannels = snapshot.guideMatches.filter { SportsMatching.confirms(match, it) }.flatMap { it.guideChannels }.distinct())
                        }
                        external to snapshot.guideMatches.filter { guide -> external.none { SportsMatching.confirms(it, guide) } }
                    }
                    value = value.copy(matches = (external + guideOnly).sortedBy { it.sortTimeMs },
                        loading = false, feedUnavailable = failed, stale = stale, broadcastUnavailable = broadcastFailed)
                    emit(value)
                    _details.value?.let { current ->
                        val updated = value.matches.find { it.id == current.match.id }
                        if (updated == null) closeMatch() else {
                            loadDetails(updated, snapshot, broadcastFailed, current.searchStation, current.broadcasterSearch)
                        }
                    }
                    val ids = prefs.teams.map { it.id }.distinct().take(24)
                    val badges = prefs.teams.mapNotNull { team -> team.badge?.let { team.id to it } }.toMap().toMutableMap()
                    external.forEach { match ->
                        match.homeId?.let { id -> match.homeBadge?.let { badges[id] = it } }
                        match.awayId?.let { id -> match.awayBadge?.let { badges[id] = it } }
                    }
                    value = value.copy(badges = badges.toMap())
                    emit(value)
                    for (id in ids) {
                        if (id in badges) continue
                        try { api.team(id)?.badge?.let { badges[id] = it } }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { continue }
                        value = value.copy(badges = badges.toMap())
                        emit(value)
                    }
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsUiState())
    private val _details = MutableStateFlow<SportsMatchDetails?>(null)
    val details = _details.asStateFlow()
    fun reload() { refresh.tryEmit(true) }
    fun saveLists(lists: Set<String>?) { val current = state.value; viewModelScope.launch { preferencesStore.save(current.profileId, current.preferences.copy(lists = lists)) } }
    fun saveTeams(teams: List<SportsTeam>) { val current = state.value; viewModelScope.launch { preferencesStore.save(current.profileId, current.preferences.copy(teams = teams.distinctBy { it.id }.take(24))) } }
    private var detailsJob: Job? = null
    fun closeMatch() { detailsJob?.cancel(); detailsJob = null; _details.value = null }
    fun openMatch(match: SportsMatch) {
        closeMatch()
        loadDetails(match, state.value.snapshot, state.value.broadcastUnavailable)
    }
    private fun loadDetails(match: SportsMatch, snapshot: SportsSnapshot, unavailable: Boolean, query: String? = null, broadcaster: Boolean = false) {
        detailsJob?.cancel()
        _details.value = SportsMatchDetails(match, match.broadcasts, unavailable = unavailable, searchStation = query, broadcasterSearch = broadcaster)
        detailsJob = viewModelScope.launch {
            val found = withContext(Dispatchers.Default) {
                val base = matchDetails(match, snapshot, unavailable)
                if (query == null) base else base.copy(results = SportsMatching.search(snapshot, query, broadcaster), searchStation = query, broadcasterSearch = broadcaster)
            }
            _details.value = found
        }
    }
    private fun matchDetails(match: SportsMatch, snapshot: SportsSnapshot, unavailable: Boolean): SportsMatchDetails {
        val guideIds = (match.guideChannels + snapshot.guideMatches.filter { SportsMatching.confirms(match, it) }.flatMap { it.guideChannels }).toSet()
        val confirmed = snapshot.channels.filter { it.id in guideIds }.map { SportsChannelResult(it, ChannelEvidence.GUIDE) }
        val guideStations = confirmed.map { SportsBroadcast(it.channel.name, "", SportsBroadcastSource.GUIDE) }
        val stations = (match.broadcasts + guideStations).distinctBy { it.country to SportsMatching.broadcasterKey(it.name) }
        val keys = match.broadcasts.map { SportsMatching.broadcasterKey(it.name) }.filter(String::isNotEmpty).toSet()
        val possible = snapshot.channels.filter { ch -> ch.id !in guideIds && keys.isNotEmpty() &&
            SportsMatching.channelKeys(snapshot, ch).any { it in keys }
        }.map { SportsChannelResult(it, ChannelEvidence.BROADCASTER) }
        return SportsMatchDetails(match, stations, SportsMatching.occurrences(snapshot, confirmed + possible), loading = false, unavailable = unavailable)
    }
    /** Substring search runs on the complete selected set before the display limit. */
    fun manualSearch(query: String) { searchChannels(query, false) }
    fun searchBroadcaster(station: String) { searchChannels(station, true) }
    private fun searchChannels(query: String, broadcaster: Boolean) {
        val current = _details.value ?: return
        val snapshot = state.value.snapshot
        detailsJob?.cancel()
        _details.value = current.copy(results = emptyList(), loading = true, searchStation = query, broadcasterSearch = broadcaster)
        detailsJob = viewModelScope.launch {
            val results = withContext(Dispatchers.Default) { SportsMatching.search(snapshot, query, broadcaster) }
            _details.value = current.copy(results = results, loading = false, searchStation = query, broadcasterSearch = broadcaster)
        }
    }
    suspend fun findTeams(query: String): List<SportsTeam> = api.findTeams(query)
    /** Caller only receives an id after checking the current profile and scope again. */
    suspend fun canPlay(id: Long): Boolean = state.value.snapshot.channels.any { it.id == id } && channelDao.getById(id) != null
}
