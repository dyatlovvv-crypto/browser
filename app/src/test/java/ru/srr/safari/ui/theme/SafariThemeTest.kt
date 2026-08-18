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
        assertThat(SafariElevation.card).isEqualTo(2)
    }
}
