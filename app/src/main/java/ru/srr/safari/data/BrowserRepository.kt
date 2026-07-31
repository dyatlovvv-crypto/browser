package ru.srr.safari.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.srr.safari.engine.UrlUtils
import java.io.File

/**
 * Lightweight JSON persistence (no Room/KSP) — fast enough for bookmarks/history/tabs.
 */
class BrowserRepository(context: Context) {
    private val dir = File(context.filesDir, "browser").also { it.mkdirs() }
    private val bookmarksFile = File(dir, "bookmarks.json")
    private val historyFile = File(dir, "history.json")
    private val tabsFile = File(dir, "tabs.json")
    private val mutex = Mutex()

    private val bookmarks = MutableStateFlow(loadBookmarks())
    private val history = MutableStateFlow(loadHistory())
    private val tabs = MutableStateFlow(loadTabs())

    fun observeBookmarks(): Flow<List<Bookmark>> = bookmarks.asStateFlow()
    fun observeHistory(): Flow<List<HistoryEntry>> = history.asStateFlow()
    fun observeTabs(private: Boolean): Flow<List<TabEntity>> =
        tabs.asStateFlow().map { list -> list.filter { it.isPrivate == private } }

    suspend fun addBookmark(title: String, url: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val nextId = (bookmarks.value.maxOfOrNull { it.id } ?: 0L) + 1L
            val list = listOf(Bookmark(id = nextId, title = title.ifBlank { url }, url = url)) +
                bookmarks.value.filterNot { it.url == url }
            bookmarks.value = list
            saveBookmarks(list)
        }
    }

    suspend fun removeBookmark(url: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = bookmarks.value.filterNot { it.url == url }
            bookmarks.value = list
            saveBookmarks(list)
        }
    }

    suspend fun isBookmarked(url: String): Boolean = bookmarks.value.any { it.url == url }

    suspend fun addHistory(title: String, url: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (url.isBlank() || url == "about:blank" || url.startsWith("chrome://") || url.startsWith("data:")) return@withContext
            val existing = history.value
            // Same URL already on top — skip disk rewrite
            if (existing.isNotEmpty() && existing.first().url == url) {
                if (existing.first().title != title.ifBlank { url }) {
                    val updated = existing.toMutableList()
                    updated[0] = existing.first().copy(title = title.ifBlank { url }, visitedAt = System.currentTimeMillis())
                    history.value = updated
                    saveHistory(updated)
                }
                return@withContext
            }
            val nextId = (existing.maxOfOrNull { it.id } ?: 0L) + 1L
            val entry = HistoryEntry(id = nextId, title = title.ifBlank { url }, url = url)
            val list = (listOf(entry) + existing.filterNot { it.url == url }).take(500)
            history.value = list
            saveHistory(list)
        }
    }

    suspend fun searchHistory(q: String): List<HistoryEntry> {
        if (q.isBlank()) return emptyList()
        val needle = q.lowercase()
        return history.value.filter {
            it.title.lowercase().contains(needle) || it.url.lowercase().contains(needle)
        }.take(20)
    }

    suspend fun clearHistory() = mutex.withLock {
        withContext(Dispatchers.IO) {
            history.value = emptyList()
            saveHistory(emptyList())
        }
    }

    suspend fun upsertTab(tab: TabEntity) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = listOf(tab) + tabs.value.filterNot { it.id == tab.id }
            tabs.value = list
            saveTabs(list)
        }
    }

    suspend fun deleteTab(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = tabs.value.filterNot { it.id == id }
            tabs.value = list
            saveTabs(list)
        }
    }

    suspend fun clearPrivateTabs() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = tabs.value.filterNot { it.isPrivate }
            tabs.value = list
            saveTabs(list)
        }
    }

    /** Non-private tabs for cold-start restore. */
    fun snapshotNormalTabs(): List<TabEntity> = tabs.value.filterNot { it.isPrivate }

    private fun loadBookmarks(): List<Bookmark> {
        if (!bookmarksFile.exists()) {
            val seeded = listOf(
                Bookmark(id = 1, title = "Google", url = "https://www.google.com"),
                Bookmark(id = 2, title = "YouTube", url = "https://www.youtube.com"),
                Bookmark(id = 3, title = "Wikipedia", url = "https://ru.wikipedia.org"),
                Bookmark(id = 4, title = "Apple", url = "https://www.apple.com"),
                Bookmark(id = 5, title = "Яндекс", url = "https://yandex.ru/search/"),
                Bookmark(id = 6, title = "GitHub", url = "https://github.com")
            )
            saveBookmarks(seeded)
            return seeded
        }
        return readArray(bookmarksFile) { o ->
            Bookmark(
                id = o.optLong("id"),
                title = o.optString("title"),
                url = rewriteYandexHome(o.optString("url")),
                faviconUrl = o.optString("faviconUrl").ifBlank { null },
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }

    suspend fun renameBookmark(url: String, newTitle: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = bookmarks.value.map {
                if (it.url == url) it.copy(title = newTitle.ifBlank { it.title }) else it
            }
            bookmarks.value = list
            saveBookmarks(list)
        }
    }

    private fun loadHistory(): List<HistoryEntry> = readArray(historyFile) { o ->
        HistoryEntry(
            id = o.optLong("id"),
            title = o.optString("title"),
            url = o.optString("url"),
            visitedAt = o.optLong("visitedAt", System.currentTimeMillis())
        )
    }

    private fun loadTabs(): List<TabEntity> = readArray(tabsFile) { o ->
        TabEntity(
            id = o.optString("id"),
            title = o.optString("title"),
            url = o.optString("url"),
            isPrivate = o.optBoolean("isPrivate"),
            lastActiveAt = o.optLong("lastActiveAt", System.currentTimeMillis()),
            thumbnailPath = o.optString("thumbnailPath").ifBlank { null }
        )
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        writeArray(bookmarksFile, list) { b ->
            JSONObject()
                .put("id", b.id)
                .put("title", b.title)
                .put("url", b.url)
                .put("faviconUrl", b.faviconUrl)
                .put("createdAt", b.createdAt)
        }
    }

    private fun saveHistory(list: List<HistoryEntry>) {
        writeArray(historyFile, list) { h ->
            JSONObject()
                .put("id", h.id)
                .put("title", h.title)
                .put("url", h.url)
                .put("visitedAt", h.visitedAt)
        }
    }

    private fun saveTabs(list: List<TabEntity>) {
        writeArray(tabsFile, list) { t ->
            JSONObject()
                .put("id", t.id)
                .put("title", t.title)
                .put("url", t.url)
                .put("isPrivate", t.isPrivate)
                .put("lastActiveAt", t.lastActiveAt)
                .put("thumbnailPath", t.thumbnailPath)
        }
    }

    private fun rewriteYandexHome(url: String): String = UrlUtils.rewriteKnownRedirects(url)

    private fun <T> readArray(file: File, map: (JSONObject) -> T): List<T> {
        return try {
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) add(map(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun <T> writeArray(file: File, list: List<T>, map: (T) -> JSONObject) {
        val arr = JSONArray()
        list.forEach { arr.put(map(it)) }
        file.writeText(arr.toString())
    }
}
