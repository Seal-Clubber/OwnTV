package tv.own.owntv.features.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.*
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

@Composable
fun SportsTeamList(
    teams: List<SportsTeam>,
    badges: Map<String, String>,
    selectedTeamId: String?,
    onSelect: (SportsTeam) -> Unit,
    arranging: Boolean,
    onReorder: (List<SportsTeam>) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var moving by remember(arranging) { mutableStateOf<String?>(null) }
    var focusedTeam by remember { mutableStateOf(selectedTeamId ?: teams.firstOrNull()?.id) }
    val rows = remember(teams.map { it.id }.toSet()) { teams.associate { it.id to FocusRequester() } }
    val save = remember { FocusRequester() }
    val list = rememberLazyListState()
    BackHandler(arranging && moving != null) { moving = null }
    LaunchedEffect(arranging) {
        if (arranging) {
            val index = teams.indexOfFirst { it.id == focusedTeam }.coerceAtLeast(0)
            if (teams.isNotEmpty()) {
                focusedTeam = teams[index].id
                list.scrollToItem(index)
                withFrameNanos { }
                rows[focusedTeam]?.requestFocus()
            }
        }
    }
    LaunchedEffect(teams, moving) {
        val index = teams.indexOfFirst { it.id == moving }
        if (index >= 0) {
            list.scrollToItem(index)
            withFrameNanos { }
            rows[moving]?.requestFocus()
        }
    }
    val panelShape = RoundedCornerShape(20.mpx)
    Column(modifier.background(StageColors.ControlFill, panelShape)
        .border(1.mpx, Color.White.copy(alpha = 0.12f), panelShape).padding(16.mpx),
        verticalArrangement = Arrangement.spacedBy(12.mpx)) {
        Text(stringResource(if (arranging) R.string.sports_arrange_teams else R.string.sports_my_teams),
            style = stageText(20, 700), color = StageColors.Text)
        if (arranging) Text(stringResource(if (moving == null) R.string.sports_arrange_pick else R.string.sports_arrange_vertical),
            style = stageText(16, 500), color = StageColors.Muted)
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.mpx), contentPadding = PaddingValues(vertical = 6.mpx)) {
            items(teams, key = { it.id }) { team ->
                val selected = selectedTeamId == team.id && !arranging
                val picked = moving == team.id
                val shape = RoundedCornerShape(16.mpx)
                StageSurface(onClick = {
                    if (arranging) moving = if (picked) null else team.id else onSelect(team)
                }, radius = 16.mpx,
                    modifier = Modifier.fillMaxWidth().height(94.mpx).focusRequester(rows.getValue(team.id))
                        .focusProperties { if (arranging) right = save }
                        .onFocusChanged { if (it.isFocused) focusedTeam = team.id }
                        .onPreviewKeyEvent { event ->
                            when {
                                !arranging || !picked -> false
                                event.key == Key.DirectionRight -> {
                                    if (event.type == KeyEventType.KeyDown) { moving = null; save.requestFocus() }
                                    true
                                }
                                event.key == Key.DirectionUp || event.key == Key.DirectionDown -> {
                                    if (event.type == KeyEventType.KeyDown) {
                                        val from = teams.indexOfFirst { it.id == team.id }
                                        val to = from + if (event.key == Key.DirectionUp) -1 else 1
                                        if (from >= 0 && to in teams.indices) {
                                            onReorder(teams.toMutableList().apply { java.util.Collections.swap(this, from, to) })
                                        }
                                    }
                                    true
                                }
                                else -> false
                            }
                        },
                    idle = Modifier.background(if (selected || picked) stageAccent.accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.045f), shape)
                        .border(2.mpx, if (selected || picked) stageAccent.accent else Color.White.copy(alpha = 0.08f), shape)) { focused ->
                    Row(Modifier.fillMaxWidth().padding(10.mpx), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.mpx)) {
                        SportsBadge(badges[team.id] ?: team.badge, team.name, Modifier.size(70.mpx))
                        Text(team.name, style = stageText(18, 700),
                            color = if (focused) stageAccent.onAccent else if (selected || picked) stageAccent.accent else StageColors.Text,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (arranging) OwnTVIcon(OwnTVIcon.MOVE, if (focused) stageAccent.onAccent else StageColors.Muted, Modifier.size(18.mpx))
                    }
                }
            }
        }
        if (arranging) {
            StagePill(stringResource(R.string.common_save), onClick = { moving = null; onSave() },
                modifier = Modifier.fillMaxWidth().focusRequester(save)
                    .focusProperties { up = rows[focusedTeam] ?: FocusRequester.Default })
            StagePill(stringResource(R.string.common_cancel), onClick = { moving = null; onCancel() }, modifier = Modifier.fillMaxWidth())
        }
    }
}