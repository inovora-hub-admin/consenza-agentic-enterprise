package com.agentic.platform.mcp;

import com.agentic.platform.tools.ToolGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP round trip on a loopback port: McpClient -> McpServerHandler, bearer token enforced. */
class McpClientServerRoundTripTest {
    private final ObjectMapper om = new ObjectMapper();

    private HttpServer start(McpServerHandler handler, String token) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/mcp", ex -> {
            byte[] out = new byte[0];
            int status;
            if (!("Bearer " + token).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                status = 401;
                ex.sendResponseHeaders(status, -1);
                ex.close();
                return;
            }
            JsonNode req = om.readTree(ex.getRequestBody().readAllBytes());
            Optional<JsonNode> resp = handler.handle(req);
            if (resp.isEmpty()) { ex.sendResponseHeaders(202, -1); ex.close(); return; }
            out = resp.get().toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        s.start();
        return s;
    }

    private McpServerHandler handler() {
        return new McpServerHandler(() -> List.of(FakeTool.reader("sanctions-check"), FakeTool.writer("open-account")),
                true, om, (t, d) -> { }, "test");
    }

    @Test
    void clientDiscoversAndCallsToolsAndGatewayAppliesWriteFlag() throws Exception {
        HttpServer s = start(handler(), "secret");
        try {
            String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/mcp";
            McpClient client = new McpClient(url, "secret", Duration.ofSeconds(5), om);
            client.initialize();
            List<JsonNode> defs = client.listTools();
            assertEquals(2, defs.size());

            ToolGateway gateway = new ToolGateway(List.of());
            for (JsonNode d : defs) assertTrue(gateway.register(new McpRemoteTool(client, "cbs", d, om)));
            assertFalse(gateway.register(new McpRemoteTool(client, "cbs", defs.get(0), om)));   // duplicate name

            McpRemoteTool read = (McpRemoteTool) gateway.get("cbs-sanctions-check").orElseThrow();
            McpRemoteTool write = (McpRemoteTool) gateway.get("cbs-open-account").orElseThrow();
            assertFalse(read.writes());
            assertTrue(write.writes());
            JsonNode out = read.execute(om.createObjectNode().put("name", "Jane"));
            assertEquals("Jane", out.path("who").asText());
            assertTrue(write.execute(om.createObjectNode()).path("opened").asBoolean());

            gateway.unregister("cbs-open-account");
            assertTrue(gateway.get("cbs-open-account").isEmpty());
        } finally {
            s.stop(0);
        }
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        HttpServer s = start(handler(), "secret");
        try {
            McpClient client = new McpClient("http://127.0.0.1:" + s.getAddress().getPort() + "/mcp", "wrong", Duration.ofSeconds(5), om);
            assertThrows(McpException.class, client::initialize);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void remoteToolErrorsSurfaceAsExceptions() throws Exception {
        McpServerHandler failing = new McpServerHandler(() -> List.of(
                new FakeTool("boom", false, "{\"type\":\"object\"}", in -> { throw new IllegalStateException("CBS down"); })),
                false, om, (t, d) -> { }, "test");
        HttpServer s = start(failing, "t");
        try {
            McpClient client = new McpClient("http://127.0.0.1:" + s.getAddress().getPort() + "/mcp", "t", Duration.ofSeconds(5), om);
            client.initialize();
            McpRemoteTool tool = new McpRemoteTool(client, "", client.listTools().get(0), om);
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> tool.execute(om.createObjectNode()));
            assertTrue(e.getMessage().contains("CBS down"));
        } finally {
            s.stop(0);
        }
    }

    @Test
    void localNamesAreSafeForModelProviders() {
        assertEquals("cbs-get_balance", McpRemoteTool.localName("cbs", "get.balance"));
        assertEquals(64, McpRemoteTool.localName("", "x".repeat(100)).length());
    }
}
