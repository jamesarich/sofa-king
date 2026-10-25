package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.util.author
import com.mikepenz.aboutlibraries.ui.compose.util.strippedLicenseContent
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryDetailMode
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryRow
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.licenses_no_text
import dev.jamesrich.sofaking.resources.licenses_title
import dev.jamesrich.sofaking.ui.BackHandler
import dev.jamesrich.sofaking.ui.LocalLayoutProfile
import dev.jamesrich.sofaking.ui.licenseTargets
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.screenPadding
import dev.jamesrich.sofaking.ui.theme.tvFocus
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

/**
 * The open-source libraries this build ships, as the AboutLibraries plugin listed them at build time, with their
 * licenses. A wide window and a TV show the list beside the focused library's license; a compact one opens the license
 * over the list, and Back returns to it.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val libs by produceLibraries { Res.readBytes(LICENSES_FILE).decodeToString() }
    val shown = remember(libs) { libs?.let { Libs(librariesFor(it.libraries, licenseTargets), it.licenses) } }
    val twoPane = !LocalLayoutProfile.current.compactWidth
    // The library whose license shows: the focused one beside the list, the opened one over it.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var lastOpen by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { LicensesFocus() }
    val listState = rememberLazyListState()
    val first = if (showsBackButton()) focus.back else focus.list
    val library = shown?.let { shownLibrary(it.libraries, open, twoPane) }

    BackHandler {
        if (licensesBackClosesLicense(twoPane, open)) open = null else onBack()
    }
    LicensesFocusEffects(shown != null, open, lastOpen, twoPane, focus, first)

    Column(modifier.fillMaxSize().screenPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle(stringResource(Res.string.licenses_title), onBack, focus.back)
        when {
            library == null || twoPane -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LibraryList(
                    shown,
                    selected = library?.uniqueId,
                    lastOpen = lastOpen,
                    focus = focus,
                    listState = listState,
                    onFocus = { if (twoPane) open = it.uniqueId },
                    onOpen = {
                        open = it.uniqueId
                        lastOpen = it.uniqueId
                        if (twoPane) focus.text.requestFocus()
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                if (library != null) LicenseText(library, focus.text, Modifier.weight(1f).fillMaxHeight())
            }

            else -> LicenseText(library, focus.text, Modifier.fillMaxSize())
        }
    }
}

/**
 * Focus on arrival, once the list is loaded, on [first]; over the list, an opened license takes focus, and Back from it
 * focuses the row it was opened from.
 */
@Composable
private fun LicensesFocusEffects(
    loaded: Boolean,
    open: String?,
    lastOpen: String?,
    twoPane: Boolean,
    focus: LicensesFocus,
    first: FocusRequester,
) {
    LaunchedEffect(loaded) { if (loaded) requestFocusWhenAttached(first) }
    LaunchedEffect(open) {
        if (twoPane) return@LaunchedEffect
        if (open != null) {
            requestFocusWhenAttached(focus.text)
        } else if (lastOpen != null) {
            requestFocusWhenAttached(focus.row)
        }
    }
}

/** Where focus goes on the licenses page: Back, the list, the row a license was opened from, and the license. */
private class LicensesFocus {
    val back = FocusRequester()
    val list = FocusRequester()
    val row = FocusRequester()
    val text = FocusRequester()
}

/** The library rows, drawn by AboutLibraries, each with the D-pad focus cue; [selected] is tinted. */
@Composable
private fun LibraryList(
    libs: Libs?,
    selected: String?,
    lastOpen: String?,
    focus: LicensesFocus,
    listState: LazyListState,
    onFocus: (Library) -> Unit,
    onOpen: (Library) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.medium
    LibrariesContainer(
        libraries = libs,
        modifier = modifier.focusRequester(focus.list).focusRestorer().focusGroup(),
        lazyListState = listState,
        detailMode = LibraryDetailMode.None,
        onLibraryClick = {
            onOpen(it)
            true
        },
        libraryRow = { _, library, _, toggle, style ->
            LibraryRow(
                library = library,
                // The library's own expanded tint marks the one whose license shows beside the list.
                expanded = library.uniqueId == selected,
                onToggle = toggle,
                style = style,
                modifier = Modifier
                    .then(if (library.uniqueId == lastOpen) Modifier.focusRequester(focus.row) else Modifier)
                    .tvFocus(shape, focusedScale = 1f)
                    .onFocusChanged { if (it.hasFocus) onFocus(library) },
            )
        },
    )
}

/**
 * A library's name, version, authors and licenses, then the license texts. The text takes focus, so Up and Down
 * scroll it on a remote or keyboard, and hand focus on once it reaches either end.
 */
@Composable
private fun LicenseText(library: Library, focusRequester: FocusRequester, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // A new library starts at the top of its license.
    LaunchedEffect(library.uniqueId) { scroll.scrollTo(0) }
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier
            .focusRequester(focusRequester)
            .tvFocus(shape, focusedScale = 1f)
            .onPreviewKeyEvent { event ->
                val step = licenseScrollStep(event.key, scroll.value, scroll.maxValue, scroll.viewportSize)
                if (step != null && event.type == KeyEventType.KeyDown) {
                    scope.launch { scroll.animateScrollBy(step.toFloat()) }
                }
                step != null
            }
            .focusable()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(library.name, style = MaterialTheme.typography.titleLarge)
        val details = listOfNotNull(library.artifactVersion, library.author.takeIf { it.isNotBlank() }, library.website)
        if (details.isNotEmpty()) {
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        library.licenses.forEach { license ->
            Text(license.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        val text = library.strippedLicenseContent.trim()
        Text(
            text.ifEmpty { stringResource(Res.string.licenses_no_text) },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The libraries [targets] ship; all of them when the list names no targets, as one built without them does. */
internal fun librariesFor(libraries: List<Library>, targets: Set<String>): List<Library> =
    libraries.filter { library -> library.targets.any { it in targets } }.ifEmpty { libraries }

/** The library whose license shows: the [open] one, or beside the list ([twoPane]) the first until one is focused. */
internal fun shownLibrary(libraries: List<Library>, open: String?, twoPane: Boolean): Library? =
    libraries.firstOrNull { it.uniqueId == open } ?: libraries.firstOrNull()?.takeIf { twoPane }

/** Back closes a license shown over the list; beside the list, or with none open, it leaves the page. */
internal fun licensesBackClosesLicense(twoPane: Boolean, open: String?): Boolean = !twoPane && open != null

/**
 * How far Up or Down scrolls a license of [max] pixels' scroll range, at [value], in a [viewport]: three quarters of
 * a screen, or what is left. Null for any other key, or at the end the key points to, so focus moves on instead.
 */
internal fun licenseScrollStep(key: Key, value: Int, max: Int, viewport: Int): Int? {
    val step = (viewport * 3 / 4).coerceAtLeast(1)
    return when (key) {
        Key.DirectionUp -> if (value > 0) -minOf(step, value) else null
        Key.DirectionDown -> if (value < max) minOf(step, max - value) else null
        else -> null
    }
}

/** The plugin's export, synced beside the common resources by the build. */
internal const val LICENSES_FILE = "files/aboutlibraries.json"

/** What Settings focuses on the way back from the licenses page. */
internal const val LICENSES_FOCUS_KEY = "licenses"
