package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.local.SongEntity
import com.example.ui.components.SongItem
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class GreetingScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun songItem_screenshot() {
        val sampleSong = SongEntity(
            id = 28444L,
            title = "چاو جوان و بهژن باریک",
            artist = "ناصر رزازی",
            coverUrl = null,
            streamUrl = "https://musickordi.com/sample.mp3",
            publishDate = System.currentTimeMillis(),
            tags = "شاد, هلپرکی, اصیل",
            isFavorite = true,
            isAvailable = true
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                SongItem(
                    song = sampleSong,
                    rank = 1,
                    isPlaying = true,
                    isCurrentSong = true,
                    onSongClick = {},
                    onFavoriteClick = {}
                )
            }
        }

        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
    }
}
