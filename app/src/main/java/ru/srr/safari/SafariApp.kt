package ru.srr.safari

import android.app.Application
import android.os.Build
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import ru.srr.safari.data.BrowserRepository
import ru.srr.safari.data.BrowserSettings

class SafariApp : Application() {
    lateinit var repository: BrowserRepository
        private set

    override fun onCreate() {
        super.onCreate()
        applyThemeMode(BrowserSettings(this).themeMode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = Application.getProcessName()
            if (process != packageName) {
                try {
                    WebView.setDataDirectorySuffix(process)
                } catch (_: IllegalStateException) {
                }
            }
        }
        repository = BrowserRepository(this)
    }

    companion object {
        fun applyThemeMode(mode: String) {
            val night = when (mode) {
                BrowserSettings.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                BrowserSettings.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(night)
        }
    }
}
