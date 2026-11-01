package com.agentic.platform.runtime;

import com.agentic.platform.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Model client for OpenAI or any OpenAI-compatible server (Ollama, vLLM, LiteLLM...). Activate with profile "openai".
 * The platform's internal message format is Anthropic-style; this class translates to and from the
 * chat-completions format so AgentRunner needs no changes.
 */
@Component
@Profile("openai")
public class OpenAiModelClient implements ModelClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper om;
    private final String apiKey;
    private final String baseUrl;
    private final String defaultModel;

    public OpenAiModelClient(@Value("${openai.api-key:}") String apiKey,
                             @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
                             @Value("${openai.default-model:gpt-4.1-mini}") String defaultModel,
                             ObjectMapper om) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.defaultModel = defaultModel;
        this.om = om;
    }

    @Override
    public JsonNode complete(String model, String system, ArrayNode messages, List<ToolHandler> tools) {
        if (baseUrl.contains("api.openai.com") && (apiKey == null || apiKey.isBlank()))
            throw new IllegalStateException("OPENAI_API_KEY is not set");

        ObjectNode body = om.createObjectNode();
        // An agent may carry a Claude model name from earlier configuration; ignore it here.
        body.put("model", model != null && !model.isBlank() && !model.startsWith("claude") ? model : defaultModel);
        body.put("max_completion_tokens", 1024);
        body.set("messages", toOpenAiMessages(om, system, messages));
        if (!tools.isEmpty()) {
            ArrayNode arr = body.putArray("tools");
            for (ToolHandler t : tools) {
                ObjectNode fn = arr.addObject().put("type", "function").putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", t.inputSchema());
            }
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if (apiKey != null && !apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey);
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                String snippet = resp.body() == null ? "" : resp.body().substring(0, Math.min(300, resp.body().length()));
                throw new IllegalStateException("OpenAI-compatible API returned HTTP " + resp.statusCode() + ": " + snippet);
            }
            return toAnthropicShape(om, om.readTree(resp.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model call interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Model call failed: " + e.getMessage(), e);
        }
    }

    static ArrayNode toOpenAiMessages(ObjectMapper om, String system, ArrayNode messages) {
        ArrayNode out = om.createArrayNode();
        out.addObject().put("role", "system").put("content", system == null ? "" : system);
        for (JsonNode m : messages) {
            String role = m.path("role").asText();
            JsonNode content = m.get("content");
            if (content == null || content.isNull() || content.isTextual()) {
                out.addObject().put("role", role).put("content", content == null || content.isNull() ? "" : content.asText());
                continue;
            }
            if ("assistant".equals(role)) {
                StringBuilder text = new StringBuilder();
                ArrayNode calls = om.createArrayNode();
                for (JsonNode b : content) {
                    String type = b.path("type").asText();
                    if ("text".equals(type)) text.append(b.path("text").asText());
                    else if ("tool_use".equals(type)) {
                        ObjectNode c = calls.addObject().put("id", b.path("id").asText()).put("type", "function");
                        c.putObject("function").put("name", b.path("name").asText())
                                .put("arguments", b.path("input").toString());
                    }
                }
                ObjectNode a = out.addObject().put("role", "assistant");
                if (text.length() > 0) a.put("content", text.toString()); else a.putNull("content");
                if (calls.size() > 0) a.set("tool_calls", calls);
            } else {   // a user message made of tool_result blocks (and possibly text)
                StringBuilder text = new StringBuilder();
                for (JsonNode b : content) {
                    String type = b.path("type").asText();
                    if ("tool_result".equals(type)) {
                        out.addObject().put("role", "tool").put("tool_call_id", b.path("tool_use_id").asText())
                                .put("content", b.path("content").asText());
                    } else if ("text".equals(type)) text.append(b.path("text").asText());
                }
                if (text.length() > 0) out.addObject().put("role", "user").put("content", text.toString());
            }
        }
        return out;
    }

    static JsonNode toAnthropicShape(ObjectMapper om, JsonNode resp) {
        JsonNode msg = resp.path("choices").path(0).path("message");
        ObjectNode out = om.createObjectNode();
        ArrayNode content = out.putArray("content");
        JsonNode textNode = msg.get("content");
        if (textNode != null && textNode.isTextual() && !textNode.asText().isEmpty())
            content.addObject().put("type", "text").put("text", textNode.asText());
        JsonNode calls = msg.path("tool_calls");
        boolean toolUse = calls.isArray() && calls.size() > 0;
        if (toolUse) {
            for (JsonNode c : calls) {
                ObjectNode use = content.addObject().put("type", "tool_use")
                        .put("id", c.path("id").asText()).put("name", c.path("function").path("name").asText());
                JsonNode args;
                try { args = om.readTree(c.path("function").path("arguments").asText("{}")); }
                catch (Exception e) { args = om.createObjectNode(); }
                use.set("input", args);
            }
        }
        out.put("stop_reason", toolUse ? "tool_use" : "end_turn");
        return out;
    }
}
