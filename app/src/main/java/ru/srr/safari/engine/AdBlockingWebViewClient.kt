package ru.srr.safari.engine

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * WebViewClient base: network ad intercept + CSS hide inject on page finish.
 * Subclass for app navigation / chrome logic.
 */
open class AdBlockingWebViewClient(
    protected val adBlocker: AdBlocker
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request != null && adBlocker.shouldBlock(request.url, request.isForMainFrame)) {
            return adBlocker.emptyResponse()
        }
        return null
    }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        if (adBlocker.shouldBlock(url, isMainFrame = false)) {
            return adBlocker.emptyResponse()
        }
        return null
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        injectAdHide(view)
    }

    /** CSS hide + cosmetic sweep. Safe to call multiple times. */
    protected fun injectAdHide(view: WebView?) {
        if (view == null || !adBlocker.enabled) return
        val url = view.url.orEmpty()
        // Google / gdebenz / other fragile SPAs — cosmetics blanked AI Mode
        if (UrlUtils.shouldSkipPageDomScripts(url)) return
        view.evaluateJavascript(CosmeticAdScript.HIDE_CSS_JS, null)
        view.evaluateJavascript(CosmeticAdScript.JS, null)
    }
}
