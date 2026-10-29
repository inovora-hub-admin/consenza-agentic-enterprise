package com.agentic.platform.runtime;

import com.agentic.platform.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
@Profile("!mock")
public class AnthropicModelClient implements ModelClient {
    private final RestClient http;
    private final ObjectMapper om;
    private final String apiKey;
    private final String defaultModel;

    public AnthropicModelClient(@Value("${anthropic.api-key}") String apiKey,
                                @Value("${anthropic.base-url}") String baseUrl,
                                @Value("${anthropic.default-model}") String defaultModel,
                                ObjectMapper om) {
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.om = om;
        this.http = RestClient.builder().baseUrl(baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .build();
    }

    @Override
    public JsonNode complete(String model, String system, ArrayNode messages, List<ToolHandler> tools) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("ANTHROPIC_API_KEY is not set");
        ObjectNode body = om.createObjectNode();
        body.put("model", model != null && !model.isBlank() ? model : defaultModel);
        body.put("max_tokens", 1024);
        body.put("system", system);
        body.set("messages", messages);
        if (!tools.isEmpty()) {
            ArrayNode arr = body.putArray("tools");
            for (ToolHandler t : tools) {
                ObjectNode n = arr.addObject();
                n.put("name", t.name());
                n.put("description", t.description());
                n.set("input_schema", t.inputSchema());
            }
        }
        return http.post().uri("/v1/messages").body(body).retrieve().body(JsonNode.class);
    }
}
