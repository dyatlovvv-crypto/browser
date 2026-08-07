package ru.srr.safari.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.srr.safari.data.BrowserSettings

class LiquidGlassTest {

    @Test
    fun alphaByte_clampsToSettingsRange() {
        assertThat(LiquidGlass.alphaByte(0))
            .isEqualTo((BrowserSettings.MIN_GLASS_OPACITY * 255) / 100)
        assertThat(LiquidGlass.alphaByte(100))
            .isEqualTo((BrowserSettings.MAX_GLASS_OPACITY * 255) / 100)
        assertThat(LiquidGlass.alphaByte(82)).isEqualTo((82 * 255) / 100)
    }
}
