package com.agentic.platform.mcp;

import com.agentic.platform.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Function;

/** Test double for a governed tool. */
final class FakeTool implements ToolHandler {
    static final ObjectMapper OM = new ObjectMapper();
    private final String name;
    private final boolean writes;
    private final String schema;
    private final Function<JsonNode, JsonNode> fn;

    FakeTool(String name, boolean writes, String schema, Function<JsonNode, JsonNode> fn) {
        this.name = name; this.writes = writes; this.schema = schema; this.fn = fn;
    }

    static FakeTool reader(String name) {
        return new FakeTool(name, false,
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}",
                in -> OM.createObjectNode().put("match", false).put("who", in.path("name").asText()));
    }

    static FakeTool writer(String name) {
        return new FakeTool(name, true, "{\"type\":\"object\"}", in -> OM.createObjectNode().put("opened", true));
    }

    public String name() { return name; }
    public String description() { return "fake " + name; }
    public boolean writes() { return writes; }
    public JsonNode inputSchema() { return ToolHandler.schema(schema); }
    public JsonNode execute(JsonNode input) { return fn.apply(input); }
}
