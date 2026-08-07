package ru.srr.safari.engine

object CosmeticAdScript {
    /**
     * Lightweight CSS hide only. No MutationObserver / TreeWalker —
     * those froze the WebView on heavy pages until reload.
     */
    val HIDE_CSS_JS: String = """
(function(){
  if (document.getElementById('srr-ad-hide-css')) return;
  var s = document.createElement('style');
  s.id = 'srr-ad-hide-css';
  s.textContent = [
    '#tads,#tadsb,#bottomads,#rhsads,',
    '[data-text-ad],.commercial-unit-desktop-rhs,.ads-ad,',
    'ins.adsbygoogle,.adsbygoogle,',
    'iframe[src*="doubleclick"],',
    'iframe[src*="googlesyndication"],',
    'iframe[src*="pagead"],',
    'iframe[src*="googletagservices"],',
    'iframe[src*="an.yandex"],',
    'iframe[src*="adfox"],',
    '[id^="google_ads_"],[id^="div-gpt-ad"],',
    '.ya-partner,.yap-container,[id^="yandex_rtb"],',
    '[class*="DirectAdvert"],.serp-adv,.serp-adv__head'
  ].join('') + '{display:none!important;visibility:hidden!important;height:0!important;max-height:0!important;overflow:hidden!important;opacity:0!important;pointer-events:none!important;}';
  (document.documentElement||document.head||document.body).appendChild(s);
})();
""".trimIndent()

    /**
     * CSS cosmetics + a few delayed, bounded badge sweeps (no live MutationObserver).
     */
    val JS: String = """
(function(){
  // Already armed — do not re-sweep (re-inject after resume used to jank taps).
  if (window.__srrAdCosmetics) return;
  window.__srrAdCosmetics = true;

  function injectCss(){
    if (document.getElementById('srr-ad-cosmetic')) return;
    var css = [
      '#tads,#tadsb,#bottomads,#rhsads,',
      '[data-text-ad],.commercial-unit-desktop-rhs,.ads-ad,.cu-container,',
      'div[aria-label="Ads"],div[aria-label="Реклама"],div[aria-label="Соцреклама"],',
      'div[aria-label="Advertisement"],span[aria-label="Реклама"],span[aria-label="Соцреклама"],',
      'ins.adsbygoogle,.adsbygoogle,',
      'iframe[src*="doubleclick"],iframe[src*="googlesyndication"],',
      'iframe[src*="googletag"],iframe[src*="yandex.ru/ads"],',
      'iframe[src*="an.yandex"],iframe[src*="adfox"],iframe[id*="google_ads"],',
      'iframe[src*="adnxs"],iframe[src*="yandexadexchange"],',
      '[id^="google_ads_"],[id^="div-gpt-ad"],',
      '.ya-partner,.yap-container,[id^="yandex_rtb"],',
      '[class*="DirectAdvert"],.serp-adv,.serp-adv__head,',
      '[data-srr-ad="1"]'
    ].join('');
    var style = document.createElement('style');
    style.id = 'srr-ad-cosmetic';
    style.textContent = css + '{display:none!important;visibility:hidden!important;height:0!important;max-height:0!important;overflow:hidden!important;opacity:0!important;pointer-events:none!important;}';
    (document.documentElement || document.head || document.body).appendChild(style);
  }

  function norm(t){
    return String(t || '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
  }

  var RE_BADGE = new RegExp('^(соц)?реклама([•·|\\s:].*)?' + String.fromCharCode(36), 'i');
  var sweeping = false;

  function isProtectedRoot(el){
    if (!el || el === document.body || el === document.documentElement) return true;
    var id = (el.id || '').toLowerCase();
    if (id === 'main' || id === 'search' || id === 'center_col' || id === 'rso' ||
        id === 'rcnt' || id === 'cnt' || id === 'app' || id === 'content' ||
        id === 'root' || id === 'sb_main' || id === 'layout') return true;
    var role = (el.getAttribute && el.getAttribute('role')) || '';
    return role === 'main' || role === 'search';
  }

  function kill(el){
    if (!el || isProtectedRoot(el)) return;
    if (el.getAttribute('data-srr-ad') === '1') return;
    el.setAttribute('data-srr-ad', '1');
    try { el.remove(); } catch (e) {
      el.style.setProperty('display', 'none', 'important');
    }
  }

  function pickCard(from){
    var best = from;
    var node = from;
    var vh = window.innerHeight || 640;
    var vw = window.innerWidth || 400;
    for (var i = 0; i < 8 && node && node !== document.body; i++){
      var p = node.parentElement;
      if (!p || isProtectedRoot(p)) break;
      var r = p.getBoundingClientRect();
      if (r.width < 40 || r.height < 12) { node = p; continue; }
      if (r.height > vh * 0.65) break;
      if (r.width > vw * 0.98 && r.height > vh * 0.5) break;
      best = p;
      node = p;
      if (r.height >= 110 && r.height <= vh * 0.6) break;
    }
    return best;
  }

  function hideBadgeChips(){
    if (!document.body) return;
    var nodes = document.body.querySelectorAll('span,a,small,label,b,i');
    var limit = Math.min(nodes.length, 400);
    var vh = window.innerHeight || 640;
    for (var i = 0; i < limit; i++){
      var el = nodes[i];
      if (el.getAttribute('data-srr-ad') === '1') continue;
      var t = '';
      try { t = norm(el.innerText || ''); } catch (e) { continue; }
      if (t.length < 3 || t.length > 36) continue;
      if (!RE_BADGE.test(t)) continue;
      var card = pickCard(el);
      if (!card || isProtectedRoot(card)) continue;
      var cr = card.getBoundingClientRect();
      if (cr.height < 90 || cr.height > vh * 0.65) continue;
      var ct = '';
      try { ct = card.innerText || ''; } catch (e2) {}
      if (ct.length > 1400) continue;
      if (!RE_BADGE.test(norm(ct.slice(0, 200))) && ct.toLowerCase().indexOf('реклама') > 200) continue;
      kill(card);
    }
  }

  function sweep(){
    if (sweeping) return;
    sweeping = true;
    try {
      injectCss();
      hideBadgeChips();
    } catch (e) {
    } finally {
      sweeping = false;
    }
  }

  window.__srrAdSweep = sweep;
  sweep();
  // A few delayed passes only — never attach a live MutationObserver
  setTimeout(sweep, 800);
  setTimeout(sweep, 2200);
  setTimeout(sweep, 5000);
})();
""".trimIndent()

    val REMOVE_JS: String = """
(function(){
  var s = document.getElementById('srr-ad-cosmetic');
  if (s) s.remove();
  var h = document.getElementById('srr-ad-hide-css');
  if (h) h.remove();
  window.__srrAdCosmetics = false;
  window.__srrAdSweep = null;
})();
""".trimIndent()
}
