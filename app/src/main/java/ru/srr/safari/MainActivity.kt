package ru.srr.safari

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
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
import ru.srr.safari.data.HistoryEntry
import ru.srr.safari.databinding.ActivityMainBinding
import ru.srr.safari.databinding.DialogSafariMoreBinding
import ru.srr.safari.databinding.ItemFavoriteBinding
import ru.srr.safari.databinding.ItemHistoryBinding
import ru.srr.safari.databinding.ItemHistorySectionBinding
import ru.srr.safari.databinding.ItemSimpleBinding
import ru.srr.safari.databinding.ItemSuggestionBinding
import ru.srr.safari.databinding.ItemTabBinding
import ru.srr.safari.engine.InPageTranslateScript
import ru.srr.safari.engine.PageTranslator
import ru.srr.safari.engine.ReaderModeScript
import ru.srr.safari.engine.Suggestion
import ru.srr.safari.engine.SuggestionProvider
import ru.srr.safari.engine.UrlUtils
import java.util.Calendar
import java.util.UUID
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: BrowserRepository

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
    private val historySectionExpanded = mutableMapOf<String, Boolean>()

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

        ViewCompat.setOnApplyWindowInsetsListener(binding.contentContainer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Контент не залезает под статусбар / вырез
            v.updatePadding(top = bars.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomChrome) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(sys.bottom, ime.bottom)
            v.updatePadding(bottom = bottom)
            val keyboardOpen = ime.bottom > 0
            if (keyboardOpen != imeVisible) {
                imeVisible = keyboardOpen
                if (keyboardOpen) {
                    expandChromeNow()
                }
                updateChromeForState()
            } else if (keyboardOpen) {
                v.translationY = 0f
            }
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.suggestionsList) { v, insets ->
            // contentContainer уже учитывает статусбар — сверху только небольшой отступ
            v.updatePadding(top = 8)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.readerOverlay) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        setupWebView()
        setupChrome()
        setupLists()
        observeData()
        showStartPage()

        intent?.data?.toString()?.let { loadUrl(it) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.historyOverlay.visibility == View.VISIBLE -> hideHistory()
                    binding.tabsOverlay.visibility == View.VISIBLE -> hideTabs()
                    binding.readerOverlay.visibility == View.VISIBLE -> closeReader()
                    editingAddress -> cancelAddressEdit()
                    canNavigateBack() -> navigateBack()
                    else -> finish()
                }
            }
        })
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
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            addJavascriptInterface(ScrollBridge(), "SafariChrome")
            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                onContentScroll(scrollY - oldScrollY, scrollY)
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
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString().orEmpty()
                    if (!(u.startsWith("http") || u.startsWith("about:"))) return true
                    val rewritten = UrlUtils.rewriteKnownRedirects(u)
                    if (rewritten != u) {
                        view?.loadUrl(rewritten)
                        return true
                    }
                    return false
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
                    if (!isPrivate) {
                        lifecycleScope.launch { repo.addHistory(active.title, u) }
                    }
                }
            }
        }
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
        val js = """
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
        binding.webView.evaluateJavascript(js, null)
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
                chrome.animate().translationY(h).setDuration(180).start()
            } else {
                chrome.translationY = 0f
                chrome.animate().translationY(0f).setDuration(180).start()
            }
        }
    }

    private fun hideSuggestions() {
        suggestJob?.cancel()
        binding.suggestionsList.visibility = View.GONE
    }

    private fun setupChrome() {
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
            if (text.isBlank() || text == "about:blank" || text.length < 2) {
                hideSuggestions()
                return@SimpleTextWatcher
            }
            suggestJob?.cancel()
            suggestJob = lifecycleScope.launch {
                delay(160)
                if (!editingAddress) return@launch
                val hist = repo.searchHistory(text)
                    .filter { it.url != "about:blank" && !it.title.equals("about:blank", true) }
                    .map { Suggestion(it.title.ifBlank { it.url }, it.url, Suggestion.Kind.HISTORY) }
                val remote = SuggestionProvider.remoteSuggestions(text)
                    .filter { !it.text.contains("about:blank", ignoreCase = true) }
                showSuggestions((hist + remote).distinctBy { it.text.lowercase() }.take(8))
            }
        })

        // Tap page to dismiss chrome edit / show chrome
        binding.webView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (editingAddress) {
                    binding.addressBar.clearFocus()
                    hideKeyboard()
                } else if (chromeCollapsed) {
                    setChromeCollapsed(false)
                }
            }
            false
        }

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
        binding.btnTabsDone.setOnClickListener { hideTabs() }
        binding.btnNewTab.setOnClickListener { newTab(isPrivate) }
        binding.tabsCountPill.setOnClickListener { togglePrivate() }
        binding.btnTogglePrivate.setOnClickListener { togglePrivate() }
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
        val width = (250 * resources.displayMetrics.density).toInt()
        val popup = PopupWindow(
            menu.root,
            width,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 18f
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        // скругление карточки
        menu.root.clipToOutline = true
        (menu.root.getChildAt(0) as? View)?.let { card ->
            card.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 26f * resources.displayMetrics.density)
                }
            }
            card.clipToOutline = true
        }
        fun closeAnd(action: () -> Unit) {
            popup.dismiss()
            action()
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
        menu.moreBookmarks.setOnClickListener { closeAnd { showBookmarks() } }
        menu.moreTabs.setOnClickListener { closeAnd { showTabs() } }
        menu.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val yOff = -(menu.root.measuredHeight + binding.btnMore.height + (8 * resources.displayMetrics.density).toInt())
        val xOff = binding.btnMore.width - width
        popup.showAsDropDown(binding.btnMore, xOff, yOff)
    }

    private fun showPageMenu() {
        if (active.isStartPage) {
            Toast.makeText(this, "Откройте страницу", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Параметры страницы")
            .setItems(
                arrayOf(
                    "Показать режим чтения",
                    "Перевести сайт…",
                    "Отмена"
                )
            ) { _, which ->
                when (which) {
                    0 -> openReader()
                    1 -> translate("ru")
                }
            }
            .show()
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
        val editing = editingAddress || imeVisible
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
                    binding.root.setBackgroundResource(R.drawable.bg_start_wallpaper)
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
                binding.root.setBackgroundResource(R.drawable.bg_start_wallpaper)
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
            }
        }
    }

    private fun setupLists() {
        binding.favoritesList.layoutManager = GridLayoutManager(this, 4)
        binding.recentList.layoutManager = LinearLayoutManager(this)
        binding.suggestionsList.layoutManager = LinearLayoutManager(this)
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.tabsList.layoutManager = GridLayoutManager(this, 2)
        refreshStartPage()
        updateTabCount()
    }

    private fun observeData() {
        lifecycleScope.launch {
            repo.observeBookmarks().collectLatest {
                bookmarks = it
                refreshStartPage()
            }
        }
        lifecycleScope.launch {
            repo.observeHistory().collectLatest {
                history = it.filter { h -> h.url != "about:blank" && h.url.isNotBlank() }
                refreshStartPage()
            }
        }
    }

    private fun refreshStartPage() {
        val favs = if (bookmarks.isNotEmpty()) bookmarks.take(12) else defaultFavorites
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
        val input = android.widget.EditText(this).apply {
            setText(bm.title)
            setSelection(text.length)
            hint = "Название"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Избранное")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    if (bookmarks.isEmpty()) {
                        defaultFavorites.forEach { repo.addBookmark(it.title, it.url) }
                    }
                    repo.renameBookmark(bm.url, name.ifBlank { bm.title })
                }
            }
            .setNeutralButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    if (bookmarks.isEmpty()) {
                        defaultFavorites.filterNot { it.url == bm.url }.forEach {
                            repo.addBookmark(it.title, it.url)
                        }
                    } else {
                        repo.removeBookmark(bm.url)
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
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
        lifecycleScope.launch {
            repo.upsertTab(
                ru.srr.safari.data.TabEntity(
                    id = active.id,
                    title = active.title,
                    url = active.url,
                    isPrivate = active.isPrivate
                )
            )
        }
    }

    private fun showWeb() {
        binding.startPage.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        hideSuggestions()
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
        hideSuggestions()
        setChromeCollapsed(false)
        refreshAddressDisplay()
        applyPrivateUi()
    }

    private fun applyPrivateSettings() {
        binding.webView.settings.domStorageEnabled = !isPrivate
        binding.webView.settings.cacheMode =
            if (isPrivate) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptCookie(!isPrivate)
    }

    private fun applyPrivateUi() {
        if (isPrivate) {
            binding.root.setBackgroundColor(ContextCompat.getColor(this, R.color.safari_private_bg))
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
            binding.root.setBackgroundResource(R.drawable.bg_start_wallpaper)
            binding.startTitle.setTextColor(ContextCompat.getColor(this, R.color.safari_start_title))
            binding.startTitle.text = "Избранное"
            binding.startSubtitle.visibility = View.GONE
            binding.recentHeader.setTextColor(ContextCompat.getColor(this, R.color.safari_start_title))
            binding.btnAllHistory.setTextColor(ContextCompat.getColor(this, R.color.safari_start_title))
        }
        binding.btnTogglePrivate.text = if (isPrivate) "Обычный" else "Приватный"
        binding.tabsTitle.text = if (isPrivate) "Приватные вкладки" else "Вкладки"
        updateTabCount()
    }

    private fun newTab(private: Boolean) {
        val tab = Tab(isPrivate = private)
        tabs.add(tab)
        activeId = tab.id
        isPrivate = private
        updateTabCount()
        hideTabs()
        showStartPage()
    }

    private fun closeTab(id: String) {
        tabs.removeAll { it.id == id }
        tabPreviews.remove(id)?.recycle()
        lifecycleScope.launch { repo.deleteTab(id) }
        val mode = tabs.filter { it.isPrivate == isPrivate }
        if (mode.isEmpty()) {
            newTab(isPrivate)
            return
        }
        if (activeId == id) activeId = mode.last().id
        updateTabCount()
        renderTabs()
        selectTab(activeId)
    }

    private fun selectTab(id: String) {
        activeId = id
        val tab = active
        isPrivate = tab.isPrivate
        applyPrivateUi()
        hideTabs()
        if (tab.isStartPage || tab.url.isBlank() || tab.url == "about:blank") showStartPage()
        else loadUrl(tab.url)
    }

    private fun togglePrivate() {
        isPrivate = !isPrivate
        applyPrivateUi()
        val mode = tabs.filter { it.isPrivate == isPrivate }
        if (mode.isEmpty()) newTab(isPrivate)
        else {
            activeId = mode.first().id
            renderTabs()
            selectTab(activeId)
            showTabs()
        }
    }

    private fun showTabs() {
        captureActiveTabPreview()
        renderTabs()
        binding.tabsOverlay.visibility = View.VISIBLE
        binding.bottomChrome.visibility = View.GONE
        updateTabCount()
    }

    private fun hideTabs() {
        binding.tabsOverlay.visibility = View.GONE
        binding.bottomChrome.visibility = View.VISIBLE
    }

    private fun captureActiveTabPreview() {
        val wv = binding.webView
        if (active.isStartPage || wv.width < 2 || wv.height < 2) {
            tabPreviews.remove(activeId)
            return
        }
        try {
            val full = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
            wv.draw(Canvas(full))
            val w = 360
            val h = (360f * full.height / full.width).toInt().coerceIn(200, 640)
            val scaled = Bitmap.createScaledBitmap(full, w, h, true)
            if (scaled !== full) full.recycle()
            tabPreviews[activeId]?.recycle()
            tabPreviews[activeId] = scaled
        } catch (_: Exception) {
            // ignore capture failures
        }
    }

    private fun renderTabs() {
        val modeTabs = tabs.filter { it.isPrivate == isPrivate }
        binding.tabsList.adapter = TabsAdapter(modeTabs, tabPreviews, ::selectTab, ::closeTab)
        updateTabCount()
    }

    private fun updateTabCount() {
        val count = tabs.count { it.isPrivate == isPrivate }.coerceAtLeast(1)
        binding.btnTabs.text = count.toString()
        if (::binding.isInitialized) {
            val label = when {
                isPrivate -> when {
                    count % 10 == 1 && count % 100 != 11 -> "$count приватная"
                    count % 10 in 2..4 && count % 100 !in 12..14 -> "$count приватные"
                    else -> "$count приватных"
                }
                count % 10 == 1 && count % 100 != 11 -> "$count вкладка"
                count % 10 in 2..4 && count % 100 !in 12..14 -> "$count вкладки"
                else -> "$count вкладок"
            }
            binding.tabsCountPill.text = label
        }
    }

    private fun showBookmarks() {
        val source = if (bookmarks.isNotEmpty()) bookmarks else defaultFavorites
        val titles = source.map { it.title.ifBlank { it.url } }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Избранное")
            .setItems(titles) { _, which -> loadUrl(source[which].url) }
            .setNeutralButton("История") { _, _ -> showHistory() }
            .setNegativeButton("Закрыть", null)
            .show()
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
        binding.historyOverlay.visibility = View.VISIBLE
        binding.bottomChrome.visibility = View.GONE
    }

    private fun hideHistory() {
        binding.historyOverlay.visibility = View.GONE
        if (binding.tabsOverlay.visibility != View.VISIBLE) {
            binding.bottomChrome.visibility = View.VISIBLE
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
        val title = active.title
        val items = arrayOf(
            "Поделиться…",
            "Скопировать",
            "Добавить в избранное",
            "Режим чтения",
            "Перевести на русский",
            "Translate to English",
            "История"
        )
        AlertDialog.Builder(this)
            .setTitle(if (active.isStartPage) "Safari" else title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        if (url.isBlank()) return@setItems
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "$title\n$url")
                        }
                        startActivity(Intent.createChooser(send, "Поделиться"))
                    }
                    1 -> {
                        if (url.isBlank()) return@setItems
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        if (url.isBlank()) return@setItems
                        lifecycleScope.launch {
                            repo.addBookmark(title, url)
                            Toast.makeText(this@MainActivity, "В избранном", Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> openReader()
                    4 -> translate("ru")
                    5 -> translate("en")
                    6 -> showHistory()
                }
            }
            .show()
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

    private class FavoriteAdapter(
        private val items: List<Bookmark>,
        private val editing: Boolean,
        private val onClick: (Bookmark) -> Unit,
        private val onDelete: (Bookmark) -> Unit
    ) : RecyclerView.Adapter<FavoriteAdapter.VH>() {
        class VH(val b: ItemFavoriteBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ItemFavoriteBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
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
            val tile = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(0xF5FFFFFF.toInt())
            }
            val badge = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(brand)
            }
            holder.b.favTile.background = tile
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
        private val items: List<Tab>,
        private val previews: Map<String, Bitmap>,
        private val onSelect: (String) -> Unit,
        private val onClose: (String) -> Unit
    ) : RecyclerView.Adapter<TabsAdapter.VH>() {
        class VH(val b: ItemTabBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ItemTabBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
            val card = b.tabPreview.parent as? View
            card?.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    val r = 22f * view.resources.displayMetrics.density
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            card?.clipToOutline = true
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
                holder.b.tabPreview.visibility = View.VISIBLE
                holder.b.tabPlaceholder.visibility = View.GONE
            } else {
                holder.b.tabPreview.setImageDrawable(null)
                holder.b.tabPreview.setBackgroundColor(0xFFEEF0F3.toInt())
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
