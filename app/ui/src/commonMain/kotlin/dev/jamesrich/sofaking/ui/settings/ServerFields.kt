package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.jamesrich.sofaking.PairingFailure
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.data.DiscoveredServer
import dev.jamesrich.sofaking.data.ServerAddress
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.settings
import dev.jamesrich.sofaking.resources.settings_api_token
import dev.jamesrich.sofaking.resources.settings_api_token_help
import dev.jamesrich.sofaking.resources.settings_api_token_invalid
import dev.jamesrich.sofaking.resources.settings_discovered_server
import dev.jamesrich.sofaking.resources.settings_discovered_servers
import dev.jamesrich.sofaking.resources.settings_manage_server
import dev.jamesrich.sofaking.resources.settings_manage_server_summary
import dev.jamesrich.sofaking.resources.settings_pair
import dev.jamesrich.sofaking.resources.settings_pairing
import dev.jamesrich.sofaking.resources.settings_pairing_code
import dev.jamesrich.sofaking.resources.settings_pairing_code_invalid
import dev.jamesrich.sofaking.resources.settings_pairing_failed_code
import dev.jamesrich.sofaking.resources.settings_pairing_failed_server
import dev.jamesrich.sofaking.resources.settings_pairing_failed_unreachable
import dev.jamesrich.sofaking.resources.settings_pairing_help
import dev.jamesrich.sofaking.resources.settings_save
import dev.jamesrich.sofaking.resources.settings_section_server
import dev.jamesrich.sofaking.resources.settings_server_cancel
import dev.jamesrich.sofaking.resources.settings_server_change
import dev.jamesrich.sofaking.resources.settings_server_connected
import dev.jamesrich.sofaking.resources.settings_server_reload
import dev.jamesrich.sofaking.resources.settings_server_reloading
import dev.jamesrich.sofaking.resources.settings_server_unreachable
import dev.jamesrich.sofaking.resources.settings_server_url
import dev.jamesrich.sofaking.resources.settings_server_url_invalid
import dev.jamesrich.sofaking.resources.settings_server_use_page
import dev.jamesrich.sofaking.ui.TvTextField
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.pageServerUrl
import dev.jamesrich.sofaking.ui.rememberIsTelevision
import dev.jamesrich.sofaking.ui.requestFocusWhenAttached
import dev.jamesrich.sofaking.ui.theme.SofaButton
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The server block's fields: the address, the pairing code and the token, each starting at what is stored, where
 * focus goes in them, and whether the block is open on them ([changing]) rather than one line with Change.
 */
@Stable
internal class ServerFields(
    val serverUrl: TextFieldState,
    val apiToken: TextFieldState,
    val pairingCode: TextFieldState,
    changing: Boolean,
) {
    val serverFocus = FocusRequester()
    val tokenFocus = FocusRequester()
    val codeFocus = FocusRequester()
    var changing by mutableStateOf(changing)

    // Save only takes an address the server's API paths can be appended to and a token a header can carry;
    // the fields say what is missing. It stays off until either differs from what is stored.
    val validUrl: String? get() = ServerAddress.normalizeUrl(serverUrl.text.toString())
    val validToken: String? get() = ServerAddress.normalizeToken(apiToken.text.toString())
    val validCode: String? get() = ServerAddress.normalizeCode(pairingCode.text.toString())

    fun canSave(stored: AppSettings): Boolean = canSaveServer(stored, validUrl, validToken)

    fun save(stored: AppSettings, onSave: (serverUrl: String, apiToken: String) -> Unit) {
        val url = validUrl
        val token = validToken
        if (url != null && token != null && canSaveServer(stored, url, token)) onSave(url, token)
    }

    fun canPair(pairing: PairingUi): Boolean = canPairServer(validUrl, validCode, pairing.busy)

    fun pair(pairing: PairingUi) {
        val url = validUrl
        val code = validCode
        if (url != null && code != null && !pairing.busy) pairing.onPair(url, code)
    }

    /** Closes the fields, back on what is stored. */
    fun cancel(stored: AppSettings, pairing: PairingUi) {
        changing = false
        pairing.onDismiss()
        serverUrl.setTextAndPlaceCursorAtEnd(stored.serverUrl)
        apiToken.setTextAndPlaceCursorAtEnd(stored.apiToken)
        pairingCode.clearText()
    }
}

/**
 * The server block opens on its fields only on a device with no server, or while a pairing attempt is being shown;
 * otherwise it reads as one line with Change, and focus lands on Back (or, where no Back button is drawn, the first
 * setting) rather than inside a field. Change moves focus to the address.
 */
@Composable
internal fun rememberServerFields(settings: AppSettings, pairing: PairingUi): ServerFields {
    val serverUrl = rememberStoredFieldState(settings.serverUrl.ifEmpty { pageServerUrl.orEmpty() })
    val apiToken = rememberStoredFieldState(settings.apiToken)
    val pairingCode = rememberTextFieldState()
    val fields = remember {
        ServerFields(serverUrl, apiToken, pairingCode, settings.serverUrl.isEmpty() || pairing.failure != null)
    }
    LaunchedEffect(pairing.failure) { if (pairing.failure != null) fields.changing = true }
    LaunchedEffect(fields.changing) {
        if (fields.changing && settings.serverUrl.isNotEmpty()) requestFocusWhenAttached(fields.serverFocus)
    }
    return fields
}

/**
 * The server block, first on a device with none and after Playback on one that has it: the server and Change while
 * it is closed, and open ([open]), the servers [found] on the network, the address, the pairing code and the token.
 */
internal fun LazyListScope.serverSection(
    server: ServerFields,
    open: Boolean,
    settings: AppSettings,
    pairing: PairingUi,
    found: List<DiscoveredServer>,
    unreachable: Boolean,
    reloading: Boolean,
    onReload: () -> Unit,
    onSave: (serverUrl: String, apiToken: String) -> Unit,
) {
    item { SectionHeader(stringResource(Res.string.settings_section_server)) }
    if (!open) {
        item { ServerStatusRow(server, settings, unreachable, reloading, onReload) }
        item { ManageServerRow(settings) }
        return
    }
    // Picking a server found on the network fills the address; the pairing code is next.
    if (found.isNotEmpty()) item { DiscoveredServers(found, server) }
    item {
        SettingField(
            label = stringResource(Res.string.settings_server_url),
            state = server.serverUrl,
            // An empty field is not yet wrong; it says so once something is typed.
            problem = stringResource(Res.string.settings_server_url_invalid).takeIf {
                server.serverUrl.text.isNotBlank() && server.validUrl == null
            },
            focusRequester = server.serverFocus,
            imeAction = ImeAction.Next,
            onAction = { server.codeFocus.requestFocus(FocusDirection.Enter) },
        )
    }
    // The pairing code is the designed path; the token below it is the fallback.
    item { PairingFields(server, pairing) }
    item { TokenFields(server, settings, pairing, onSave) }
}

/**
 * Manage server: the server's admin portal in a browser, where its sources, devices and recordings are set. A TV has
 * no browser to open it in, so it shows none.
 */
@Composable
private fun ManageServerRow(settings: AppSettings) {
    val portal = ServerAddress.normalizeUrl(settings.serverUrl)?.let { "$it/admin" } ?: return
    if (rememberIsTelevision()) return
    val uriHandler = LocalUriHandler.current
    LinkRow(
        label = stringResource(Res.string.settings_manage_server),
        summary = stringResource(Res.string.settings_manage_server_summary),
        icon = SofaIcons.Dashboard,
        onClick = { uriHandler.openUri(portal) },
    )
}

/** The server in use, whether it answers, and Change, the page's own server where that may help, and Reload. */
@Composable
private fun ServerStatusRow(
    server: ServerFields,
    settings: AppSettings,
    unreachable: Boolean,
    reloading: Boolean,
    onReload: () -> Unit,
) {
    val status = if (unreachable) Res.string.settings_server_unreachable else Res.string.settings_server_connected
    SettingRow(label = stringResource(status, settings.serverUrl)) {
        SofaButton(onClick = { server.changing = true }) { Text(stringResource(Res.string.settings_server_change)) }
        // A web page that cannot reach a server elsewhere most likely sits behind the proxy that serves it.
        val page = pageServerUrl?.takeIf { unreachable && it != ServerAddress.normalizeUrl(settings.serverUrl) }
        if (page != null) {
            SofaButton(onClick = {
                server.serverUrl.setTextAndPlaceCursorAtEnd(page)
                server.changing = true
            }) { Text(stringResource(Res.string.settings_server_use_page)) }
        }
        // Stays enabled while it runs, so the D-pad focus never drops.
        SofaButton(onClick = { if (!reloading) onReload() }) {
            val reload = if (reloading) Res.string.settings_server_reloading else Res.string.settings_server_reload
            Text(stringResource(reload))
        }
    }
}

/** The servers found on the network; picking one fills the address and moves on to the pairing code. */
@Composable
private fun DiscoveredServers(found: List<DiscoveredServer>, server: ServerFields) {
    Column(Modifier.width(FIELD_WIDTH).focusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HelpText(stringResource(Res.string.settings_discovered_servers))
        found.forEach { discovered ->
            SofaButton(onClick = {
                server.serverUrl.setTextAndPlaceCursorAtEnd(discovered.url)
                server.codeFocus.requestFocus()
            }) {
                Text(
                    stringResource(
                        Res.string.settings_discovered_server,
                        discovered.name,
                        discovered.url.substringAfter("://"),
                    ),
                )
            }
        }
    }
}

/** The pairing code with Pair, and why the last claim failed. */
@Composable
private fun PairingFields(server: ServerFields, pairing: PairingUi) {
    val pair = { server.pair(pairing) }
    // The help sits above the field, where the on-screen keyboard does not cover it.
    Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HelpText(stringResource(Res.string.settings_pairing_help))
        SettingField(
            label = stringResource(Res.string.settings_pairing_code),
            state = server.pairingCode,
            problem = stringResource(Res.string.settings_pairing_code_invalid).takeIf {
                server.pairingCode.text.isNotEmpty() && server.validCode == null
            },
            focusRequester = server.codeFocus,
            imeAction = ImeAction.Done,
            onAction = pair,
        )
        SofaButton(main = true, onClick = pair, enabled = server.canPair(pairing)) {
            Text(stringResource(if (pairing.busy) Res.string.settings_pairing else Res.string.settings_pair))
        }
        pairing.failure?.let { failure ->
            Text(
                stringResource(
                    when (failure) {
                        PairingFailure.INVALID_CODE -> Res.string.settings_pairing_failed_code
                        PairingFailure.OLDER_SERVER -> Res.string.settings_pairing_failed_server
                        PairingFailure.UNREACHABLE -> Res.string.settings_pairing_failed_unreachable
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The API token, the fallback to pairing, with Save and, on a device that has a server, Cancel. */
@Composable
private fun TokenFields(
    server: ServerFields,
    settings: AppSettings,
    pairing: PairingUi,
    onSave: (serverUrl: String, apiToken: String) -> Unit,
) {
    val save = { server.save(settings, onSave) }
    Column(Modifier.width(FIELD_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingField(
            label = stringResource(Res.string.settings_api_token),
            state = server.apiToken,
            problem = stringResource(Res.string.settings_api_token_invalid).takeIf { server.validToken == null },
            focusRequester = server.tokenFocus,
            imeAction = ImeAction.Done,
            onAction = save,
            maskUnfocused = ::maskToken,
        )
        HelpText(stringResource(Res.string.settings_api_token_help))
        Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SofaButton(main = true, onClick = save, enabled = server.canSave(settings)) {
                Text(stringResource(Res.string.settings_save))
            }
            if (settings.serverUrl.isNotEmpty()) {
                SofaButton(onClick = { server.cancel(settings, pairing) }) {
                    Text(stringResource(Res.string.settings_server_cancel))
                }
            }
        }
    }
}

/**
 * Save & reload is on only for a valid address and token that differ from what is stored, since saving the
 * same server reloads for nothing.
 */
internal fun canSaveServer(stored: AppSettings, url: String?, token: String?): Boolean =
    url != null && token != null && (url != stored.serverUrl || token != stored.apiToken)

/** Pair is on for a valid address and a six-digit code while no claim is running. */
internal fun canPairServer(url: String?, code: String?, busy: Boolean): Boolean = url != null && code != null && !busy

/**
 * The one state of a field's text, starting at the saved [stored] value; only a change to that value replaces what
 * is typed. The screen reads and resets the field through it.
 */
@Composable
private fun rememberStoredFieldState(stored: String): TextFieldState {
    val state = rememberTextFieldState(stored)
    LaunchedEffect(stored) {
        if (state.text.toString() != stored) state.setTextAndPlaceCursorAtEnd(stored)
    }
    return state
}

@Composable
private fun SettingField(
    label: String,
    state: TextFieldState,
    problem: String?,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    onAction: () -> Unit,
    maskUnfocused: ((String) -> String)? = null,
) {
    Column(Modifier.width(FIELD_WIDTH)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        TvTextField(
            state = state,
            focusRequester = focusRequester,
            imeAction = imeAction,
            onAction = onAction,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            maskUnfocused = maskUnfocused,
        )
        if (problem != null) {
            Text(
                problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
