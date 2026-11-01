package com.agentic.platform.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class McpServerHandlerTest {
    private final ObjectMapper om = new ObjectMapper();
    private final List<String> audit = new ArrayList<>();

    private McpServerHandler handler(boolean allowWrites) {
        return new McpServerHandler(() -> List.of(FakeTool.reader("sanctions-check"), FakeTool.writer("open-account")),
                allowWrites, om, (type, detail) -> audit.add(type + ":" + detail), "test");
    }

    private JsonNode call(McpServerHandler h, String json) throws Exception {
        Optional<JsonNode> r = h.handle(om.readTree(json));
        assertTrue(r.isPresent());
        return r.get();
    }

    @Test
    void initializeEchoesSupportedVersionAndFallsBackToLatest() throws Exception {
        JsonNode r = call(handler(false), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}");
        assertEquals("2025-03-26", r.path("result").path("protocolVersion").asText());
        JsonNode r2 = call(handler(false), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
        assertEquals(McpServerHandler.LATEST, r2.path("result").path("protocolVersion").asText());
        assertTrue(r.path("result").path("capabilities").has("tools"));
    }

    @Test
    void notificationsGetNoResponse() throws Exception {
        assertTrue(handler(false).handle(om.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).isEmpty());
    }

    @Test
    void writeToolsAreHiddenAndUncallableByDefault() throws Exception {
        McpServerHandler h = handler(false);
        JsonNode list = call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertEquals(1, list.path("result").path("tools").size());
        assertEquals("sanctions-check", list.path("result").path("tools").get(0).path("name").asText());
        assertTrue(list.path("result").path("tools").get(0).path("annotations").path("readOnlyHint").asBoolean());
        JsonNode denied = call(h, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"open-account\",\"arguments\":{}}}");
        assertEquals(-32602, denied.path("error").path("code").asInt());
        assertTrue(audit.contains("MCP_TOOL_DENIED:open-account"));
    }

    @Test
    void writeToolsAvailableWhenExplicitlyAllowed() throws Exception {
        McpServerHandler h = handler(true);
        assertEquals(2, call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").path("result").path("tools").size());
        JsonNode ok = call(h, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"open-account\",\"arguments\":{}}}");
        assertFalse(ok.path("result").path("isError").asBoolean());
        assertTrue(ok.path("result").path("structuredContent").path("opened").asBoolean());
    }

    @Test
    void callReturnsTextAndStructuredContentAndIsAudited() throws Exception {
        JsonNode r = call(handler(false), "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"sanctions-check\",\"arguments\":{\"name\":\"Jane\"}}}");
        assertEquals(7, r.path("id").asInt());
        assertFalse(r.path("result").path("isError").asBoolean());
        assertEquals("Jane", r.path("result").path("structuredContent").path("who").asText());
        assertEquals("text", r.path("result").path("content").get(0).path("type").asText());
        assertEquals(1, audit.stream().filter(a -> a.startsWith("MCP_TOOL_CALL:")).count());
    }

    @Test
    void missingRequiredArgumentIsAToolError() throws Exception {
        JsonNode r = call(handler(false), "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"sanctions-check\",\"arguments\":{}}}");
        assertTrue(r.path("result").path("isError").asBoolean());
        assertTrue(r.path("result").path("content").get(0).path("text").asText().contains("name"));
    }

    @Test
    void unknownMethodAndBadRequests() throws Exception {
        assertEquals(-32601, call(handler(false), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"nope\"}").path("error").path("code").asInt());
        assertEquals(-32600, handler(false).handle(om.readTree("[1,2]")).get().path("error").path("code").asInt());
        assertEquals(0, call(handler(false), "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}").path("result").size());
    }
}
