package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.data.LiveHit
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.guide_today
import dev.jamesrich.sofaking.resources.guide_tomorrow
import dev.jamesrich.sofaking.resources.on_later_when
import dev.jamesrich.sofaking.ui.ChannelLogo
import dev.jamesrich.sofaking.ui.airingWhen
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.rememberMinuteNow
import dev.jamesrich.sofaking.ui.rememberTimeFormatter
import dev.jamesrich.sofaking.ui.theme.TvFocusControl
import org.jetbrains.compose.resources.stringResource

/**
 * The search's live TV hits under [title], a card each: the channels to watch now, or with [later] the programs
 * coming up, each with its day and time. [onOpen] opens a hit's details.
 */
@Composable
internal fun LiveSearchRow(
    title: String,
    hits: List<LiveHit>,
    later: Boolean,
    insets: PaddingValues,
    focus: LiveRowFocus,
    onOpen: (LiveHit) -> Unit,
) {
    val now by rememberMinuteNow()
    val timeFormat = rememberTimeFormatter()
    val today = stringResource(Res.string.guide_today)
    val tomorrow = stringResource(Res.string.guide_tomorrow)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(title, insets)
        val fallback = when {
            hits.any { it.key == focus.returnKey } -> focus.returnTo
            focus.first != null -> focus.first
            else -> FocusRequester.Default
        }
        LazyRow(
            Modifier.focusRestorer(fallback),
            contentPadding = insets.horizontalOnly(),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            items(hits, key = { it.key }) { hit ->
                val modifier = when {
                    hit.key == focus.returnKey -> Modifier.focusRequester(focus.returnTo)
                    hit === hits.first() && focus.first != null -> Modifier.focusRequester(focus.first)
                    else -> Modifier
                }
                val start = hit.program?.start
                val channel = hit.channel.label()
                val supporting = if (later && start != null) {
                    val time = airingWhen(start, now, timeFormat, today, tomorrow)
                    stringResource(Res.string.on_later_when, time, channel)
                } else {
                    channel
                }
                LiveHitCard(hit, supporting, onClick = { onOpen(hit) }, modifier)
            }
        }
    }
}

/** One hit: the channel's logo beside its program, or its name where the guide has none, over [supporting]. */
@Composable
private fun LiveHitCard(hit: LiveHit, supporting: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvFocusControl(CardDefaults.shape, modifier, focusedScale = LIVE_FOCUS_SCALE) { ringed ->
        Card(onClick = onClick, modifier = ringed.width(LIVE_CARD_WIDTH)) {
            Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelLogo(hit.channel, LOGO_WIDTH, LOGO_HEIGHT)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        hit.program?.title ?: hit.channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val LIVE_CARD_WIDTH = 280.dp
private val LOGO_WIDTH = 64.dp
private val LOGO_HEIGHT = 48.dp

/** Wide cards grow less than posters on focus. */
private const val LIVE_FOCUS_SCALE = 1.05f
