package com.agentic.platform.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class SanctionsCheckTool implements ToolHandler {
    private final ObjectMapper om;
    public SanctionsCheckTool(ObjectMapper om) { this.om = om; }

    public String name() { return "sanctions-check"; }
    public String description() { return "Screen a person or company name against sanctions watchlists."; }
    public boolean writes() { return false; }
    public JsonNode inputSchema() {
        return ToolHandler.schema("""
            {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""");
    }
    public JsonNode execute(JsonNode in) {
        // TODO: replace with a real screening service call
        boolean hit = in.path("name").asText("").toLowerCase().contains("blocked");
        return om.createObjectNode().put("match", hit).put("source", "mock-watchlist");
    }
}
