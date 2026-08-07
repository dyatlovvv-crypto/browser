package ru.srr.safari.engine

import android.net.Uri
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
class AdBlockerTest {

    private lateinit var blocker: AdBlocker

    @Before
    fun setUp() {
        blocker = AdBlocker(ApplicationProvider.getApplicationContext())
        blocker.enabled = true
    }

    @Test
    fun neverBlocksMainFrame() {
        val uri = Uri.parse("https://doubleclick.net/page")
        assertThat(blocker.shouldBlock(uri, isMainFrame = true)).isFalse()
    }

    @Test
    fun blocksKnownAdHost() {
        val uri = Uri.parse("https://pagead2.googlesyndication.com/pagead/js")
        assertThat(blocker.shouldBlock(uri, isMainFrame = false)).isTrue()
    }

    @Test
    fun allowsNormalSites() {
        val uri = Uri.parse("https://www.google.com/search?q=test")
        assertThat(blocker.shouldBlock(uri, isMainFrame = false)).isFalse()
    }

    @Test
    fun allowsGoogleFirstPartyEvenIfPathLooksLikeAds() {
        // AI Mode / SERP load google.com URLs that match broad /ads/ needles
        val uri = Uri.parse("https://www.google.com/async/bgas?ei=1")
        assertThat(blocker.shouldBlock(uri, isMainFrame = false)).isFalse()
        val gstatic = Uri.parse("https://www.gstatic.com/og/_/js/ads.js")
        assertThat(blocker.shouldBlock(gstatic, isMainFrame = false)).isFalse()
    }

    @Test
    fun stillBlocksGoogleAdServices() {
        val uri = Uri.parse("https://www.googleadservices.com/pagead/aclk")
        assertThat(blocker.shouldBlock(uri, isMainFrame = false)).isTrue()
    }

    @Test
    fun disabled_blocksNothing() {
        blocker.enabled = false
        val uri = Uri.parse("https://doubleclick.net/ads")
        assertThat(blocker.shouldBlock(uri, isMainFrame = false)).isFalse()
    }
}
