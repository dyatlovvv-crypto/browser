package ru.srr.safari

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.net.http.SslError
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import ru.srr.safari.data.Bookmark
import ru.srr.safari.data.BrowserRepository
import ru.srr.safari.data.BrowserSettings
import ru.srr.safari.data.HistoryEntry
import ru.srr.safari.databinding.ActivityMainBinding
import ru.srr.safari.databinding.DialogSafariMoreBinding
import ru.srr.safari.databinding.DialogTabsMenuBinding
import ru.srr.safari.databinding.ItemFavoriteBinding
import ru.srr.safari.databinding.ItemHistoryBinding
import ru.srr.safari.databinding.ItemHistorySectionBinding
import ru.srr.safari.databinding.ItemSimpleBinding
import ru.srr.safari.databinding.ItemSuggestionBinding
import ru.srr.safari.databinding.ItemTabBinding
import ru.srr.safari.engine.AdBlocker
import ru.srr.safari.engine.CosmeticAdScript
import ru.srr.safari.engine.InPageTranslateScript
import ru.srr.safari.engine.PageTranslator
import ru.srr.safari.engine.ReaderModeScript
import ru.srr.safari.engine.Suggestion
import ru.srr.safari.engine.SuggestionProvider
import ru.srr.safari.engine.UrlUtils
import ru.srr.safari.ui.AddressTabSwipe
import ru.srr.safari.ui.EdgeBackGesture
import ru.srr.safari.ui.GlassSheet
import ru.srr.safari.ui.LiquidGlass
import ru.srr.safari.ui.SafariMotion
import ru.srr.safari.ui.TabsModeLiquidSwitch
import androidx.dynamicanimation.animation.SpringForce
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.UUID
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: BrowserRepository
    private lateinit var settings: BrowserSettings
    private lateinit var adBlocker: AdBlocker

    private data class Tab(
        val id: String = UUID.randomUUID().toString(),
        var title: String = "Новая вкладка",
        var url: String = "",
        var isPrivate: Boolean = false,
        var isStartPage: Boolean = true
    )

    private val tabs = mutableListOf(Tab())
    private var activeId: String = tabs.first().id
    private var isPrivate = false
    private var suggestJob: Job? = null
    private var translateJob: Job? = null
    private var bookmarks: List<Bookmark> = emptyList()
    private var history: List<HistoryEntry> = emptyList()
    private var editingAddress = false
    private var chromeCollapsed = false
    private var lastJsScrollY = 0
    private var imeVisible = false
    private var suggestionsExpanded = false
    private var favoritesEditing = false
    private val tabPreviews = mutableMapOf<String, Bitmap>()
    private val previewDir by lazy {
        File(cacheDir, "tab_previews").also { it.mkdirs() }
    }
    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewCaptureRunnable: Runnable? = null
    private var previewHydrateJob: Job? = null
    private val historySectionExpanded = mutableMapOf<String, Boolean>()
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var tabsRestored = false
    private var edgeBack: EdgeBackGesture? = null
    private var addressTabSwipe: AddressTabSwipe? = null
    private var tabsModeLiquid: TabsModeLiquidSwitch? = null

    private val SCROLL_BOOT_JS = """
            (function(){
              if (window.__safariScrollHooked) return;
              window.__safariScrollHooked = true;
              var last = 0;
              var ticking = false;
              function report(){
                ticking = false;
                var y = window.scrollY || document.documentElement.scrollTop || document.body.scrollTop || 0;
                if (Math.abs(y - last) < 2) return;
                last = y;
                try { SafariChrome.onScroll(Math.round(y)); } catch(e) {}
              }
              window.addEventListener('scroll', function(){
                if (!ticking) { ticking = true; requestAnimationFrame(report); }
              }, {passive:true});
              document.addEventListener('scroll', function(){
                if (!ticking) { ticking = true; requestAnimationFrame(report); }
              }, {passive:true, capture:true});
            })();
        """.trimIndent()

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult
            val data = result.data
            val uris = when {
                result.resultCode != RESULT_OK -> null
                data?.clipData != null -> {
                    Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            callback.onReceiveValue(uris)
        }

    private val wallpaperPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { copyWallpaperFromUri(uri) }
                if (ok) {
                    settings.wallpaperId = "custom"
                    applyWallpaper()
                    Toast.makeText(this@MainActivity, "Обои обновлены", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Не удалось взять фото", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val defaultFavorites = listOf(
        Bookmark(title = "Google", url = "https://www.google.com"),
        Bookmark(title = "YouTube", url = "https://www.youtube.com"),
        Bookmark(title = "Wikipedia", url = "https://ru.wikipedia.org"),
        Bookmark(title = "Apple", url = "https://www.apple.com"),
        Bookmark(title = "Яндекс", url = "https://yandex.ru/search/"),
        Bookmark(title = "GitHub", url = "https://github.com")
    )

    private val active: Tab
        get() = tabs.find { it.id == activeId } ?: tabs.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        unlockHighRefreshRate()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = (application as SafariApp).repository
        settings = BrowserSettings(this)
        adBlocker = AdBlocker(this)
        adBlocker.enabled = settings.adBlockEnabled
        applyWallpaper()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeBottom = ime.bottom
            val navBottom = bars.bottom

            binding.contentContainer.updatePadding(top = bars.top)
            binding.historyOverlay.getChildAt(0)?.updatePadding(
                top = bars.top + (12 * resources.displayMetrics.density).toInt()
            )
            binding.tabsList.updatePadding(
                top = bars.top + (56 * resources.displayMetrics.density).toInt(),
                bottom = binding.tabsList.paddingBottom
            )
            (binding.btnTabsMenu.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                val top = bars.top + (8 * resources.displayMetrics.density).toInt()
                if (lp.topMargin != top) {
                    lp.topMargin = top
                    binding.btnTabsMenu.layoutParams = lp
                }
            }
            binding.tabsPrivateEmpty.updatePadding(
                top = bars.top + (100 * resources.displayMetrics.density).toInt()
            )
            binding.readerOverlay.updatePadding(top = bars.top, bottom = navBottom)

            binding.bottomChrome.updatePadding(bottom = maxOf(navBottom, imeBottom))
            // No opaque slab under chrome — transparent over page / wallpaper
            binding.bottomChrome.setBackgroundResource(android.R.color.transparent)
            binding.tabsBottomBar.updatePadding(bottom = 0)
            (binding.tabsBottomBar.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                val bottom = navBottom + (10 * resources.displayMetrics.density).toInt()
                if (lp.bottomMargin != bottom) {
                    lp.bottomMargin = bottom
                    binding.tabsBottomBar.layoutParams = lp
                }
            }

            val keyboardOpen = imeBottom > 0
            if (keyboardOpen != imeVisible) {
                imeVisible = keyboardOpen
                if (keyboardOpen) {
                    // Keyboard up: keep chrome pinned, no mid-collapse leftovers
                    chromeCollapsed = false
                    binding.bottomChrome.animate().cancel()
                    binding.bottomChrome.translationY = 0f
                    binding.bottomChrome.alpha = 1f
                    binding.bottomChrome.visibility = View.VISIBLE
                }
            }

            // WebView ignores View padding — shrink via bottomMargin so sticky page
            // inputs (Google "Ask a question") sit above the address bar.
            binding.bottomChrome.post { syncContentAboveChrome() }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        setupWebView()
        setupChrome()
        setupLists()
        observeData()
        restoreTabsIfNeeded()
        if (!tabsRestored) showStartPage()

        intent?.data?.toString()?.let { loadUrl(it) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.historyOverlay.visibility == View.VISIBLE -> hideHistory()
                    binding.tabsOverlay.visibility == View.VISIBLE -> hideTabs()
                    binding.readerOverlay.visibility == View.VISIBLE -> closeReader()
                    editingAddress -> cancelAddressEdit()
                    canGoBackInWebHistory() -> binding.webView.goBack()
                    else -> showExitConfirmDialog()
                }
            }
        })
    }

    /** Real page behind current entry — ignore about:blank ghosts that keep canGoBack() true. */
    private fun canGoBackInWebHistory(): Boolean {
        val wv = binding.webView
        if (!wv.canGoBack()) return false
        val list = wv.copyBackForwardList()
        val prevIndex = list.currentIndex - 1
        if (prevIndex < 0) return false
        val prev = list.getItemAtIndex(prevIndex)?.url.orEmpty()
        return prev.isNotBlank() && prev != "about:blank"
    }

    override fun onPause() {
        captureActiveTabPreview()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { loadUrl(it) }
    }

    private fun unlockHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val modes = windowManager.defaultDisplay.supportedModes
            val best = modes.maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.textZoom = this@MainActivity.settings.textZoom
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                settings.offscreenPreRaster = true
            }
            applyUserAgent()
            addJavascriptInterface(ScrollBridge(), "SafariChrome")
            try {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    CosmeticAdScript.JS + "\n" + SCROLL_BOOT_JS,
                    setOf("*")
                )
            } catch (_: Throwable) {
                // Older WebView — fall back to onPageFinished inject
            }
            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                onContentScroll(scrollY - oldScrollY, scrollY)
            }
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                enqueueDownload(url, userAgent, contentDisposition, mimeType)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    binding.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                    binding.progress.progress = newProgress
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val u = view?.url.orEmpty()
                    if (u.isBlank() || u == "about:blank") return
                    active.title = title?.ifBlank { UrlUtils.displayHost(u) } ?: active.title
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    return try {
                        val intent = fileChooserParams?.createIntent()
                            ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (_: Exception) {
                        this@MainActivity.filePathCallback = null
                        false
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString().orEmpty()
                    if (u.isBlank()) return true
                    if (u.startsWith("tel:") || u.startsWith("mailto:") || u.startsWith("sms:")) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                        } catch (_: Exception) {
                        }
                        return true
                    }
                    if (!(u.startsWith("http") || u.startsWith("about:"))) return true
                    val rewritten = UrlUtils.rewriteKnownRedirects(u)
                    if (rewritten != u) {
                        view?.loadUrl(rewritten)
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request == null) return null
                    return if (adBlocker.shouldBlock(request.url, request.isForMainFrame)) {
                        adBlocker.emptyResponse()
                    } else null
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    val u = url.orEmpty()
                    if (u == "about:blank") return
                    val rewritten = UrlUtils.rewriteKnownRedirects(u)
                    if (rewritten != u) {
                        view?.stopLoading()
                        view?.loadUrl(rewritten)
                        return
                    }
                    active.url = u
                    active.isStartPage = false
                    hideSuggestions()
                    if (!editingAddress) {
                        binding.addressBar.clearFocus()
                        refreshAddressDisplay()
                    }
                    showWeb()
                    setChromeCollapsed(false)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val u = url.orEmpty()
                    if (u.isBlank() || u == "about:blank") return
                    restoreWebViewMotion()
                    active.url = u
                    active.title = view?.title?.ifBlank { UrlUtils.displayHost(u) }
                        ?: UrlUtils.displayHost(u)
                    hideSuggestions()
                    if (!editingAddress) refreshAddressDisplay()
                    updateChromeForState()
                    injectScrollObserver()
                    // Cosmetic CSS usually already via document-start; re-apply cheaply if needed
                    if (adBlocker.enabled) {
                        view?.evaluateJavascript(CosmeticAdScript.JS, null)
                    }
                    if (!isPrivate) {
                        val title = active.title
                        lifecycleScope.launch { repo.addHistory(title, u) }
                    }
                    scheduleTabPreviewCapture()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame != true) return
                    val current = view?.url?.takeIf { it.isNotBlank() && it != "about:blank" }
                    if (current != null) {
                        active.url = current
                        active.title = UrlUtils.displayHost(current)
                        if (!editingAddress) refreshAddressDisplay()
                    }
                    val desc = error?.description?.toString().orEmpty()
                    Toast.makeText(
                        this@MainActivity,
                        "Не удалось загрузить страницу${if (desc.isNotBlank()) ": $desc" else ""}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Проблема с сертификатом")
                        .setMessage("Соединение может быть небезопасным. Открыть страницу всё равно?")
                        .setPositiveButton("Открыть") { _, _ -> handler?.proceed() }
                        .setNegativeButton("Отмена") { _, _ -> handler?.cancel() }
                        .setOnCancelListener { handler?.cancel() }
                        .show()
                }
            }
        }
    }

    private fun applyUserAgent() {
        binding.webView.settings.userAgentString = if (settings.desktopMode) {
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        } else {
            // System WebView UA — real Android version + device model (not a fake Pixel).
            WebSettings.getDefaultUserAgent(this)
        }
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent.orEmpty())
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setTitle(name)
                setDescription(url)
                setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, name)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Скачивание: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось скачать файл", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreTabsIfNeeded() {
        val saved = repo.snapshotNormalTabs().filter {
            it.url.isNotBlank() && it.url != "about:blank"
        }
        if (saved.isEmpty()) return
        tabs.clear()
        saved.forEach { entity ->
            tabs += Tab(
                id = entity.id.ifBlank { UUID.randomUUID().toString() },
                title = entity.title.ifBlank { UrlUtils.displayHost(entity.url) },
                url = entity.url,
                isPrivate = false,
                isStartPage = false
            )
        }
        if (tabs.isEmpty()) tabs += Tab()
        activeId = tabs.first().id
        isPrivate = false
        tabsRestored = true
        updateTabCount()
        applyPrivateUi()
        val first = tabs.first()
        if (first.url.isNotBlank()) loadUrl(first.url) else showStartPage()
    }

    private inner class ScrollBridge {
        @android.webkit.JavascriptInterface
        fun onScroll(y: Int) {
            runOnUiThread {
                val dy = y - lastJsScrollY
                lastJsScrollY = y
                onContentScroll(dy, y)
            }
        }
    }

    private fun injectScrollObserver() {
        binding.webView.evaluateJavascript(SCROLL_BOOT_JS, null)
    }

    private fun onContentScroll(dy: Int, scrollY: Int) {
        if (editingAddress || imeVisible || active.isStartPage ||
            binding.tabsOverlay.visibility == View.VISIBLE ||
            binding.historyOverlay.visibility == View.VISIBLE ||
            binding.readerOverlay.visibility == View.VISIBLE
        ) return
        when {
            dy > 10 && scrollY > 40 -> setChromeCollapsed(true)
            dy < -10 -> setChromeCollapsed(false)
            scrollY <= 8 -> setChromeCollapsed(false)
        }
    }

    private fun expandChromeNow() {
        chromeCollapsed = false
        binding.bottomChrome.animate().cancel()
        binding.bottomChrome.translationY = 0f
        binding.bottomChrome.visibility = View.VISIBLE
        binding.bottomChrome.alpha = 1f
        syncContentAboveChrome()
    }

    /**
     * Overlays and normal browsing are full-bleed under floating chrome (no grey
     * slab). Only reserve space when IME is up so inputs stay above the keyboard.
     */
    private fun syncContentAboveChrome() {
        val chrome = binding.bottomChrome
        val overlayOpen =
            binding.tabsOverlay.visibility == View.VISIBLE ||
                binding.historyOverlay.visibility == View.VISIBLE
        val reserve = when {
            overlayOpen -> 0
            imeVisible -> chrome.height
            else -> 0
        }
        val lp = binding.contentContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        if (lp.bottomMargin != reserve) {
            lp.bottomMargin = reserve
            binding.contentContainer.layoutParams = lp
        }
        // Start page always keeps room so favorites aren't under the address bar
        val startPad = (130 * resources.displayMetrics.density).toInt()
        if (binding.startPage.paddingBottom != startPad) {
            binding.startPage.updatePadding(bottom = startPad)
        }
    }

    private fun setChromeCollapsed(collapsed: Boolean) {
        if (imeVisible && collapsed) return
        if (editingAddress && collapsed) return
        if (chromeCollapsed == collapsed) {
            if (!collapsed) expandChromeNow()
            return
        }
        chromeCollapsed = collapsed
        val chrome = binding.bottomChrome
        chrome.animate().cancel()
        chrome.post {
            val h = chrome.height.toFloat().coerceAtLeast(1f)
            if (collapsed) {
                hideSuggestions()
                syncContentAboveChrome()
                chrome.animate()
                    .translationY(h)
                    .alpha(0.92f)
                    .setDuration(SafariMotion.CHROME)
                    .setInterpolator(SafariMotion.softIn)
                    .start()
            } else {
                chrome.visibility = View.VISIBLE
                syncContentAboveChrome()
                chrome.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(SafariMotion.CHROME)
                    .setInterpolator(SafariMotion.softOut)
                    .start()
            }
        }
    }

    private fun hideSuggestions() {
        suggestJob?.cancel()
        val list = binding.suggestionsList
        if (list.visibility != View.VISIBLE) return
        SafariMotion.disappear(list, toScale = 1f, toY = 6f * resources.displayMetrics.density, duration = 120L) {
            list.visibility = View.GONE
            list.adapter = null
            SafariMotion.reset(list)
        }
    }

    /** Opaque sheet over the page while editing the address — Safari-like, hides site chrome. */
    private fun showSearchCover() {
        val list = binding.suggestionsList
        val wasHidden = list.visibility != View.VISIBLE
        list.visibility = View.VISIBLE
        if (list.adapter == null) {
            list.adapter = SuggestionAdapter(emptyList()) {}
        }
        if (wasHidden) {
            SafariMotion.appear(list, fromScale = 1f, fromY = 10f * resources.displayMetrics.density, duration = 130L)
        }
    }

    private fun setupChrome() {
        LiquidGlass.polishChrome(binding.bottomChrome)
        // No press OnTouchListener here — AddressTabSwipe owns capsule touches
        LiquidGlass.polishCapsule(binding.addressCapsule, pressFeedback = false)
        LiquidGlass.polishCircle(binding.btnBack)
        LiquidGlass.polishCircle(binding.btnMore)
        LiquidGlass.polishSheet(binding.tabsOverlay)
        LiquidGlass.polishSheet(binding.historyOverlay)
        LiquidGlass.polishCircle(binding.btnNewTab)
        LiquidGlass.polishCircle(binding.btnTabsDone)
        LiquidGlass.polishCircle(binding.btnTabsMenu)
        setupTabsModeSwipe()
        setupAddressTabSwipe()
        setupEdgeBackGesture()
        refreshTabsModeChrome()
        LiquidGlass.polishCapsule(binding.historyToolbar, 22f)
        LiquidGlass.polishSheet(binding.suggestionsList)
        LiquidGlass.polishSheet(binding.btnCloseReader)
        binding.addressBar.setTextAppearance(R.style.TextAppearance_Safari_Address)
        applyGlassOpacity()

        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submitAddress()
                true
            } else false
        }
        binding.addressBar.setOnFocusChangeListener { _, hasFocus ->
            editingAddress = hasFocus
            if (hasFocus) {
                expandChromeNow()
                binding.btnAa.setImageResource(R.drawable.ic_search)
                val full = active.url.takeIf { it.isNotBlank() && it != "about:blank" }.orEmpty()
                if (full.isNotEmpty()) {
                    binding.addressBar.setText(full)
                    // Как в Safari: вся ссылка выделена, сразу можно печатать новый запрос
                    binding.addressBar.post {
                        binding.addressBar.selectAll()
                    }
                }
                showSearchCover()
                updateChromeForState()
            } else {
                hideSuggestions()
                updateChromeForState()
            }
        }
        binding.addressBar.addTextChangedListener(SimpleTextWatcher { text ->
            if (editingAddress) {
                binding.btnClearField.visibility =
                    if (text.isNotBlank()) View.VISIBLE else View.GONE
            }
            if (!editingAddress) {
                hideSuggestions()
                return@SimpleTextWatcher
            }
            // Keep opaque cover over the page (hides Google's own search chrome)
            if (text.isBlank() || text == "about:blank" || text.length < 2) {
                showSearchCover()
                return@SimpleTextWatcher
            }
            suggestJob?.cancel()
            suggestJob = lifecycleScope.launch {
                delay(140)
                if (!editingAddress) return@launch
                val hist = repo.searchHistory(text)
                    .filter { it.url != "about:blank" && !it.title.equals("about:blank", true) }
                    .map { Suggestion(it.title.ifBlank { it.url }, it.url, Suggestion.Kind.HISTORY) }
                val remote = SuggestionProvider.remoteSuggestions(text)
                    .filter { !it.text.contains("about:blank", ignoreCase = true) }
                showSuggestions((hist + remote).distinctBy { it.text.lowercase() }.take(8))
            }
        })

        binding.btnBack.setOnClickListener { navigateBack() }
        binding.btnForward.setOnClickListener { binding.webView.goForward() }
        binding.btnReload.setOnClickListener {
            if (binding.webView.progress in 1..99) binding.webView.stopLoading()
            else if (!active.isStartPage) binding.webView.reload()
        }
        binding.btnClearField.setOnClickListener {
            binding.addressBar.setText("")
            binding.addressBar.requestFocus()
            showKeyboard()
        }
        binding.btnShare.setOnClickListener { showShareMenu() }
        binding.btnBookmarks.setOnClickListener { showBookmarks() }
        binding.btnTabs.setOnClickListener { showTabs() }
        binding.btnMore.setOnClickListener { showMoreMenu() }
        binding.btnTabsDone.setOnClickListener {
            ensureActiveTabInMode()
            hideTabs()
        }
        binding.btnNewTab.setOnClickListener { newTab(isPrivate) }
        binding.btnTabsMenu.setOnClickListener { showTabsMenu() }
        binding.tabsCountPill.setOnClickListener { switchTabsMode(!isPrivate) }
        binding.btnTogglePrivate.setOnClickListener { switchTabsMode(!isPrivate) }
        binding.btnAa.setOnClickListener {
            if (editingAddress) return@setOnClickListener
            if (active.isStartPage) {
                binding.addressBar.requestFocus()
                showKeyboard()
            } else {
                showPageMenu()
            }
        }
        binding.btnCloseReader.setOnClickListener { closeReader() }
        binding.btnCollapseFav.setOnClickListener {
            lifecycleScope.launch {
                if (bookmarks.isEmpty()) {
                    defaultFavorites.forEach { repo.addBookmark(it.title, it.url) }
                }
                favoritesEditing = !favoritesEditing
                binding.btnCollapseFav.text = if (favoritesEditing) "Готово" else "Изменить"
                binding.favoritesList.visibility = View.VISIBLE
                refreshStartPage()
            }
        }
        binding.btnAllHistory.setOnClickListener { showHistory() }
        binding.recentHeader.setOnClickListener { toggleSuggestionsExpanded() }
        binding.btnHistoryDone.setOnClickListener { hideHistory() }
        binding.btnClearHistory.setOnClickListener {
            lifecycleScope.launch {
                repo.clearHistory()
                hideHistory()
                Toast.makeText(this@MainActivity, "История очищена", Toast.LENGTH_SHORT).show()
            }
        }
        updateChromeForState()
    }

    private fun toggleSuggestionsExpanded() {
        suggestionsExpanded = !suggestionsExpanded
        applySuggestionsExpanded()
    }

    private fun applySuggestionsExpanded() {
        if (binding.recentHeaderRow.visibility != View.VISIBLE) {
            binding.recentList.visibility = View.GONE
            return
        }
        binding.recentList.visibility = if (suggestionsExpanded) View.VISIBLE else View.GONE
        binding.recentHeader.text = if (suggestionsExpanded) "Предложения ▸" else "Предложения ▾"
    }

    /** Назад как в Safari: история WebView, иначе стартовая; about:blank = старт */
    private fun navigateBack() {
        if (active.isStartPage) return
        if (binding.webView.canGoBack()) {
            val list = binding.webView.copyBackForwardList()
            val prevIndex = list.currentIndex - 1
            val prevUrl = if (prevIndex >= 0) list.getItemAtIndex(prevIndex)?.url.orEmpty() else ""
            if (prevUrl.isBlank() || prevUrl == "about:blank") {
                showStartPage()
                return
            }
            binding.webView.goBack()
            binding.webView.post {
                val u = binding.webView.url.orEmpty()
                if (u.isBlank() || u == "about:blank") showStartPage()
                else updateChromeForState()
            }
        } else {
            showStartPage()
        }
    }

    private fun cancelAddressEdit() {
        binding.addressBar.clearFocus()
        hideKeyboard()
        hideSuggestions()
        updateChromeForState()
    }

    private fun canNavigateBack(): Boolean {
        if (active.isStartPage || active.url.isBlank() || active.url == "about:blank") return false
        return true
    }

    private fun showMoreMenu() {
        val menu = DialogSafariMoreBinding.inflate(layoutInflater)
        val width = (228 * resources.displayMetrics.density).toInt()
        val popup = PopupWindow(
            menu.root,
            width,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 24f
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        // скругление + liquid glass card
        menu.root.clipToOutline = true
        (menu.root.getChildAt(0) as? View)?.let { card ->
            LiquidGlass.polishCapsule(card, 26f)
            card.background = LiquidGlass.menuPopoverDrawable(
                this,
                settings.glassOpacity.coerceAtLeast(72),
                26f,
                privateMode = isPrivate
            )
        }
        fun closeAnd(action: () -> Unit) {
            val card = menu.root.getChildAt(0)
            if (card != null) {
                SafariMotion.disappear(card, toScale = 0.94f, toY = 8f * resources.displayMetrics.density, duration = 110L) {
                    popup.dismiss()
                    action()
                }
            } else {
                popup.dismiss()
                action()
            }
        }
        fun addCurrentBookmark() {
            val url = active.url
            if (url.isNotBlank() && url != "about:blank") {
                lifecycleScope.launch {
                    repo.addBookmark(active.title, url)
                    Toast.makeText(this@MainActivity, "В закладках", Toast.LENGTH_SHORT).show()
                }
            }
        }
        menu.moreShare.setOnClickListener { closeAnd { showShareMenu() } }
        menu.moreAddFav.setOnClickListener { closeAnd { addCurrentBookmark() } }
        menu.moreAddFolder.setOnClickListener { closeAnd { addCurrentBookmark() } }
        menu.moreNewTab.setOnClickListener { closeAnd { newTab(false) } }
        menu.moreNewPrivate.setOnClickListener { closeAnd { newTab(true) } }
        menu.moreSettings.setOnClickListener { closeAnd { showSettingsMenu() } }
        menu.moreBookmarks.setOnClickListener { closeAnd { showBookmarks() } }
        menu.moreTabs.setOnClickListener { closeAnd { showTabs() } }
        menu.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val yOff = -(menu.root.measuredHeight + binding.btnMore.height + (8 * resources.displayMetrics.density).toInt())
        val xOff = binding.btnMore.width - width
        popup.showAsDropDown(binding.btnMore, xOff, yOff)
        (menu.root.getChildAt(0) as? View)?.let { card ->
            SafariMotion.appear(
                card,
                fromScale = 0.92f,
                fromY = 10f * resources.displayMetrics.density,
                duration = SafariMotion.POPOVER
            )
        }
    }

    private fun applyGlassOpacity() {
        val o = settings.glassOpacity
        binding.addressCapsule.background = LiquidGlass.capsuleDrawable(this, o, 22f)
        binding.btnBack.background = LiquidGlass.circleDrawable(this, o)
        binding.btnMore.background = LiquidGlass.circleDrawable(this, o)
        binding.btnNewTab.background = LiquidGlass.circleDrawable(this, o)
        binding.btnTabsMenu.background = LiquidGlass.circleDrawable(this, o)
        binding.historyToolbar.background = LiquidGlass.capsuleDrawable(this, o, 22f)
        LiquidGlass.applyOpacity(binding.btnTabsDone, o)
        // Tabs overlay backdrop stays dense — page must not bleed through
        LiquidGlass.applyOpacity(binding.historyOverlay, o)
        LiquidGlass.applyOpacity(binding.suggestionsList, o)
        LiquidGlass.applyOpacity(binding.btnCloseReader, o)
        refreshTabsModeChrome()
        syncTabsModeTrack(animate = false)
        // Glass is set in onCreateViewHolder — recreate rows when opacity changes
        binding.favoritesList.adapter = null
        binding.tabsList.adapter = null
        refreshStartPage()
        if (binding.tabsOverlay.visibility == View.VISIBLE) {
            renderTabs(syncMode = false)
        }
    }

    private fun showGlassOpacityPicker() {
        val dialog = android.app.Dialog(this, R.style.Theme_Safari_GlassDialog)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        val sheet = ru.srr.safari.databinding.DialogGlassOpacityBinding.inflate(layoutInflater)
        fun paint(value: Int) {
            sheet.opacityValue.text = "$value%"
            sheet.opacityPreview.background = LiquidGlass.capsuleDrawable(this, value, 22f)
            sheet.opacityCard.background = LiquidGlass.menuPopoverDrawable(
                this,
                value.coerceAtLeast(72),
                26f,
                privateMode = isPrivate
            )
        }
        val current = settings.glassOpacity
        sheet.opacitySeek.max = BrowserSettings.MAX_GLASS_OPACITY - BrowserSettings.MIN_GLASS_OPACITY
        sheet.opacitySeek.progress = current - BrowserSettings.MIN_GLASS_OPACITY
        paint(current)
        LiquidGlass.polishCapsule(sheet.opacityCard, 26f)
        LiquidGlass.polishCapsule(sheet.opacityPreview, 22f)
        sheet.opacitySeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + BrowserSettings.MIN_GLASS_OPACITY
                settings.glassOpacity = value
                paint(value)
                applyGlassOpacity()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        sheet.opacityDone.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(sheet.root)
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        SafariMotion.appear(sheet.opacityCard, fromScale = 0.94f, fromY = 12f * resources.displayMetrics.density)
    }

    private fun showSettingsMenu() {
        val adLabel = if (settings.adBlockEnabled) "Блокировка рекламы: вкл." else "Блокировка рекламы: выкл."
        val themeLabel = when (settings.themeMode) {
            BrowserSettings.THEME_LIGHT -> "Тема: день"
            BrowserSettings.THEME_DARK -> "Тема: ночь"
            else -> "Тема: системная"
        }
        GlassSheet.showList(
            this,
            title = "Настройки",
            items = listOf(
                GlassSheet.Item(adLabel) { toggleAdBlock(reloadPage = false) },
                GlassSheet.Item(themeLabel) { cycleThemeMode() },
                GlassSheet.Item("Обои стартовой") { showWallpaperPicker() },
                GlassSheet.Item("Прозрачность стекла (${settings.glassOpacity}%)") {
                    showGlassOpacityPicker()
                }
            ),
            privateMode = isPrivate
        )
    }

    private fun cycleThemeMode() {
        settings.themeMode = when (settings.themeMode) {
            BrowserSettings.THEME_SYSTEM -> BrowserSettings.THEME_LIGHT
            BrowserSettings.THEME_LIGHT -> BrowserSettings.THEME_DARK
            else -> BrowserSettings.THEME_SYSTEM
        }
        SafariApp.applyThemeMode(settings.themeMode)
        val label = when (settings.themeMode) {
            BrowserSettings.THEME_LIGHT -> "День"
            BrowserSettings.THEME_DARK -> "Ночь"
            else -> "Системная"
        }
        Toast.makeText(this, "Тема: $label", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupAddressTabSwipe() {
        addressTabSwipe?.detach()
        // Bind to addressBar (domain pill text): EditText must consume DOWN so MOVE
        // is not stolen by selection / parent scroll. Aa / reload stay as sibling buttons.
        addressTabSwipe = AddressTabSwipe(
            touchTarget = binding.addressBar,
            slideTarget = binding.contentContainer,
            canSwipe = {
                !editingAddress &&
                    !binding.addressBar.hasFocus() &&
                    binding.tabsOverlay.visibility != View.VISIBLE
            },
            tabCount = { tabs.count { it.isPrivate == isPrivate } },
            onSwitchTab = { next -> switchToAdjacentTab(next) },
            onTapAddress = {
                binding.addressBar.requestFocus()
                showKeyboard()
            }
        ).also { it.attach() }
    }

    private fun switchToAdjacentTab(next: Boolean) {
        val mode = tabs.filter { it.isPrivate == isPrivate }
        if (mode.size < 2) return
        val idx = mode.indexOfFirst { it.id == activeId }.let { if (it < 0) 0 else it }
        val target = mode[if (next) (idx + 1) % mode.size else (idx - 1 + mode.size) % mode.size]
        if (target.id == activeId) return
        selectTab(target.id)
    }

    private fun setupEdgeBackGesture() {
        edgeBack?.detach()
        edgeBack = EdgeBackGesture(
            touchTarget = binding.webView,
            slideTarget = binding.contentContainer,
            underlay = binding.wallpaperView,
            canGoBack = {
                canNavigateBack() &&
                    binding.tabsOverlay.visibility != View.VISIBLE &&
                    !editingAddress
            },
            onCommitBack = { navigateBack() },
            onDownExtra = {
                if (editingAddress) {
                    binding.addressBar.clearFocus()
                    hideKeyboard()
                } else if (chromeCollapsed) {
                    setChromeCollapsed(false)
                }
            }
        ).also { it.attach() }
    }

    private fun showPageMenu() {
        if (active.isStartPage) {
            Toast.makeText(this, "Откройте страницу", Toast.LENGTH_SHORT).show()
            return
        }
        val menu = ru.srr.safari.databinding.DialogPageAaBinding.inflate(layoutInflater)
        val width = (268 * resources.displayMetrics.density).toInt()
        val popup = PopupWindow(
            menu.root,
            width,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        menu.aaDesktopLabel.text =
            if (settings.desktopMode) "Мобильная версия" else "Версия для компьютера"
        LiquidGlass.polishCapsule(menu.aaCard, 26f)
        menu.aaCard.background = LiquidGlass.menuPopoverDrawable(
            this,
            settings.glassOpacity.coerceAtLeast(72),
            26f,
            privateMode = isPrivate
        )

        fun closeAnd(action: () -> Unit) {
            SafariMotion.disappear(menu.aaCard, toScale = 0.94f, duration = 110L) {
                popup.dismiss()
                action()
            }
        }
        fun applyZoom(delta: Int) {
            settings.textZoom = settings.textZoom + delta
            binding.webView.settings.textZoom = settings.textZoom
            Toast.makeText(this, "Шрифт ${settings.textZoom}%", Toast.LENGTH_SHORT).show()
        }
        menu.aaReader.setOnClickListener { closeAnd { openReader() } }
        menu.aaTranslate.setOnClickListener { closeAnd { translate("ru") } }
        menu.aaFind.setOnClickListener { closeAnd { showFindInPage() } }
        menu.aaFindBar.setOnClickListener { closeAnd { showFindInPage() } }
        menu.aaDesktop.setOnClickListener {
            closeAnd {
                settings.desktopMode = !settings.desktopMode
                applyUserAgent()
                binding.webView.reload()
            }
        }
        menu.aaFontMinus.setOnClickListener {
            applyZoom(-BrowserSettings.TEXT_ZOOM_STEP)
        }
        menu.aaFontPlus.setOnClickListener {
            applyZoom(BrowserSettings.TEXT_ZOOM_STEP)
        }
        menu.aaMoreBar.setOnClickListener { closeAnd { showMoreMenu() } }

        menu.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val yOff = -(menu.root.measuredHeight + binding.btnAa.height + (6 * resources.displayMetrics.density).toInt())
        val xOff = ((binding.btnAa.width - width) / 2f).toInt()
        popup.showAsDropDown(binding.btnAa, xOff, yOff)
        SafariMotion.appear(menu.aaCard, fromScale = 0.92f, fromY = 8f * resources.displayMetrics.density)
    }

    private fun toggleAdBlock(reloadPage: Boolean) {
        settings.adBlockEnabled = !settings.adBlockEnabled
        adBlocker.enabled = settings.adBlockEnabled
        if (!active.isStartPage && active.url.isNotBlank()) {
            if (settings.adBlockEnabled) {
                binding.webView.evaluateJavascript(CosmeticAdScript.JS, null)
            } else {
                binding.webView.evaluateJavascript(CosmeticAdScript.REMOVE_JS, null)
            }
            if (reloadPage) binding.webView.reload()
        }
        Toast.makeText(
            this,
            if (settings.adBlockEnabled) "Блокировка рекламы включена" else "Блокировка рекламы выключена",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showWallpaperPicker() {
        GlassSheet.showList(
            this,
            title = "Обои стартовой",
            items = listOf(
                GlassSheet.Item("По умолчанию") {
                    settings.wallpaperId = "default"
                    applyWallpaper()
                },
                GlassSheet.Item("Океан") {
                    settings.wallpaperId = "ocean"
                    applyWallpaper()
                },
                GlassSheet.Item("Закат") {
                    settings.wallpaperId = "dusk"
                    applyWallpaper()
                },
                GlassSheet.Item("Лес") {
                    settings.wallpaperId = "forest"
                    applyWallpaper()
                },
                GlassSheet.Item("Песок") {
                    settings.wallpaperId = "sand"
                    applyWallpaper()
                },
                GlassSheet.Item("Из галереи…") {
                    wallpaperPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            ),
            privateMode = isPrivate
        )
    }

    private fun copyWallpaperFromUri(uri: Uri): Boolean {
        return try {
            val out = File(filesDir, BrowserSettings.WALLPAPER_FILE)
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun applyWallpaper() {
        val view = binding.wallpaperView
        val id = settings.wallpaperId
        if (id == "custom") {
            view.setImageDrawable(null)
            view.background = null
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    val file = File(filesDir, BrowserSettings.WALLPAPER_FILE)
                    if (!file.exists()) return@withContext null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    val maxSide = 2048
                    var sample = 1
                    while ((bounds.outWidth / sample) > maxSide || (bounds.outHeight / sample) > maxSide) {
                        sample *= 2
                    }
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                }
                if (settings.wallpaperId != "custom") return@launch
                if (bmp != null) {
                    view.setImageBitmap(bmp)
                } else {
                    view.setBackgroundResource(R.drawable.bg_start_wallpaper)
                }
                updateWallpaperVisibility()
            }
        } else {
            view.setImageDrawable(null)
            when (id) {
                "ocean" -> view.setBackgroundResource(R.drawable.bg_wall_ocean)
                "dusk" -> view.setBackgroundResource(R.drawable.bg_wall_dusk)
                "forest" -> view.setBackgroundResource(R.drawable.bg_wall_forest)
                "sand" -> view.setBackgroundResource(R.drawable.bg_wall_sand)
                else -> view.setBackgroundResource(R.drawable.bg_start_wallpaper)
            }
            updateWallpaperVisibility()
        }
    }

    private fun updateWallpaperVisibility() {
        val onStart = !::binding.isInitialized ||
            active.isStartPage ||
            active.url.isBlank() ||
            active.url == "about:blank" ||
            binding.startPage.visibility == View.VISIBLE
        binding.wallpaperView.visibility = if (onStart) View.VISIBLE else View.GONE
        syncContentSurface()
    }

    /**
     * Top inset area of contentContainer is transparent padding — wallpaper used to
     * bleed through as a colored strip under the status bar. On web pages fill with
     * page color; on start keep clear so wallpaper stays immersive.
     */
    private fun syncContentSurface() {
        if (!::binding.isInitialized) return
        val onStart = active.isStartPage || binding.startPage.visibility == View.VISIBLE
        if (onStart) {
            binding.contentContainer.background = null
        } else {
            val color = ContextCompat.getColor(
                this,
                if (isPrivate) R.color.safari_private_bg else R.color.safari_page_bg
            )
            binding.contentContainer.setBackgroundColor(color)
        }
    }

    private fun showFindInPage() {
        if (active.isStartPage) {
            Toast.makeText(this, "Откройте страницу", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "Найти на странице"
            setSingleLine()
        }
        GlassSheet.showInput(
            this,
            title = "Найти",
            input = input,
            positive = "Искать",
            onPositive = {
                val q = input.text?.toString().orEmpty()
                if (q.isBlank()) return@showInput
                binding.webView.findAllAsync(q)
                binding.webView.findNext(true)
            },
            neutral = "Далее",
            onNeutral = { binding.webView.findNext(true) },
            negative = "Закрыть",
            onNegative = { binding.webView.clearMatches() },
            privateMode = isPrivate
        )
    }

    private fun showKeyboard() {
        binding.addressBar.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.addressBar, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun refreshAddressDisplay() {
        updateChromeForState()
    }

    /** Состояния хрома как в Safari iOS 18 */
    private fun updateChromeForState() {
        val onStart = active.isStartPage || active.url.isBlank() || active.url == "about:blank"
        // Только фокус адресной строки — не путать с клавиатурой поля на сайте
        val editing = editingAddress
        val canBack = canNavigateBack()
        val hasAddressText = binding.addressBar.text?.isNotBlank() == true

        when {
            editing -> {
                // Поле + clear внутри + Cancel (X) справа. Без «назад».
                binding.btnBack.visibility = View.GONE
                binding.btnMore.visibility = View.VISIBLE
                binding.btnMore.setImageResource(R.drawable.ic_close)
                binding.btnMore.imageTintList = null
                binding.btnMore.setOnClickListener { cancelAddressEdit() }
                binding.btnAa.setImageResource(R.drawable.ic_search)
                binding.btnAa.imageTintList = null
                binding.btnAa.visibility = View.VISIBLE
                binding.btnReload.visibility = View.GONE
                binding.btnClearField.visibility = if (hasAddressText) View.VISIBLE else View.GONE
                binding.addressBar.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                binding.addressBar.hint = "Запрос или сайт"
                if (onStart) {
                    updateWallpaperVisibility()
                }
            }
            onStart -> {
                // Как на iPhone: тусклый назад + поиск + •••
                binding.btnBack.visibility = View.VISIBLE
                binding.btnBack.alpha = 0.35f
                binding.btnBack.isEnabled = false
                binding.btnMore.visibility = View.VISIBLE
                binding.btnMore.setImageResource(R.drawable.ic_more)
                binding.btnMore.imageTintList = null
                binding.btnMore.setOnClickListener { showMoreMenu() }
                binding.btnAa.setImageResource(R.drawable.ic_search)
                binding.btnAa.imageTintList = null
                binding.btnAa.visibility = View.VISIBLE
                binding.btnReload.visibility = View.GONE
                binding.btnClearField.visibility = View.GONE
                binding.addressBar.setText("")
                binding.addressBar.hint = "Запрос или сайт"
                binding.addressBar.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                updateWallpaperVisibility()
            }
            else -> {
                // Страница: назад всегда активен (на старт / историю) · домен · ещё
                binding.btnBack.visibility = View.VISIBLE
                binding.btnBack.alpha = if (canBack) 1f else 0.35f
                binding.btnBack.isEnabled = canBack
                binding.btnMore.visibility = View.VISIBLE
                binding.btnMore.setImageResource(R.drawable.ic_more)
                binding.btnMore.imageTintList = null
                binding.btnMore.setOnClickListener { showMoreMenu() }
                binding.btnAa.setImageResource(R.drawable.ic_aa)
                binding.btnAa.imageTintList = null
                binding.btnAa.visibility = View.VISIBLE
                binding.btnReload.visibility = View.VISIBLE
                binding.btnReload.imageTintList = null
                binding.btnClearField.visibility = View.GONE
                binding.addressBar.setText(UrlUtils.displayHost(active.url))
                binding.addressBar.gravity = android.view.Gravity.CENTER
                binding.root.setBackgroundColor(ContextCompat.getColor(this, R.color.safari_page_bg))
                updateWallpaperVisibility()
            }
        }
    }

    private fun setupLists() {
        binding.favoritesList.layoutManager = GridLayoutManager(this, 4)
        binding.recentList.layoutManager = LinearLayoutManager(this)
        binding.suggestionsList.layoutManager = LinearLayoutManager(this)
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.tabsList.layoutManager = GridLayoutManager(this, 2)
        binding.tabsList.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 120
            removeDuration = 140
            moveDuration = 140
            changeDuration = 100
        }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val adapter = binding.favoritesList.adapter as? FavoriteAdapter ?: return
                val bm = adapter.itemAt(viewHolder.bindingAdapterPosition) ?: return
                lifecycleScope.launch {
                    if (bookmarks.isEmpty()) {
                        defaultFavorites.filterNot { it.url == bm.url }.forEach {
                            repo.addBookmark(it.title, it.url)
                        }
                    } else {
                        repo.removeBookmark(bm.url)
                    }
                    Toast.makeText(this@MainActivity, "Удалено из избранного", Toast.LENGTH_SHORT).show()
                }
            }
        }).attachToRecyclerView(binding.favoritesList)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val adapter = binding.tabsList.adapter as? TabsAdapter ?: return
                val id = adapter.tabIdAt(viewHolder.bindingAdapterPosition) ?: return
                closeTab(id)
            }
        }).attachToRecyclerView(binding.tabsList)
        refreshStartPage()
        updateTabCount()
    }

    private fun observeData() {
        lifecycleScope.launch {
            repo.observeBookmarks().collectLatest {
                bookmarks = it
                if (binding.startPage.visibility == View.VISIBLE) refreshStartPage()
            }
        }
        lifecycleScope.launch {
            repo.observeHistory().collectLatest {
                history = it.filter { h -> h.url != "about:blank" && h.url.isNotBlank() }
                if (binding.startPage.visibility == View.VISIBLE) refreshStartPage()
            }
        }
    }

    private fun refreshStartPage() {
        val favs = if (bookmarks.isNotEmpty()) bookmarks.take(12) else defaultFavorites
        val existingFav = binding.favoritesList.adapter as? FavoriteAdapter
        if (existingFav != null) {
            existingFav.submit(favs, favoritesEditing)
        } else {
            binding.favoritesList.adapter = FavoriteAdapter(
                items = favs,
                editing = favoritesEditing,
                onClick = { bm ->
                    if (favoritesEditing) {
                        editFavorite(bm)
                    } else {
                        loadUrl(bm.url)
                    }
                },
                onDelete = { bm ->
                    lifecycleScope.launch {
                        // если ещё дефолты без файла — сначала сохраним остальные
                        if (bookmarks.isEmpty()) {
                            defaultFavorites.filterNot { it.url == bm.url }.forEach {
                                repo.addBookmark(it.title, it.url)
                            }
                        } else {
                            repo.removeBookmark(bm.url)
                        }
                        Toast.makeText(this@MainActivity, "Удалено из избранного", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
        binding.btnCollapseFav.text = if (favoritesEditing) "Готово" else "Изменить"
        val recent = history.filter {
            it.url != "about:blank" &&
                !it.title.equals("about:blank", true) &&
                it.url.isNotBlank()
        }.take(12)
        val showRecent = recent.isNotEmpty() && !isPrivate
        binding.recentHeaderRow.visibility = if (showRecent) View.VISIBLE else View.GONE
        binding.recentHeader.visibility = if (showRecent) View.VISIBLE else View.GONE
        binding.btnAllHistory.visibility = if (showRecent) View.VISIBLE else View.GONE
        binding.btnAllHistory.setTextColor(ContextCompat.getColor(this, R.color.safari_start_title))
        binding.recentList.adapter = SimpleAdapter(
            recent.map { it.title.ifBlank { UrlUtils.displayHost(it.url) } to UrlUtils.displayHost(it.url) }
        ) { idx -> loadUrl(recent[idx].url) }
        applySuggestionsExpanded()
    }

    private fun editFavorite(bm: Bookmark) {
        val input = EditText(this).apply {
            setText(bm.title)
            setSelection(text.length)
            hint = "Название"
        }
        GlassSheet.showInput(
            this,
            title = "Избранное",
            input = input,
            positive = "Сохранить",
            onPositive = {
                val name = input.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    if (bookmarks.isEmpty()) {
                        defaultFavorites.forEach { repo.addBookmark(it.title, it.url) }
                    }
                    repo.renameBookmark(bm.url, name.ifBlank { bm.title })
                }
            },
            neutral = "Удалить",
            onNeutral = {
                lifecycleScope.launch {
                    if (bookmarks.isEmpty()) {
                        defaultFavorites.filterNot { it.url == bm.url }.forEach {
                            repo.addBookmark(it.title, it.url)
                        }
                    } else {
                        repo.removeBookmark(bm.url)
                    }
                }
            },
            negative = "Отмена",
            privateMode = isPrivate
        )
    }

    private fun showSuggestions(items: List<Suggestion>) {
        if (!editingAddress || items.isEmpty()) {
            hideSuggestions()
            return
        }
        binding.suggestionsList.visibility = View.VISIBLE
        binding.suggestionsList.adapter = SuggestionAdapter(
            items.map {
                it.text to when (it.kind) {
                    Suggestion.Kind.HISTORY -> "История"
                    Suggestion.Kind.SEARCH -> "Поиск Google"
                    else -> "Адрес"
                }
            }
        ) { idx ->
            val s = items[idx]
            hideSuggestions()
            if (s.url != null) loadUrl(s.url) else submitAddress(s.text)
            binding.addressBar.clearFocus()
            hideKeyboard()
        }
    }

    private fun submitAddress(raw: String = binding.addressBar.text?.toString().orEmpty()) {
        val cleaned = raw.trim()
        if (cleaned.isEmpty() || cleaned == "about:blank") return
        hideSuggestions()
        loadUrl(UrlUtils.normalizeInput(cleaned))
        binding.addressBar.clearFocus()
        hideKeyboard()
    }

    private fun loadUrl(url: String) {
        val target = UrlUtils.rewriteKnownRedirects(url)
        if (target.isBlank() || target == "about:blank") {
            showStartPage()
            return
        }
        active.url = target
        active.isStartPage = false
        active.title = UrlUtils.displayHost(target)
        applyPrivateSettings()
        hideSuggestions()
        setChromeCollapsed(false)
        binding.webView.loadUrl(target)
        showWeb()
        hideTabs()
        refreshAddressDisplay()
        if (!isPrivate) {
            lifecycleScope.launch {
                repo.upsertTab(
                    ru.srr.safari.data.TabEntity(
                        id = active.id,
                        title = active.title,
                        url = active.url,
                        isPrivate = false
                    )
                )
            }
        }
    }

    private fun showWeb() {
        binding.startPage.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        hideSuggestions()
        updateWallpaperVisibility()
    }

    private fun showStartPage() {
        active.isStartPage = true
        active.url = ""
        active.title = "Новая вкладка"
        lastJsScrollY = 0
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        binding.webView.visibility = View.GONE
        binding.startPage.visibility = View.VISIBLE
        // Drop blank history so system Back is not swallowed by canGoBack()
        binding.webView.post {
            try {
                binding.webView.clearHistory()
            } catch (_: Exception) {
            }
        }
        hideSuggestions()
        setChromeCollapsed(false)
        refreshAddressDisplay()
        applyPrivateUi()
        updateWallpaperVisibility()
        syncContentSurface()
    }

    private fun applyPrivateSettings() {
        binding.webView.settings.domStorageEnabled = !isPrivate
        binding.webView.settings.cacheMode =
            if (isPrivate) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(!isPrivate)
        cookies.setAcceptThirdPartyCookies(binding.webView, !isPrivate)
        if (isPrivate) {
            cookies.flush()
        }
    }

    private fun clearPrivateBrowsingData() {
        try {
            binding.webView.clearCache(true)
            binding.webView.clearFormData()
            binding.webView.clearHistory()
        } catch (_: Exception) {
        }
        lifecycleScope.launch { repo.clearPrivateTabs() }
    }

    private fun applyPrivateUi() {
        if (isPrivate) {
            binding.wallpaperView.setImageDrawable(null)
            binding.wallpaperView.setBackgroundColor(
                ContextCompat.getColor(this, R.color.safari_private_bg)
            )
            binding.startTitle.setTextColor(ContextCompat.getColor(this, R.color.safari_start_title))
            binding.startTitle.text = "Приватный доступ"
            binding.startSubtitle.visibility = View.VISIBLE
            binding.startSubtitle.setTextColor(ContextCompat.getColor(this, R.color.safari_start_muted))
            binding.startSubtitle.text =
                "Safari не будет запоминать ваши действия в режиме «Приватный доступ»."
            binding.recentHeader.visibility = View.GONE
            binding.btnAllHistory.visibility = View.GONE
            binding.recentHeaderRow.visibility = View.GONE
            binding.recentList.visibility = View.GONE
        } else {
            applyWallpaper()
            binding.startTitle.setTextColor(ContextCompat.getColor(this, R.color.safari_start_title))
            binding.startTitle.text = "Избранное"
            binding.startSubtitle.visibility = View.GONE
            binding.recentHeader.setTextColor(ContextCompat.getColor(this, R.color.safari_start_title))
            binding.btnAllHistory.setTextColor(ContextCompat.getColor(this, R.color.safari_start_title))
        }
        binding.btnTogglePrivate.text = if (isPrivate) "Обычный" else "Приватный"
        binding.tabsTitle.text = if (isPrivate) "Приватные вкладки" else "Вкладки"
        updateTabCount()
        updateWallpaperVisibility()
    }

    private fun newTab(private: Boolean) {
        captureActiveTabPreview()
        val tab = Tab(isPrivate = private)
        tabs.add(tab)
        activeId = tab.id
        isPrivate = private
        applyPrivateSettings()
        updateTabCount()
        hideTabs()
        showStartPage()
    }

    private fun closeTab(id: String) {
        val inOverview = binding.tabsOverlay.visibility == View.VISIBLE
        val wasPrivate = tabs.find { it.id == id }?.isPrivate == true
        tabs.removeAll { it.id == id }
        deleteTabPreview(id)
        lifecycleScope.launch { repo.deleteTab(id) }
        val mode = tabs.filter { it.isPrivate == isPrivate }
        if (mode.isEmpty()) {
            if (wasPrivate && !tabs.any { it.isPrivate }) {
                clearPrivateBrowsingData()
            }
            if (inOverview) {
                if (!isPrivate) {
                    val tab = Tab(isPrivate = false)
                    tabs.add(tab)
                    activeId = tab.id
                }
                renderTabs()
                updateTabCount()
                return
            }
            newTab(isPrivate)
            return
        }
        if (activeId == id) activeId = mode.last().id
        updateTabCount()
        if (inOverview) {
            renderTabs()
            return
        }
        selectTab(activeId)
    }

    private fun selectTab(id: String) {
        if (id != activeId) {
            // Snapshot current page before replacing WebView content
            captureActiveTabPreview()
        }
        activeId = id
        val tab = active
        isPrivate = tab.isPrivate
        applyPrivateUi()
        hideTabs()
        if (tab.isStartPage || tab.url.isBlank() || tab.url == "about:blank") showStartPage()
        else loadUrl(tab.url)
    }

    /** Switch All ↔ Private while staying in the tab overview (iOS swipe). */
    private fun switchTabsMode(private: Boolean, animateTrack: Boolean = true) {
        if (isPrivate == private) {
            syncTabsModeTrack(animateTrack)
            return
        }
        isPrivate = private
        applyPrivateSettings()
        applyPrivateUi()
        val mode = tabs.filter { it.isPrivate == isPrivate }
        if (mode.isNotEmpty()) {
            activeId = mode.first().id
        }
        if (binding.tabsOverlay.visibility == View.VISIBLE) {
            renderTabs(syncMode = false)
            syncTabsModeTrack(animateTrack)
        } else {
            if (mode.isEmpty()) newTab(isPrivate)
            else {
                selectTab(activeId)
                showTabs()
            }
        }
    }

    private fun togglePrivate() = switchTabsMode(!isPrivate)

    private fun ensureActiveTabInMode() {
        val mode = tabs.filter { it.isPrivate == isPrivate }
        if (mode.isEmpty()) {
            newTab(isPrivate)
            return
        }
        if (mode.none { it.id == activeId }) {
            activeId = mode.first().id
            val tab = active
            applyPrivateSettings()
            applyPrivateUi()
            if (tab.isStartPage || tab.url.isBlank() || tab.url == "about:blank") showStartPage()
            else loadUrl(tab.url)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTabsModeSwipe() {
        tabsModeLiquid = TabsModeLiquidSwitch(
            host = binding.tabsModeHost,
            blob = binding.tabsModeBlob,
            track = binding.tabsModeTrack,
            labelPrivate = binding.tabsModePrivate,
            labelAll = binding.tabsModeAll,
            glassOpacity = { settings.glassOpacity },
            isPrivateMode = { isPrivate },
            onCommit = { private -> switchTabsMode(private, animateTrack = true) }
        ).also { it.attach() }
    }

    private fun refreshTabsModeChrome() {
        if (!::binding.isInitialized) return
        tabsModeLiquid?.refreshChrome()
    }

    private fun syncTabsModeTrack(animate: Boolean) {
        if (!::binding.isInitialized) return
        binding.tabsModeHost.post {
            binding.tabsModeHost.post {
                tabsModeLiquid?.syncFromMode(animate)
            }
        }
    }

    private fun animateTabsBubblesIn() {
        val d = resources.displayMetrics.density
        val bubbles = listOf(binding.btnNewTab, binding.tabsModeHost, binding.btnTabsDone)
        bubbles.forEachIndexed { i, view ->
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = 14f * d
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(40L + i * 35L)
                .setDuration(SafariMotion.OVERLAY)
                .setInterpolator(SafariMotion.modeSpring)
                .withLayer()
                .start()
        }
    }

    private fun showTabsMenu() {
        val modeTabs = tabs.filter { it.isPrivate == isPrivate }
        val count = modeTabs.size
        val menu = DialogTabsMenuBinding.inflate(layoutInflater)
        val width = (268 * resources.displayMetrics.density).toInt()
        val popup = PopupWindow(
            menu.root,
            width,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 20f
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        menu.root.clipToOutline = true
        (menu.root.getChildAt(0) as? View)?.let { card ->
            LiquidGlass.polishCapsule(card, 18f)
            card.background = LiquidGlass.menuPopoverDrawable(
                this,
                settings.glassOpacity.coerceAtLeast(78),
                18f,
                privateMode = isPrivate
            )
        }
        menu.tabsMenuCopyLabel.text = when {
            count <= 0 -> "Скопировать ссылки"
            count % 10 == 1 && count % 100 != 11 -> "Скопировать $count ссылку"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "Скопировать $count ссылки"
            else -> "Скопировать $count ссылок"
        }
        menu.tabsMenuCloseLabel.text = when {
            count <= 0 -> "Закрыть вкладки"
            count % 10 == 1 && count % 100 != 11 -> "Закрыть $count вкладку"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "Закрыть $count вкладки"
            else -> "Закрыть $count вкладок"
        }
        fun closeAnd(action: () -> Unit) {
            val card = menu.root.getChildAt(0)
            if (card != null) {
                SafariMotion.disappear(
                    card,
                    toScale = 0.96f,
                    toY = -4f * resources.displayMetrics.density,
                    duration = 100L
                ) {
                    popup.dismiss()
                    action()
                }
            } else {
                popup.dismiss()
                action()
            }
        }
        menu.tabsMenuGroups.setOnClickListener {
            closeAnd {
                Toast.makeText(this, "Группы вкладок скоро", Toast.LENGTH_SHORT).show()
            }
        }
        menu.tabsMenuSelect.setOnClickListener {
            closeAnd {
                Toast.makeText(this, "Выбор вкладок скоро", Toast.LENGTH_SHORT).show()
            }
        }
        menu.tabsMenuSort.setOnClickListener {
            closeAnd {
                val sorted = modeTabs.sortedBy { it.title.ifBlank { it.url }.lowercase() }
                val others = tabs.filter { it.isPrivate != isPrivate }
                tabs.clear()
                tabs.addAll(if (isPrivate) others + sorted else sorted + others)
                renderTabs()
            }
        }
        menu.tabsMenuCopy.setOnClickListener {
            closeAnd {
                val urls = modeTabs.map { it.url.trim() }
                    .filter { it.isNotBlank() && it != "about:blank" }
                if (urls.isEmpty()) {
                    Toast.makeText(this, "Нет ссылок", Toast.LENGTH_SHORT).show()
                } else {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("tabs", urls.joinToString("\n")))
                    Toast.makeText(this, "Скопировано: ${urls.size}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        menu.tabsMenuClose.setOnClickListener {
            closeAnd { closeAllTabsInMode() }
        }
        popup.showAsDropDown(
            binding.btnTabsMenu,
            0,
            (4 * resources.displayMetrics.density).toInt()
        )
        (menu.root.getChildAt(0) as? View)?.let { card ->
            SafariMotion.appear(
                card,
                fromScale = 0.94f,
                fromY = -6f * resources.displayMetrics.density,
                duration = SafariMotion.POPOVER
            )
        }
    }

    private fun closeAllTabsInMode() {
        val ids = tabs.filter { it.isPrivate == isPrivate }.map { it.id }
        if (ids.isEmpty()) return
        val closingPrivate = isPrivate
        ids.forEach { id ->
            deleteTabPreview(id)
            lifecycleScope.launch { repo.deleteTab(id) }
        }
        tabs.removeAll { it.isPrivate == isPrivate }
        if (closingPrivate) clearPrivateBrowsingData()
        if (!isPrivate) {
            val tab = Tab(isPrivate = false)
            tabs.add(tab)
            activeId = tab.id
            showStartPage()
        }
        renderTabs()
        updateTabCount()
    }

    private fun showTabs() {
        // minSdk 26: PixelCopy only — skip sync WebView.draw on UI thread
        var revealed = false
        fun reveal() {
            if (revealed) return
            revealed = true
            renderTabs()
            hydrateTabPreviewsAsync()
            syncTabsModeTrack(animate = false)
            val overlay = binding.tabsOverlay
            overlay.animate().cancel()
            overlay.visibility = View.VISIBLE
            binding.bottomChrome.animate().cancel()
            binding.bottomChrome.visibility = View.GONE
            binding.bottomChrome.alpha = 1f
            binding.bottomChrome.translationY = 0f
            syncContentAboveChrome()
            SafariMotion.appear(overlay, fromScale = 0.97f, fromY = 18f * resources.displayMetrics.density)
            animateTabsBubblesIn()
            updateTabCount()
        }
        captureActiveTabPreviewAsync {
            if (revealed) {
                if (binding.tabsOverlay.visibility == View.VISIBLE) {
                    renderTabs(syncMode = false)
                }
            } else {
                reveal()
            }
        }
        // Don't stall forever if PixelCopy fails
        previewHandler.postDelayed({ reveal() }, 160L)
    }

    private fun hideTabs() {
        val overlay = binding.tabsOverlay
        if (overlay.visibility != View.VISIBLE) {
            binding.bottomChrome.visibility = View.VISIBLE
            SafariMotion.reset(binding.bottomChrome)
            syncContentAboveChrome()
            return
        }
        overlay.animate().cancel()
        SafariMotion.disappear(
            overlay,
            toScale = 0.985f,
            toY = 10f * resources.displayMetrics.density
        ) {
            overlay.visibility = View.GONE
            SafariMotion.reset(overlay)
            syncContentAboveChrome()
        }
        binding.bottomChrome.animate().cancel()
        binding.bottomChrome.visibility = View.VISIBLE
        binding.bottomChrome.alpha = 0f
        binding.bottomChrome.translationY = 16f * resources.displayMetrics.density
        syncContentAboveChrome()
        binding.bottomChrome.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(SafariMotion.OVERLAY)
            .setInterpolator(SafariMotion.softOut)
            .start()
    }

    private fun previewFile(id: String) = File(previewDir, "$id.webp")

    private fun deleteTabPreview(id: String) {
        tabPreviews.remove(id)?.recycle()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                previewFile(id).delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun hydrateTabPreviewsAsync() {
        val missing = tabs.map { it.id }.filter { id ->
            val cached = tabPreviews[id]
            cached == null || cached.isRecycled
        }
        if (missing.isEmpty()) return
        previewHydrateJob?.cancel()
        previewHydrateJob = lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                missing.mapNotNull { id ->
                    loadPreviewFromDisk(id)?.let { id to it }
                }
            }
            if (loaded.isEmpty()) return@launch
            loaded.forEach { (id, bmp) ->
                val cached = tabPreviews[id]
                if (cached == null || cached.isRecycled) {
                    tabPreviews[id] = bmp
                } else {
                    bmp.recycle()
                }
            }
            if (binding.tabsOverlay.visibility == View.VISIBLE) {
                (binding.tabsList.adapter as? TabsAdapter)?.notifyPreviewsChanged()
            }
        }
    }

    private fun loadPreviewFromDisk(id: String): Bitmap? {
        val file = previewFile(id)
        if (!file.exists() || file.length() < 32) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun persistPreview(id: String, bmp: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tmp = File(previewDir, "$id.tmp")
                FileOutputStream(tmp).use { out ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 72, out)
                    } else {
                        @Suppress("DEPRECATION")
                        bmp.compress(Bitmap.CompressFormat.WEBP, 72, out)
                    }
                }
                if (!tmp.renameTo(previewFile(id))) {
                    tmp.copyTo(previewFile(id), overwrite = true)
                    tmp.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun storeTabPreview(id: String, bmp: Bitmap) {
        tabPreviews.remove(id)?.recycle()
        tabPreviews[id] = bmp
        persistPreview(id, bmp)
    }

    private fun scheduleTabPreviewCapture() {
        previewCaptureRunnable?.let { previewHandler.removeCallbacks(it) }
        val tabId = activeId
        val run = Runnable {
            if (activeId == tabId) captureActiveTabPreviewAsync()
        }
        previewCaptureRunnable = run
        previewHandler.postDelayed(run, 450L)
    }

    private fun captureActiveTabPreview() {
        val wv = binding.webView
        val tabId = activeId
        if (active.isStartPage || wv.width < 2 || wv.height < 2) return
        // May be briefly GONE during transitions — still try if laid out
        try {
            val targetW = 360
            val targetH = (360f * wv.height / wv.width).toInt().coerceIn(200, 640)
            val scaled = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(scaled)
            canvas.drawColor(Color.WHITE)
            val scaleX = targetW.toFloat() / wv.width
            val scaleY = targetH.toFloat() / wv.height
            canvas.save()
            canvas.scale(scaleX, scaleY)
            wv.draw(canvas)
            canvas.restore()
            if (!isUselessCapture(scaled)) {
                storeTabPreview(tabId, scaled)
            } else {
                scaled.recycle()
            }
        } catch (_: Exception) {
        }
    }

    private fun captureActiveTabPreviewAsync(onDone: (() -> Unit)? = null) {
        val wv = binding.webView
        val tabId = activeId
        if (active.isStartPage || wv.width < 2 || wv.height < 2) {
            onDone?.invoke()
            return
        }
        try {
            val w = wv.width
            val h = wv.height
            val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val loc = IntArray(2)
            wv.getLocationInWindow(loc)
            val src = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
            val decor = window.decorView
            val bounds = Rect(0, 0, decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1))
            if (!src.intersect(bounds) || src.width() < 2 || src.height() < 2) {
                full.recycle()
                onDone?.invoke()
                return
            }
            // PixelCopy needs dest bitmap matching src size
            val shot = if (src.width() == w && src.height() == h) {
                full
            } else {
                full.recycle()
                Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
            }
            PixelCopy.request(window, src, shot, { result ->
                if (result == PixelCopy.SUCCESS && activeId == tabId) {
                    lifecycleScope.launch(Dispatchers.Default) {
                        var scaled: Bitmap? = null
                        try {
                            val targetW = 360
                            val targetH = (360f * shot.height / shot.width).toInt().coerceIn(200, 640)
                            scaled = Bitmap.createScaledBitmap(shot, targetW, targetH, false)
                            val keep = !isUselessCapture(scaled!!)
                            withContext(Dispatchers.Main) {
                                if (keep && activeId == tabId) {
                                    storeTabPreview(tabId, scaled!!)
                                    scaled = null
                                }
                                onDone?.invoke()
                            }
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) { onDone?.invoke() }
                        } finally {
                            if (!shot.isRecycled) shot.recycle()
                            scaled?.recycle()
                        }
                    }
                } else {
                    if (!shot.isRecycled) shot.recycle()
                    onDone?.invoke()
                }
            }, previewHandler)
        } catch (_: Exception) {
            onDone?.invoke()
        }
    }

    /** Reject only flat failed captures, not real light/white pages. */
    private fun isUselessCapture(bmp: Bitmap): Boolean {
        val stepX = (bmp.width / 10).coerceAtLeast(1)
        val stepY = (bmp.height / 10).coerceAtLeast(1)
        var minL = 255
        var maxL = 0
        var samples = 0
        var y = stepY / 2
        while (y < bmp.height) {
            var x = stepX / 2
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val l = (r * 3 + g * 4 + b) / 8
                if (l < minL) minL = l
                if (l > maxL) maxL = l
                samples++
                x += stepX
            }
            y += stepY
        }
        if (samples < 4) return true
        // Truly empty HW dump ≈ flat color
        return (maxL - minL) < 6
    }

    private fun renderTabs(syncMode: Boolean = true) {
        val modeTabs = tabs.filter { it.isPrivate == isPrivate }
        val emptyPrivate = isPrivate && modeTabs.isEmpty()
        binding.tabsPrivateEmpty.visibility = if (emptyPrivate) View.VISIBLE else View.GONE
        binding.tabsList.visibility = if (emptyPrivate) View.GONE else View.VISIBLE
        val existing = binding.tabsList.adapter as? TabsAdapter
        if (existing != null) {
            existing.submit(modeTabs, tabPreviews)
        } else {
            binding.tabsList.adapter = TabsAdapter(modeTabs, tabPreviews, ::selectTab, ::closeTab)
        }
        updateTabCount(syncMode = syncMode)
    }

    private fun updateTabCount(syncMode: Boolean = true) {
        val count = tabs.count { it.isPrivate == isPrivate }
        val chromeCount = count.coerceAtLeast(1)
        binding.btnTabs.text = chromeCount.toString()
        if (::binding.isInitialized) {
            val allCount = tabs.count { !it.isPrivate }.coerceAtLeast(0)
            // iOS: when private is active, the other group peeks as a number only ("36")
            binding.tabsModeAll.text = if (isPrivate) {
                allCount.coerceAtLeast(1).toString()
            } else {
                val n = allCount.coerceAtLeast(1)
                when {
                    n % 10 == 1 && n % 100 != 11 -> "$n вкладка"
                    n % 10 in 2..4 && n % 100 !in 12..14 -> "$n вкладки"
                    else -> "$n вкладок"
                }
            }
            binding.tabsModePrivate.text = "Частный доступ"
            val label = when {
                isPrivate -> "Частный доступ"
                count % 10 == 1 && count % 100 != 11 -> "$count вкладка"
                count % 10 in 2..4 && count % 100 !in 12..14 -> "$count вкладки"
                else -> "$count вкладок"
            }
            binding.tabsCountPill.text = label
            binding.tabsTitle.text = if (isPrivate) "Приватные вкладки" else "Вкладки"
            binding.tabsModeAll.requestLayout()
            if (syncMode) syncTabsModeTrack(animate = false)
        }
    }

    private fun showBookmarks() {
        val source = (if (bookmarks.isNotEmpty()) bookmarks else defaultFavorites).toMutableList()
        val dialog = android.app.Dialog(this, R.style.Theme_Safari_GlassDialog)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        val sheet = ru.srr.safari.databinding.DialogBookmarksBinding.inflate(layoutInflater)
        val card = sheet.bookmarksCard
        val list = sheet.bookmarksList
        card.background = LiquidGlass.menuPopoverDrawable(
            this,
            settings.glassOpacity.coerceAtLeast(72),
            26f,
            privateMode = isPrivate
        )
        LiquidGlass.polishCapsule(card, 26f, pressFeedback = false)
        val maxListH = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        list.layoutParams = list.layoutParams.apply {
            height = if (source.isEmpty()) {
                (48 * resources.displayMetrics.density).toInt()
            } else {
                maxListH
            }
        }
        list.layoutManager = LinearLayoutManager(this)
        lateinit var adapter: BookmarkListAdapter
        fun deleteAt(pos: Int) {
            if (pos !in source.indices) return
            val bm = source.removeAt(pos)
            adapter.notifyItemRemoved(pos)
            lifecycleScope.launch {
                if (bookmarks.isEmpty()) {
                    source.forEach { repo.addBookmark(it.title, it.url) }
                } else {
                    repo.removeBookmark(bm.url)
                }
                Toast.makeText(this@MainActivity, "Удалено", Toast.LENGTH_SHORT).show()
            }
        }
        adapter = BookmarkListAdapter(source, onClick = {
            dialog.dismiss()
            loadUrl(it.url)
        })
        list.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) deleteAt(pos)
            }
        }).attachToRecyclerView(list)
        sheet.bookmarksHistory.setOnClickListener {
            dialog.dismiss()
            showHistory()
        }
        sheet.bookmarksClose.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(sheet.root)
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        SafariMotion.appear(card, fromScale = 0.94f)
    }

    private fun showExitConfirmDialog() {
        val dialog = android.app.Dialog(this, R.style.Theme_Safari_GlassDialog)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        val sheet = ru.srr.safari.databinding.DialogExitConfirmBinding.inflate(layoutInflater)
        sheet.exitCard.background = LiquidGlass.menuPopoverDrawable(
            this,
            settings.glassOpacity.coerceAtLeast(78),
            22f,
            privateMode = isPrivate
        )
        LiquidGlass.polishCapsule(sheet.exitCard, 22f, pressFeedback = false)
        sheet.exitConfirm.setOnClickListener {
            dialog.dismiss()
            finish()
        }
        sheet.exitCancel.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(sheet.root)
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        SafariMotion.appear(sheet.exitCard, fromScale = 0.94f)
    }

    private fun showHistory() {
        val items = history.filter {
            it.url.isNotBlank() && it.url != "about:blank"
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "История пуста", Toast.LENGTH_SHORT).show()
            return
        }
        renderHistoryList(items)
        val overlay = binding.historyOverlay
        overlay.animate().cancel()
        overlay.visibility = View.VISIBLE
        SafariMotion.appear(overlay, fromScale = 0.99f, fromY = 16f * resources.displayMetrics.density)
        binding.bottomChrome.visibility = View.GONE
    }

    private fun hideHistory() {
        val overlay = binding.historyOverlay
        if (overlay.visibility != View.VISIBLE) return
        SafariMotion.disappear(
            overlay,
            toScale = 0.99f,
            toY = 12f * resources.displayMetrics.density
        ) {
            overlay.visibility = View.GONE
            SafariMotion.reset(overlay)
        }
        if (binding.tabsOverlay.visibility != View.VISIBLE) {
            binding.bottomChrome.visibility = View.VISIBLE
            SafariMotion.reset(binding.bottomChrome)
        }
    }

    private fun renderHistoryList(items: List<HistoryEntry>) {
        val grouped = linkedMapOf<String, MutableList<HistoryEntry>>()
        for (entry in items) {
            val key = historySectionKey(entry.visitedAt)
            grouped.getOrPut(key) { mutableListOf() }.add(entry)
        }
        val rows = mutableListOf<HistoryRow>()
        for ((key, list) in grouped) {
            val expanded = historySectionExpanded.getOrDefault(key, true)
            rows += HistoryRow.Header(key, historySectionTitle(key), expanded)
            if (expanded) {
                list.forEachIndexed { index, entry ->
                    val place = when {
                        list.size == 1 -> HistoryPlace.ONLY
                        index == 0 -> HistoryPlace.FIRST
                        index == list.lastIndex -> HistoryPlace.LAST
                        else -> HistoryPlace.MIDDLE
                    }
                    rows += HistoryRow.Entry(entry, place)
                }
            }
        }
        binding.historyList.adapter = HistoryAdapter(
            rows = rows,
            onOpen = { entry ->
                hideHistory()
                loadUrl(entry.url)
            },
            onToggleSection = { key ->
                val cur = historySectionExpanded.getOrDefault(key, true)
                historySectionExpanded[key] = !cur
                renderHistoryList(items)
            }
        )
    }

    private fun historySectionKey(visitedAt: Long): String {
        val visit = Calendar.getInstance().apply { timeInMillis = visitedAt }
        val now = Calendar.getInstance()
        fun dayKey(c: Calendar) =
            "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
        return when {
            dayKey(visit) == dayKey(now) -> {
                when (visit.get(Calendar.HOUR_OF_DAY)) {
                    in 5..11 -> "today_morning"
                    in 12..16 -> "today_day"
                    in 17..22 -> "today_evening"
                    else -> "today_night"
                }
            }
            run {
                val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                dayKey(visit) == dayKey(y)
            } -> "yesterday"
            else -> "earlier"
        }
    }

    private fun historySectionTitle(key: String): String = when (key) {
        "today_morning" -> "Сегодня утром"
        "today_day" -> "Сегодня днем"
        "today_evening" -> "Сегодня вечером"
        "today_night" -> "Сегодня ночью"
        "yesterday" -> "Вчера"
        else -> "Ранее"
    }

    private enum class HistoryPlace { FIRST, MIDDLE, LAST, ONLY }

    private sealed class HistoryRow {
        data class Header(val key: String, val title: String, val expanded: Boolean) : HistoryRow()
        data class Entry(val item: HistoryEntry, val place: HistoryPlace) : HistoryRow()
    }

    private fun showShareMenu() {
        val url = active.url.takeIf { it.isNotBlank() && it != "about:blank" }.orEmpty()
        val title = active.title.ifBlank { if (active.isStartPage) "Safari" else "Страница" }
        val host = if (url.isBlank()) "safari" else UrlUtils.displayHost(url)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.Theme_Safari_BottomSheet)
        val b = ru.srr.safari.databinding.DialogShareSheetBinding.inflate(layoutInflater)
        b.shareCard.background = LiquidGlass.menuPopoverDrawable(
            this,
            settings.glassOpacity.coerceAtLeast(72),
            26f,
            privateMode = isPrivate
        )
        LiquidGlass.polishCapsule(b.shareCard, 28f)
        b.shareTitle.text = title
        b.shareHost.text = host
        b.shareFavicon.text = host.firstOrNull()?.uppercaseChar()?.toString() ?: "S"
        b.shareClose.setOnClickListener { sheet.dismiss() }

        fun expandExtras() {
            b.shareExtras.visibility = View.VISIBLE
            sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        b.shareOptions.setOnClickListener { expandExtras() }

        fun shareText() {
            if (url.isBlank()) return
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "$title\n$url")
                putExtra(Intent.EXTRA_SUBJECT, title)
            }
            startActivity(Intent.createChooser(send, "Поделиться"))
        }
        fun shareToPackage(pkg: String) {
            if (url.isBlank()) return
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "$title\n$url")
                setPackage(pkg)
            }
            try {
                startActivity(send)
            } catch (_: Exception) {
                shareText()
            }
        }
        fun copyUrl() {
            if (url.isBlank()) return
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
            Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
        }
        fun addBookmark() {
            if (url.isBlank()) return
            lifecycleScope.launch {
                repo.addBookmark(title, url)
                Toast.makeText(this@MainActivity, "В закладках", Toast.LENGTH_SHORT).show()
            }
        }

        data class AppItem(val label: String, val icon: Int, val click: () -> Unit)
        val apps = listOf(
            AppItem("Telegram", R.drawable.ic_menu_share) {
                sheet.dismiss()
                shareToPackage("org.telegram.messenger")
            },
            AppItem("Сообщения", R.drawable.ic_share_message) {
                sheet.dismiss()
                if (url.isBlank()) return@AppItem
                try {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
                        putExtra("sms_body", "$title\n$url")
                    })
                } catch (_: Exception) {
                    shareText()
                }
            },
            AppItem("Заметки", R.drawable.ic_share_notes) {
                sheet.dismiss()
                shareText()
            },
            AppItem("Напоминания", R.drawable.ic_share_reminder) {
                sheet.dismiss()
                if (url.isBlank()) return@AppItem
                try {
                    startActivity(
                        Intent(Intent.ACTION_INSERT).apply {
                            data = android.provider.CalendarContract.Events.CONTENT_URI
                            putExtra(android.provider.CalendarContract.Events.TITLE, title)
                            putExtra(android.provider.CalendarContract.Events.DESCRIPTION, url)
                        }
                    )
                } catch (_: Exception) {
                    shareText()
                }
            },
            AppItem("Ещё", R.drawable.ic_more) {
                sheet.dismiss()
                shareText()
            }
        )
        apps.forEach { item ->
            val row = ru.srr.safari.databinding.ItemShareAppBinding.inflate(layoutInflater, b.shareAppsRow, false)
            row.shareAppLabel.text = item.label
            row.shareAppIcon.setImageResource(item.icon)
            row.root.setOnClickListener { item.click() }
            b.shareAppsRow.addView(row.root)
        }

        val actions = listOf(
            AppItem("Скопировать", R.drawable.ic_menu_copy) {
                copyUrl()
                sheet.dismiss()
            },
            AppItem("Добавить в: Закладки", R.drawable.ic_menu_bookmark) {
                addBookmark()
                sheet.dismiss()
            }
        )
        actions.forEach { item ->
            val row = ru.srr.safari.databinding.ItemShareActionBinding.inflate(layoutInflater, b.shareActionsRow, false)
            row.shareActionLabel.text = item.label
            row.shareActionIcon.setImageResource(item.icon)
            row.root.setOnClickListener { item.click() }
            b.shareActionsRow.addView(row.root)
        }

        fun addExtra(label: String, icon: Int, block: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(
                    (14 * resources.displayMetrics.density).toInt(),
                    0,
                    (12 * resources.displayMetrics.density).toInt(),
                    0
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (44 * resources.displayMetrics.density).toInt()
                )
                setBackgroundResource(
                    android.util.TypedValue().also {
                        theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                    }.resourceId
                )
                setOnClickListener {
                    sheet.dismiss()
                    block()
                }
            }
            val iv = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (18 * resources.displayMetrics.density).toInt(),
                    (18 * resources.displayMetrics.density).toInt()
                )
                setImageResource(icon)
            }
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (10 * resources.displayMetrics.density).toInt()
                }
                text = label
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.safari_text))
                textSize = 15f
            }
            row.addView(iv)
            row.addView(tv)
            b.shareExtraList.addView(row)
        }
        addExtra("Добавить закладку в папку…", R.drawable.ic_menu_book) { addBookmark() }
        addExtra("Добавить в Избранное", R.drawable.ic_menu_star) { addBookmark() }
        addExtra("Найти на странице", R.drawable.ic_menu_find) { showFindInPage() }
        addExtra("Режим чтения", R.drawable.ic_menu_reader) { openReader() }
        addExtra("Перевести на русский", R.drawable.ic_menu_translate) { translate("ru") }
        addExtra("Translate to English", R.drawable.ic_menu_translate) { translate("en") }
        addExtra("История", R.drawable.ic_menu_book) { showHistory() }

        sheet.setContentView(b.root)
        sheet.setOnShowListener {
            val parent = b.root.parent as? View
            parent?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val behavior = sheet.behavior
            behavior.skipCollapsed = false
            behavior.isFitToContents = true
            behavior.isDraggable = true
            b.root.post {
                val peek = b.shareCard.height.coerceAtMost(
                    (resources.displayMetrics.heightPixels * 0.48f).toInt()
                ).coerceAtLeast((280 * resources.displayMetrics.density).toInt())
                behavior.peekHeight = peek
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
            }
            behavior.addBottomSheetCallback(object :
                com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED) {
                        b.shareExtras.visibility = View.VISIBLE
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    if (slideOffset > 0.15f) b.shareExtras.visibility = View.VISIBLE
                }
            })
        }
        sheet.show()
        SafariMotion.appear(b.shareCard, fromScale = 0.96f, fromY = 24f * resources.displayMetrics.density)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openReader() {
        if (active.isStartPage) return
        binding.webView.evaluateJavascript(ReaderModeScript.JS) { raw ->
            try {
                val unquoted = JSONTokener(raw).nextValue()?.toString() ?: return@evaluateJavascript
                val json = JSONObject(unquoted)
                val title = json.optString("title").ifBlank { active.title }
                var html = json.optString("html")
                val text = json.optString("text")
                if (html.isBlank() || text.length < 80) {
                    Toast.makeText(this, "Для этой страницы режим чтения недоступен", Toast.LENGTH_SHORT).show()
                    return@evaluateJavascript
                }
                // Не показываем мусор из нашей же разметки
                if (text.contains("Режим чтения Режим чтения")) {
                    Toast.makeText(this, "Для этой страницы режим чтения недоступен", Toast.LENGTH_SHORT).show()
                    return@evaluateJavascript
                }
                val page = ReaderModeScript.wrapHtml(title, html, false)
                binding.readerWebView.settings.javaScriptEnabled = false
                binding.readerWebView.loadDataWithBaseURL(active.url, page, "text/html", "utf-8", null)
                binding.readerOverlay.visibility = View.VISIBLE
                expandChromeNow()
            } catch (_: Exception) {
                Toast.makeText(this, "Не удалось открыть режим чтения", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun closeReader() {
        binding.readerOverlay.visibility = View.GONE
        binding.readerWebView.loadUrl("about:blank")
    }

    private fun translate(toLang: String) {
        if (active.isStartPage) return
        val url = active.url
        if (url.isBlank() || url == "about:blank") return
        translateJob?.cancel()
        val wv = binding.webView
        // keep page visible — no whiteout
        wv.animate().cancel()
        wv.alpha = 1f
        wv.scaleX = 1f
        wv.scaleY = 1f

        translateJob = lifecycleScope.launch {
            wv.evaluateJavascript(InPageTranslateScript.injectCss, null)
            delay(30)
            val raw = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    wv.evaluateJavascript(InPageTranslateScript.extractNodes) { result ->
                        if (cont.isActive) cont.resume(result.orEmpty())
                    }
                }
            }
            val payload = try {
                val unquoted = JSONTokener(raw).nextValue()?.toString() ?: "[]"
                JSONArray(unquoted)
            } catch (_: Exception) {
                JSONArray()
            }
            if (payload.length() == 0) {
                Toast.makeText(this@MainActivity, "Нечего переводить", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val sample = buildString {
                for (i in 0 until minOf(payload.length(), 8)) {
                    append(payload.getJSONObject(i).optString("text"))
                    append(' ')
                }
            }
            val from = PageTranslator.detectLikelyLang(sample)
            val target = when {
                toLang == from -> if (from == "ru") "en" else "ru"
                else -> toLang
            }
            val flash = if (target == "ru") "srr-tr-flash-ru" else "srr-tr-flash"

            val ids = mutableListOf<Int>()
            val texts = mutableListOf<String>()
            for (i in 0 until payload.length()) {
                val o = payload.getJSONObject(i)
                ids += o.optInt("id", i)
                texts += o.optString("text")
            }

            // progressive chunks so words appear one after another with highlight
            val chunkSize = 10
            var offset = 0
            while (offset < texts.size) {
                val end = minOf(offset + chunkSize, texts.size)
                val sliceTexts = texts.subList(offset, end).toList()
                val sliceIds = ids.subList(offset, end).toList()
                val translated = PageTranslator.translateBatch(sliceTexts, "auto", target)
                val out = JSONArray()
                for (i in sliceIds.indices) {
                    out.put(
                        JSONObject()
                            .put("id", sliceIds[i])
                            .put("text", translated.getOrElse(i) { sliceTexts[i] })
                    )
                }
                val js = InPageTranslateScript.applyBatch(out.toString(), flash)
                withContext(Dispatchers.Main) {
                    wv.evaluateJavascript(js, null)
                }
                offset = end
                delay(80)
            }
        }
    }

    private fun restoreWebViewMotion() {
        binding.webView.animate().cancel()
        binding.webView.alpha = 1f
        binding.webView.scaleX = 1f
        binding.webView.scaleY = 1f
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.addressBar.windowToken, 0)
    }

    private class BookmarkListAdapter(
        private val items: List<Bookmark>,
        private val onClick: (Bookmark) -> Unit
    ) : RecyclerView.Adapter<BookmarkListAdapter.VH>() {
        class VH(val b: ru.srr.safari.databinding.ItemBookmarkRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ru.srr.safari.databinding.ItemBookmarkRowBinding.inflate(
                android.view.LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.bookmarkTitle.text = item.title.ifBlank { UrlUtils.displayHost(item.url) }
            holder.b.root.setOnClickListener { onClick(item) }
        }
    }

    private class FavoriteAdapter(
        items: List<Bookmark>,
        editing: Boolean,
        private val onClick: (Bookmark) -> Unit,
        private val onDelete: (Bookmark) -> Unit
    ) : RecyclerView.Adapter<FavoriteAdapter.VH>() {
        private var items: List<Bookmark> = items
        private var editing: Boolean = editing

        class VH(val b: ItemFavoriteBinding) : RecyclerView.ViewHolder(b.root)

        fun itemAt(position: Int): Bookmark? = items.getOrNull(position)

        fun submit(next: List<Bookmark>, editing: Boolean) {
            this.items = next
            this.editing = editing
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ItemFavoriteBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
            b.favTile.background = LiquidGlass.capsuleDrawable(
                parent.context,
                BrowserSettings(parent.context).glassOpacity,
                16f
            )
            LiquidGlass.polishCapsule(b.favTile, 16f)
            return VH(b)
        }

        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val letter = item.title.firstOrNull()?.uppercaseChar()?.toString()
                ?: UrlUtils.displayHost(item.url).firstOrNull()?.uppercaseChar()?.toString()
                ?: "?"
            holder.b.favLetter.text = letter
            holder.b.favTitle.text = item.title.ifBlank { UrlUtils.displayHost(item.url) }
            holder.b.favDelete.visibility = if (editing) View.VISIBLE else View.GONE
            val colors = intArrayOf(
                0xFF34C759.toInt(), 0xFF007AFF.toInt(), 0xFFFF9500.toInt(),
                0xFFAF52DE.toInt(), 0xFFFF2D55.toInt(), 0xFF5856D6.toInt(),
                0xFF5AC8FA.toInt(), 0xFF8E8E93.toInt()
            )
            val density = holder.itemView.resources.displayMetrics.density
            val brand = when {
                item.url.contains("ya.ru") || item.url.contains("yandex") -> 0xFFFC3F1D.toInt()
                item.url.contains("google") && !item.url.contains("github") -> 0xFF4285F4.toInt()
                item.url.contains("youtube") -> 0xFFFF0000.toInt()
                item.url.contains("apple") -> 0xFF555555.toInt()
                item.url.contains("github") -> 0xFF24292F.toInt()
                item.url.contains("wikipedia") -> 0xFF000000.toInt()
                else -> colors[position % colors.size]
            }
            val badge = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(brand)
            }
            holder.b.favLetter.background = badge
            val pad = (5 * density).toInt()
            holder.b.favLetter.setPadding(pad, pad, pad, pad)
            holder.b.root.setOnClickListener { onClick(item) }
            holder.b.favDelete.setOnClickListener { onDelete(item) }
            holder.b.root.setOnLongClickListener {
                onClick(item)
                true
            }
        }
    }

    private class SimpleAdapter(
        private val items: List<Pair<String, String>>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<SimpleAdapter.VH>() {
        class VH(val b: ItemSimpleBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ItemSimpleBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.b.itemTitle.text = items[position].first
            holder.b.itemSubtitle.text = items[position].second
            holder.b.root.setOnClickListener { onClick(position) }
        }
    }

    private class SuggestionAdapter(
        private val items: List<Pair<String, String>>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<SuggestionAdapter.VH>() {
        class VH(val b: ItemSuggestionBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ItemSuggestionBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.b.itemTitle.text = items[position].first
            holder.b.itemSubtitle.text = items[position].second
            holder.b.root.setOnClickListener { onClick(position) }
        }
    }

    private class HistoryAdapter(
        private val rows: List<HistoryRow>,
        private val onOpen: (HistoryEntry) -> Unit,
        private val onToggleSection: (String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private companion object {
            const val TYPE_HEADER = 0
            const val TYPE_ENTRY = 1
        }

        class HeaderVH(val b: ItemHistorySectionBinding) : RecyclerView.ViewHolder(b.root)
        class EntryVH(val b: ItemHistoryBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is HistoryRow.Header -> TYPE_HEADER
            is HistoryRow.Entry -> TYPE_ENTRY
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = android.view.LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderVH(ItemHistorySectionBinding.inflate(inflater, parent, false))
            } else {
                EntryVH(ItemHistoryBinding.inflate(inflater, parent, false))
            }
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is HistoryRow.Header -> {
                    val h = holder as HeaderVH
                    h.b.historySectionTitle.text = row.title
                    h.b.historySectionChevron.text = if (row.expanded) "▾" else "▸"
                    h.b.root.setOnClickListener { onToggleSection(row.key) }
                }
                is HistoryRow.Entry -> {
                    val h = holder as EntryVH
                    val item = row.item
                    h.b.historyTitle.text = item.title.ifBlank { UrlUtils.displayHost(item.url) }
                    h.b.historyHost.text = item.url.removePrefix("https://").removePrefix("http://")
                    h.b.historyRow.setBackgroundResource(
                        when (row.place) {
                            HistoryPlace.ONLY -> R.drawable.bg_history_cell_only
                            HistoryPlace.FIRST -> R.drawable.bg_history_cell_top
                            HistoryPlace.LAST -> R.drawable.bg_history_cell_bot
                            HistoryPlace.MIDDLE -> R.drawable.bg_history_cell_mid
                        }
                    )
                    h.b.historyDivider.visibility =
                        if (row.place == HistoryPlace.LAST || row.place == HistoryPlace.ONLY) {
                            View.INVISIBLE
                        } else {
                            View.VISIBLE
                        }
                    h.b.historyRow.setOnClickListener { onOpen(item) }
                }
            }
        }
    }

    private class TabsAdapter(
        items: List<Tab>,
        previews: Map<String, Bitmap>,
        private val onSelect: (String) -> Unit,
        private val onClose: (String) -> Unit
    ) : RecyclerView.Adapter<TabsAdapter.VH>() {
        private var items: List<Tab> = items
        private var previews: Map<String, Bitmap> = previews

        class VH(val b: ItemTabBinding) : RecyclerView.ViewHolder(b.root)

        fun tabIdAt(position: Int): String? = items.getOrNull(position)?.id

        fun submit(next: List<Tab>, previews: Map<String, Bitmap>) {
            this.items = next
            this.previews = previews
            notifyDataSetChanged()
        }

        fun notifyPreviewsChanged() {
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ItemTabBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
            val opacity = BrowserSettings(parent.context).glassOpacity
            val card = b.tabPreview.parent as? View
            card?.background = LiquidGlass.capsuleDrawable(parent.context, opacity, 22f)
            card?.let { LiquidGlass.polishTabCard(it, 22f) }
            b.tabCardClose.background = LiquidGlass.circleDrawable(parent.context, opacity)
            LiquidGlass.polishCircle(b.tabCardClose)
            return VH(b)
        }

        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val tab = items[position]
            val title = when {
                tab.isStartPage -> "Стартовая страница"
                tab.title.isNotBlank() -> tab.title
                else -> UrlUtils.displayHost(tab.url).ifBlank { "Вкладка" }
            }
            holder.b.tabCardTitle.text = title
            val letter = title.firstOrNull()?.uppercaseChar()?.toString() ?: "S"
            holder.b.tabFavicon.text = letter

            val preview = previews[tab.id]
            if (preview != null && !preview.isRecycled) {
                holder.b.tabPreview.setImageBitmap(preview)
                holder.b.tabPreview.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                holder.b.tabPreview.visibility = View.VISIBLE
                holder.b.tabPlaceholder.visibility = View.GONE
            } else {
                holder.b.tabPreview.setImageDrawable(null)
                holder.b.tabPreview.setBackgroundResource(R.color.safari_glass_preview)
                holder.b.tabPlaceholder.visibility = View.VISIBLE
            }
            holder.b.root.setOnClickListener { onSelect(tab.id) }
            holder.b.tabCardClose.setOnClickListener { onClose(tab.id) }
        }
    }
}

class SimpleTextWatcher(private val onChanged: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) {
        onChanged(s?.toString().orEmpty())
    }
}
