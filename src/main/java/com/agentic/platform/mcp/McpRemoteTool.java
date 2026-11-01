package com.agentic.platform.mcp;

import com.agentic.platform.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * A tool served by a remote MCP server, presented to agents like any other ToolHandler.
 * Unless the server marks it readOnlyHint=true it is treated as a write tool, so the platform's
 * autonomy and approval rules apply to it.
 */
public final class McpRemoteTool implements ToolHandler {
    private final McpClient client;
    private final ObjectMapper om;
    private final String remoteName;
    private final String localName;
    private final String description;
    private final JsonNode schema;
    private final boolean writes;

    public McpRemoteTool(McpClient client, String prefix, JsonNode def, ObjectMapper om) {
        this.client = client;
        this.om = om;
        this.remoteName = def.path("name").asText();
        this.localName = localName(prefix, remoteName);
        String d = def.path("description").asText("");
        this.description = d.isEmpty() ? "Remote MCP tool " + remoteName : d;
        JsonNode s = def.get("inputSchema");
        this.schema = s != null && s.isObject() ? s : om.createObjectNode().put("type", "object");
        this.writes = !def.path("annotations").path("readOnlyHint").asBoolean(false);
    }

    /** LLM providers only accept [a-zA-Z0-9_-]{1,64} as tool names. */
    static String localName(String prefix, String remote) {
        String raw = (prefix == null || prefix.isBlank() ? "" : prefix + "-") + remote;
        String clean = raw.replaceAll("[^a-zA-Z0-9_-]", "_");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    @Override public String name() { return localName; }
    @Override public String description() { return description; }
    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean writes() { return writes; }

    @Override
    public JsonNode execute(JsonNode input) {
        try {
            JsonNode r = client.callTool(remoteName, input == null ? om.createObjectNode() : input);
            StringBuilder text = new StringBuilder();
            for (JsonNode c : r.path("content"))
                if ("text".equals(c.path("type").asText())) text.append(c.path("text").asText());
            if (r.path("isError").asBoolean(false))
                throw new IllegalStateException(text.length() == 0 ? "Remote tool error" : text.toString());
            JsonNode structured = r.get("structuredContent");
            if (structured != null && !structured.isNull()) return structured;
            if (text.length() > 0) {
                try {
                    JsonNode parsed = om.readTree(text.toString());
                    if (parsed.isContainerNode()) return parsed;
                } catch (IOException ignored) { /* plain text result */ }
            }
            return om.createObjectNode().put("text", text.toString());
        } catch (IOException e) {
            throw new IllegalStateException("MCP call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP call interrupted", e);
        }
    }
}
