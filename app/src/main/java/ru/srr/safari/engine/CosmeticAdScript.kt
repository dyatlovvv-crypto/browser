package ru.srr.safari.engine

object CosmeticAdScript {
    /**
     * Lightweight CSS hide on [onPageFinished].
     * Avoid broad [id*="ad-"] / iframe[src*="ad"] — they blank Google SERP.
     */
    val HIDE_CSS_JS: String = """
(function(){
  if (document.getElementById('srr-ad-hide-css')) return;
  var s = document.createElement('style');
  s.id = 'srr-ad-hide-css';
  s.textContent = [
    '#tads,#tadsb,#bottomads,#tvcap,#taw,#rhsads,',
    '[data-text-ad],.commercial-unit-desktop-rhs,.ads-ad,',
    'ins.adsbygoogle,.adsbygoogle,',
    'iframe[src*="doubleclick"],',
    'iframe[src*="googlesyndication"],',
    'iframe[src*="pagead"],',
    'iframe[src*="googletagservices"],',
    'iframe[src*="an.yandex"],',
    'iframe[src*="adfox"],',
    '[id^="google_ads_"],[id^="div-gpt-ad"]'
  ].join('') + '{display:none!important;visibility:hidden!important;height:0!important;max-height:0!important;overflow:hidden!important;opacity:0!important;pointer-events:none!important;}';
  (document.documentElement||document.head||document.body).appendChild(s);
})();
""".trimIndent()

    /**
     * Native feed ads (Yandex weather cards etc.): hide by «Реклама» badge,
     * but never remove SERP / main content roots (Google blank-page bug).
     */
    val JS: String = """
(function(){
  function injectCss(){
    if (document.getElementById('srr-ad-cosmetic')) return;
    var css = [
      '#tads,#tadsb,#bottomads,#tvcap,#taw,#rhsads,',
      '[data-text-ad],.commercial-unit-desktop-rhs,.ads-ad,.cu-container,',
      'div[aria-label="Ads"],div[aria-label="Реклама"],div[aria-label="Соцреклама"],',
      'div[aria-label="Advertisement"],span[aria-label="Реклама"],span[aria-label="Соцреклама"],',
      'span[aria-label="Ads"],',
      'ins.adsbygoogle,.adsbygoogle,',
      'iframe[src*="doubleclick"],iframe[src*="googlesyndication"],',
      'iframe[src*="googletag"],iframe[src*="yandex.ru/ads"],',
      'iframe[src*="an.yandex"],iframe[src*="adfox"],iframe[id*="google_ads"],',
      'iframe[src*="adnxs"],iframe[src*="yandexadexchange"],iframe[src*="awaps.yandex"],',
      'iframe[src*="imasdk"],iframe[src*="vast"],',
      'div[data-ad-client],div[data-ad-slot],',
      'aside[class*="advert"],.ad-container,.ad_container,.adbox,.ad-wrapper,',
      '.banner-ad,.AdSlot,.adslot,.ad_slot,.ad-banner,.adBanner,',
      '[id^="google_ads_"],[id^="div-gpt-ad"],[id*="google_ads_iframe"],',
      '.ya-partner,.yap-container,[id^="yandex_rtb"],[id*="yandex_ad"],',
      '[class*="DirectAdvert"],.serp-adv,.serp-adv__head,.serp-list__adv,',
      '.sticky-ad,.stickyAd,[id*="sticky-ad"],[class*="sticky-ad"],',
      '[data-srr-ad="1"]'
    ].join('');
    var style = document.createElement('style');
    style.id = 'srr-ad-cosmetic';
    style.textContent = css + '{display:none!important;visibility:hidden!important;height:0!important;max-height:0!important;overflow:hidden!important;opacity:0!important;pointer-events:none!important;margin:0!important;padding:0!important;}';
    (document.documentElement || document.head || document.body).appendChild(style);
  }

  function norm(t){
    return String(t || '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
  }

  var RE_MARK = /(соц)?реклама/i;
  var RE_BADGE = new RegExp('^(соц)?реклама([•·|\\s:].*)?' + String.fromCharCode(36), 'i');
  var RE_BADGE_EN = new RegExp('^ad(vertisement)?([•·|\\s:].*)?' + String.fromCharCode(36), 'i');
  var sweeping = false;

  function isProtectedRoot(el){
    if (!el || el === document.body || el === document.documentElement) return true;
    var id = (el.id || '').toLowerCase();
    if (id === 'main' || id === 'search' || id === 'center_col' || id === 'rso' ||
        id === 'rcnt' || id === 'cnt' || id === 'app' || id === 'content' ||
        id === 'maincontent' || id === 'root' || id === 'sb_main' || id === 'layout') return true;
    var role = (el.getAttribute && el.getAttribute('role')) || '';
    if (role === 'main' || role === 'search') return true;
    var cls = (typeof el.className === 'string' ? el.className : '').toLowerCase();
    if (cls.indexOf('main-content') >= 0 || cls.indexOf('page-content') >= 0) return true;
    return false;
  }

  function kill(el){
    if (!el || isProtectedRoot(el)) return;
    if (el.getAttribute('data-srr-ad') === '1') return;
    el.setAttribute('data-srr-ad', '1');
    try {
      var vids = el.querySelectorAll('video');
      for (var i = 0; i < vids.length; i++){
        try { vids[i].pause(); vids[i].removeAttribute('src'); vids[i].load(); } catch (e1) {}
      }
    } catch (e2) {}
    try { el.remove(); } catch (e3) {
      el.style.setProperty('display', 'none', 'important');
    }
  }

  function pickCard(from){
    var best = from;
    var node = from;
    var vh = window.innerHeight || 640;
    var vw = window.innerWidth || 400;
    for (var i = 0; i < 10 && node && node !== document.body; i++){
      var p = node.parentElement;
      if (!p || isProtectedRoot(p)) break;
      var r = p.getBoundingClientRect();
      if (r.width < 40 || r.height < 12) { node = p; continue; }
      // Never climb into near-full-page wrappers (Google SERP / feeds)
      if (r.height > vh * 0.72) break;
      if (r.width > vw * 0.98 && r.height > vh * 0.55) break;
      best = p;
      node = p;
      if (r.height >= 120 && r.height <= vh * 0.65 && r.width >= Math.min(150, vw * 0.5)) break;
    }
    return best;
  }

  function tooMuchContent(t){
    if (!t) return false;
    if (t.length > 1800) return true;
    var hits = 0;
    if (/Погода и самочувствие/i.test(t)) hits++;
    if (/Прогноз на месяц|Прогноз на 10/i.test(t)) hits++;
    if (/магнитное поле/i.test(t)) hits++;
    if (/Сезон пыльцы|давление/i.test(t)) hits++;
    if (/Что надеть/i.test(t)) hits++;
    if (/AI Overview|Обзор от ИИ|Результаты поиска|Search results/i.test(t)) hits += 2;
    if (/Все результаты|People also ask|Другие пользователи спрашивают/i.test(t)) hits += 2;
    return hits >= 2;
  }

  function isCompactAdCard(el, t){
    var vh = window.innerHeight || 640;
    var r = el.getBoundingClientRect();
    if (r.height < 90 || r.height > vh * 0.68) return false;
    if (r.width < 120) return false;
    if (!t || t.length < 8 || t.length > 1600) return false;
    if (tooMuchContent(t)) return false;
    if (isProtectedRoot(el)) return false;
    // Mark should appear near the top of the card (badge), not buried in long article
    var head = t.slice(0, 240);
    return RE_MARK.test(head);
  }

  function hideByTextNodes(){
    if (!document.body) return;
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var n = 0;
    var node;
    while ((node = walker.nextNode()) && n < 5000){
      n++;
      var v = node.nodeValue || '';
      if (v.length < 3 || v.length > 64) continue;
      var nv = norm(v);
      if (!RE_BADGE.test(nv) && !RE_BADGE_EN.test(nv)) continue;
      var el = node.parentElement;
      if (!el || el.getAttribute('data-srr-ad') === '1') continue;
      var card = pickCard(el);
      if (!card || card.getAttribute('data-srr-ad') === '1') continue;
      var t = '';
      try { t = card.innerText || ''; } catch (e) { continue; }
      if (!isCompactAdCard(card, t)) continue;
      kill(card);
    }
  }

  function hideFeedCards(){
    if (!document.body) return;
    var vh = window.innerHeight || 640;
    var nodes = document.body.querySelectorAll('div,section,article,aside,figure');
    var limit = Math.min(nodes.length, 2500);
    for (var i = 0; i < limit; i++){
      var el = nodes[i];
      if (el.getAttribute('data-srr-ad') === '1') continue;
      if (isProtectedRoot(el)) continue;
      var r = el.getBoundingClientRect();
      if (r.height < 110 || r.height > vh * 0.68) continue;
      if (r.width < 130) continue;
      var t = '';
      try { t = el.innerText || ''; } catch (e) { continue; }
      if (!isCompactAdCard(el, t)) continue;
      kill(el);
    }
  }

  function hideBadgeChips(){
    if (!document.body) return;
    var nodes = document.body.querySelectorAll('span,div,a,p,small,label,b,i');
    var limit = Math.min(nodes.length, 3500);
    var vh = window.innerHeight || 640;
    for (var i = 0; i < limit; i++){
      var el = nodes[i];
      if (el.getAttribute('data-srr-ad') === '1') continue;
      var t = '';
      try { t = norm(el.innerText || el.textContent || ''); } catch (e) { continue; }
      if (t.length < 3 || t.length > 42) continue;
      if (!RE_BADGE.test(t) && !RE_BADGE_EN.test(t)) continue;
      var card = pickCard(el);
      if (!card || isProtectedRoot(card)) continue;
      var cr = card.getBoundingClientRect();
      if (cr.height < 90 || cr.height > vh * 0.68) continue;
      var ct = '';
      try { ct = card.innerText || ''; } catch (e2) {}
      if (!isCompactAdCard(card, ct)) continue;
      kill(card);
    }
  }

  function sweep(){
    if (sweeping) return;
    sweeping = true;
    try {
      injectCss();
      hideByTextNodes();
      hideBadgeChips();
      hideFeedCards();
    } catch (e) {
    } finally {
      sweeping = false;
    }
  }

  function scheduleSweep(){
    if (window.__srrAdRaf) return;
    window.__srrAdRaf = requestAnimationFrame(function(){
      window.__srrAdRaf = 0;
      sweep();
    });
  }

  window.__srrAdSweep = sweep;
  sweep();
  try { if (window.__srrAdMo) window.__srrAdMo.disconnect(); } catch (e) {}
  try {
    var mo = new MutationObserver(function(){ scheduleSweep(); });
    mo.observe(document.documentElement, {childList:true, subtree:true});
    window.__srrAdMo = mo;
  } catch (e) {}
  if (!window.__srrAdTimer) {
    var ticks = 0;
    window.__srrAdTimer = setInterval(function(){
      sweep();
      if (++ticks >= 120) {
        clearInterval(window.__srrAdTimer);
        window.__srrAdTimer = null;
      }
    }, 400);
  }
  window.__srrAdCosmetics = true;
})();
""".trimIndent()

    val REMOVE_JS: String = """
(function(){
  var s = document.getElementById('srr-ad-cosmetic');
  if (s) s.remove();
  var h = document.getElementById('srr-ad-hide-css');
  if (h) h.remove();
  try { if (window.__srrAdMo) { window.__srrAdMo.disconnect(); window.__srrAdMo = null; } } catch (e) {}
  try { if (window.__srrAdTimer) { clearInterval(window.__srrAdTimer); window.__srrAdTimer = null; } } catch (e) {}
  window.__srrAdCosmetics = false;
  window.__srrAdSweep = null;
})();
""".trimIndent()
}
