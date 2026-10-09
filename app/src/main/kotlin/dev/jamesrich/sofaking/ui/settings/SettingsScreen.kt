package dev.jamesrich.sofaking.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.jamesrich.sofaking.BuildConfig
import dev.jamesrich.sofaking.R
import dev.jamesrich.sofaking.data.AppSettings

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (serverUrl: String, profile: String) -> Unit,
    onBack: () -> Unit,
    channelCount: Int,
    modifier: Modifier = Modifier,
    onCaptionsChanged: (Boolean) -> Unit = {},
) {
    var serverUrl by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var profile by remember(settings.profile) { mutableStateOf(settings.profile) }
    var captionsOn by remember(settings.captionsEnabled) { mutableStateOf(settings.captionsEnabled) }
    val serverFocus = remember { FocusRequester() }
    val profileFocus = remember { FocusRequester() }

    BackHandler { onBack() }
    LaunchedEffect(Unit) { serverFocus.requestFocus() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        SettingField(
            label = "Server URL",
            value = serverUrl,
            onValueChange = { serverUrl = it },
            focusRequester = serverFocus,
            imeAction = ImeAction.Next,
            keyboardActions = KeyboardActions(onNext = { profileFocus.requestFocus() }),
        )
        SettingField(
            label = "Channel profile",
            value = profile,
            onValueChange = { profile = it },
            focusRequester = profileFocus,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { onSave(serverUrl, profile) }),
        )
        Text(
            "$channelCount channels loaded, version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.captions_setting), style = MaterialTheme.typography.titleSmall)
            Switch(
                checked = captionsOn,
                onCheckedChange = {
                    captionsOn = it
                    onCaptionsChanged(it)
                },
            )
        }
        Row(
            modifier = Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(onClick = { onSave(serverUrl, profile) }) {
                Text("Save & reload")
            }
            Button(onClick = onBack) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    keyboardActions: KeyboardActions,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val borderWidth = if (focused) 4.dp else 1.dp

    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .onFocusChanged { focused = it.hasFocus },
            shape = RoundedCornerShape(8.dp),
            border = Border(border = BorderStroke(borderWidth, borderColor)),
        ) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = imeAction),
                    keyboardActions = keyboardActions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
        }
    }
}
