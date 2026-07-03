package com.gao.agent.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.openqa.selenium.WebElement;

public class BrowserState {
    private String url;
    private String title;
    private String header;
    private String content;
    private String footer;
    private Map<Integer, ElementInfo> selectorMap;
    private Map<Integer, WebElement> webElements = new ConcurrentHashMap<>();

    public String toLLMContext() {
        return header + "\n" + content + "\n" + footer;
    }

    public WebElement getElement(int index) {
        return webElements.get(index);
    }

    public void putElement(int index, WebElement el) {
        webElements.put(index, el);
    }

    // getters / setters
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getHeader() { return header; }
    public void setHeader(String header) { this.header = header; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getFooter() { return footer; }
    public void setFooter(String footer) { this.footer = footer; }
    public Map<Integer, ElementInfo> getSelectorMap() { return selectorMap; }
    public void setSelectorMap(Map<Integer, ElementInfo> selectorMap) { this.selectorMap = selectorMap; }
    public Map<Integer, WebElement> getWebElements() { return webElements; }

    public static class ElementInfo {
        private String tag, text, id, name, type, placeholder, ariaLabel, role, href;
        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getPlaceholder() { return placeholder; }
        public void setPlaceholder(String placeholder) { this.placeholder = placeholder; }
        public String getAriaLabel() { return ariaLabel; }
        public void setAriaLabel(String ariaLabel) { this.ariaLabel = ariaLabel; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getHref() { return href; }
        public void setHref(String href) { this.href = href; }
    }
}
