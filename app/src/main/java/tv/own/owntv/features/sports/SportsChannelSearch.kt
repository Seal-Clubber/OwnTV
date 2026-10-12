package tv.own.owntv.features.sports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.*
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText
import tv.own.owntv.ui.theme.stageAccent

/** Shortcuts are manual channel searches, never evidence of a match's broadcaster. */
@Composable
fun ColumnScope.SportsChannelSearchContent(
    details: SportsMatchDetails,
    onSearch: (String, Boolean) -> Unit,
    onPlay: (Long) -> Unit,
) {
    var query by rememberSaveable(details.match.id) { mutableStateOf(details.searchStation.orEmpty()) }
    var broadcaster by rememberSaveable(details.match.id) { mutableStateOf(details.broadcasterSearch) }
    var trOpen by rememberSaveable { mutableStateOf(false) }
    var nlOpen by rememberSaveable { mutableStateOf(false) }
    val headers = remember { SportsRegion.entries.associateWith { FocusRequester() } }
    val firstRows = remember { SportsRegion.entries.associateWith { FocusRequester() } }
    val first = headers.getValue(SportsRegion.TURKEY)
    val turkishScroll = rememberLazyListState()
    val dutchScroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val field = remember { FocusRequester() }
    var lastShortcut by remember { mutableStateOf(first) }
    val turkish = stringArrayResource(R.array.sports_quick_channels_tr).toList()
    val dutch = stringArrayResource(R.array.sports_quick_channels_nl).toList()
    val needle = query.trim()
    LaunchedEffect(needle, broadcaster) {
        if (needle.isNotEmpty()) {
            delay(250)
            onSearch(needle, broadcaster)
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    LaunchedEffect(trOpen, nlOpen) {
        val region = if (trOpen) SportsRegion.TURKEY else if (nlOpen) SportsRegion.NETHERLANDS else return@LaunchedEffect
        (if (trOpen) turkishScroll else dutchScroll).scrollToItem(0)
        withFrameNanos { }
        runCatching { firstRows.getValue(region).requestFocus() }
    }
    Row(Modifier.fillMaxWidth().heightIn(max = 560.mpx).weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(24.mpx)) {
        // Previously 1 / 2.15 of the row; 1 / 4.3 gives the shortcuts half their old width.
        SportsDetailPanel(stringResource(R.string.sports_quick_searches), Modifier.weight(1f).fillMaxHeight(), compact = true) {
            SportsRegion.entries.reversed().forEach { region ->
                key(region) {
                    val open = if (region == SportsRegion.TURKEY) trOpen else nlOpen
                    val names = if (region == SportsRegion.TURKEY) turkish else dutch
                    val header = headers.getValue(region)
                    StageSurface(radius = 12.mpx,
                        onClick = {
                            if (region == SportsRegion.TURKEY) { trOpen = !trOpen; nlOpen = false }
                            else { nlOpen = !nlOpen; trOpen = false }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.mpx).focusRequester(header).focusProperties {
                            right = field
                            if (region == SportsRegion.TURKEY) down = headers.getValue(SportsRegion.NETHERLANDS)
                            else up = headers.getValue(SportsRegion.TURKEY)
                        }.onFocusChanged { if (it.isFocused) lastShortcut = header }
                            .onPreviewKeyEvent { event ->
                                if (!open || event.key != Key.DirectionRight) false
                                else {
                                    if (event.type == KeyEventType.KeyDown) scope.launch {
                                        (if (region == SportsRegion.TURKEY) turkishScroll else dutchScroll).scrollToItem(0)
                                        withFrameNanos { }
                                        runCatching { firstRows.getValue(region).requestFocus() }
                                    }
                                    true
                                }
                            },
                    ) { focused ->
                        val tint = if (focused) stageAccent.onAccent else StageColors.Text
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.mpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.mpx)) {
                            OwnTVIcon(OwnTVIcon.FOLDER, tint, Modifier.size(18.mpx))
                            Text(stringResource(if (region == SportsRegion.TURKEY) R.string.sports_turkey else R.string.sports_netherlands),
                                style = stageText(18, 700), color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            OwnTVIcon(if (open) OwnTVIcon.CHEVRON_UP else OwnTVIcon.CHEVRON_DOWN, tint, Modifier.size(18.mpx))
                        }
                    }
                    // The expanded names are children of this country, before the next header.
                    if (open) LazyColumn(state = if (region == SportsRegion.TURKEY) turkishScroll else dutchScroll,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                        .background(Color.Black.copy(alpha = 0.16f), RoundedCornerShape(12.mpx))
                        .border(1.mpx, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.mpx)).padding(4.mpx),
                        contentPadding = PaddingValues(vertical = 4.mpx), verticalArrangement = Arrangement.spacedBy(4.mpx)) {
                        items(names, key = { it }) { name ->
                            val row = if (name == names.first()) firstRows.getValue(region) else remember { FocusRequester() }
                            StageSurface(radius = 8.mpx,
                                idle = Modifier.background(if (query == name) stageAccent.accent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.mpx)),
                                onClick = { query = name; broadcaster = true },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.mpx).focusRequester(row).focusProperties { left = header; right = field }.onFocusChanged { if (it.isFocused) lastShortcut = row },
                            ) { focused ->
                                Text(name, style = stageText(18, 600), color = if (focused) stageAccent.onAccent else StageColors.Text,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.mpx, vertical = 8.mpx))
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(3.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.mpx)) {
            StageSearchField(
                query = query,
                onQueryChange = { query = it; broadcaster = false },
                placeholder = stringResource(R.string.sports_channel_name),
                height = 54.mpx,
                modifier = Modifier.fillMaxWidth().focusRequester(field).focusProperties { left = lastShortcut },
            )
            val pending = needle.isNotEmpty() && (details.loading || details.searchStation != needle || details.broadcasterSearch != broadcaster)
            SportsChannelResults(details, onPlay, Modifier.fillMaxWidth().weight(1f), query = needle, pending = pending,
                searchOnly = true, rowModifier = Modifier.focusProperties { left = lastShortcut })
        }
    }
}
