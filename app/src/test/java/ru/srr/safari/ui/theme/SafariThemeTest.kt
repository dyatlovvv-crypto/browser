package ru.srr.safari.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SafariThemeTest {
    @Test
    fun spaceGrid_isFourEightSixteen() {
        assertThat(SafariSpace.xs).isEqualTo(4)
        assertThat(SafariSpace.sm).isEqualTo(8)
        assertThat(SafariSpace.md).isEqualTo(16)
        assertThat(SafariSpace.lg).isEqualTo(24)
        assertThat(SafariSpace.xl).isEqualTo(32)
        assertThat(SafariRadii.card).isEqualTo(20)
        assertThat(SafariRadii.capsule).isEqualTo(24)
        assertThat(SafariRadii.sheet).isEqualTo(28)
        assertThat(SafariElevation.card).isEqualTo(2)
        assertThat(SafariChrome.height).isEqualTo(52)
        assertThat(SafariChrome.btn).isEqualTo(44)
        assertThat(SafariChrome.btnPad).isEqualTo(10)
        assertThat(SafariChrome.icon).isEqualTo(28)
    }
}
