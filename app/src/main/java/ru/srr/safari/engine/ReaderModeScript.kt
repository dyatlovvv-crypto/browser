package ru.srr.safari.engine

/**
 * Lightweight reader-mode extractor injected into the page.
 * Returns title + cleaned HTML via JS bridge callback.
 */
object ReaderModeScript {
    const val JS = """
(function() {
  function textOf(el) {
    if (!el) return '';
    return (el.innerText || el.textContent || '').trim();
  }
  function absUrl(u) {
    try { return new URL(u, document.baseURI).href; } catch(e) { return u; }
  }
  var article = document.querySelector('article')
    || document.querySelector('[role="main"]')
    || document.querySelector('main')
    || document.querySelector('.post-content, .article-content, .entry-content, #content')
    || document.body;
  var title = document.querySelector('h1') ? textOf(document.querySelector('h1'))
    : (document.title || '');
  var clone = article.cloneNode(true);
  clone.querySelectorAll('script, style, nav, footer, aside, iframe, noscript, form, button, .ad, .ads, [class*="cookie"], [class*="banner"]').forEach(function(n){ n.remove(); });
  clone.querySelectorAll('img').forEach(function(img){
    var s = img.getAttribute('src') || img.getAttribute('data-src');
    if (s) img.setAttribute('src', absUrl(s));
  });
  var html = clone.innerHTML;
  var plain = textOf(clone);
  if (plain.length < 200) {
    plain = textOf(document.body);
    html = '<p>' + plain.replace(/\\n{2,}/g, '</p><p>').replace(/\\n/g, '<br>') + '</p>';
  }
  return JSON.stringify({ title: title, html: html, text: plain.slice(0, 200000) });
})();
"""

    fun wrapHtml(title: String, bodyHtml: String, dark: Boolean): String {
        val bg = if (dark) "#1C1C1E" else "#F5F5F7"
        val fg = if (dark) "#F5F5F7" else "#1C1C1E"
        val muted = if (dark) "#98989D" else "#6C6C70"
        return """
<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1"/>
<style>
  :root { color-scheme: ${if (dark) "dark" else "light"}; }
  html, body { margin:0; padding:0; background:$bg; color:$fg;
    font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", Roboto, sans-serif;
    -webkit-font-smoothing: antialiased; }
  .wrap { max-width: 680px; margin: 0 auto; padding: 28px 20px 120px; }
  h1 { font-size: 1.75rem; line-height: 1.25; font-weight: 700; margin: 0 0 12px; }
  .meta { color:$muted; font-size: 0.9rem; margin-bottom: 24px; }
  p, li { font-size: 1.15rem; line-height: 1.65; margin: 0 0 1em; }
  img { max-width: 100%; height: auto; border-radius: 12px; margin: 12px 0; }
  a { color: #007AFF; text-decoration: none; }
  blockquote { border-left: 3px solid #007AFF; margin: 1em 0; padding-left: 14px; color:$muted; }
</style>
</head>
<body>
<div class="wrap">
  <h1>${escape(title)}</h1>
  <div class="meta">Режим чтения</div>
  $bodyHtml
</div>
</body>
</html>
""".trimIndent()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
