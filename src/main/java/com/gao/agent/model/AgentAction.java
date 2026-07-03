package com.gao.agent.model;

public class AgentAction {

    public enum ActionType {
        click_element,
        input_text,
        scroll,
        go_to_url,
        go_back,
        wait,
        done,
        needs_input
    }

    private ActionType action;
    private Integer index;
    private String text;
    private Integer pixels;     // scroll 像素，正=下滚
    private String url;
    private Integer waitMs;
    private String thinking;
    private String summary;
    private Boolean success;    // done 时：true=成功完成，false=无法完成

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
