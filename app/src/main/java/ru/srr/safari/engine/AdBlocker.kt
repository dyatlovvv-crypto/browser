package ru.srr.safari.engine

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Fast network ad/tracker blocker for [android.webkit.WebViewClient.shouldInterceptRequest].
 * Hosts: HashSet suffix walk. Host/path needles: precompiled [Pattern].
 * Does not block main-frame documents.
 */
class AdBlocker(context: Context) {
    @Volatile
    var enabled: Boolean = true

    /** Exact hosts + registrable suffixes from assets (no leading "*."). */
    private val blockedHosts = ConcurrentHashMap.newKeySet<String>()

    private val emptyBody = ByteArray(0)

    init {
        load(context)
    }

    fun load(context: Context) {
        blockedHosts.clear()
        try {
            context.assets.open(ASSET).bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trim().lowercase()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    if (line.startsWith("*.")) {
                        blockedHosts.add(line.removePrefix("*."))
                    } else {
                        blockedHosts.add(line)
                    }
                }
            }
        } catch (_: Exception) {
            // fail open
        }
        // Always-on fast labels (even if asset missing)
        blockedHosts.addAll(
            listOf(
                "doubleclick.net",
                "googlesyndication.com",
                "googleadservices.com",
                "googletagservices.com",
                "adservice.google.com",
                "adservice.google.ru",
                "pagead2.googlesyndication.com",
                "an.yandex.ru",
                "awaps.yandex.ru",
                "adfstat.yandex.ru",
                "yandexadexchange.net",
                "adfox.ru",
                "ads.adfox.ru"
            )
        )
    }

    fun shouldBlock(uri: Uri?, isMainFrame: Boolean): Boolean {
        if (!enabled || isMainFrame || uri == null) return false
        val scheme = uri.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        // Never touch Google first-party — AI Mode / SERP hang when /ads/ needles
        // match legitimate google.com paths or related CDNs.
        if (isGoogleFirstPartyHost(host)) return false
        if (hostBlocked(host)) return true
        if (HOST_NEEDLE.matcher(host).find()) return true
        val path = uri.encodedPath.orEmpty()
        val query = uri.encodedQuery
        val haystack = if (query.isNullOrEmpty()) {
            path.lowercase()
        } else {
            buildString(path.length + query.length + 1) {
                append(path)
                append('?')
                append(query)
            }.lowercase()
        }
        if (haystack.isEmpty()) return false
        return PATH_NEEDLE.matcher(haystack).find()
    }

    fun shouldBlock(url: String?, isMainFrame: Boolean): Boolean {
        if (!enabled || isMainFrame || url.isNullOrBlank()) return false
        return shouldBlock(Uri.parse(url), isMainFrame)
    }

    private fun isGoogleFirstPartyHost(host: String): Boolean {
        val h = host.removePrefix("www.")
        // Explicit allowlist only — do NOT use startsWith("google") (would allow googleadservices).
        return h == "google.com" || h.endsWith(".google.com") ||
            h == "google.ru" || h.endsWith(".google.ru") ||
            h == "google.com.ua" || h.endsWith(".google.com.ua") ||
            h == "gstatic.com" || h.endsWith(".gstatic.com") ||
            h == "googleapis.com" || h.endsWith(".googleapis.com") ||
            h == "googleusercontent.com" || h.endsWith(".googleusercontent.com") ||
            h == "ggpht.com" || h.endsWith(".ggpht.com") ||
            h == "googlezip.net" || h.endsWith(".googlezip.net") ||
            h == "googletagmanager.com" || h.endsWith(".googletagmanager.com")
    }

    private fun hostBlocked(host: String): Boolean {
        var h = host
        while (true) {
            if (blockedHosts.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(emptyBody)
        )

    companion object {
        private const val ASSET = "adblock_hosts.txt"

        /** Host labels: ads.*, doubleclick, adservice, googleads, … */
        private val HOST_NEEDLE: Pattern = Pattern.compile(
            "(^|\\.)(" +
                "ads|" +
                "adservice|" +
                "googleads|" +
                "doubleclick|" +
                "googlesyndication|" +
                "googleadservices|" +
                "pagead2|" +
                "adfox|" +
                "adnxs|" +
                "adsafeprotected|" +
                "moatads|" +
                "amazon-adsystem|" +
                "yandexadexchange|" +
                "advertising" +
                ")(\\.|$)",
            Pattern.CASE_INSENSITIVE
        )

        private val PATH_NEEDLE: Pattern = Pattern.compile(
            "/ads/|/ad/|/advert|/banner|/pagead|/pagead2|" +
                "doubleclick|googlesyndication|googleadservices|/adservice|" +
                "prebid|adsystem|adserver|yandex_ad|/adfstat|/adfox|" +
                "pagead/js|pagead/ads|safe_frame|imasdk|/vast|vast\\.xml|/vmap|" +
                "video-ad|video_ad|get-video-ad|adsdk|an\\.yandex",
            Pattern.CASE_INSENSITIVE
        )
    }
}
