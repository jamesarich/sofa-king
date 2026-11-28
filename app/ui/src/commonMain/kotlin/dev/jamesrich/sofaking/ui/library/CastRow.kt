package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jamesrich.sofaking.data.CastPerson
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.billed
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.library_cast
import dev.jamesrich.sofaking.resources.library_members
import dev.jamesrich.sofaking.ui.rememberFadingRequest
import dev.jamesrich.sofaking.ui.sectionHeading
import dev.jamesrich.sofaking.ui.theme.SofaActionChip
import dev.jamesrich.sofaking.ui.theme.TvFocusControl
import org.jetbrains.compose.resources.stringResource

/**
 * The cast, or a band's members; OK on one searches the libraries for what else they are in. Where the server knows
 * a role or a photo for any of them, each is a card with their photo, name and role; otherwise each is a chip.
 */
@Composable
internal fun Cast(detail: MediaItemDetail, onPerson: (String) -> Unit, sides: PaddingValues) {
    val cast = castShown(detail.billed)
    val cards = cast.any { it.pictured }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(
                if (detail.item.kind == ItemKind.ARTIST) Res.string.library_members else Res.string.library_cast,
            ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(sides).sectionHeading(),
        )
        LazyRow(
            contentPadding = sides,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.focusRestorer(),
        ) {
            // Keyed by place too: one actor can be billed twice, for two roles.
            itemsIndexed(cast, key = { index, person -> "$index:${person.name}" }) { _, person ->
                if (cards) {
                    CastCard(person, onClick = { onPerson(person.name) })
                } else {
                    SofaActionChip(onClick = { onPerson(person.name) }, label = person.name)
                }
            }
        }
    }
}

/** One of the cast: their photo in a circle, or their initials where there is none, over their name and role. */
@Composable
private fun CastCard(person: CastPerson, onClick: () -> Unit) {
    TvFocusControl(CardDefaults.shape) { ringed ->
        Card(onClick = onClick, modifier = ringed.width(CAST_CARD_WIDTH)) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(CAST_PHOTO)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        castInitials(person.name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // The name under the photo already says who this is.
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                    person.photo?.let { photo ->
                        AsyncImage(
                            rememberFadingRequest(photo),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Text(
                    person.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Always a line, so the cards in a row are one height whether or not each has a role.
                Text(
                    person.role.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val CastPerson.pictured: Boolean get() = role != null || photo != null

/**
 * The cast a page lists: the first [CAST_CARDS_SHOWN] where any of them has a role or a photo and so shows as cards,
 * and the first [CAST_CHIPS_SHOWN] names otherwise, since a chip says no more than the name.
 */
internal fun castShown(cast: List<CastPerson>): List<CastPerson> =
    if (cast.take(CAST_CARDS_SHOWN).any { it.pictured }) cast.take(CAST_CARDS_SHOWN) else cast.take(CAST_CHIPS_SHOWN)

/** The first letters of a name's first and last words, which stand in for a photo. */
internal fun castInitials(name: String): String {
    val words = name.split(' ').filter { it.isNotBlank() }
    val letters = listOfNotNull(words.firstOrNull(), words.lastOrNull().takeIf { words.size > 1 })
    return letters.mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
}

private const val CAST_CHIPS_SHOWN = 6
private const val CAST_CARDS_SHOWN = 12
private val CAST_CARD_WIDTH = 132.dp
private val CAST_PHOTO = 72.dp
