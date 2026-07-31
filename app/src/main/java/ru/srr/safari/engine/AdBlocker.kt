package ru.srr.safari.engine

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Network ad/tracker blocker for WebView via shouldInterceptRequest.
 * Host list from assets + path heuristics. Does not block main-frame documents.
 */
class AdBlocker(context: Context) {
    @Volatile
    var enabled: Boolean = true

    /** Exact hosts + suffix hosts flattened into one set for label-walk matching. */
    private val blockedHosts = ConcurrentHashMap.newKeySet<String>()

    private val pathNeedles = listOf(
        "/ads/",
        "/ad/",
        "/advert",
        "/banner",
        "/pagead",
        "/pagead2",
        "/doubleclick",
        "/googlesyndication",
        "/googleadservices",
        "/adservice",
        "/sponsor",
        "/tracking",
        "/pixel.",
        "/beacon",
        "/collect?",
        "prebid",
        "adsystem",
        "adserver",
        "/ytimg.com/generate_204",
        "an.yandex.ru",
        "advertising",
        "yandex_ad",
        "yandexadexchange",
        "/adfstat",
        "/adfox",
        "pagead/js",
        "pagead/ads",
        "safe_frame",
        "imasdk.googleapis"
    )

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
            // keep empty — fail open
        }
    }

    fun shouldBlock(uri: Uri?, isMainFrame: Boolean): Boolean {
        if (!enabled || isMainFrame || uri == null) return false
        val scheme = uri.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (hostBlocked(host)) return true
        val path = buildString {
            append(uri.encodedPath.orEmpty())
            val q = uri.encodedQuery
            if (!q.isNullOrEmpty()) {
                append('?')
                append(q)
            }
        }.lowercase()
        if (path.isEmpty()) return false
        return pathNeedles.any { path.contains(it) }
    }

    /** Backward-compatible string entry used by older call sites. */
    fun shouldBlock(url: String?, isMainFrame: Boolean): Boolean {
        if (!enabled || isMainFrame || url.isNullOrBlank()) return false
        return shouldBlock(Uri.parse(url), isMainFrame)
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
            "utf-8",
            ByteArrayInputStream(emptyBody)
        )

    companion object {
        private const val ASSET = "adblock_hosts.txt"
    }
}
