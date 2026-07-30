package ru.srr.safari

import android.app.Application
import android.os.Build
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import ru.srr.safari.data.BrowserRepository

class SafariApp : Application() {
    lateinit var repository: BrowserRepository
        private set

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
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
}
