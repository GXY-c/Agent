package com.gao.agent.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.openqa.selenium.WebElement;

/**
 * 浏览器页面状态快照。
 * 在 Agent Loop 每一步执行前/后采集当前页面的完整状态，
 * 包含页面 URL、标题、各区域文本内容，以及可交互元素的编号映射。
 *
 * 核心用途：
 * <ul>
 *   <li>将页面元素列表转为文本发给 LLM，让 AI 理解当前页面并决定下一步操作</li>
 *   <li>通过 index → WebElement 映射，让 Agent 能用编号直接操作 DOM 元素</li>
 * </ul>
 */
public class BrowserState {
    /** 当前页面 URL */
    private String url;
    /** 页面标题（document.title） */
    private String title;
    /** 页面顶部区域文本（导航栏、侧边栏等） */
    private String header;
    /** 页面主体区域文本内容 */
    private String content;
    /** 页面底部区域文本 */
    private String footer;
    /** 元素编号 → ElementInfo 映射，描述每个可交互元素的属性（tag、text、placeholder 等） */
    private Map<Integer, ElementInfo> selectorMap;
    /** 元素编号 → Selenium WebElement 映射，用于实际执行点击/输入等操作（线程安全） */
    private Map<Integer, WebElement> webElements = new ConcurrentHashMap<>();

    /**
     * 将页面状态转为 LLM 可理解的文本上下文。
     * 拼接 header + content + footer，作为每步调用 LLM 时 user 消息中的页面信息。
     */
    public String toLLMContext() {
        return (header != null ? header : "") + "\n" +
               (content != null ? content : "") + "\n" +
               (footer != null ? footer : "");
    }

    /** 根据编号获取对应的 Selenium WebElement */
    public WebElement getElement(int index) {
        return webElements.get(index);
    }

    /** 注册一个元素编号与 WebElement 的映射 */
    public void putElement(int index, WebElement el) {
        webElements.put(index, el);
    }

    // ---- getters / setters ----

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

    /**
     * 页面可交互元素信息。
     * 记录单个 DOM 元素的关键属性，用于生成发给 LLM 的元素列表文本，
     * 帮助 AI 根据 placeholder、text、role 等信息判断应该操作哪个元素。
     */
    public static class ElementInfo {
        /** HTML 标签名（如 input、button、a） */
        private String tag;
        /** 元素可见文本 */
        private String text;
        /** 元素 id 属性 */
        private String id;
        /** 元素 name 属性 */
        private String name;
        /** input 的 type 属性（如 text、password、email） */
        private String type;
        /** input 的 placeholder 属性，常用于判断输入框用途 */
        private String placeholder;
        /** ARIA 无障碍标签，辅助判断元素功能 */
        private String ariaLabel;
        /** ARIA 角色（如 button、link、textbox） */
        private String role;
        /** 链接 href 属性（仅 a 标签） */
        private String href;
        /** 元素相对于 viewport 的 X 坐标（像素），用于前端截图高亮标注 */
        private int x;
        /** 元素相对于 viewport 的 Y 坐标（像素） */
        private int y;
        /** 元素宽度（像素） */
        private int width;
        /** 元素高度（像素） */
        private int height;

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
        public int getX() { return x; }
        public void setX(int x) { this.x = x; }
        public int getY() { return y; }
        public void setY(int y) { this.y = y; }
        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }
        public int getHeight() { return height; }
        public void setHeight(int height) { this.height = height; }
    }
}
