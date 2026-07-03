package com.gao.agent.service.agent;

/**
 * AgentRunner content.js 核心逻辑 —— 移植为 Selenium executeScript 注入脚本。
 * 所有脚本自包含、无外部依赖，通过 {@code (String) driver.executeScript(script, args...)} 注入。
 */
public final class JsScripts {

    private JsScripts() {}

    /**
     * 完整页面状态采集：构建 DOM 树 → 检测可交互元素 → 生成 flatTreeToString。
     * 返回 JSON: { url, title, header, content, footer, selectorMap }
     */
    public static final String GET_BROWSER_STATE = """
(function () {
  try {
    var ID = {current: 0}, DOM_HASH_MAP = {}, HIGHLIGHT_CONTAINER_ID = '__agent_highlight__';
    var extraData = new WeakMap(), cachedStyles = new WeakMap(), cachedRects = new WeakMap();
    function css(el) {
        if (cachedStyles.has(el)) return cachedStyles.get(el);
        try { var s = getComputedStyle(el); cachedStyles.set(el, s); return s; }
        catch (e) { cachedStyles.set(el, null); return null; }
    }
    function rect(el) {
        if (cachedRects.has(el)) return cachedRects.get(el);
        try { var r = el.getBoundingClientRect(); cachedRects.set(el, r); return r; }
        catch (e) { cachedRects.set(el, null); return null; }
    }
    function visible(el) {
        var s = css(el);
        return el.offsetWidth > 0 && el.offsetHeight > 0 && s && s.visibility !== 'hidden' && s.display !== 'none';
    }
    function textVisible(tn) {
        try {
            var p = tn.parentElement;
            if (!p) return false;
            try { return p.checkVisibility({checkOpacity: true, checkVisibilityCSS: true}); }
            catch (e) { var s = getComputedStyle(p); return s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0'; }
        } catch (e) { return false; }
    }
    function scrollable(el) {
        if (!el || el.nodeType !== 1) return null;
        var s = css(el);
        if (!s || s.display === 'inline' || s.display === 'inline-block') return null;
        var sx = s.overflowX, sy = s.overflowY;
        var scX = sx === 'auto' || sx === 'scroll', scY = sy === 'auto' || sy === 'scroll';
        var sw = el.scrollWidth - el.clientWidth, sh = el.scrollHeight - el.clientHeight;
        if (sw < 4 && sh < 4) return null;
        return extraData.set(el, {
            scrollable: true,
            scrollData: {top: el.scrollTop, right: el.scrollWidth - el.clientWidth - el.scrollLeft,
                         bottom: el.scrollHeight - el.clientHeight - el.scrollTop, left: el.scrollLeft}
        }).get(el);
    }
    function topElement(el) {
        var rects = el.getClientRects();
        if (!rects || rects.length === 0) return false;
        var r = null;
        for (var i = 0; i < rects.length; i++) {
            if (rects[i].width > 0 && rects[i].height > 0) { r = rects[i]; break; }
        }
        if (!r) return false;
        var pts = [{x: r.left + r.width / 2, y: r.top + r.height / 2},
                   {x: r.left + 5, y: r.top + 5},
                   {x: r.right - 5, y: r.bottom - 5}];
        for (var p = 0; p < pts.length; p++) {
            try {
                var top = document.elementFromPoint(pts[p].x, pts[p].y);
                if (!top) continue;
                var cur = top;
                while (cur && cur !== document.documentElement) {
                    if (cur === el) return true;
                    cur = cur.parentElement;
                }
            } catch (e) {}
        }
        return false;
    }
    var ic = {pointer:1, move:1, text:1, grab:1, grabbing:1, cell:1, copy:1, alias:1, 'all-scroll':1,
              'col-resize':1, 'context-menu':1, crosshair:1, 'e-resize':1, 'ew-resize':1, help:1,
              'n-resize':1, 'ne-resize':1, 'nesw-resize':1, 'ns-resize':1, 'nw-resize':1, 'nwse-resize':1,
              'row-resize':1, 's-resize':1, 'se-resize':1, 'sw-resize':1, 'vertical-text':1, 'w-resize':1,
              'zoom-in':1, 'zoom-out':1};
    var nic = {'not-allowed':1, 'no-drop':1, wait:1, progress:1, initial:1, inherit:1};
    var iTags = {a:1, button:1, input:1, select:1, textarea:1, details:1, summary:1, option:1, optgroup:1, fieldset:1, legend:1};
    var iRoles = {button:1, menu:1, menubar:1, menuitem:1, menuitemradio:1, menuitemcheckbox:1, radio:1, checkbox:1,
                  tab:1, switch:1, slider:1, spinbutton:1, combobox:1, searchbox:1, textbox:1, listbox:1, option:1, scrollbar:1};
    function interactive(el) {
        if (!el || el.nodeType !== 1) return false;
        var tag = el.tagName.toLowerCase(), s = css(el);
        if (tag !== 'html' && s && s.cursor && ic[s.cursor]) return true;
        if (iTags[tag]) {
            if (s && s.cursor && nic[s.cursor]) return false;
            if (el.hasAttribute('disabled') || el.disabled || el.readOnly || el.inert) return false;
            return true;
        }
        var role = el.getAttribute('role');
        if (el.isContentEditable || el.getAttribute('contenteditable') === 'true') return true;
        if (el.classList && (el.classList.contains('button') || el.classList.contains('dropdown-toggle') ||
            el.getAttribute('data-toggle') === 'dropdown' || el.getAttribute('aria-haspopup') === 'true')) return true;
        if (role && iRoles[role]) return true;
        if (el.hasAttribute('onclick') || el.hasAttribute('onmousedown') || typeof el.onclick === 'function') return true;
        if (scrollable(el)) return true;
        return false;
    }
    function accepted(el) {
        if (!el || !el.tagName) return false;
        var tag = el.tagName.toLowerCase();
        return !({svg:1, script:1, style:1, link:1, meta:1, noscript:1, template:1}[tag]);
    }
    var hIdx = 0, selMap = {};
    function build(node, pIframe, pHigh) {
        if (!node || node.id === HIGHLIGHT_CONTAINER_ID) return null;
        if (node.dataset && (node.dataset.browserUseIgnore === 'true' || node.dataset.pageAgentIgnore === 'true')) return null;
        if (node.getAttribute && node.getAttribute('aria-hidden') === 'true') return null;
        if (node === document.body) {
            var nd = {tagName: 'body', attributes: {}, children: []};
            for (var i = 0; i < node.childNodes.length; i++) {
                var c = build(node.childNodes[i], pIframe, false);
                if (c) nd.children.push(c);
            }
            var id = '' + (ID.current++);
            DOM_HASH_MAP[id] = nd;
            return id;
        }
        if (node.nodeType !== 1 && node.nodeType !== 3) return null;
        if (node.nodeType === 3) {
            var t = (node.textContent || '').trim();
            if (!t) return null;
            var p = node.parentElement;
            if (!p || p.tagName.toLowerCase() === 'script') return null;
            var tid = '' + (ID.current++);
            DOM_HASH_MAP[tid] = {type: 'TEXT_NODE', text: t, isVisible: textVisible(node)};
            return tid;
        }
        if (!accepted(node)) return null;
        var nd = {tagName: node.tagName.toLowerCase(), attributes: {}, children: []};
        nd.isVisible = visible(node);
        var wasHigh = false;
        if (nd.isVisible) {
            nd.isTopElement = topElement(node);
            var role = node.getAttribute('role');
            if (nd.isTopElement || role === 'menu' || role === 'menubar' || role === 'listbox') {
                nd.isInteractive = interactive(node);
                if (nd.isInteractive) {
                    nd.highlightIndex = hIdx++;
                    selMap[nd.highlightIndex] = {
                        tag: nd.tagName,
                        text: (node.textContent || '').trim().substring(0, 100),
                        id: node.id || '', name: node.getAttribute('name') || '',
                        type: node.getAttribute('type') || '', placeholder: node.getAttribute('placeholder') || '',
                        ariaLabel: node.getAttribute('aria-label') || '', role: node.getAttribute('role') || '',
                        href: node.getAttribute('href') || ''
                    };
                    node.__agentIndex = nd.highlightIndex;
                    wasHigh = true;
                }
            }
        }
        var attrs = node.getAttributeNames ? node.getAttributeNames() : [];
        for (var a = 0; a < attrs.length; a++) nd.attributes[attrs[a]] = node.getAttribute(attrs[a]);
        var tag = nd.tagName;
        if (tag === 'input' && (node.type === 'checkbox' || node.type === 'radio'))
            nd.attributes.checked = node.checked ? 'true' : 'false';
        nd.extra = extraData.get(node) || null;
        if (tag === 'iframe') {
            try {
                var doc = node.contentDocument || (node.contentWindow && node.contentWindow.document);
                if (doc) for (var i = 0; i < doc.childNodes.length; i++) {
                    var c = build(doc.childNodes[i], node, false);
                    if (c) nd.children.push(c);
                }
            } catch (e) {}
        } else if (node.isContentEditable || node.getAttribute('contenteditable') === 'true') {
            for (var i = 0; i < node.childNodes.length; i++) {
                var c = build(node.childNodes[i], pIframe, wasHigh);
                if (c) nd.children.push(c);
            }
        } else {
            if (node.shadowRoot) {
                nd.shadowRoot = true;
                for (var i = 0; i < node.shadowRoot.childNodes.length; i++) {
                    var c = build(node.shadowRoot.childNodes[i], pIframe, wasHigh);
                    if (c) nd.children.push(c);
                }
            }
            for (var i = 0; i < node.childNodes.length; i++) {
                var c = build(node.childNodes[i], pIframe, wasHigh || pHigh);
                if (c) nd.children.push(c);
            }
        }
        if (nd.tagName === 'a' && nd.children.length === 0 && !nd.attributes.href) {
            var r = rect(node);
            if (!(r && r.width > 0 && r.height > 0 || node.offsetWidth > 0 || node.offsetHeight > 0)) return null;
        }
        var nid = '' + (ID.current++);
        DOM_HASH_MAP[nid] = nd;
        return nid;
    }
    var INCLUDE_ATTRS = ['title', 'type', 'checked', 'name', 'role', 'value', 'placeholder', 'aria-label',
        'aria-expanded', 'data-state', 'aria-checked', 'id', 'for', 'target', 'aria-haspopup', 'aria-controls', 'contenteditable'];
    function matchAttrs(attrs) {
        var result = {};
        for (var i = 0; i < INCLUDE_ATTRS.length; i++) {
            var k = INCLUDE_ATTRS[i];
            if (attrs[k] && attrs[k].trim()) result[k] = attrs[k].trim();
        }
        if (result.role === result.tagName) delete result.role;
        return result;
    }
    function capT(s, max) { max = max || 20; return s.length > max ? s.substring(0, max) + '...' : s; }
    function getText(nodeId, excludeId) {
        var node = DOM_HASH_MAP[nodeId];
        if (!node || nodeId === excludeId) return '';
        if (node.type === 'TEXT_NODE') return node.text || '';
        if (node.highlightIndex !== undefined) return '';
        var parts = [];
        if (node.children) for (var i = 0; i < node.children.length; i++) parts.push(getText(node.children[i], excludeId));
        return parts.join('\\n');
    }
    function flatToStr(rootId) {
        var lines = [];
        function proc(nodeId, depth, excludeId) {
            var node = DOM_HASH_MAP[nodeId];
            if (!node) return;
            var indent = '\\t'.repeat(depth);
            if (node.type === 'TEXT_NODE') return;
            if (node.highlightIndex !== undefined) {
                var text = getText(nodeId, nodeId).trim();
                var ma = matchAttrs(node.attributes || {});
                var attrStr = '';
                var keys = Object.keys(ma);
                for (var k = 0; k < keys.length; k++) {
                    var key = keys[k], val = ma[key];
                    if ((key === 'aria-label' || key === 'placeholder' || key === 'title') &&
                        val.toLowerCase().trim() === text.toLowerCase().trim()) continue;
                    attrStr += ' ' + key + '=' + capT(val);
                }
                var line = indent + '[' + node.highlightIndex + ']<' + (node.tagName || '');
                if (attrStr) line += attrStr;
                if (text) line += '>' + text;
                else if (!attrStr) line += ' ';
                line += ' />';
                if (node.extra && node.extra.scrollable && node.extra.scrollData) {
                    var sd = node.extra.scrollData, sp = [];
                    if (sd.left) sp.push('left=' + sd.left);
                    if (sd.top) sp.push('top=' + sd.top);
                    if (sd.right) sp.push('right=' + sd.right);
                    if (sd.bottom) sp.push('bottom=' + sd.bottom);
                    line += ' data-scrollable="' + sp.join(', ') + '"';
                }
                lines.push(line);
            }
            if (node.children) {
                for (var i = 0; i < node.children.length; i++)
                    proc(node.children[i], node.highlightIndex !== undefined ? depth + 1 : depth, excludeId);
            }
        }
        proc(rootId, 0, null);
        return lines.join('\\n');
    }
    var rootId = build(document.body);
    var html = flatToStr(rootId);
    cachedStyles = null; cachedRects = null;
    var vw = innerWidth, vh = innerHeight;
    var pw = document.documentElement.scrollWidth, ph = document.documentElement.scrollHeight;
    var sy = scrollY, pa = sy / vh, pb = (ph - sy - vh) / vh, tp = ph / vh, cp = ph > 0 ? sy / ph : 0;
    var header = 'Current Page: [' + document.title + '](' + location.href + ')\\n' +
        'Page info: ' + vw + 'x' + vh + 'px viewport, ' + pw + 'x' + ph + 'px total, ' +
        pa.toFixed(1) + ' pages above, ' + pb.toFixed(1) + ' below, ' + tp.toFixed(1) + ' total, at ' +
        (cp * 100).toFixed(0) + '%\\n\\n' +
        'Interactive elements from top layer:\\n\\n' +
        (sy > 4 ? '... ' + sy + 'px above - scroll to see more ...' : '[Start of page]');
    var footer = (ph - sy - vh) > 4 ? '... ' + (ph - sy - vh) + 'px below - scroll to see more ...' : '[End of page]';
    return JSON.stringify({url: location.href, title: document.title, header: header, content: html, footer: footer, selectorMap: selMap});
  } catch (err) {
    return JSON.stringify({url: location.href, title: document.title || '', header: 'Current Page: [' + (document.title || '') + '](' + location.href + ')', content: '(DOM parse error: ' + (err.message || err) + ')', footer: '[End of page]', selectorMap: {}});
  }
})();
""";

    /** 通过 __agentIndex 点击元素 —— W3C Pointer Events 完整事件链 */
    public static final String CLICK_ELEMENT = """
(function () {
    var idx = arguments[0];
    var el = null;
    (function find(n) {
        if (n.__agentIndex === idx) { el = n; return true; }
        for (var i = 0; i < n.childNodes.length; i++) {
            if (n.childNodes[i].nodeType === 1 && find(n.childNodes[i])) return true;
        }
        if (n.shadowRoot) for (var i = 0; i < n.shadowRoot.childNodes.length; i++) {
            if (n.shadowRoot.childNodes[i].nodeType === 1 && find(n.shadowRoot.childNodes[i])) return true;
        }
        return false;
    })(document.body);
    if (!el) return JSON.stringify({success: false, message: 'Element index ' + idx + ' not found'});
    el.scrollIntoView({behavior: 'auto', block: 'center'});
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    var po = {bubbles: true, cancelable: true, clientX: x, clientY: y, pointerType: 'mouse'};
    var mo = {bubbles: true, cancelable: true, clientX: x, clientY: y, button: 0};
    el.dispatchEvent(new PointerEvent('pointerover', po));
    el.dispatchEvent(new MouseEvent('mouseover', mo));
    el.dispatchEvent(new PointerEvent('pointerdown', po));
    el.dispatchEvent(new MouseEvent('mousedown', mo));
    el.focus({preventScroll: true});
    el.dispatchEvent(new PointerEvent('pointerup', po));
    el.dispatchEvent(new MouseEvent('mouseup', mo));
    el.click();
    return JSON.stringify({success: true, message: 'Clicked [' + idx + ']'});
})();
""";

    /** 通过 __agentIndex 输入文本 —— native value setter + 事件 */
    public static final String INPUT_TEXT = """
(function () {
    var idx = arguments[0], text = arguments[1];
    var el = null;
    (function find(n) {
        if (n.__agentIndex === idx) { el = n; return true; }
        for (var i = 0; i < n.childNodes.length; i++) {
            if (n.childNodes[i].nodeType === 1 && find(n.childNodes[i])) return true;
        }
        if (n.shadowRoot) for (var i = 0; i < n.shadowRoot.childNodes.length; i++) {
            if (n.shadowRoot.childNodes[i].nodeType === 1 && find(n.shadowRoot.childNodes[i])) return true;
        }
        return false;
    })(document.body);
    if (!el) return JSON.stringify({success: false, message: 'Element ' + idx + ' not found'});
    el.scrollIntoView({behavior: 'auto', block: 'center'});
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    var po = {bubbles: true, cancelable: true, clientX: x, clientY: y, pointerType: 'mouse'};
    var mo = {bubbles: true, cancelable: true, clientX: x, clientY: y, button: 0};
    el.dispatchEvent(new PointerEvent('pointerdown', po));
    el.dispatchEvent(new MouseEvent('mousedown', mo));
    el.focus({preventScroll: true});
    el.dispatchEvent(new PointerEvent('pointerup', po));
    el.dispatchEvent(new MouseEvent('mouseup', mo));
    el.click();
    if (el.isContentEditable) {
        el.innerText = '';
        el.focus();
        document.execCommand('selectAll', false);
        document.execCommand('delete', false);
        document.execCommand('insertText', false, text);
    } else if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
        var ns = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value').set;
        ns.call(el, text);
    } else { el.value = text; }
    el.dispatchEvent(new Event('input', {bubbles: true}));
    el.dispatchEvent(new Event('change', {bubbles: true}));
    return JSON.stringify({success: true, message: 'Input text into [' + idx + ']'});
})();
""";

    /** 滚动 */
    public static final String SCROLL = """
(function () {
    var px = arguments[0];
    window.scrollBy({top: px, behavior: 'smooth'});
    return JSON.stringify({success: true, message: 'Scrolled ' + px + 'px'});
})();
""";

    public static final String GET_BROWSER_STATE_SIMPLE =
        "(function(){" +
        "try{" +
        "var els=document.querySelectorAll('input,select,textarea,button,a,[role=button],[onclick],[type=submit]');" +
        "var selMap={},lines=[];" +
        "for(var i=0;i<els.length;i++){" +
        "var el=els[i];" +
        "if(!el||(!el.offsetParent&&el.tagName.toLowerCase()!=='body'))continue;" +
        "var tag=el.tagName.toLowerCase();" +
        "var text=(el.textContent||'').trim().substring(0,80);" +
        "var id=el.id||'',name=el.getAttribute('name')||'',type=el.getAttribute('type')||'';" +
        "var ph=el.getAttribute('placeholder')||'',al=el.getAttribute('aria-label')||'';" +
        "var role=el.getAttribute('role')||'',href=el.getAttribute('href')||'';" +
        "selMap[i]={tag:tag,text:text,id:id,name:name,type:type,placeholder:ph,ariaLabel:al,role:role,href:href};" +
        "var line='['+i+']<'+tag;" +
        "if(type)line+=' type='+type;" +
        "if(name)line+=' name='+name;" +
        "if(id)line+=' id='+id;" +
        "if(ph)line+=' placeholder='+ph;" +
        "if(al)line+=' aria-label='+al;" +
        "if(text)line+='>'+text;else line+=' />';" +
        "lines.push(line);" +
        "el.__agentIndex=i;" +
        "}" +
        "var header='Current Page: ['+(document.title||'')+']('+location.href+')\\n\\nInteractive elements ('+els.length+'):\\n';" +
        "return JSON.stringify({url:location.href,title:document.title||'',header:header,content:lines.join('\\n'),footer:'[End of page]',selectorMap:selMap});" +
        "}catch(err){" +
        "return JSON.stringify({url:location.href,title:document.title||'',header:'Page: '+location.href,content:'Error: '+(err.message||err),footer:'[End of page]',selectorMap:{}});" +
        "}" +
        "})();";
}
