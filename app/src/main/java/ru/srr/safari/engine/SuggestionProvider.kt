package ru.srr.safari.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Suggestion(
    val text: String,
    val url: String?,
    val kind: Kind
) {
    enum class Kind { HISTORY, SEARCH, URL, BOOKMARK }
}

object SuggestionProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun remoteSuggestions(query: String): List<Suggestion> = withContext(Dispatchers.IO) {
        if (query.isBlank() || query.length < 2) return@withContext emptyList()
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://duckduckgo.com/ac/?q=$q&type=list")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string().orEmpty()
                val arr = JSONArray(body)
                if (arr.length() < 2) return@withContext emptyList()
                val phrases = arr.getJSONArray(1)
                buildList {
                    for (i in 0 until minOf(phrases.length(), 8)) {
                        val phrase = phrases.getString(i)
                        add(
                            Suggestion(
                                text = phrase,
                                url = null,
                                kind = Suggestion.Kind.SEARCH
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

object UrlUtils {
    private val domainRegex = Regex(
        """^(https?://)?([a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}(/.*)?$"""
    )

    fun normalizeInput(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "about:blank"
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("about:")) {
            return trimmed
        }
        return if (domainRegex.matches(trimmed) || trimmed.contains(".") && !trimmed.contains(" ")) {
            "https://$trimmed"
        } else {
            "https://www.google.com/search?q=${URLEncoder.encode(trimmed, "UTF-8")}&hl=ru"
        }
    }

    fun displayHost(url: String): String {
        return try {
            val u = java.net.URI(url)
            (u.host ?: url).removePrefix("www.")
        } catch (_: Exception) {
            url
        }
    }

    /** Дзен и «домашние» yandex/ya часто уводят в ленту — блокируем. */
    fun isDzenHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        return h == "dzen.ru" ||
            h.endsWith(".dzen.ru") ||
            h == "zen.yandex.ru" ||
            h.endsWith(".zen.yandex.ru") ||
            h.startsWith("dzen.yandex") ||
            h.contains("dzen.yandex")
    }

    fun rewriteKnownRedirects(url: String): String {
        val uri = try {
            java.net.URI(url)
        } catch (_: Exception) {
            return url
        }
        val host = uri.host?.lowercase().orEmpty()
        if (host.isEmpty()) return url
        if (isDzenHost(host)) return "https://www.google.com/"
        // Главная Яндекса / ya.ru на мобилке часто → Дзен
        val path = (uri.path ?: "/").trimEnd('/').ifEmpty { "/" }
        val isYandexHome = host in setOf(
            "yandex.ru", "www.yandex.ru", "m.yandex.ru",
            "ya.ru", "www.ya.ru"
        ) && (path == "/" || path.isEmpty())
        if (isYandexHome) return "https://yandex.ru/search/"
        // Google click-wrap /url?q=… → real destination for peek preview
        if (host.contains("google.") && (path == "/url" || path.startsWith("/url"))) {
            val q = uri.query.orEmpty()
            fun param(name: String): String? {
                q.split('&').forEach { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) return@forEach
                    if (part.substring(0, i) != name) return@forEach
                    return try {
                        java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
                    } catch (_: Exception) {
                        part.substring(i + 1)
                    }
                }
                return null
            }
            val target = param("q") ?: param("url")
            if (!target.isNullOrBlank() &&
                (target.startsWith("http://") || target.startsWith("https://"))
            ) {
                return target
            }
        }
        return url
    }
}
