package ru.srr.safari.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Free Google gtx endpoint — sentence/word chunks for in-page highlight translate.
 */
object PageTranslator {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun translate(text: String, from: String, to: String): String =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext text
            translateGtx(text, from, to) ?: text
        }

    /** Ordered batch translate (sequential — stable for free gtx). */
    suspend fun translateBatch(
        texts: List<String>,
        from: String,
        to: String
    ): List<String> = withContext(Dispatchers.IO) {
        texts.map { t ->
            if (t.isBlank()) t else (translateGtx(t, from, to) ?: t)
        }
    }

    private fun translateGtx(text: String, from: String, to: String): String? {
        return try {
            val q = URLEncoder.encode(text.take(900), "UTF-8")
            val sl = from.ifBlank { "auto" }
            val tl = to.ifBlank { "ru" }
            val url =
                "https://translate.googleapis.com/translate_a/single" +
                    "?client=gtx&sl=$sl&tl=$tl&dt=t&q=$q"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val root = JSONArray(body)
                val sentences = root.optJSONArray(0) ?: return null
                buildString {
                    for (i in 0 until sentences.length()) {
                        val row = sentences.optJSONArray(i) ?: continue
                        append(row.optString(0))
                    }
                }.ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun detectLikelyLang(sample: String): String {
        val cyr = sample.count { it in '\u0400'..'\u04FF' }
        val lat = sample.count { it.isLetter() && it.code < 128 }
        return if (cyr >= lat) "ru" else "en"
    }
}
