package com.agentic.platform.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal MCP client over Streamable HTTP. Accepts JSON or single-shot SSE responses and keeps the
 * Mcp-Session-Id if the server issues one. One instance per remote server; calls are synchronized.
 */
public final class McpClient {
    private final URI uri;
    private final String token;
    private final Duration timeout;
    private final ObjectMapper om;
    private final HttpClient http;
    private final AtomicLong ids = new AtomicLong(1);
    private String sessionId;
    private String protocolVersion = McpServerHandler.LATEST;

    public McpClient(String url, String token, Duration timeout, ObjectMapper om) {
        this.uri = URI.create(url);
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) throw new IllegalArgumentException("MCP url must be http(s): " + url);
        this.token = token;
        this.timeout = timeout;
        this.om = om;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public synchronized void initialize() throws IOException, InterruptedException {
        ObjectNode params = om.createObjectNode();
        params.put("protocolVersion", McpServerHandler.LATEST);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "consenza-agentic-platform").put("version", "0.1.0");
        JsonNode result = rpc("initialize", params);
        String v = result.path("protocolVersion").asText("");
        if (!v.isEmpty()) protocolVersion = v;
        ObjectNode note = om.createObjectNode();
        note.put("jsonrpc", "2.0").put("method", "notifications/initialized");
        post(note.toString());
    }

    public synchronized List<JsonNode> listTools() throws IOException, InterruptedException {
        List<JsonNode> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            ObjectNode params = om.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode r = rpc("tools/list", params);
            for (JsonNode t : r.path("tools")) out.add(t);
            cursor = r.path("nextCursor").asText(null);
            if (cursor == null || cursor.isEmpty()) break;
        }
        return out;
    }

    public synchronized JsonNode callTool(String name, JsonNode arguments) throws IOException, InterruptedException {
        ObjectNode params = om.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        return rpc("tools/call", params);
    }

    private JsonNode rpc(String method, JsonNode params) throws IOException, InterruptedException {
        long id = ids.getAndIncrement();
        ObjectNode req = om.createObjectNode();
        req.put("jsonrpc", "2.0").put("id", id).put("method", method);
        req.set("params", params);
        HttpResponse<String> resp = post(req.toString());
        if ("initialize".equals(method)) resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
        JsonNode msg = findResponse(resp, id);
        if (msg == null) throw new McpException(-32603, "No response to " + method);
        if (msg.has("error")) throw new McpException(msg.path("error").path("code").asInt(-32603),
                msg.path("error").path("message").asText("MCP error"));
        return msg.path("result");
    }

    private HttpResponse<String> post(String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", protocolVersion)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token);
        if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new McpException(-32000, "MCP server returned HTTP " + resp.statusCode());
        return resp;
    }

    private JsonNode findResponse(HttpResponse<String> resp, long id) throws IOException {
        String body = resp.body();
        if (body == null || body.isBlank()) return null;
        String type = resp.headers().firstValue("Content-Type").orElse("");
        if (!type.contains("text/event-stream")) return om.readTree(body);
        StringBuilder data = new StringBuilder();
        for (String line : (body + "\n\n").split("\\R", -1)) {
            if (line.startsWith("data:")) { data.append(line.substring(5).stripLeading()); continue; }
            if (line.isEmpty() && data.length() > 0) {
                try {
                    JsonNode n = om.readTree(data.toString());
                    if (n.has("id") && n.path("id").asLong(-1) == id) return n;
                } catch (IOException ignored) { /* skip non-JSON events */ }
                data.setLength(0);
            }
        }
        return null;
    }
}
