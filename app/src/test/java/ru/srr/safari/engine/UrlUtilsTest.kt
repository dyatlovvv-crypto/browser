package ru.srr.safari.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun normalizeInput_blank_returnsAboutBlank() {
        assertThat(UrlUtils.normalizeInput("")).isEqualTo("about:blank")
        assertThat(UrlUtils.normalizeInput("   ")).isEqualTo("about:blank")
    }

    @Test
    fun normalizeInput_domain_getsHttps() {
        assertThat(UrlUtils.normalizeInput("example.com")).isEqualTo("https://example.com")
        assertThat(UrlUtils.normalizeInput("https://foo.bar/path")).isEqualTo("https://foo.bar/path")
    }

    @Test
    fun normalizeInput_searchQuery_goesToGoogle() {
        val out = UrlUtils.normalizeInput("мобилизация 2026")
        assertThat(out).startsWith("https://www.google.com/search?q=")
        assertThat(out).contains("hl=ru")
    }

    @Test
    fun displayHost_stripsWww() {
        assertThat(UrlUtils.displayHost("https://www.google.com/search?q=1")).isEqualTo("google.com")
    }

    @Test
    fun isDzenHost_detectsVariants() {
        assertThat(UrlUtils.isDzenHost("dzen.ru")).isTrue()
        assertThat(UrlUtils.isDzenHost("www.dzen.ru")).isTrue()
        assertThat(UrlUtils.isDzenHost("zen.yandex.ru")).isTrue()
        assertThat(UrlUtils.isDzenHost("google.com")).isFalse()
    }

    @Test
    fun rewriteKnownRedirects_dzen_toGoogle() {
        assertThat(UrlUtils.rewriteKnownRedirects("https://dzen.ru/news")).isEqualTo("https://www.google.com/")
    }

    @Test
    fun rewriteKnownRedirects_yandexHome_toSearch() {
        assertThat(UrlUtils.rewriteKnownRedirects("https://yandex.ru/")).isEqualTo("https://yandex.ru/search/")
        assertThat(UrlUtils.rewriteKnownRedirects("https://ya.ru")).isEqualTo("https://yandex.ru/search/")
    }

    @Test
    fun rewriteKnownRedirects_googleUrlWrap_unwraps() {
        val wrapped =
            "https://www.google.com/url?q=https%3A%2F%2Fwww.glavbukh.ru%2Fnews&sa=U"
        assertThat(UrlUtils.rewriteKnownRedirects(wrapped)).isEqualTo("https://www.glavbukh.ru/news")
    }

    @Test
    fun isFragileFullViewportUrl_googleAiMode() {
        assertThat(
            UrlUtils.isFragileFullViewportUrl("https://www.google.com/search?q=test&udm=50")
        ).isTrue()
        assertThat(
            UrlUtils.isFragileFullViewportUrl("https://www.google.com/search?q=test")
        ).isFalse()
    }

    @Test
    fun shouldSkipPageDomScripts_googleAndGdebenz() {
        assertThat(UrlUtils.shouldSkipPageDomScripts("https://www.google.com/search?q=1")).isTrue()
        assertThat(UrlUtils.shouldSkipPageDomScripts("https://gdebenz.ru/")).isTrue()
        assertThat(UrlUtils.shouldSkipPageDomScripts("https://example.com/")).isFalse()
    }
}
