package ru.srr.safari.data

data class Bookmark(
    val id: Long = 0,
    val title: String,
    val url: String,
    val faviconUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class HistoryEntry(
    val id: Long = 0,
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis()
)

data class TabEntity(
    val id: String,
    val title: String,
    val url: String,
    val isPrivate: Boolean,
    val lastActiveAt: Long = System.currentTimeMillis(),
    val thumbnailPath: String? = null
)
