package tv.own.owntv.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.format.localizedInteger
import tv.own.owntv.ui.stage.*
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

@Composable
fun SportsMatchPopup(
    details: SportsMatchDetails,
    badges: Map<String, String>,
    listLabels: List<String>,
    listsActive: Boolean,
    onChangeLists: () -> Unit,
    onSearch: (String, Boolean) -> Unit,
    onPlay: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchTab by rememberSaveable(details.match.id) { mutableStateOf(false) }
    val first = remember(details.match.id) { FocusRequester() }
    LaunchedEffect(details.match.id) { delay(80); runCatching { first.requestFocus() } }
    StagePopup(onDismiss = onDismiss, title = if (searchTab) null else stringResource(R.string.sports_match_details), width = 1580.mpx, scroll = false,
        buttons = { StageButton(stringResource(R.string.sports_close), onClick = onDismiss) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.mpx)) {
            StageTab(stringResource(R.string.sports_where_to_watch), localizedInteger(details.broadcasts.size), !searchTab,
                onClick = { searchTab = false }, modifier = Modifier.focusRequester(first))
            StageTab(stringResource(R.string.sports_search_channel), localizedInteger(details.results.size), searchTab,
                onClick = { searchTab = true })
        }
        Column(Modifier.fillMaxWidth().padding(vertical = if (searchTab) 6.mpx else 18.mpx), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.mpx)) {
            SportsTeamsLine(details.match, badges)
            Text(listOf(matchTime(details.match), details.match.league).filter(String::isNotBlank).joinToString(stringResource(R.string.sports_separator)),
                style = stageText(17, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.mpx), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.mpx)) {
            StageTool(stringResource(R.string.sports_search_lists), onClick = onChangeLists, icon = OwnTVIcon.FOLDER, boxed = true)
            SportsListChips(listLabels, active = listsActive, modifier = Modifier.weight(1f))
        }
        if (searchTab) SportsChannelSearchContent(details, onSearch, onPlay)
        else Row(Modifier.fillMaxWidth().heightIn(max = 460.mpx).weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(24.mpx)) {
            SportsDetailPanel(stringResource(R.string.sports_broadcast_channels), Modifier.weight(1f).fillMaxHeight()) {
                Text(stringResource(R.string.sports_choose_broadcaster), style = stageText(16, 500), color = StageColors.Muted)
                if (details.unavailable) Text(stringResource(R.string.sports_broadcast_unavailable), style = stageText(16, 500), color = StageColors.Muted)
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.mpx)) {
                    SportsRegion.entries.forEach { region ->
                        val stations = details.broadcasts.filter { it.country == region.country }
                        item(key = region) {
                            Text(stringResource(if (region == SportsRegion.NETHERLANDS) R.string.sports_netherlands else R.string.sports_turkey),
                                style = stageText(20, 700), color = StageColors.Text, modifier = Modifier.padding(top = 12.mpx, bottom = 4.mpx))
                            if (stations.isEmpty()) Text(stringResource(R.string.sports_not_confirmed), style = stageText(18, 500), color = StageColors.Muted)
                        }
                        items(stations, key = SportsMatching::broadcastRowKey) { station ->
                            val related = SportsMatching.relatedTvSearch(station)
                            val query = related ?: station.name
                            StagePopupOption(
                                title = stringResource(if (station.streaming) R.string.sports_stream_service else R.string.sports_on_channel, station.name),
                                subtitle = if (related != null) stringResource(R.string.sports_stream_related_search, related) else stringResource(when {
                                    station.source == SportsBroadcastSource.OFFICIAL -> R.string.sports_official_listing
                                    station.streaming -> R.string.sports_stream_listing
                                    else -> R.string.sports_fotmob_listing
                                }),
                                chosen = details.broadcasterSearch && details.searchStation == query,
                                onClick = { onSearch(query, true) },
                            )
                        }
                    }
                    val guide = details.broadcasts.filter { it.source == SportsBroadcastSource.GUIDE }
                    if (guide.isNotEmpty()) item { Text(stringResource(R.string.sports_your_tv_guide), style = stageText(20, 700), color = StageColors.Text, modifier = Modifier.padding(top = 12.mpx)) }
                    items(guide, key = SportsMatching::broadcastRowKey) { station ->
                        StagePopupOption(title = station.name, subtitle = stringResource(R.string.sports_guide_evidence), onClick = { onSearch(station.name, true) })
                    }
                }
            }
            SportsChannelResults(details, onPlay, Modifier.weight(1.15f).fillMaxHeight())
        }
    }
}

@Composable
internal fun SportsDetailPanel(title: String, modifier: Modifier = Modifier, compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.mpx)
    Column(modifier.background(StageColors.ControlFill, shape).border(1.mpx, Color.White.copy(alpha = 0.10f), shape).padding(if (compact) 12.mpx else 18.mpx),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.mpx else 10.mpx)) {
        Text(title, style = stageText(if (compact) 19 else 23, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(1.mpx).background(Color.White.copy(alpha = 0.10f)))
        content()
    }
}

@Composable
internal fun SportsChannelResults(
    details: SportsMatchDetails,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
    query: String? = details.searchStation,
    pending: Boolean = details.loading,
    searchOnly: Boolean = false,
    rowModifier: Modifier = Modifier,
) {
    SportsDetailPanel(stringResource(R.string.sports_channels_in_lists), modifier) {
        when {
            searchOnly && query.isNullOrEmpty() -> Text(stringResource(R.string.sports_quick_channels_prompt), style = stageText(18, 500), color = StageColors.Muted)
            pending -> Text(stringResource(R.string.sports_finding_channels), style = stageText(18, 500), color = StageColors.Muted)
            else -> {
                if (!query.isNullOrEmpty()) Text(stringResource(R.string.sports_search_results, query), style = stageText(17, 600), color = StageColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (details.results.isEmpty()) Text(stringResource(R.string.sports_no_channels), style = stageText(18, 500), color = StageColors.Muted)
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.mpx)) {
                    items(details.results, key = SportsMatching::channelRowKey) { result ->
                        StagePopupOption(title = result.channel.name,
                            subtitle = listOfNotNull(result.list?.name, stringResource(when {
                                searchOnly -> R.string.sports_manual_evidence
                                result.evidence == ChannelEvidence.GUIDE -> R.string.sports_guide_evidence
                                result.evidence == ChannelEvidence.BROADCASTER -> R.string.sports_broadcaster_evidence
                                else -> R.string.sports_manual_evidence
                            })).joinToString(stringResource(R.string.sports_separator)),
                            leading = { StagePopupIcon(OwnTVIcon.PLAY) },
                            onClick = { onPlay(result.channel.id) }, modifier = rowModifier)
                    }
                }
            }
        }
    }
}
