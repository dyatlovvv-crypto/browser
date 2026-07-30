# Safari browser — keep WebView bridges
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class ru.srr.safari.BuildConfig { *; }

