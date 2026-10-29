package dev.jamesrich.sofaking

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.jamesrich.sofaking.previews.GuidePreview
import dev.jamesrich.sofaking.previews.LibraryHomePreview
import dev.jamesrich.sofaking.previews.LiveBarPreview
import dev.jamesrich.sofaking.previews.PlayerIconsPreview
import dev.jamesrich.sofaking.previews.PlayerInfoPreview
import dev.jamesrich.sofaking.previews.ProgramDetailsPreview
import dev.jamesrich.sofaking.previews.SettingsPreview
import dev.jamesrich.sofaking.previews.ShowPagePreview
import dev.jamesrich.sofaking.previews.VodBarPreview

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
private fun GuideTv() = GuidePreview(tv = true)

@PreviewTest
@Preview(device = PHONE)
@Composable
private fun GuidePhone() = GuidePreview(tv = false)

@PreviewTest
@Preview(device = TV)
@Composable
private fun ProgramDetailsTv() = ProgramDetailsPreview(tv = true)

@PreviewTest
@Preview(device = TV)
@Composable
private fun PlayerInfoTv() = PlayerInfoPreview(tv = true)

@PreviewTest
@Preview(device = PHONE)
@Composable
private fun PlayerInfoPhone() = PlayerInfoPreview(tv = false)

@PreviewTest
@Preview(device = TV)
@Composable
private fun LiveBarTv() = LiveBarPreview(tv = true)

@PreviewTest
@Preview(device = PHONE)
@Composable
private fun LiveBarPhone() = LiveBarPreview(tv = false)

@PreviewTest
@Preview(device = TV)
@Composable
private fun VodBarTv() = VodBarPreview(tv = true)

@PreviewTest
@Preview(device = PHONE)
@Composable
private fun VodBarPhone() = VodBarPreview(tv = false)

@PreviewTest
@Preview(device = PHONE)
@Composable
private fun PlayerIcons() = PlayerIconsPreview()

@PreviewTest
@Preview(device = TV)
@Composable
private fun LibraryHomeTv() = LibraryHomePreview(tv = true)

@PreviewTest
@Preview(device = PHONE)
@Composable
private fun LibraryHomePhone() = LibraryHomePreview(tv = false)

@PreviewTest
@Preview(device = TV)
@Composable
private fun ShowPageTv() = ShowPagePreview(tv = true)

@PreviewTest
@Preview(device = PHONE)
@Composable
private fun ShowPagePhone() = ShowPagePreview(tv = false)

@PreviewTest
@Preview(device = TV)
@Composable
private fun SettingsTv() = SettingsPreview(tv = true)

@PreviewTest
@Preview(device = PHONE)
@Composable
private fun SettingsPhone() = SettingsPreview(tv = false)
