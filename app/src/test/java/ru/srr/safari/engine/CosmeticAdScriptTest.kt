package ru.srr.safari.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CosmeticAdScriptTest {

    @Test
    fun hideCss_isInjectable() {
        assertThat(CosmeticAdScript.HIDE_CSS_JS).contains("srr-ad-hide-css")
        assertThat(CosmeticAdScript.HIDE_CSS_JS).contains("adsbygoogle")
    }

    @Test
    fun cosmeticsJs_hasNoLiveMutationObserver() {
        // Continuous MO froze pages until reload — must stay out of executable code.
        assertThat(CosmeticAdScript.JS).doesNotContain("new MutationObserver")
        assertThat(CosmeticAdScript.JS).contains("__srrAdSweep")
        assertThat(CosmeticAdScript.JS).contains("setTimeout")
    }

    @Test
    fun removeJs_clearsFlags() {
        assertThat(CosmeticAdScript.REMOVE_JS).contains("srr-ad-cosmetic")
        assertThat(CosmeticAdScript.REMOVE_JS).contains("__srrAdCosmetics")
    }
}
