package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.jamesrich.sofaking.previews.GuidePreview
import dev.jamesrich.sofaking.previews.LibraryHomePreview
import dev.jamesrich.sofaking.previews.PlayerInfoPreview
import dev.jamesrich.sofaking.previews.ProgramDetailsPreview
import dev.jamesrich.sofaking.previews.SettingsPreview
import dev.jamesrich.sofaking.previews.ShowPagePreview

/*
 * The main screens on a 1080p TV and a phone, compared against the reference images in
 * src/screenshotTestDefaultDebug/reference by `testDebugScreenshotTestDefaultTestSuite`;
 * `updateDebugScreenshotTestDefaultTestSuite` writes them again after an intended change.
 */

/** A 1080p TV: 1920 by 1080 pixels at 2x. */
private const val TV = "spec:width=960dp,height=540dp,dpi=320"

/** A phone held upright. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

@PreviewTest
@Preview(device = TV)
@Composable
fun GuideTv() = GuidePreview(tv = true)

@PreviewTest
@Preview(device = PHONE)
@Composable
fun GuidePhone() = GuidePreview(tv = false)

@PreviewTest
@Preview(device = TV)
@Composable
fun ProgramDetailsTv() = ProgramDetailsPreview(tv = true)

@PreviewTest
@Preview(device = TV)
@Composable
fun PlayerInfoTv() = PlayerInfoPreview(tv = true)

@PreviewTest
@Preview(device = PHONE)
@Composable
fun PlayerInfoPhone() = PlayerInfoPreview(tv = false)

@PreviewTest
@Preview(device = TV)
@Composable
fun LibraryHomeTv() = LibraryHomePreview(tv = true)

@PreviewTest
@Preview(device = PHONE)
@Composable
fun LibraryHomePhone() = LibraryHomePreview(tv = false)

@PreviewTest
@Preview(device = TV)
@Composable
fun ShowPageTv() = ShowPagePreview(tv = true)

@PreviewTest
@Preview(device = PHONE)
@Composable
fun ShowPagePhone() = ShowPagePreview(tv = false)

@PreviewTest
@Preview(device = TV)
@Composable
fun SettingsTv() = SettingsPreview(tv = true)

@PreviewTest
@Preview(device = PHONE)
@Composable
fun SettingsPhone() = SettingsPreview(tv = false)
