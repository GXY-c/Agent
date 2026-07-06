package com.gao.agent.service.agent;

/**
 * 浏览器注入脚本集合。
 * 从 AgentRunner Chrome 扩展的 content.js 核心逻辑移植而来，
 * 通过 Selenium 的 {@code JavascriptExecutor.executeScript(script, args...)} 注入到页面中执行。
 *
 * 所有脚本均为自包含的 IIFE（立即执行函数），无外部依赖。
 * 脚本通过 {@code window.__agentIndex} 属性在 DOM 元素上标记编号，
 * 后续操作（点击、输入）通过该编号定位元素，避免使用脆弱的 CSS 选择器。
 *
 * 包含以下脚本：
 * <ul>
 *   <li>{@link #GET_BROWSER_STATE} — 完整版页面状态采集（构建 DOM 树 + 可交互元素检测）</li>
 *   <li>{@link #GET_BROWSER_STATE_SIMPLE} — 简化版页面状态采集（仅 querySelectorAll 获取常见交互元素）</li>
 *   <li>{@link #CLICK_ELEMENT} — 通过 __agentIndex 点击元素（完整 W3C Pointer Events 事件链）</li>
 *   <li>{@link #INPUT_TEXT} — 通过 __agentIndex 输入文本（native value setter + 事件触发）</li>
 *   <li>{@link #SCROLL} — 页面滚动</li>
 * </ul>
 */
public final class JsScripts {

    private JsScripts() {}

    /**
     * 完整版页面状态采集脚本。
     *
     * 执行流程：
     * <ol>
     *   <li>递归遍历 DOM 树（build），为每个节点构建扁平化描述</li>
     *   <li>检测可见性（visible/textVisible）、是否为顶层元素（topElement）、是否可交互（interactive）</li>
     *   <li>为可交互元素分配 highlightIndex 编号，保存到 window.__agentIndex</li>
     *   <li>将 DOM 树转为扁平字符串（flatToStr），格式如 [0]<button>登录</li>
     *   <li>处理 iframe、Shadow DOM、contentEditable 等特殊场景</li>
     * </ol>
     *
     * 返回 JSON：
     * <pre>
     * {
     *   "url": "当前页面 URL",
     *   "title": "页面标题",
     *   "header": "页面信息（标题、URL、视口尺寸、滚动位置等）",
     *   "content": "可交互元素的扁平化文本表示",
     *   "footer": "页面底部提示",
     *   "selectorMap": { "0": {tag, text, id, name, type, placeholder, ariaLabel, role, href}, ... }
     * }
     * </pre>
     *
     * 可交互元素判定规则（interactive 函数）：
     * <ul>
     *   <li>cursor 属性为 pointer/move/text/grab 等交互样式</li>
     *   <li>标签名为 a/button/input/select/textarea/details/summary 等</li>
     *   <li>元素设置了 contenteditable 属性</li>
     *   <li>元素包含 button/dropdown-toggle 等 CSS 类名</li>
     *   <li>元素设置了 ARIA role 为 button/menu/tab/slider 等交互角色</li>
     *   <li>元素绑定了 onclick/onmousedown 事件</li>
     *   <li>元素为可滚动容器</li>
     * </ul>
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
    // 判断元素是否可见（尺寸 > 0 且未被隐藏）
    function visible(el) {
        var s = css(el);
        return el.offsetWidth > 0 && el.offsetHeight > 0 && s && s.visibility !== 'hidden' && s.display !== 'none';
    }
    // 判断文本节点是否可见（父元素未被隐藏）
    function textVisible(tn) {
        try {
            var p = tn.parentElement;
            if (!p) return false;
            try { return p.checkVisibility({checkOpacity: true, checkVisibilityCSS: true}); }
            catch (e) { var s = getComputedStyle(p); return s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0'; }
        } catch (e) { return false; }
    }
    // 判断元素是否为可滚动容器
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
    // 判断元素是否为顶层元素（未被其他元素遮挡，可被用户点击到）
    function topElement(el) {
        var rects = el.getClientRects();
        if (!rects || rects.length === 0) return false;
        var r = null;
        for (var i = 0; i < rects.length; i++) {
            if (rects[i].width > 0 && rects[i].height > 0) { r = rects[i]; break; }
        }
        if (!r) return false;
        // 在元素中心、左上角、右下角三个采样点检测
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
    // 交互型 cursor 样式集合（有这些 cursor 的元素视为可交互）
    var ic = {pointer:1, move:1, text:1, grab:1, grabbing:1, cell:1, copy:1, alias:1, 'all-scroll':1,
              'col-resize':1, 'context-menu':1, crosshair:1, 'e-resize':1, 'ew-resize':1, help:1,
              'n-resize':1, 'ne-resize':1, 'nesw-resize':1, 'ns-resize':1, 'nw-resize':1, 'nwse-resize':1,
              'row-resize':1, 's-resize':1, 'se-resize':1, 'sw-resize':1, 'vertical-text':1, 'w-resize':1,
              'zoom-in':1, 'zoom-out':1};
    // 非交互型 cursor（即使标签名匹配，这些 cursor 也排除）
    var nic = {'not-allowed':1, 'no-drop':1, wait:1, progress:1, initial:1, inherit:1};
    // 天然可交互的 HTML 标签
    var iTags = {a:1, button:1, input:1, select:1, textarea:1, details:1, summary:1, option:1, optgroup:1, fieldset:1, legend:1};
    // 可交互的 ARIA role
    var iRoles = {button:1, menu:1, menubar:1, menuitem:1, menuitemradio:1, menuitemcheckbox:1, radio:1, checkbox:1,
                  tab:1, switch:1, slider:1, spinbutton:1, combobox:1, searchbox:1, textbox:1, listbox:1, option:1, scrollbar:1};
    // 综合判断元素是否可交互
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
    // 过滤不应采集的标签（svg/script/style 等）
    function accepted(el) {
        if (!el || !el.tagName) return false;
        var tag = el.tagName.toLowerCase();
        return !({svg:1, script:1, style:1, link:1, meta:1, noscript:1, template:1}[tag]);
    }
    // 递归构建 DOM 树，为可交互元素分配编号
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
        // 文本节点
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
            // 仅对顶层且可交互的元素分配编号
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
        // 处理 iframe 内容
        if (tag === 'iframe') {
            try {
                var doc = node.contentDocument || (node.contentWindow && node.contentWindow.document);
                if (doc) for (var i = 0; i < doc.childNodes.length; i++) {
                    var c = build(doc.childNodes[i], node, false);
                    if (c) nd.children.push(c);
                }
            } catch (e) {}
        } else if (node.isContentEditable || node.getAttribute('contenteditable') === 'true') {
            // contentEditable 元素递归处理子节点
            for (var i = 0; i < node.childNodes.length; i++) {
                var c = build(node.childNodes[i], pIframe, wasHigh);
                if (c) nd.children.push(c);
            }
        } else {
            // Shadow DOM 支持
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
    // 需要保留的属性列表（用于生成发给 LLM 的元素描述）
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
    // 获取节点内非交互子元素的文本内容（排除已分配编号的元素自身）
    function getText(nodeId, excludeId) {
        var node = DOM_HASH_MAP[nodeId];
        if (!node || nodeId === excludeId) return '';
        if (node.type === 'TEXT_NODE') return node.text || '';
        if (node.highlightIndex !== undefined) return '';
        var parts = [];
        if (node.children) for (var i = 0; i < node.children.length; i++) parts.push(getText(node.children[i], excludeId));
        return parts.join('\\n');
    }
    // 将 DOM 树转为扁平字符串，格式：[编号]<标签 属性=值>文本内容 />
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
                    // 属性值与文本内容相同时去重
                    if ((key === 'aria-label' || key === 'placeholder' || key === 'title') &&
                        val.toLowerCase().trim() === text.toLowerCase().trim()) continue;
                    attrStr += ' ' + key + '=' + capT(val);
                }
                var line = indent + '[' + node.highlightIndex + ']<' + (node.tagName || '');
                if (attrStr) line += attrStr;
                if (text) line += '>' + text;
                else if (!attrStr) line += ' ';
                line += ' />';
                // 附加滚动信息
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
    // 执行采集：构建 DOM 树 → 生成扁平字符串 → 组装页面信息
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

    /**
     * 通过 __agentIndex 编号点击元素。
     * 递归遍历 DOM 树（含 Shadow DOM）查找匹配编号的元素，
     * 滚动到可视区域后触发完整的 W3C Pointer Events 事件链：
     * pointerover → mouseover → pointerdown → mousedown → focus → pointerup → mouseup → click
     *
     * 参数：arguments[0] = 元素编号（int）
     * 返回：JSON { success: boolean, message: string }
     */
    public static final String CLICK_ELEMENT = """
(function () {
    var idx = arguments[0];
    var el = null;
    // 递归查找 __agentIndex 匹配的元素（含 Shadow DOM）
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
    // 触发完整事件链以兼容各类 UI 框架
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

    /**
     * 通过 __agentIndex 编号输入文本。
     * 先触发完整的事件链聚焦元素，然后根据元素类型选择输入方式：
     * <ul>
     *   <li>contentEditable → 使用 execCommand('insertText') 以触发富文本编辑器</li>
     *   <li>INPUT/TEXTAREA → 使用 native value setter 绕过 React/Vue 的受控组件拦截</li>
     *   <li>其他 → 直接赋值 el.value</li>
     * </ul>
     * 赋值后触发 input + change 事件通知 UI 框架更新状态。
     *
     * 参数：arguments[0] = 元素编号（int），arguments[1] = 输入文本（string）
     * 返回：JSON { success: boolean, message: string }
     */
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
    // 聚焦元素
    el.dispatchEvent(new PointerEvent('pointerdown', po));
    el.dispatchEvent(new MouseEvent('mousedown', mo));
    el.focus({preventScroll: true});
    el.dispatchEvent(new PointerEvent('pointerup', po));
    el.dispatchEvent(new MouseEvent('mouseup', mo));
    el.click();
    // 根据元素类型选择输入方式
    if (el.isContentEditable) {
        el.innerText = '';
        el.focus();
        document.execCommand('selectAll', false);
        document.execCommand('delete', false);
        document.execCommand('insertText', false, text);
    } else if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
        // 使用 native setter 绕过 React/Vue 受控组件
        var ns = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value').set;
        ns.call(el, text);
    } else { el.value = text; }
    // 通知 UI 框架值已变化
    el.dispatchEvent(new Event('input', {bubbles: true}));
    el.dispatchEvent(new Event('change', {bubbles: true}));
    return JSON.stringify({success: true, message: 'Input text into [' + idx + ']'});
})();
""";

    /**
     * 页面滚动脚本。
     * 参数：arguments[0] = 滚动像素数（正数向下，负数向上）
     * 返回：JSON { success: boolean, message: string }
     */
    public static final String SCROLL = """
(function () {
    var px = arguments[0];
    window.scrollBy({top: px, behavior: 'smooth'});
    return JSON.stringify({success: true, message: 'Scrolled ' + px + 'px'});
})();
""";

    /**
     * 简化版页面状态采集脚本。
     * 不使用 DOM 树递归，直接通过 querySelectorAll 获取常见交互元素。
     * 适用于不需要完整页面上下文的轻量场景。
     *
     * 采集的元素类型：input, select, textarea, button, a, [role=button], [onclick], [type=submit]
     * 返回格式与 GET_BROWSER_STATE 相同。
     */
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
