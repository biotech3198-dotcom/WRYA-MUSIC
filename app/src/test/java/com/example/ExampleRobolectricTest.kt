package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Wrya Music", appName)
    }

    @Test
    fun `song entity creation test`() {
        val song = SongEntity(
            id = 28444L,
            title = "چاو جوان",
            artist = "ناصر رزازی",
            coverUrl = null,
            streamUrl = "https://musickordi.com/sample.mp3",
            publishDate = System.currentTimeMillis(),
            tags = "شاد, هلپرکی",
            isFavorite = true,
            isAvailable = true
        )
        assertEquals(28444L, song.id)
        assertEquals("ناصر رزازی", song.artist)
        assertNotNull(song.streamUrl)
    }
}
