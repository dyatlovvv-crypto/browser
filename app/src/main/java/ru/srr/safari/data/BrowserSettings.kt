package ru.srr.safari.data

import android.content.Context

class BrowserSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var adBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_ADBLOCK, value).apply()

    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP, value).apply()

    /** default | ocean | dusk | forest | sand | custom */
    var wallpaperId: String
        get() = prefs.getString(KEY_WALLPAPER, "default") ?: "default"
        set(value) = prefs.edit().putString(KEY_WALLPAPER, value).apply()

    /** Liquid Glass body opacity, 40…100. Higher = denser / less see-through. */
    var glassOpacity: Int
        get() = prefs.getInt(KEY_GLASS_OPACITY, DEFAULT_GLASS_OPACITY).coerceIn(MIN_GLASS_OPACITY, MAX_GLASS_OPACITY)
        set(value) = prefs.edit()
            .putInt(KEY_GLASS_OPACITY, value.coerceIn(MIN_GLASS_OPACITY, MAX_GLASS_OPACITY))
            .apply()

    /** Page text zoom percent for WebView (75…175). */
    var textZoom: Int
        get() = prefs.getInt(KEY_TEXT_ZOOM, DEFAULT_TEXT_ZOOM).coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
        set(value) = prefs.edit()
            .putInt(KEY_TEXT_ZOOM, value.coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM))
            .apply()

    companion object {
        private const val PREFS = "safari_settings"
        private const val KEY_ADBLOCK = "adblock_enabled"
        private const val KEY_DESKTOP = "desktop_mode"
        private const val KEY_WALLPAPER = "wallpaper_id"
        private const val KEY_GLASS_OPACITY = "glass_opacity"
        private const val KEY_TEXT_ZOOM = "text_zoom"
        const val WALLPAPER_FILE = "start_wallpaper.jpg"
        const val MIN_GLASS_OPACITY = 40
        const val MAX_GLASS_OPACITY = 100
        const val DEFAULT_GLASS_OPACITY = 82
        const val MIN_TEXT_ZOOM = 75
        const val MAX_TEXT_ZOOM = 175
        const val DEFAULT_TEXT_ZOOM = 100
        const val TEXT_ZOOM_STEP = 10
    }
}
