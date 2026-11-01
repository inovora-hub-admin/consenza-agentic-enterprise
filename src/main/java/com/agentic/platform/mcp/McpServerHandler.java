package com.agentic.platform.mcp;

import com.agentic.platform.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.function.Supplier;

/**
 * Minimal Model Context Protocol server (JSON-RPC 2.0 over Streamable HTTP, JSON responses only).
 * Exposes the platform's tools so any MCP-capable agent or framework can reuse the same CBS, database
 * and Kafka adapters. Stateless: no sessions, no server-initiated messages.
 * Tools that change state are hidden and uncallable unless allowWrites is true.
 */
public final class McpServerHandler {
    public static final String LATEST = "2025-06-18";
    static final List<String> SUPPORTED = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    public interface Audit { void log(String type, String detail); }

    private final Supplier<Collection<ToolHandler>> tools;
    private final boolean allowWrites;
    private final ObjectMapper om;
    private final Audit audit;
    private final String serverName;

    public McpServerHandler(Supplier<Collection<ToolHandler>> tools, boolean allowWrites, ObjectMapper om,
                            Audit audit, String serverName) {
        this.tools = tools;
        this.allowWrites = allowWrites;
        this.om = om;
        this.audit = audit;
        this.serverName = serverName;
    }

    /** Returns the JSON-RPC response, or empty for notifications and client responses (HTTP 202). */
    public Optional<JsonNode> handle(JsonNode req) {
        if (req == null || !req.isObject()) return Optional.<JsonNode>of(error(null, -32600, "Invalid request"));
        JsonNode id = req.get("id");
        boolean hasId = id != null && !id.isNull();
        String method = req.path("method").asText("");
        if (method.isEmpty()) {
            if (req.has("result") || req.has("error")) return Optional.empty();   // a response from the client
            return Optional.<JsonNode>of(error(id, -32600, "Missing method"));
        }
        if (!hasId) return Optional.empty();                                       // notification
        try {
            JsonNode params = req.path("params");
            ObjectNode out = switch (method) {
                case "initialize" -> ok(id, initialize(params));
                case "ping" -> ok(id, om.createObjectNode());
                case "tools/list" -> ok(id, listTools());
                case "tools/call" -> callTool(id, params);
                default -> error(id, -32601, "Method not found: " + method);
            };
            return Optional.<JsonNode>of(out);
        } catch (RuntimeException e) {
            return Optional.<JsonNode>of(error(id, -32603, "Internal error"));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        ObjectNode r = om.createObjectNode();
        r.put("protocolVersion", SUPPORTED.contains(requested) ? requested : LATEST);
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        r.putObject("serverInfo").put("name", serverName).put("version", "0.1.0");
        return r;
    }

    private List<ToolHandler> visible() {
        return tools.get().stream()
                .filter(t -> allowWrites || !t.writes())
                .sorted(Comparator.comparing(ToolHandler::name))
                .toList();
    }

    private ObjectNode listTools() {
        ObjectNode r = om.createObjectNode();
        ArrayNode arr = r.putArray("tools");
        for (ToolHandler t : visible()) {
            ObjectNode n = arr.addObject();
            n.put("name", t.name());
            n.put("description", t.description());
            n.set("inputSchema", t.inputSchema());
            n.putObject("annotations").put("readOnlyHint", !t.writes());
        }
        return r;
    }

    private ObjectNode callTool(JsonNode id, JsonNode params) {
        String name = params.path("name").asText("");
        ToolHandler tool = visible().stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            audit.log("MCP_TOOL_DENIED", name);
            return error(id, -32602, "Unknown tool: " + name);
        }
        JsonNode args = params.get("arguments");
        if (args == null || args.isNull()) args = om.createObjectNode();
        if (!args.isObject()) return ok(id, toolResult("arguments must be an object", null, true));
        for (JsonNode req : tool.inputSchema().path("required")) {
            JsonNode v = args.get(req.asText());
            if (v == null || v.isNull()) return ok(id, toolResult("Missing required argument: " + req.asText(), null, true));
        }
        try {
            JsonNode out = tool.execute(args);
            audit.log("MCP_TOOL_CALL", name + " " + args + " -> " + out);
            return ok(id, toolResult(out.toString(), out, false));
        } catch (RuntimeException e) {
            audit.log("MCP_TOOL_ERROR", name + ": " + e.getMessage());
            return ok(id, toolResult("Tool error: " + e.getMessage(), null, true));
        }
    }

    private ObjectNode toolResult(String text, JsonNode structured, boolean isError) {
        ObjectNode r = om.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", text);
        if (structured != null && structured.isObject()) r.set("structuredContent", structured);
        r.put("isError", isError);
        return r;
    }

    private ObjectNode ok(JsonNode id, JsonNode result) {
        ObjectNode n = om.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id);
        n.set("result", result);
        return n;
    }

    /** Public so the HTTP layer can build a parse-error response. */
    public ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode n = om.createObjectNode();
        n.put("jsonrpc", "2.0");
        if (id == null) n.putNull("id"); else n.set("id", id);
        n.putObject("error").put("code", code).put("message", message);
        return n;
    }
}
