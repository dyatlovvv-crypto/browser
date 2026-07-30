package ru.srr.safari.engine

/**
 * In-page translation: mark text nodes → apply replacements with gold highlight flash.
 * No full-page whiteout / blur.
 */
object InPageTranslateScript {

    val injectCss: String = """
        (function(){
          if (document.getElementById('srr-tr-style')) return 'ok';
          var s = document.createElement('style');
          s.id = 'srr-tr-style';
          s.textContent = ''
            + '.srr-tr-unit{display:inline;border-radius:3px;transition:background-color .85s ease, box-shadow .85s ease;}'
            + '.srr-tr-flash{background-color:rgba(255,214,10,.55)!important;'
            + 'box-shadow:0 0 0 2px rgba(255,214,10,.28);}'
            + '.srr-tr-flash-ru{background-color:rgba(10,132,255,.28)!important;'
            + 'box-shadow:0 0 0 2px rgba(10,132,255,.18);}';
          document.documentElement.appendChild(s);
          return 'ok';
        })();
    """.trimIndent()

    /** Collect visible text nodes into spans; return JSON [{id,text},...] */
    val extractNodes: String = """
        (function(){
          try {
            var SKIP = /^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA|INPUT|CODE|PRE|SVG|IFRAME|BUTTON|SELECT|OPTION|META|LINK|HEAD|TITLE)$/i;
            var out = [];
            var id = 0;
            var max = 160;
            var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
              acceptNode: function(n){
                if (!n || !n.nodeValue) return NodeFilter.FILTER_REJECT;
                var t = n.nodeValue;
                if (!/\S/.test(t)) return NodeFilter.FILTER_REJECT;
                if (t.trim().length < 2) return NodeFilter.FILTER_REJECT;
                var p = n.parentElement;
                if (!p || SKIP.test(p.tagName) || p.isContentEditable) return NodeFilter.FILTER_REJECT;
                if (p.closest('.srr-tr-unit, .goog-te-banner-frame, noscript')) return NodeFilter.FILTER_REJECT;
                var st = window.getComputedStyle(p);
                if (st && (st.display === 'none' || st.visibility === 'hidden')) return NodeFilter.FILTER_REJECT;
                return NodeFilter.FILTER_ACCEPT;
              }
            });
            var nodes = [];
            while (walker.nextNode() && nodes.length < max) nodes.push(walker.currentNode);
            for (var i = 0; i < nodes.length; i++) {
              var node = nodes[i];
              var text = node.nodeValue;
              if (!text || !/\S/.test(text)) continue;
              var parent = node.parentNode;
              if (!parent) continue;
              var span = document.createElement('span');
              span.className = 'srr-tr-unit';
              span.setAttribute('data-srr-id', String(id));
              span.textContent = text;
              try { parent.replaceChild(span, node); } catch(e) { continue; }
              out.push({id: id, text: text});
              id++;
            }
            return JSON.stringify(out);
          } catch(e) {
            return '[]';
          }
        })();
    """.trimIndent()

    fun applyBatch(itemsJson: String, flashClass: String): String {
        // itemsJson is already a JS array literal string like [{"id":0,"text":"..."}]
        val safeClass = if (flashClass == "srr-tr-flash-ru") "srr-tr-flash-ru" else "srr-tr-flash"
        return """
            (function(){
              try {
                var items = $itemsJson;
                var flash = '$safeClass';
                for (var i = 0; i < items.length; i++) {
                  (function(item, delay){
                    setTimeout(function(){
                      var el = document.querySelector('span.srr-tr-unit[data-srr-id="'+item.id+'"]');
                      if (!el) return;
                      el.textContent = item.text;
                      el.classList.remove('srr-tr-flash','srr-tr-flash-ru');
                      void el.offsetWidth;
                      el.classList.add(flash);
                      setTimeout(function(){ el.classList.remove(flash); }, 900);
                    }, delay);
                  })(items[i], Math.min(i * 28, 900));
                }
                return 'ok';
              } catch(e) {
                return 'err';
              }
            })();
        """.trimIndent()
    }
}
