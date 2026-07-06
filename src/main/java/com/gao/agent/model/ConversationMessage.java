package com.gao.agent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 多模态对话消息模型。
 * 支持纯文本消息和包含图片的多模态消息，用于 Vision 模式下的 Agent Loop。
 *
 * 与纯文本的 Map&lt;String, String&gt; 对话历史不同，本类可以携带页面截图，
 * 在调用 Vision API 时将文本 + 图片一起发送给大模型，让 AI 能"看到"页面状态。
 *
 * 使用示例：
 * <pre>
 * // 纯文本消息
 * ConversationMessage.text("user", "当前页面元素列表...");
 *
 * // 带截图的多模态消息
 * ConversationMessage.withImage("user", "请分析页面状态", screenshotBase64);
 * </pre>
 */
public class ConversationMessage {

    /** 消息角色：system / user / assistant */
    private String role;
    /** 文本内容（所有消息都有） */
    private String textContent;
    /** 附带的图片列表（Vision 模式下使用，纯文本模式为 null） */
    private List<ImageContent> images;

    public ConversationMessage() {}

    /** 创建纯文本消息 */
    public ConversationMessage(String role, String textContent) {
        this.role = role;
        this.textContent = textContent;
    }

    /** 工厂方法：创建纯文本消息 */
    public static ConversationMessage text(String role, String content) {
        return new ConversationMessage(role, content);
    }

    /**
     * 工厂方法：创建带图片的多模态消息。
     *
     * @param role          消息角色
     * @param textContent   文本描述
     * @param imageBase64   图片 base64 数据（可带或不带 data:image/png;base64, 前缀）
     */
    public static ConversationMessage withImage(String role, String textContent, String imageBase64) {
        ConversationMessage msg = new ConversationMessage(role, textContent);
        if (imageBase64 != null && !imageBase64.isEmpty()) {
            msg.addImage(imageBase64);
        }
        return msg;
    }

    /**
     * 添加一张图片到消息中。
     * 自动补全 data URI 前缀（如果传入的 base64 数据没有前缀）。
     *
     * @param base64Data 图片的 base64 编码数据
     */
    public void addImage(String base64Data) {
        if (images == null) images = new ArrayList<>();
        String url = base64Data.startsWith("data:") ? base64Data
                : "data:image/png;base64," + base64Data;
        images.add(new ImageContent(url));
    }

    /** 判断消息是否包含图片 */
    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getTextContent() { return textContent; }
    public void setTextContent(String textContent) { this.textContent = textContent; }
    public List<ImageContent> getImages() { return images; }
    public void setImages(List<ImageContent> images) { this.images = images; }

    /**
     * 图片内容模型。
     * 对应 OpenAI Vision API 中 content 数组的 image_url 部分。
     * detail 默认为 "high"，表示使用高精度图片分析。
     */
    public static class ImageContent {
        /** 图片 URL（data URI 格式，如 data:image/png;base64,...） */
        private String url;
        /** 图片分析精度：low / high（high 更准确但消耗更多 token） */
        private String detail;

        public ImageContent() {}

        public ImageContent(String url) {
            this.url = url;
            this.detail = "high";
        }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getDetail() { return detail; }
        public void setDetail(String detail) { this.detail = detail; }
    }
}
