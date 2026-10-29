package dev.jamesrich.sofaking.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.RecordingPadding
import dev.jamesrich.sofaking.data.SeriesRule
import dev.jamesrich.sofaking.data.nextKeep
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.recordings_series
import dev.jamesrich.sofaking.resources.recordings_series_all_airings
import dev.jamesrich.sofaking.resources.recordings_series_any_channel
import dev.jamesrich.sofaking.resources.recordings_series_confirm_stop
import dev.jamesrich.sofaking.resources.recordings_series_keep_all
import dev.jamesrich.sofaking.resources.recordings_series_keep_all_short
import dev.jamesrich.sofaking.resources.recordings_series_keep_button
import dev.jamesrich.sofaking.resources.recordings_series_keeps
import dev.jamesrich.sofaking.resources.recordings_series_new_only
import dev.jamesrich.sofaking.resources.recordings_series_padding_button
import dev.jamesrich.sofaking.resources.recordings_series_padding_summary
import dev.jamesrich.sofaking.resources.recordings_series_stop
import dev.jamesrich.sofaking.resources.recordings_series_take_all
import dev.jamesrich.sofaking.resources.recordings_series_take_new
import dev.jamesrich.sofaking.resources.recordings_series_use_any_channel
import dev.jamesrich.sofaking.ui.paddingName
import dev.jamesrich.sofaking.ui.sectionHeading
import dev.jamesrich.sofaking.ui.theme.SofaButton
import org.jetbrains.compose.resources.stringResource

/** The Series heading and one row per series rule, when there are any. */
internal fun LazyListScope.seriesRules(
    rules: List<SeriesRule>,
    onRuleChange: suspend (SeriesRule, RuleEdit) -> Boolean,
    onRuleDelete: suspend (SeriesRule) -> Boolean,
) {
    if (rules.isEmpty()) return
    item(key = "series") {
        Text(
            stringResource(Res.string.recordings_series),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.sectionHeading(),
        )
    }
    items(rules, key = { "rule-${it.id}" }) { rule -> SeriesRow(rule, onRuleChange, onRuleDelete) }
}

/** One series rule: its title and what it takes and keeps, with buttons that change them and one that stops it. */
@Composable
private fun SeriesRow(
    rule: SeriesRule,
    onChange: suspend (SeriesRule, RuleEdit) -> Boolean,
    onDelete: suspend (SeriesRule) -> Boolean,
) {
    val actions = rememberRowActions(rule.id)
    Column(Modifier.widthIn(max = 960.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(rule.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            seriesSummary(rule),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RowButtons(actions) {
            SofaButton(onClick = { actions.run { onChange(rule, RuleEdit(!rule.newOnly, rule.keep)) } }) {
                val take = if (rule.newOnly) {
                    Res.string.recordings_series_take_all
                } else {
                    Res.string.recordings_series_take_new
                }
                Text(stringResource(take))
            }
            SofaButton(onClick = { actions.run { onChange(rule, RuleEdit(rule.newOnly, nextKeep(rule.keep))) } }) {
                val count = if (rule.keep == 0) {
                    stringResource(Res.string.recordings_series_keep_all_short)
                } else {
                    rule.keep.toString()
                }
                Text(stringResource(Res.string.recordings_series_keep_button, count))
            }
            val padding = nextPadding(rule.padding)
            SofaButton(
                onClick = { actions.run { onChange(rule, RuleEdit(rule.newOnly, rule.keep, padding = padding)) } },
            ) {
                Text(stringResource(Res.string.recordings_series_padding_button, paddingName(rule.padding)))
            }
            if (rule.channelId != null) {
                SofaButton(onClick = {
                    actions.run { onChange(rule, RuleEdit(rule.newOnly, rule.keep, anyChannel = true)) }
                }) {
                    Text(stringResource(Res.string.recordings_series_use_any_channel))
                }
            }
            val stopFocus = remember { FocusRequester() }
            SofaButton(modifier = Modifier.focusRequester(stopFocus), onClick = {
                actions.confirmThen { onDelete(rule) }
            }) {
                val stop = if (actions.confirming) {
                    Res.string.recordings_series_confirm_stop
                } else {
                    Res.string.recordings_series_stop
                }
                Text(stringResource(stop))
            }
            if (actions.confirming) KeepButton(actions, stopFocus)
        }
        if (actions.failed) ChangeFailed()
    }
}

/** What a rule takes and keeps, such as "New episodes only · Keeps 5 · Any channel". */
@Composable
private fun seriesSummary(rule: SeriesRule): String = listOfNotNull(
    stringResource(
        if (rule.newOnly) Res.string.recordings_series_new_only else Res.string.recordings_series_all_airings,
    ),
    if (rule.keep == 0) {
        stringResource(Res.string.recordings_series_keep_all)
    } else {
        stringResource(Res.string.recordings_series_keeps, rule.keep)
    },
    stringResource(Res.string.recordings_series_padding_summary, paddingName(rule.padding)),
    stringResource(Res.string.recordings_series_any_channel).takeIf { rule.channelId == null },
).joinToString(" · ")

/** The padding after [padding] in the Padding button's steps; a padding set on the portal goes to the default. */
internal fun nextPadding(padding: RecordingPadding?): RecordingPadding = padding?.next() ?: RecordingPadding.DEFAULT
