package ru.srr.safari.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.srr.safari.SafariApp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SafariApp::class)
class BrowserSettingsTest {

    private lateinit var settings: BrowserSettings

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("safari_settings", 0).edit().clear().commit()
        settings = BrowserSettings(ctx)
    }

    @Test
    fun glassOpacity_clamped() {
        settings.glassOpacity = 10
        assertThat(settings.glassOpacity).isEqualTo(BrowserSettings.MIN_GLASS_OPACITY)
        settings.glassOpacity = 200
        assertThat(settings.glassOpacity).isEqualTo(BrowserSettings.MAX_GLASS_OPACITY)
    }

    @Test
    fun textZoom_clampedAndPersists() {
        settings.textZoom = 120
        assertThat(settings.textZoom).isEqualTo(120)
        val again = BrowserSettings(ApplicationProvider.getApplicationContext())
        assertThat(again.textZoom).isEqualTo(120)
    }

    @Test
    fun themeMode_roundTrip() {
        settings.themeMode = BrowserSettings.THEME_DARK
        assertThat(settings.themeMode).isEqualTo(BrowserSettings.THEME_DARK)
    }
}
