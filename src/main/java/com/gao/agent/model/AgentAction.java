package com.gao.agent.model;

/**
 * Agent 单步动作模型。
 * 由 LLM 在 Agent Loop 每一步中决策返回，描述下一步要执行的操作。
 * 不同动作类型使用不同的字段组合，例如：
 * <ul>
 *   <li>click_element → action + index</li>
 *   <li>input_text → action + index + text</li>
 *   <li>scroll → action + pixels</li>
 *   <li>go_to_url → action + url</li>
 *   <li>wait → action + waitMs</li>
 *   <li>done → action + success + summary</li>
 *   <li>needs_input → action + summary</li>
 * </ul>
 */
public class AgentAction {

    /**
     * 动作类型枚举。
     * 对应 LLM 返回 JSON 中 "action" 字段的值。
     */
    public enum ActionType {
        /** 点击页面元素，需配合 index 使用 */
        click_element,
        /** 在输入框中输入文本，需配合 index + text 使用 */
        input_text,
        /** 滚动页面，需配合 pixels（正数向下，负数向上） */
        scroll,
        /** 导航到指定 URL，需配合 url */
        go_to_url,
        /** 浏览器后退到上一页 */
        go_back,
        /** 等待指定毫秒数，需配合 waitMs */
        wait,
        /** 任务结束，需配合 success + summary */
        done,
        /** 需要用户提供信息才能继续（如验证码），需配合 summary */
        needs_input
    }

    /** 动作类型 */
    private ActionType action;
    /** 目标元素在页面元素列表中的编号（click_element / input_text 时使用） */
    private Integer index;
    /** 要输入的文本内容（input_text 时使用） */
    private String text;
    /** 滚动像素数，正数向下滚动，负数向上滚动（scroll 时使用） */
    private Integer pixels;
    /** 目标 URL（go_to_url 时使用） */
    private String url;
    /** 等待毫秒数（wait 时使用） */
    private Integer waitMs;
    /** LLM 的思考过程描述，用于前端展示 AI 决策理由 */
    private String thinking;
    /** 任务完成/失败时的总结描述（done / needs_input 时使用） */
    private String summary;
    /** 任务是否成功完成（done 时使用：true=成功，false=失败） */
    private Boolean success;

    public ActionType getAction() { return action; }
    public void setAction(ActionType action) { this.action = action; }
    public Integer getIndex() { return index; }
    public void setIndex(Integer index) { this.index = index; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Integer getPixels() { return pixels; }
    public void setPixels(Integer pixels) { this.pixels = pixels; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Integer getWaitMs() { return waitMs; }
    public void setWaitMs(Integer waitMs) { this.waitMs = waitMs; }
    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
}
