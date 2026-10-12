package dev.jamesrich.sofaking.ui.guide

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.jamesrich.sofaking.R

/** The query the recognizer heard: its first non-blank result when it finished, else null (dismissed or heard nothing). */
internal fun voiceQuery(ok: Boolean, results: List<String>?): String? =
    if (!ok) null else results?.firstOrNull { it.isNotBlank() }?.trim()

/**
 * Speech input for the guide search through the TV's speech recognizer activity. The returned function starts
 * it and is false when no recognizer is installed, so the caller opens the text field instead; [onResult] gets
 * the spoken query, or null when the recognizer was dismissed.
 */
@Composable
internal fun rememberVoiceSearch(onResult: (String?) -> Unit): () -> Boolean {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        currentOnResult(voiceQuery(result.resultCode == Activity.RESULT_OK, result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)))
    }
    val prompt = stringResource(R.string.guide_search_voice_prompt)
    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        if (intent.resolveActivity(context.packageManager) == null) {
            false
        } else {
            try {
                launcher.launch(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }
}
