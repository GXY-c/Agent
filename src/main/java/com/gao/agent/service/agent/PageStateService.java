package com.gao.agent.service.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gao.agent.model.BrowserState;

/**
 * 页面状态采集服务。
 * 在 Agent Loop 每一步执行前/后调用，负责：
 * <ol>
 *   <li>通过 JS 脚本扫描页面中所有可交互元素</li>
 *   <li>为每个元素分配编号（index），保存到 window.__agentEls 数组和 selectorMap 中</li>
 *   <li>生成面向 LLM 的文本描述（如 "[0] <button> 登录"）</li>
 *   <li>组装 BrowserState 对象（URL、标题、header、content、footer、selectorMap）</li>
 * </ol>
 *
 * 可交互元素的判定规则（JS_COLLECT 脚本中的 inter 函数）：
 * <ul>
 *   <li>cursor 样式为 pointer/move/text/grab 等交互类型</li>
 *   <li>标签名为 a/button/input/select/textarea/details/summary/label</li>
 *   <li>元素设置了 contenteditable 属性</li>
 *   <li>元素 ARIA role 为 button/link/menuitem/checkbox 等交互角色</li>
 *   <li>元素绑定了 onclick/tabindex/data-action/data-toggle 属性</li>
 *   <li>元素 CSS 类名包含 Element UI / Ant Design 等框架的组件标识</li>
 *   <li>父元素 cursor 为交互样式且自身尺寸较小（可能是子组件）</li>
 * </ul>
 */
@Component
public class PageStateService {

    private static final Logger log = LoggerFactory.getLogger(PageStateService.class);

    /**
     * 主采集脚本：通过 JS 遍历 DOM 树，收集所有可见的可交互元素。
     * 将元素信息存入 window.__agentEls 数组（供后续点击/输入操作使用），
     * 同时返回元素属性列表供 Java 端组装 BrowserState。
     */
    private static final String JS_COLLECT =
        "window.__agentEls=[];" +
        // 交互型 cursor 样式集合
        "var IC=new Set(['pointer','move','text','grab','grabbing','cell','copy','alias'," +
        "'all-scroll','col-resize','context-menu','crosshair','e-resize','ew-resize','help'," +
        "'n-resize','ne-resize','nesw-resize','ns-resize','nw-resize','nwse-resize','row-resize'," +
        "'s-resize','se-resize','sw-resize','vertical-text','w-resize','zoom-in','zoom-out']);" +
        // 可交互的 ARIA role 集合
        "var IR=new Set(['button','link','menuitem','menuitemradio','menuitemcheckbox','radio'," +
        "'checkbox','tab','switch','slider','spinbutton','combobox','searchbox','textbox'," +
        "'listbox','option','listitem','treeitem','row','scrollbar','menu','menubar']);" +
        // 需要检测的 ARIA 属性列表（有这些属性的元素视为可交互）
        "var IA=['aria-expanded','aria-checked','aria-selected','aria-pressed','aria-haspopup'," +
        "'aria-controls','aria-owns','aria-activedescendant'];" +
        // 应跳过的标签（不可交互或无意义）
        "var SKIP=new Set(['script','style','link','meta','noscript','template','svg','head','br','hr']);" +
        "var R=[];" +
        // 可见性判断：尺寸 > 2px 且未被 CSS 隐藏
        "function vis(e){if(!e)return false;try{var r=e.getBoundingClientRect();" +
        "if(r.width<2||r.height<2)return false;" +
        "var s=getComputedStyle(e);" +
        "return s.display!=='none'&&s.visibility!=='hidden'&&parseFloat(s.opacity)>0;}catch(x){return false;}}" +
        // 综合判断元素是否可交互
        "function inter(e){" +
        "if(!e||e.nodeType!==1)return false;" +
        "var t=e.tagName.toLowerCase();if(SKIP.has(t))return false;" +
        "if(!vis(e))return false;" +
        "var s=getComputedStyle(e);" +
        "if(IC.has(s.cursor))return true;" +
        "if(['a','button','input','select','textarea','details','summary','label'].indexOf(t)>=0){" +
        "if(!e.disabled&&!e.readOnly)return true;}" +
        "if(e.isContentEditable||e.getAttribute('contenteditable')==='true')return true;" +
        "var rl=e.getAttribute('role');if(rl&&IR.has(rl))return true;" +
        "for(var i=0;i<IA.length;i++){if(e.hasAttribute(IA[i]))return true;}" +
        "if(e.hasAttribute('onclick')||e.hasAttribute('tabindex'))return true;" +
        "if(e.hasAttribute('data-action')||e.hasAttribute('data-toggle'))return true;" +
        // Element UI / Ant Design 等 UI 框架的组件 CSS 类名匹配
        "var cn=e.className||'';" +
        "if(typeof cn!=='string')cn=cn.baseVal||'';" +
        "if(cn.indexOf('el-menu-item')>=0||cn.indexOf('el-submenu__title')>=0||" +
        "cn.indexOf('el-select')>=0||cn.indexOf('el-button')>=0||" +
        "cn.indexOf('el-checkbox')>=0||cn.indexOf('el-radio')>=0||" +
        "cn.indexOf('el-switch')>=0||cn.indexOf('el-tab')>=0||" +
        "cn.indexOf('ant-menu')>=0||cn.indexOf('ant-select')>=0||" +
        "cn.indexOf('ant-btn')>=0||cn.indexOf('ant-checkbox')>=0||" +
        "cn.indexOf('ant-radio')>=0||cn.indexOf('ant-switch')>=0||" +
        "cn.indexOf('dropdown-toggle')>=0||cn.indexOf('nav-item')>=0||" +
        "cn.indexOf('sidebar-item')>=0||cn.indexOf('menu-item')>=0)return true;" +
        // 父元素 cursor 为交互样式时，子元素也可能是可交互的（如菜单项内的文字）
        "var p=e.parentElement;" +
        "if(p){var ps=getComputedStyle(p);" +
        "if(IC.has(ps.cursor)&&s.cursor==='auto'){" +
        "var r=e.getBoundingClientRect();" +
        "if(r.width>0&&r.height>0&&r.width<600&&r.height<120)return true;}}" +
        "return false;}" +
        // 递归遍历 DOM 树，收集可交互元素
        "function walk(el){" +
        "if(!el)return;" +
        "var ch=el.children;" +
        "for(var i=0;i<ch.length;i++){var c=ch[i];" +
        "var t=c.tagName.toLowerCase();if(SKIP.has(t))continue;" +
        "if(inter(c)){var r=c.getBoundingClientRect();" +
        "if(r.width>0&&r.height>0){" +
        "var tx=(c.innerText||c.value||'').trim();" +
        "if(tx.length>120)tx=tx.substring(0,120)+'...';" +
        "R.push({tag:t,text:tx,type:c.getAttribute('type')||'',name:c.getAttribute('name')||''," +
        "id:c.getAttribute('id')||'',ph:c.getAttribute('placeholder')||''," +
        "al:c.getAttribute('aria-label')||'',role:c.getAttribute('role')||''," +
        "href:c.getAttribute('href')||'',cls:(c.className&&typeof c.className==='string'?c.className:'').split(' ').slice(0,3).join(' ')});" +
        "window.__agentEls.push(c);}}" +
        "walk(c);}}" +
        "walk(document.body);return R;";

    /**
     * 降级采集用的 CSS 选择器。
     * 当 JS_COLLECT 脚本执行失败时，通过 Selenium 原生的 findElements 使用此选择器兜底。
     */
    private static final String FALLBACK_SELECTOR =
            "input, select, textarea, button, a[href], [role='button'], [role='link'], " +
            "[role='textbox'], [role='checkbox'], [role='radio'], [role='tab'], " +
            "[onclick], [type='submit']";

    /**
     * 采集当前页面的完整状态。
     * 优先使用 JS_COLLECT 脚本（功能完整，支持 UI 框架组件识别），
     * 失败时降级到 Selenium findElements + CSS 选择器。
     *
     * @param driver Selenium WebDriver 实例
     * @return BrowserState 包含 URL、标题、页面文本、元素编号映射等
     */
    public BrowserState getBrowserState(WebDriver driver) {
        BrowserState state = new BrowserState();
        try { state.setUrl(driver.getCurrentUrl()); } catch (Exception ignored) {}
        try { state.setTitle(driver.getTitle()); } catch (Exception ignored) {}

        Map<Integer, BrowserState.ElementInfo> selectorMap = new HashMap<>();
        StringBuilder sb = new StringBuilder();

        try {
            // 主路径：JS 脚本采集
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> jsResults = (List<Map<String, Object>>)
                    ((JavascriptExecutor) driver).executeScript(JS_COLLECT);

            int idx = 0;
            if (jsResults != null) {
                for (Map<String, Object> item : jsResults) {
                    String tag = str(item.get("tag"));
                    String text = str(item.get("text"));
                    String type = str(item.get("type"));
                    String name = str(item.get("name"));
                    String id = str(item.get("id"));
                    String placeholder = str(item.get("ph"));
                    String ariaLabel = str(item.get("al"));
                    String role = str(item.get("role"));
                    String href = str(item.get("href"));

                    BrowserState.ElementInfo info = new BrowserState.ElementInfo();
                    info.setTag(tag);
                    info.setText(text);
                    info.setId(id);
                    info.setName(name);
                    info.setType(type);
                    info.setPlaceholder(placeholder);
                    info.setAriaLabel(ariaLabel);
                    info.setRole(role);
                    info.setHref(href);
                    selectorMap.put(idx, info);

                    // 生成面向 LLM 的元素描述文本
                    sb.append("[").append(idx).append("] <").append(tag).append(">");
                    if (!type.isEmpty()) sb.append(" type=").append(type);
                    if (!name.isEmpty()) sb.append(" name=").append(name);
                    if (!id.isEmpty()) sb.append(" id=").append(id);
                    if (!placeholder.isEmpty()) sb.append(" placeholder=\"").append(placeholder).append("\"");
                    if (!ariaLabel.isEmpty()) sb.append(" aria-label=\"").append(ariaLabel).append("\"");
                    if (!role.isEmpty()) sb.append(" role=").append(role);
                    if (!text.isEmpty()) sb.append(" ").append(text);
                    sb.append("\n");

                    idx++;
                }
            }

        } catch (Exception e) {
            log.warn("JS element collection failed, falling back to CSS", e);
            fallbackCollect(driver, state, sb, selectorMap);
        }

        state.setSelectorMap(selectorMap);

        // 组装页面信息头部
        String header = "Current Page: [" + state.getTitle() + "](" + state.getUrl() + ")\n\n" +
                "Interactive elements (" + selectorMap.size() + "):\n";
        state.setHeader(header);
        state.setContent(sb.toString());
        state.setFooter("[End of page]");

        log.info("BrowserState: url={}, {} interactive elements", state.getUrl(), selectorMap.size());
        return state;
    }

    /**
     * 降级采集：当 JS_COLLECT 脚本失败时，使用 Selenium 原生 API 采集元素。
     * 通过 CSS 选择器查找常见交互元素，功能较 JS_COLLECT 有限，
     * 但作为兜底方案保证基本可用性。
     */
    private void fallbackCollect(WebDriver driver, BrowserState state, StringBuilder sb,
                                  Map<Integer, BrowserState.ElementInfo> selectorMap) {
        List<WebElement> elements;
        try {
            elements = driver.findElements(By.cssSelector(FALLBACK_SELECTOR));
        } catch (Exception e) {
            log.error("Fallback findElements also failed", e);
            return;
        }
        int idx = 0;
        for (WebElement el : elements) {
            try {
                if (!el.isDisplayed()) continue;
            } catch (Exception ignored) { continue; }
            try {
                String tag = el.getTagName();
                String type = safeAttr(el, "type");
                String name = safeAttr(el, "name");
                String id = safeAttr(el, "id");
                String placeholder = safeAttr(el, "placeholder");
                String ariaLabel = safeAttr(el, "aria-label");
                String role = safeAttr(el, "role");
                String href = safeAttr(el, "href");
                String text = safeText(el);

                BrowserState.ElementInfo info = new BrowserState.ElementInfo();
                info.setTag(tag);
                info.setText(text);
                info.setId(id);
                info.setName(name);
                info.setType(type);
                info.setPlaceholder(placeholder);
                info.setAriaLabel(ariaLabel);
                info.setRole(role);
                info.setHref(href);
                selectorMap.put(idx, info);
                state.putElement(idx, el);

                sb.append("[").append(idx).append("] <").append(tag).append(">");
                if (!type.isEmpty()) sb.append(" type=").append(type);
                if (!name.isEmpty()) sb.append(" name=").append(name);
                if (!id.isEmpty()) sb.append(" id=").append(id);
                if (!placeholder.isEmpty()) sb.append(" placeholder=\"").append(placeholder).append("\"");
                if (!ariaLabel.isEmpty()) sb.append(" aria-label=\"").append(ariaLabel).append("\"");
                if (!text.isEmpty()) sb.append(" ").append(text);
                sb.append("\n");
                idx++;
            } catch (Exception e) {
                log.debug("Skipping element: {}", e.getMessage());
            }
        }
    }

    /** 安全地将 Object 转为 String，null 返回空字符串 */
    private String str(Object o) { return o != null ? o.toString() : ""; }

    /** 安全地获取 WebElement 属性值，异常时返回空字符串 */
    private String safeAttr(WebElement el, String attr) {
        try {
            String v = el.getAttribute(attr);
            return v != null ? v : "";
        } catch (Exception e) { return ""; }
    }

    /** 安全地获取 WebElement 文本内容，超过 80 字符时截断 */
    private String safeText(WebElement el) {
        try {
            String t = el.getText();
            if (t == null || t.trim().isEmpty()) t = el.getAttribute("value");
            if (t == null) return "";
            t = t.trim();
            return t.length() > 80 ? t.substring(0, 80) + "..." : t;
        } catch (Exception e) { return ""; }
    }
}
