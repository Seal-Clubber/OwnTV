package tv.own.owntv.features.customize

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.*
import tv.own.owntv.ui.stage.*
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText
import tv.own.owntv.ui.theme.StageColors

@Composable
fun AddToCustomCategoryFlow(session: CategoryAdditionSession, onSaved: () -> Unit = {}) {
    val state by session.state.collectAsStateWithLifecycle()
    val s = state ?: return
    var editingName by remember { mutableStateOf(false) }
    var preview by remember(s.targetId) { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    if (editingName) {
        TextInputDialog(title = stringResource(R.string.settings_customize_new_category_title),
            hint = stringResource(R.string.category_add_description), allowBlank = false,
            onConfirm = { session.newCategory(it); editingName = false }, onDismiss = { editingName = false })
        return
    }
    val channels = session.channels.collectAsLazyPagingItems()
    LaunchedEffect(s.targetId, preview, channels.itemCount > 0) { runCatching { firstFocus.requestFocus() } }
    StagePopup(
        title = stringResource(R.string.category_add_title),
        body = if (s.failed) stringResource(R.string.category_add_failed) else stringResource(R.string.category_add_description),
        width = 880.mpx,
        onDismiss = { if (preview && !s.saving) preview = false else session.dismiss() },
        buttons = {
            OwnTVButton(stringResource(R.string.common_cancel), onClick = session::dismiss,
                enabled = !s.saving, style = OwnTVButtonStyle.SECONDARY)
            if (s.targetId != null) OwnTVButton(stringResource(R.string.category_add_preview),
                onClick = { preview = !preview }, enabled = !s.saving && s.count != null, style = OwnTVButtonStyle.SECONDARY,
                modifier = if (preview && channels.itemCount == 0) Modifier.focusRequester(firstFocus) else Modifier)
            OwnTVButton(stringResource(if (s.saving) R.string.category_add_saving else R.string.common_save),
                onClick = { session.save(onSaved) }, enabled = !s.saving && s.targetId != null && s.count != null)
        },
    ) {
        if (s.targetId != null) {
            Text(s.newName ?: s.targets.firstOrNull { it.id == s.targetId }?.displayName.orEmpty(), style = stageText(22, 700))
            s.count?.let { count -> Text(pluralStringResource(R.plurals.content_live_channel_count,
                count, count), style = stageText(18, 500)) }
        }
        if (preview) {
            LazyColumn(modifier = Modifier.heightIn(max = 420.mpx), verticalArrangement = Arrangement.spacedBy(4.mpx)) {
                items(channels.itemCount, key = { index -> channels.peek(index)?.id ?: -index.toLong() - 1 }) { index ->
                    val channel = channels[index] ?: return@items
                    val checked = channel.id !in s.exclusions
                    StagePopupOption(title = channel.name, enabled = !s.saving,
                        onClick = { session.toggleChannel(channel.id) },
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        leading = { focused -> StagePopupCheck(checked, focused) })
                }
            }
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 380.mpx), verticalArrangement = Arrangement.spacedBy(4.mpx)) {
                item { StagePopupOption(title = stringResource(R.string.settings_move_category_new),
                    onClick = { editingName = true }, enabled = !s.saving,
                    modifier = Modifier.focusRequester(firstFocus), leading = { StagePopupIcon(OwnTVIcon.ADD) }) }
                items(s.targets, key = { it.id }) { target ->
                    StagePopupOption(title = target.displayName, chosen = target.id == s.targetId,
                        enabled = !s.saving, onClick = { session.selectTarget(target.id) },
                        leading = { focused -> StagePopupRadio(target.id == s.targetId, focused) })
                }
                items(s.labels.take(20)) { label -> Text(label, style = stageText(16, 400), color = StageColors.Muted) }
            }
        }
    }
}
