package com.agentic.platform.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One governed capability. Implement a bean per bank system action (or wrap an MCP server). */
public interface ToolHandler {
    String name();
    String description();
    JsonNode inputSchema();
    boolean writes();                 // true = changes state; policy engine gates these
    JsonNode execute(JsonNode input);

    static JsonNode schema(String json) {
        try { return new ObjectMapper().readTree(json); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
