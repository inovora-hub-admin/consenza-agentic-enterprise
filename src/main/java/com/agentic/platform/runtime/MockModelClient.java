package com.agentic.platform.runtime;

import com.agentic.platform.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.*;

/** Offline stand-in for the model (run with the "mock" profile). Scripted, deterministic, no network. */
@Component
@Profile("mock")
public class MockModelClient implements ModelClient {
    private static final Map<String, List<String>> KEYWORDS = Map.of(
            "kyc", List.of("onboard", "kyc"),
            "credit", List.of("credit", "loan"),
            "fraud", List.of("fraud", "suspicious"));

    private final ObjectMapper om;
    public MockModelClient(ObjectMapper om) { this.om = om; }

    @Override
    public JsonNode complete(String model, String system, ArrayNode messages, List<ToolHandler> tools) {
        ObjectNode resp = om.createObjectNode();
        ArrayNode content = resp.putArray("content");
        String userText = messages.get(0).path("content").asText("");

        if (system.startsWith("You are a planner")) {
            return text(resp, content, plan(system, userText));
        }
        if (system.startsWith("Pick the single best agent")) {
            return text(resp, content, system.lines().skip(1).findFirst().orElse("").split(":")[0].trim());
        }
        int toolRounds = 0;
        String lastResult = "";
        for (JsonNode m : messages) {
            if ("user".equals(m.path("role").asText()) && m.path("content").isArray()) {
                toolRounds++;
                lastResult = m.path("content").get(0).path("content").asText("");
            }
        }
        if (toolRounds < tools.size()) {
            ToolHandler t = tools.get(toolRounds);
            ObjectNode use = content.addObject();
            use.put("type", "tool_use").put("id", "mock_" + t.name() + "_" + toolRounds).put("name", t.name());
            use.set("input", sampleInput(t.inputSchema()));
            resp.put("stop_reason", "tool_use");
            return resp;
        }
        return text(resp, content, "[mock] Done. Last tool result: " + lastResult);
    }

    private JsonNode text(ObjectNode resp, ArrayNode content, String t) {
        content.addObject().put("type", "text").put("text", t);
        resp.put("stop_reason", "end_turn");
        return resp;
    }

    private String plan(String system, String request) {
        String lower = request.toLowerCase();
        List<String> names = system.substring(system.indexOf("Agents:\n") + 8).lines()
                .map(l -> l.split(":")[0].trim()).filter(s -> !s.isEmpty()).toList();
        TreeMap<Integer, String> hits = new TreeMap<>();
        for (String n : names) {
            for (String kw : KEYWORDS.getOrDefault(n.split("-")[0], List.of())) {
                int i = lower.indexOf(kw);
                if (i >= 0) hits.merge(i, n, (a, b) -> a);
            }
        }
        List<String> chosen = hits.isEmpty() ? List.of(names.get(0)) : new ArrayList<>(new LinkedHashSet<>(hits.values()));
        ArrayNode arr = om.createArrayNode();
        for (String n : chosen) arr.addObject().put("agent", n).put("task", "Handle your part of: " + request);
        return arr.toString();
    }

    private JsonNode sampleInput(JsonNode schema) {
        ObjectNode in = om.createObjectNode();
        schema.path("properties").fields().forEachRemaining(e -> {
            String k = e.getKey().toLowerCase();
            String type = e.getValue().path("type").asText("string");
            if (type.equals("number") || type.equals("integer")) in.put(e.getKey(), 10000);
            else if (k.contains("name")) in.put(e.getKey(), "Jane Doe");
            else if (k.contains("product")) in.put(e.getKey(), "checking");
            else if (k.endsWith("id")) in.put(e.getKey(), "D-1");
            else in.put(e.getKey(), "demo");
        });
        return in;
    }
}
