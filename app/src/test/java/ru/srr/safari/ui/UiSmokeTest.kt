package ru.srr.safari.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.srr.safari.R
import ru.srr.safari.SafariApp

/**
 * Layout / chrome smoke without launching WebView Activity —
 * catches missing labels and broken link-peek menu rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SafariApp::class)
class UiSmokeTest {

    private lateinit var inflater: LayoutInflater

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.setTheme(R.style.Theme_Safari)
        inflater = LayoutInflater.from(app)
    }

    @Test
    fun mainLayout_hasStartFavoritesAndAddressHint() {
        val root = inflater.inflate(R.layout.activity_main, null, false)
        assertThat(root.findViewById<TextView>(R.id.startTitle)?.text?.toString())
            .isEqualTo("Избранное")
        assertThat(root.findViewById<EditText>(R.id.addressBar)?.hint?.toString())
            .isEqualTo("Запрос или сайт")
        assertThat(root.findViewById<View>(R.id.bottomChrome)).isNotNull()
        assertThat(root.findViewById<View>(R.id.btnBack)).isNotNull()
        assertThat(root.findViewById<View>(R.id.btnMore)).isNotNull()
    }

    @Test
    fun linkPreviewMenu_hasCopyAndShareRows() {
        val root = inflater.inflate(R.layout.dialog_link_preview, null, false)
        assertThat(root.findViewById<View>(R.id.linkActionCopy)).isNotNull()
        assertThat(root.findViewById<View>(R.id.linkActionShare)).isNotNull()
        assertThat(root.findViewById<View>(R.id.linkActionOpen)).isNotNull()
        assertThat(root.findViewById<View>(R.id.linkActionNewTab)).isNotNull()
        assertThat(root.findViewById<View>(R.id.linkActionReading)).isNotNull()
        assertThat(root.findViewById<View>(R.id.linkPreviewWeb)).isNotNull()
    }

    @Test
    fun moreMenu_hasTabsAndBookmarks() {
        val root = inflater.inflate(R.layout.dialog_safari_more, null, false)
        assertThat(root.findViewById<View>(R.id.moreTabs)).isNotNull()
        assertThat(root.findViewById<View>(R.id.moreBookmarks)).isNotNull()
        assertThat(root.findViewById<View>(R.id.moreNewTab)).isNotNull()
        assertThat(root.findViewById<View>(R.id.moreShare)).isNotNull()
    }

    @Test
    fun linkDragChip_inflatesTitleAndUrl() {
        val root = inflater.inflate(R.layout.view_link_drag_chip, null, false)
        assertThat(root.findViewById<TextView>(R.id.linkDragTitle)).isNotNull()
        assertThat(root.findViewById<TextView>(R.id.linkDragUrl)).isNotNull()
    }
}
