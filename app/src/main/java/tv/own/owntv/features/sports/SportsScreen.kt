package tv.own.owntv.features.sports

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.TextInputDialog
import tv.own.owntv.ui.format.localizedInteger
import tv.own.owntv.ui.stage.*
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SportsScreen(
    onPlayChannel: (Long) -> Unit,
    onChildFocused: () -> Unit,
    onEntryHook: ((() -> Boolean)?) -> Unit,
    modifier: Modifier = Modifier,
    vm: SportsViewModel = org.koin.androidx.compose.koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val details by vm.details.collectAsStateWithLifecycle()
    val entry = remember { FocusRequester() }
    val arrangeButton = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var listsOpen by remember(state.profileId) { mutableStateOf(false) }
    var teamsOpen by remember(state.profileId) { mutableStateOf(false) }
    var myTeamsTab by remember(state.profileId) { mutableStateOf(true) }
    var orderOpen by remember(state.profileId) { mutableStateOf(false) }
    var selectedTeamId by remember(state.profileId) { mutableStateOf<String?>(null) }
    var reopenMatch by remember(state.profileId) { mutableStateOf<SportsMatch?>(null) }
    var reopenScope by remember(state.profileId) { mutableStateOf<Set<String>?>(null) }
    val prefs = state.preferences
    var orderedTeams by remember(state.profileId) { mutableStateOf(prefs.teams) }
    BackHandler(orderOpen) { orderOpen = false; arrangeButton.requestFocus() }
    LaunchedEffect(state.preferences.lists, state.snapshot, state.guideReady, listsOpen) {
        val match = reopenMatch
        if (match != null && !listsOpen && state.guideReady && state.preferences.lists == reopenScope) {
            vm.openMatch(match)
            reopenMatch = null
        }
    }
    val selectedNames = prefs.lists?.map { key -> state.snapshot.lists.find { it.key == key }?.name ?: stringResource(R.string.sports_missing_list) }
    val listLabel = if (selectedNames == null) stringResource(R.string.sports_all_lists)
        else if (selectedNames.isEmpty()) stringResource(R.string.sports_no_lists)
        else selectedNames.joinToString(stringResource(R.string.sports_separator))
    val listLabels = selectedNames?.takeIf { it.isNotEmpty() } ?: listOf(listLabel)
    DisposableEffect(Unit) {
        onEntryHook { runCatching { entry.requestFocus() }.getOrDefault(false) }
        onDispose { onEntryHook(null); vm.closeMatch() }
    }
    val mine = state.matches.filter { match -> prefs.teams.any(match::follows) }
    val selectedTeam = prefs.teams.find { it.id == selectedTeamId }
    val visibleMine = if (selectedTeam == null) mine else mine.filter { match -> match.follows(selectedTeam) }
    LaunchedEffect(prefs.teams, state.guideReady) {
        if (state.guideReady && selectedTeamId != null && prefs.teams.none { it.id == selectedTeamId }) selectedTeamId = null
    }
    val others = state.matches.filter { it !in mine }.sortedWith(compareByDescending<SportsMatch> { it.isLive }.thenByDescending { SportsMatching.notable(it) }.thenBy { it.sortTimeMs })
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(myTeamsTab, selectedTeamId) { listState.scrollToItem(0) }
    Column(modifier.fillMaxSize().padding(start = 136.mpx, end = 64.mpx, top = 130.mpx, bottom = 24.mpx)
        .onFocusChanged { if (it.hasFocus) onChildFocused() }.focusGroup()) {
        Text(stringResource(R.string.sports_title), style = stageText(40, 800), color = StageColors.Text)
        Row(horizontalArrangement = Arrangement.spacedBy(24.mpx), modifier = Modifier.padding(top = 12.mpx)) {
            StageTab(stringResource(R.string.sports_my_teams), localizedInteger(visibleMine.size), myTeamsTab,
                onClick = { myTeamsTab = true }, modifier = Modifier.focusRequester(entry).onFocusChanged { if (it.isFocused) myTeamsTab = true })
            StageTab(stringResource(R.string.sports_upcoming_games), localizedInteger(others.size), !myTeamsTab,
                onClick = { myTeamsTab = false; orderOpen = false }, modifier = Modifier.onFocusChanged { if (it.isFocused) { myTeamsTab = false; orderOpen = false } })
        }
        val toolbarShape = RoundedCornerShape(20.mpx)
        Row(horizontalArrangement = Arrangement.spacedBy(14.mpx), modifier = Modifier.fillMaxWidth().padding(top = 14.mpx, bottom = 12.mpx)
            .background(StageColors.ControlFill, toolbarShape).border(1.mpx, Color.White.copy(alpha = 0.12f), toolbarShape).padding(12.mpx)) {
            StagePill(stringResource(R.string.sports_manage_teams), onClick = { orderOpen = false; teamsOpen = true }, icon = OwnTVIcon.STAR, enabled = state.guideReady, modifier = Modifier.weight(1f))
            StagePill(stringResource(R.string.sports_arrange_teams), onClick = { orderedTeams = prefs.teams; myTeamsTab = true; orderOpen = true }, icon = OwnTVIcon.MOVE, enabled = state.guideReady && prefs.teams.isNotEmpty(), modifier = Modifier.weight(1f).focusRequester(arrangeButton))
            StagePill(stringResource(R.string.sports_refresh), onClick = vm::reload, icon = OwnTVIcon.REFRESH, modifier = Modifier.weight(1f))
        }
        if (state.feedUnavailable || state.stale) Text(stringResource(if (state.stale) R.string.sports_stale else R.string.sports_feed_unavailable), style = stageText(17, 600), color = StageColors.Muted)
        if (state.broadcastUnavailable) Text(stringResource(R.string.sports_broadcast_unavailable), style = stageText(17, 600), color = StageColors.Muted)
        if (state.guideUnavailable) Text(stringResource(R.string.sports_guide_unavailable), style = stageText(17, 600), color = StageColors.Muted)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(28.mpx)) {
            if (myTeamsTab) {
                SportsTeamList(if (orderOpen) orderedTeams else prefs.teams, state.badges, selectedTeam?.id,
                    onSelect = { team -> selectedTeamId = if (selectedTeamId == team.id) null else team.id },
                    arranging = orderOpen, onReorder = { orderedTeams = it },
                    onSave = { vm.saveTeams(orderedTeams); orderOpen = false; arrangeButton.requestFocus() },
                    onCancel = { orderOpen = false; arrangeButton.requestFocus() },
                    modifier = Modifier.width(300.mpx).fillMaxHeight())
            }
            val matchesShape = RoundedCornerShape(20.mpx)
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxHeight()
                .background(StageColors.ControlFill, matchesShape).border(1.mpx, Color.White.copy(alpha = 0.12f), matchesShape),
                contentPadding = PaddingValues(16.mpx), verticalArrangement = Arrangement.spacedBy(12.mpx)) {
                if (myTeamsTab) {
                    if (selectedTeam != null) item { Text(stringResource(R.string.sports_matches_for_team, selectedTeam.name), style = stageText(24, 700), color = StageColors.Text) }
                    if (visibleMine.isEmpty()) item { Text(if (selectedTeam == null) stringResource(R.string.sports_no_team_matches) else stringResource(R.string.sports_no_matches_for_team, selectedTeam.name), style = stageText(19, 500), color = StageColors.Muted) }
                    items(visibleMine.take(100), key = { it.id }) { match -> SportsMatchRow(match, state.badges) { vm.openMatch(match) } }
                } else {
                    if (others.isEmpty()) item { Text(stringResource(R.string.sports_no_other_matches), style = stageText(19, 500), color = StageColors.Muted) }
                    items(others.take(100), key = { it.id }) { match -> SportsMatchRow(match, state.badges) { vm.openMatch(match) } }
                }
            }
        }
    }
    if (listsOpen) SportsListsPicker(state.snapshot.lists, prefs.lists, onApply = { reopenScope = it; vm.saveLists(it); listsOpen = false }, onDismiss = { reopenScope = prefs.lists; listsOpen = false })
    if (teamsOpen) SportsTeamsPicker(prefs.teams.map { it.copy(badge = state.badges[it.id] ?: it.badge) }, vm, onApply = { vm.saveTeams(it); teamsOpen = false }, onDismiss = { teamsOpen = false })
    details?.let { found ->
        SportsMatchPopup(
            details = found,
            badges = state.badges,
            listLabels = listLabels,
            listsActive = selectedNames?.isEmpty() != true,
            onChangeLists = {
                reopenMatch = found.match
                reopenScope = prefs.lists
                vm.closeMatch()
                listsOpen = true
            },
            onSearch = { query, broadcaster -> if (broadcaster) vm.searchBroadcaster(query) else vm.manualSearch(query) },
            onPlay = { id -> scope.launch { if (vm.canPlay(id)) { vm.closeMatch(); onPlayChannel(id) } } },
            onDismiss = vm::closeMatch,
        )
    }
}

@Composable
internal fun SportsListChips(labels: List<String>, active: Boolean, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.mpx), verticalArrangement = Arrangement.spacedBy(8.mpx)) {
        labels.forEach { label ->
            val shape = RoundedCornerShape(12.mpx)
            Row(Modifier.background(StageColors.ControlFill, shape)
                .border(1.mpx, if (active) stageAccent.accent.copy(alpha = 0.55f) else StageColors.Muted, shape)
                .padding(horizontal = 14.mpx, vertical = 8.mpx), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.mpx)) {
                if (active) StageSelectedDot()
                Text(label, style = stageText(17, 600), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun matchTime(match: SportsMatch): String {
    if (match.isLive) return liveStatus(match)
    if (match.dateUnconfirmed) return stringResource(R.string.sports_provisional_date, match.date.orEmpty())
    val time = match.kickoffMs?.let { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(SportsClock.amsterdam(it)) }
        ?: stringResource(R.string.sports_time_unknown, match.date.orEmpty())
    return if (match.guideTitle != null) stringResource(R.string.sports_guide_time, time) else time
}
@Composable
private fun liveStatus(match: SportsMatch): String = when {
    match.scoreStale -> stringResource(R.string.sports_last_score)
    match.liveTime != null -> stringResource(R.string.sports_live_time, match.liveTime)
    else -> stringResource(R.string.sports_live)
}

@Composable
internal fun SportsTeamsLine(match: SportsMatch, badges: Map<String, String>, badgeSize: androidx.compose.ui.unit.Dp = 80.mpx, showBadges: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.mpx)) {
        if (showBadges) SportsBadge(match.homeBadge ?: badges[match.homeId], match.home.orEmpty(), Modifier.size(badgeSize))
        Text(match.home ?: match.title, style = stageText(23, 700), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Column(Modifier.width(150.mpx), horizontalAlignment = Alignment.CenterHorizontally) {
            val centre = when {
                match.isLive && match.homeScore != null && match.awayScore != null -> stringResource(R.string.sports_score, match.homeScore, match.awayScore)
                match.isLive -> stringResource(R.string.sports_score_unknown)
                match.kickoffMs != null -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(SportsClock.amsterdam(match.kickoffMs))
                else -> stringResource(R.string.sports_time_tbc)
            }
            Text(centre, style = stageText(30, 800), color = StageColors.Text, maxLines = 1)
            if (match.isLive) Text(liveStatus(match), style = stageText(16, 700), color = StageColors.Muted, maxLines = 1)
        }
        Text(match.away.orEmpty(), style = stageText(23, 700), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (showBadges) SportsBadge(match.awayBadge ?: badges[match.awayId], match.away.orEmpty(), Modifier.size(badgeSize))
    }
}

@Composable
private fun SportsMatchRow(match: SportsMatch, badges: Map<String, String>, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.mpx)
    StageSurface(onClick, radius = 20.mpx, focusStyle = StageFocus.FX, modifier = Modifier.fillMaxWidth().height(240.mpx),
        idle = Modifier.background(Color.White.copy(alpha = 0.045f), shape).border(1.mpx, Color.White.copy(alpha = 0.08f), shape)) { _ ->
        Row(Modifier.fillMaxWidth().padding(24.mpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.mpx)) {
            SportsBadge(match.homeBadge ?: badges[match.homeId], match.home.orEmpty(), Modifier.size(160.mpx))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.mpx)) {
                SportsTeamsLine(match, badges, showBadges = false)
                Text(matchTime(match), style = stageText(26, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (match.league.isNotBlank()) Text(match.league, style = stageText(16, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SportsRegion.entries.forEach { region ->
                    val stations = match.broadcasts.filter { it.country == region.country }.map {
                        if (it.streaming) stringResource(R.string.sports_stream_service, it.name) else it.name
                    }.joinToString(stringResource(R.string.sports_separator))
                    Text(stringResource(if (region == SportsRegion.NETHERLANDS) R.string.sports_region_nl else R.string.sports_region_tr,
                        stations.ifEmpty { stringResource(R.string.sports_not_confirmed) }), style = stageText(17, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SportsBadge(match.awayBadge ?: badges[match.awayId], match.away.orEmpty(), Modifier.size(160.mpx))
        }
    }
}

@Composable
internal fun SportsBadge(url: String?, team: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) AsyncImage(model = url, contentDescription = team, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text(team.take(1), style = stageText(24, 700), color = StageColors.Muted)
    }
}

@Composable
private fun SportsListsPicker(options: List<SportsList>, initial: Set<String>?, onApply: (Set<String>?) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    val first = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    var returnTo by remember { mutableStateOf(first) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
    StagePopup(onDismiss, title = stringResource(R.string.sports_search_lists), body = stringResource(R.string.sports_union_help), scroll = false) {
        Text(stringResource(R.string.sports_save_shortcut), style = stageText(17, 500), color = StageColors.Muted)
        Row(Modifier.fillMaxWidth().weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(18.mpx)) {
            Column(Modifier.weight(1f)) {
                StagePopupOption(stringResource(R.string.sports_all_lists), onClick = { selected = null },
                    modifier = Modifier.focusRequester(first).focusProperties { right = save }.onFocusChanged { if (it.isFocused) returnTo = first },
                    trailing = { focused -> StagePopupCheck(selected == null, focused) })
                if (selected?.any { key -> options.none { it.key == key } } == true) {
                    Text(stringResource(R.string.sports_missing_lists_help), style = stageText(17, 500), color = StageColors.Muted)
                }
                if (selected?.isEmpty() == true) Text(stringResource(R.string.sports_no_lists), style = stageText(17, 500), color = StageColors.Muted)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 410.mpx).weight(1f, fill = false)) {
                    items(options, key = { it.key }) { option ->
                        val row = remember(option.key) { FocusRequester() }
                        StagePopupOption(option.name, onClick = {
                            val before = selected.orEmpty()
                            selected = if (option.key in before) before - option.key else before + option.key
                        }, modifier = Modifier.focusRequester(row).focusProperties { right = save }.onFocusChanged { if (it.isFocused) returnTo = row },
                            trailing = { focused -> StagePopupCheck(selected?.contains(option.key) == true, focused) })
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.mpx)) {
                StageButton(stringResource(R.string.common_save), onClick = { onApply(selected) }, tinted = true,
                    modifier = Modifier.focusRequester(save).focusProperties { left = returnTo })
                StageButton(stringResource(R.string.common_cancel), onClick = onDismiss, modifier = Modifier.focusProperties { left = returnTo })
            }
        }
    }
}

@Composable
private fun SportsTeamsPicker(initial: List<SportsTeam>, vm: SportsViewModel, onApply: (List<SportsTeam>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    var options by remember { mutableStateOf((initial + SportsTeam.defaults).distinctBy { it.id }) }
    var searchOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    var returnTo by remember { mutableStateOf(first) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
    StagePopup(onDismiss, title = stringResource(R.string.sports_my_teams), body = stringResource(R.string.sports_teams_help), scroll = false,
        buttons = {
            StageButton(stringResource(R.string.common_cancel), onClick = onDismiss)
            StageButton(stringResource(R.string.common_save), onClick = { onApply(selected) }, tinted = true, modifier = Modifier.focusRequester(save).focusProperties { left = returnTo })
        }) {
        Text(stringResource(R.string.sports_save_shortcut), style = stageText(17, 500), color = StageColors.Muted)
        StageTool(stringResource(R.string.sports_find_team), onClick = { searchOpen = true }, icon = OwnTVIcon.SEARCH, modifier = Modifier.focusRequester(first).focusProperties { right = save }.onFocusChanged { if (it.isFocused) returnTo = first })
        if (searching) Text(stringResource(R.string.sports_loading), style = stageText(18, 500), color = StageColors.Text)
        if (searchFailed) Text(stringResource(R.string.sports_team_search_failed), style = stageText(18, 500), color = StageColors.Muted)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.mpx).weight(1f, fill = false)) {
            items(options, key = { it.id }) { team ->
                val row = remember(team.id) { FocusRequester() }
                StagePopupOption(team.name, onClick = {
                    selected = if (selected.any { it.id == team.id }) selected.filterNot { it.id == team.id }
                        else if (selected.size < 24) selected + team else selected
                }, modifier = Modifier.focusRequester(row).focusProperties { right = save }.onFocusChanged { if (it.isFocused) returnTo = row },
                    leading = { SportsBadge(team.badge, team.name, Modifier.size(44.mpx)) }, trailing = { focused -> StagePopupCheck(selected.any { it.id == team.id }, focused) })
            }
        }
    }
    if (searchOpen) TextInputDialog(title = stringResource(R.string.sports_find_team), label = stringResource(R.string.sports_team_name), allowBlank = false,
        onConfirm = { query ->
            searchOpen = false; searching = true; searchFailed = false
            scope.launch {
                try {
                    val found = vm.findTeams(query)
                    searchFailed = found.isEmpty()
                    options = (selected + found + SportsTeam.defaults).distinctBy { it.id }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { searchFailed = true }
                finally { searching = false }
            }
        }, onDismiss = { searchOpen = false })
}
