# Safari browser — keep WebView bridges
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Authorship marks must survive minify
-keep class ru.srr.safari.BuildConfig { *; }

