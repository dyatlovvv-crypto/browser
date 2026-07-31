package ru.srr.safari.engine

object CosmeticAdScript {
    /**
     * Keep selectors specific. Never use `[class*="ad-"]` — it matches "load-", "pad-", "read-".
     */
    val JS: String = """
(function(){
  if (window.__srrAdCosmetics) return;
  window.__srrAdCosmetics = true;
  var css = [
    /* Google SERP ads */
    '#tads,#tadsb,#bottomads,#tvcap,#taw,#rhsads,',
    '[data-text-ad],.commercial-unit-desktop-rhs,.ads-ad,.cu-container,',
    'div[aria-label="Ads"],div[aria-label="Реклама"],',
    /* Common ad slots / networks */
    'ins.adsbygoogle,.adsbygoogle,',
    'iframe[src*="doubleclick"],iframe[src*="googlesyndication"],',
    'iframe[src*="googletag"],iframe[src*="yandex.ru/ads"],',
    'iframe[src*="an.yandex"],iframe[src*="adfox"],iframe[id*="google_ads"],',
    'iframe[src*="adnxs"],iframe[src*="rubiconproject"],',
    'div[data-ad],div[data-ad-client],div[data-ad-slot],',
    'aside[class*="advert"],.ad-container,.ad_container,.adbox,.ad-wrapper,',
    '.banner-ad,.AdSlot,.adslot,.ad_slot,.ad-banner,.adBanner,',
    '[id^="google_ads_"],[id^="div-gpt-ad"],[id*="google_ads_iframe"],',
    /* Yandex / VK / RU */
    '.ya-partner,.yap-container,[id^="yandex_rtb"],[id*="yandex_ad"],',
    '[class*="DirectAdvert"],.serp-adv,.serp-adv__head,',
    /* sticky shells */
    '.sticky-ad,.stickyAd,[id*="sticky-ad"],[class*="sticky-ad"]'
  ].join('');
  function inject(){
    if (document.getElementById('srr-ad-cosmetic')) return;
    var style = document.createElement('style');
    style.id = 'srr-ad-cosmetic';
    style.textContent = css + '{display:none!important;visibility:hidden!important;height:0!important;max-height:0!important;overflow:hidden!important;opacity:0!important;pointer-events:none!important;}';
    (document.documentElement || document.head || document.body).appendChild(style);
  }
  inject();
  try {
    var mo = new MutationObserver(function(){ inject(); });
    mo.observe(document.documentElement, {childList:true, subtree:true});
    setTimeout(function(){ try{ mo.disconnect(); }catch(e){} }, 12000);
  } catch (e) {}
})();
""".trimIndent()

    val REMOVE_JS: String = """
(function(){
  var s = document.getElementById('srr-ad-cosmetic');
  if (s) s.remove();
  window.__srrAdCosmetics = false;
})();
""".trimIndent()
}
