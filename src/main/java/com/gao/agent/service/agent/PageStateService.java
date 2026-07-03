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

@Component
public class PageStateService {

    private static final Logger log = LoggerFactory.getLogger(PageStateService.class);

    private static final String JS_COLLECT =
        "window.__agentEls=[];" +
        "var IC=new Set(['pointer','move','text','grab','grabbing','cell','copy','alias'," +
        "'all-scroll','col-resize','context-menu','crosshair','e-resize','ew-resize','help'," +
        "'n-resize','ne-resize','nesw-resize','ns-resize','nw-resize','nwse-resize','row-resize'," +
        "'s-resize','se-resize','sw-resize','vertical-text','w-resize','zoom-in','zoom-out']);" +
        "var IR=new Set(['button','link','menuitem','menuitemradio','menuitemcheckbox','radio'," +
        "'checkbox','tab','switch','slider','spinbutton','combobox','searchbox','textbox'," +
        "'listbox','option','listitem','treeitem','row','scrollbar','menu','menubar']);" +
        "var IA=['aria-expanded','aria-checked','aria-selected','aria-pressed','aria-haspopup'," +
        "'aria-controls','aria-owns','aria-activedescendant'];" +
        "var SKIP=new Set(['script','style','link','meta','noscript','template','svg','head','br','hr']);" +
        "var R=[];" +
        "function vis(e){if(!e)return false;try{var r=e.getBoundingClientRect();" +
        "if(r.width<2||r.height<2)return false;" +
        "var s=getComputedStyle(e);" +
        "return s.display!=='none'&&s.visibility!=='hidden'&&parseFloat(s.opacity)>0;}catch(x){return false;}}" +
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
        "var p=e.parentElement;" +
        "if(p){var ps=getComputedStyle(p);" +
        "if(IC.has(ps.cursor)&&s.cursor==='auto'){" +
        "var r=e.getBoundingClientRect();" +
        "if(r.width>0&&r.height>0&&r.width<600&&r.height<120)return true;}}" +
        "return false;}" +
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

    private static final String FALLBACK_SELECTOR =
            "input, select, textarea, button, a[href], [role='button'], [role='link'], " +
            "[role='textbox'], [role='checkbox'], [role='radio'], [role='tab'], " +
            "[onclick], [type='submit']";

    public BrowserState getBrowserState(WebDriver driver) {
        BrowserState state = new BrowserState();
        try { state.setUrl(driver.getCurrentUrl()); } catch (Exception ignored) {}
        try { state.setTitle(driver.getTitle()); } catch (Exception ignored) {}

        Map<Integer, BrowserState.ElementInfo> selectorMap = new HashMap<>();
        StringBuilder sb = new StringBuilder();

        try {
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

        String header = "Current Page: [" + state.getTitle() + "](" + state.getUrl() + ")\n\n" +
                "Interactive elements (" + selectorMap.size() + "):\n";
        state.setHeader(header);
        state.setContent(sb.toString());
        state.setFooter("[End of page]");

        log.info("BrowserState: url={}, {} interactive elements", state.getUrl(), selectorMap.size());
        return state;
    }

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

    private String str(Object o) { return o != null ? o.toString() : ""; }

    private String safeAttr(WebElement el, String attr) {
        try {
            String v = el.getAttribute(attr);
            return v != null ? v : "";
        } catch (Exception e) { return ""; }
    }

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
