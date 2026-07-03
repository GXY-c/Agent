package com.gao.agent.service.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gao.agent.model.AgentAction;
import com.gao.agent.model.TestAction;
import com.gao.agent.model.TestActionType;

@Component
@Primary
@ConditionalOnProperty(prefix = "llm.openai", name = "enabled", havingValue = "true", matchIfMissing = false)
public class OpenAiLargeModelService implements LargeModelService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLargeModelService.class);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiLargeModelService(
            @Value("${llm.openai.api-key:}") String apiKey,
            @Value("${llm.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${llm.openai.model:gpt-4o-mini}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<TestAction> buildTestPlan(String targetUrl, String taskDescription, String pageElements) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is not configured. Please set llm.openai.api-key.");
        }

        try {
            String systemPrompt = "你是一个自动化测试计划生成助手。根据用户的描述和页面元素信息，生成浏览器自动化操作列表。\n\n" +
                    "重要规则（必须严格遵守）：\n" +
                    "1. 操作类型仅允许 NAVIGATE, CLICK, TYPE, ASSERT_TEXT, WAIT, EXECUTE_JS。\n" +
                    "2. CLICK/TYPE/ASSERT_TEXT 操作必须使用 'index' 参数，index 必须来自下面提供的页面元素列表中的编号。\n" +
                    "3. 严禁使用 selector、xpath、css 或任何选择器！只使用 index。\n" +
                    "4. TYPE 操作必须包含 'text' 参数。\n" +
                    "5. 返回纯 JSON 数组，不要输出额外解释。\n" +
                    "6. 仔细根据元素的 placeholder、type、text 等信息来判断应该操作哪个元素，不要猜测！\n" +
                    "7. NAVIGATE 之后建议添加 WAIT 等待页面加载。\n" +
                    "8. 如果页面有验证码（captcha、code、verification），且任务要求登录，必须填写验证码！从页面元素列表中找验证码图片显示的文本（如 text=\"adJZ\"），然后在对应的输入框中输入该文本。\n\n" +
                    "示例（带验证码的登录页）：\n" +
                    "[ { \"type\": \"NAVIGATE\", \"url\": \"http://example.com/login\" }, { \"type\": \"WAIT\", \"ms\": 2000 }, { \"type\": \"TYPE\", \"index\": 0, \"text\": \"admin\" }, { \"type\": \"TYPE\", \"index\": 1, \"text\": \"password123\" }, { \"type\": \"TYPE\", \"index\": 3, \"text\": \"adJZ\" }, { \"type\": \"CLICK\", \"index\": 5 } ]";

            String userPrompt = "目标页面 URL: " + targetUrl + "\n" +
                    "任务描述: " + taskDescription + "\n" +
                    "页面可交互元素列表:\n" + (pageElements != null ? pageElements : "（无法获取页面元素信息）") + "\n" +
                    "请根据上面的元素列表，使用正确的 index 生成 JSON 数组。";

            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "temperature", 0.2,
                    "max_tokens", 4096,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("LLM request status={}", response.statusCode());
            log.info("LLM response body: {}", response.body());
            if (response.statusCode() >= 300) {
                throw new IOException("OpenAI request failed: " + response.statusCode() + " " + response.body());
            }

            JsonNode responseRoot = objectMapper.readTree(response.body());
            JsonNode choices = responseRoot.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                throw new IOException("LLM response has no choices: " + response.body());
            }

            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").asText(null);

            if (content == null || content.isEmpty()) {
                content = message.path("reasoning_content").asText(null);
                log.info("Falling back to reasoning_content: {}", content != null ? content.substring(0, Math.min(100, content.length())) : "null");
            }

            log.info("LLM extracted content (first 200 chars): {}",
                    content != null ? content.substring(0, Math.min(200, content.length())) : "null");

            return parseActions(content, targetUrl);
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI HTTP request failed", ex);
        }
    }

    private List<TestAction> parseActions(String content, String targetUrl) throws IOException {
        log.info("=== LLM Raw Response ===");
        log.info(content);
        log.info("========================");

        if (content == null || content.trim().isEmpty()) {
            throw new IllegalStateException("LLM 返回的内容为空，无法解析动作数组。原始响应: " + content);
        }

        String sanitized = sanitizeResponseText(content);
        log.debug("Sanitized content: {}", sanitized);

        String json = extractJsonArray(sanitized);
        if (json == null) {
            log.error("Failed to extract JSON array from content: {}", content);
            throw new IllegalStateException("无法从大模型响应中解析 JSON 动作数组。请检查 LLM 是否返回了有效的 JSON 格式。\n原始响应: " + content);
        }

        log.info("=== Extracted JSON ===");
        log.info(json);
        log.info("======================");

        JsonNode root = objectMapper.readTree(json);
        if (root.isObject() && root.has("actions")) {
            root = root.get("actions");
        }
        if (!root.isArray()) {
            throw new IllegalStateException("解析到的内容不是数组：" + root.toString());
        }

        List<TestAction> actions = new ArrayList<>();
        Iterator<JsonNode> iterator = root.elements();
        while (iterator.hasNext()) {
            JsonNode node = iterator.next();
            String type = node.path("type").asText();
            TestAction action = new TestAction(TestActionType.valueOf(type.toUpperCase()));
            if (node.has("url")) {
                action.addParameter("url", node.path("url").asText());
            }
            if (node.has("index")) {
                action.addParameter("index", node.path("index").asInt());
            }
            if (node.has("selector")) {
                action.addParameter("selector", node.path("selector").asText());
            }
            if (node.has("text")) {
                action.addParameter("text", node.path("text").asText());
            }
            if (node.has("expected")) {
                action.addParameter("expected", node.path("expected").asText());
            }
            if (node.has("script")) {
                action.addParameter("script", node.path("script").asText());
            }
            if (node.has("seconds")) {
                action.addParameter("seconds", node.path("seconds").asInt());
            }
            if (node.has("ms")) {
                action.addParameter("ms", node.path("ms").asInt());
            }
            actions.add(action);
        }

        if (actions.stream().noneMatch(a -> a.getType() == TestActionType.NAVIGATE)) {
            TestAction navigate = new TestAction(TestActionType.NAVIGATE);
            navigate.addParameter("url", targetUrl);
            actions.add(0, navigate);
        }

        return actions;
    }

    // 正则: (?s) + 三个反引号 + (?:json)? + \s* + (.*?) + \s* + 三个反引号
    private static final Pattern MD_FENCE = Pattern.compile(
            "(?s)```(?:json)?\s*(.*?)```"
                    , Pattern.CASE_INSENSITIVE);

    private String extractJsonArray(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        Matcher fenceMatcher = MD_FENCE.matcher(trimmed);
        if (fenceMatcher.find()) {
            trimmed = fenceMatcher.group(1).trim();
        }
        trimmed = trimmed.replaceAll(",\\s*]", "]");

        int length = trimmed.length();
        for (int start = 0; start < length; start++) {
            if (trimmed.charAt(start) != '[') continue;
            int depth = 0;
            boolean inString = false;
            boolean escape = false;
            for (int i = start; i < length; i++) {
                char c = trimmed.charAt(i);
                if (c == '"' && !escape) inString = !inString;
                if (!inString) {
                    if (c == '[') depth++;
                    else if (c == ']') {
                        depth--;
                        if (depth == 0) return trimmed.substring(start, i + 1).trim();
                    }
                }
                escape = (c == '\\' && !escape);
            }
        }
        return null;
    }

    private String sanitizeResponseText(String content) {
        if (content == null) return null;
        String text = content.trim();
        Matcher matcher = MD_FENCE.matcher(text);
        if (matcher.find()) {
            text = matcher.group(1).trim();
        }
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
            text = text.substring(1, text.length() - 1);
        }
        return text.trim();
    }

    // ==================== Agent Loop 模式 ====================

    @Override
    public AgentAction decideNextAction(List<Map<String, String>> conversationHistory) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is not configured.");
        }
        try {
            List<Map<String, String>> msgs = new ArrayList<>();
            for (Map<String, String> m : conversationHistory) {
                msgs.add(Map.of("role", m.get("role"), "content", m.get("content")));
            }
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", model, "temperature", 0.2, "max_tokens", 4096, "messages", msgs));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                throw new IOException("OpenAI error: " + resp.statusCode() + " " + resp.body());
            }

            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                throw new IOException("No choices in response");
            }

            JsonNode msg = choices.get(0).path("message");
            String content = msg.path("content").asText(null);
            if (content == null || content.isEmpty()) {
                content = msg.path("reasoning_content").asText(null);
            }
            log.info("LLM decided: {}", content != null ? content.substring(0, Math.min(200, content.length())) : "null");

            AgentAction action = parseSingleAction(content);

            if (action.getAction() == AgentAction.ActionType.done
                    && action.getSummary() != null
                    && action.getSummary().contains("非JSON")) {
                log.warn("LLM返回非JSON，尝试追加格式纠正提示后重试...");
                List<Map<String, String>> retryMsgs = new ArrayList<>(msgs);
                retryMsgs.add(Map.of("role", "assistant", "content", content != null ? content : ""));
                retryMsgs.add(Map.of("role", "user", "content",
                        "你的回复不是JSON格式！请严格按照以下格式重新返回（不要输出任何其他内容）：\n" +
                        "{\"action\": \"动作类型\", \"index\": 数字, \"thinking\": \"思考\"}\n" +
                        "如果是最后一步，返回：\n" +
                        "{\"action\": \"done\", \"success\": true/false, \"summary\": \"总结\"}"));

                String retryBody = objectMapper.writeValueAsString(Map.of(
                        "model", model, "temperature", 0.1, "max_tokens", 4096, "messages", retryMsgs));
                HttpRequest retryReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(retryBody))
                        .build();
                HttpResponse<String> retryResp = httpClient.send(retryReq, HttpResponse.BodyHandlers.ofString());
                if (retryResp.statusCode() < 300) {
                    JsonNode retryRoot = objectMapper.readTree(retryResp.body());
                    JsonNode retryChoices = retryRoot.path("choices");
                    if (retryChoices.isArray() && retryChoices.size() > 0) {
                        String retryContent = retryChoices.get(0).path("message").path("content").asText(null);
                        log.info("Retry LLM decided: {}", retryContent != null ? retryContent.substring(0, Math.min(200, retryContent.length())) : "null");
                        AgentAction retryAction = parseSingleAction(retryContent);
                        if (retryAction.getAction() != AgentAction.ActionType.done
                                || (retryAction.getSummary() != null && !retryAction.getSummary().contains("非JSON"))) {
                            return retryAction;
                        }
                    }
                }
            }

            return action;
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("decideNextAction failed", ex);
        }
    }

    private AgentAction parseSingleAction(String content) throws IOException {
        if (content == null || content.trim().isEmpty()) {
            log.warn("LLM returned empty content, defaulting to done");
            return buildDoneAction("LLM返回空内容");
        }
        String json = sanitizeResponseText(content);
        json = json.trim();

        if (json.startsWith("[")) {
            int depth = 0, start = -1;
            boolean inStr = false, esc = false;
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '"' && !esc) { inStr = !inStr; }
                if (inStr) { esc = (c == '\\' && !esc); continue; }
                if (c == '{') { if (depth == 0) start = i; depth++; }
                else if (c == '}') { depth--; if (depth == 0 && start >= 0) { json = json.substring(start, i + 1); break; } }
                esc = false;
            }
        }

        if (!json.startsWith("{")) {
            int braceStart = json.indexOf('{');
            int braceEnd = json.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                String candidate = json.substring(braceStart, braceEnd + 1);
                if (isValidJson(candidate)) {
                    json = candidate;
                } else {
                    String fixed = tryFixJson(candidate);
                    if (fixed != null && isValidJson(fixed)) {
                        log.info("Fixed malformed JSON");
                        json = fixed;
                    } else {
                        log.warn("Extracted braces but invalid JSON, trying regex fallback");
                        AgentAction fallback = tryParseKeyValueFormat(json);
                        if (fallback != null) {
                            log.info("Parsed via regex fallback: action={}", fallback.getAction());
                            return fallback;
                        }
                        return buildDoneAction("LLM返回非JSON内容，视为任务结束");
                    }
                }
            } else {
                AgentAction parsed = tryParseKeyValueFormat(json);
                if (parsed != null) {
                    log.info("Parsed key=value format successfully: action={}", parsed.getAction());
                    return parsed;
                }
                log.warn("LLM response is not JSON: '{}', defaulting to done", content.length() > 200 ? content.substring(0, 200) : content);
                return buildDoneAction("LLM返回非JSON内容，视为任务结束");
            }
        }

        json = json.replaceAll(",\\s*}", "}");
        json = json.replaceAll(",\\s*]", "]");

        try {
            JsonNode root = objectMapper.readTree(json);
            AgentAction action = new AgentAction();
            String at = root.path("action").asText("done");
            try {
                action.setAction(AgentAction.ActionType.valueOf(at));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown action '{}', defaulting to done", at);
                action.setAction(AgentAction.ActionType.done);
            }
            if (root.has("index") && !root.path("index").isNull())
                action.setIndex(root.path("index").asInt());
            if (root.has("text") && !root.path("text").isNull())
                action.setText(root.path("text").asText());
            if (root.has("pixels") && !root.path("pixels").isNull())
                action.setPixels(root.path("pixels").asInt());
            if (root.has("url") && !root.path("url").isNull())
                action.setUrl(root.path("url").asText());
            if (root.has("waitMs") && !root.path("waitMs").isNull())
                action.setWaitMs(root.path("waitMs").asInt());
            if (root.has("thinking") && !root.path("thinking").isNull())
                action.setThinking(root.path("thinking").asText());
            if (root.has("summary") && !root.path("summary").isNull())
                action.setSummary(root.path("summary").asText());
            if (root.has("success") && !root.path("success").isNull())
                action.setSuccess(root.path("success").asBoolean());
            return action;
        } catch (Exception e) {
            log.warn("JSON parse failed on: '{}', trying regex fallback", json.length() > 200 ? json.substring(0, 200) : json);
            AgentAction fallback = tryParseKeyValueFormat(json);
            if (fallback != null) {
                log.info("Regex fallback succeeded: action={}", fallback.getAction());
                return fallback;
            }
            log.warn("All parse attempts failed, defaulting to done", e);
            return buildDoneAction("LLM返回了无法解析的内容: " + (json.length() > 100 ? json.substring(0, 100) : json));
        }
    }

    private String tryFixJson(String json) {
        if (json == null) return null;
        String fixed = json.replaceAll(",\\s*}", "}");
        fixed = fixed.replaceAll(",\\s*]", "]");

        try {
            objectMapper.readTree(fixed);
            return fixed;
        } catch (Exception e) {
            Pattern kvPat = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|\\d+\\.?\\d*|true|false|null)");
            Matcher m = kvPat.matcher(json);
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            while (m.find()) {
                if (!first) sb.append(",");
                sb.append("\"").append(m.group(1)).append("\":").append(m.group(2));
                first = false;
            }
            if (!first) {
                sb.append("}");
                return sb.toString();
            }
            return null;
        }
    }

    private AgentAction tryParseKeyValueFormat(String content) {
        java.util.regex.Pattern actionPat = java.util.regex.Pattern.compile("action\\s*=\\s*\"([^\"]+)\"");
        java.util.regex.Pattern indexPat = java.util.regex.Pattern.compile("index\\s*=\\s*(\\d+)");
        java.util.regex.Pattern textPat = java.util.regex.Pattern.compile("text\\s*=\\s*\"([^\"]+)\"");
        java.util.regex.Pattern urlPat = java.util.regex.Pattern.compile("url\\s*=\\s*\"([^\"]+)\"");
        java.util.regex.Pattern pixelsPat = java.util.regex.Pattern.compile("pixels\\s*=\\s*(-?\\d+)");
        java.util.regex.Pattern waitMsPat = java.util.regex.Pattern.compile("waitMs\\s*=\\s*(\\d+)");

        java.util.regex.Matcher am = actionPat.matcher(content);
        if (!am.find()) {
            return tryInferActionFromNaturalLanguage(content);
        }

        AgentAction action = new AgentAction();
        String actionStr = am.group(1);
        try {
            action.setAction(AgentAction.ActionType.valueOf(actionStr));
        } catch (IllegalArgumentException e) {
            action.setAction(AgentAction.ActionType.done);
            action.setSummary("Unknown action in key=value format: " + actionStr);
            action.setSuccess(false);
            return action;
        }

        java.util.regex.Matcher m;
        m = indexPat.matcher(content); if (m.find()) action.setIndex(Integer.parseInt(m.group(1)));
        m = textPat.matcher(content); if (m.find()) action.setText(m.group(1));
        m = urlPat.matcher(content); if (m.find()) action.setUrl(m.group(1));
        m = pixelsPat.matcher(content); if (m.find()) action.setPixels(Integer.parseInt(m.group(1)));
        m = waitMsPat.matcher(content); if (m.find()) action.setWaitMs(Integer.parseInt(m.group(1)));

        java.util.regex.Pattern thinkingPat = java.util.regex.Pattern.compile("thinking\\s*=\\s*\"([^\"]+)\"");
        m = thinkingPat.matcher(content); if (m.find()) action.setThinking(m.group(1));

        return action;
    }

    private AgentAction tryInferActionFromNaturalLanguage(String content) {
        if (content == null || content.isEmpty()) return null;
        String lower = content.toLowerCase();

        java.util.regex.Pattern indexInTextPat = java.util.regex.Pattern.compile("\\[(\\d+)\\]");
        java.util.regex.Matcher indexMatcher = indexInTextPat.matcher(content);
        Integer mentionedIndex = null;
        if (indexMatcher.find()) {
            mentionedIndex = Integer.parseInt(indexMatcher.group(1));
        }

        if (lower.contains("点击") || lower.contains("click") || lower.contains("按钮")) {
            AgentAction action = new AgentAction();
            action.setAction(AgentAction.ActionType.click_element);
            if (mentionedIndex != null) action.setIndex(mentionedIndex);
            action.setThinking("从自然语言推断: 点击操作 - " + content.substring(0, Math.min(80, content.length())));
            log.info("Inferred click_element from natural language, index={}", mentionedIndex);
            return action;
        }
        if (lower.contains("输入") || lower.contains("填写") || lower.contains("type") || lower.contains("input")) {
            java.util.regex.Pattern quotePat = java.util.regex.Pattern.compile("[\"\"'']([^\"\"'']+)[\"\"'']");
            java.util.regex.Matcher qm = quotePat.matcher(content);
            String text = null;
            if (qm.find()) text = qm.group(1);

            AgentAction action = new AgentAction();
            action.setAction(AgentAction.ActionType.input_text);
            if (mentionedIndex != null) action.setIndex(mentionedIndex);
            if (text != null) action.setText(text);
            action.setThinking("从自然语言推断: 输入操作 - " + content.substring(0, Math.min(80, content.length())));
            log.info("Inferred input_text from natural language, index={}, text={}", mentionedIndex, text);
            return action;
        }
        if (lower.contains("滚动") || lower.contains("scroll")) {
            AgentAction action = new AgentAction();
            action.setAction(AgentAction.ActionType.scroll);
            action.setPixels(500);
            action.setThinking("从自然语言推断: 滚动操作");
            return action;
        }
        if (lower.contains("等待") || lower.contains("wait")) {
            AgentAction action = new AgentAction();
            action.setAction(AgentAction.ActionType.wait);
            action.setWaitMs(2000);
            action.setThinking("从自然语言推断: 等待操作");
            return action;
        }

        return null;
    }

    private boolean isValidJson(String s) {
        try { objectMapper.readTree(s); return true; } catch (Exception e) { return false; }
    }

    private AgentAction buildDoneAction(String summary) {
        AgentAction action = new AgentAction();
        action.setAction(AgentAction.ActionType.done);
        action.setSuccess(false);
        action.setSummary(summary);
        action.setThinking("LLM响应解析失败，标记为未完成");
        return action;
    }
}
