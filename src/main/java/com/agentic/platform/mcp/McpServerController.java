package com.agentic.platform.mcp;

import com.agentic.platform.governance.AuditService;
import com.agentic.platform.tools.ToolGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;

/**
 * MCP endpoint for external agents and frameworks. Disabled unless platform.mcp.server.enabled=true.
 * Fails closed: requires a bearer token (platform.mcp.server.token) and rejects browser Origins that
 * are not allow-listed (DNS-rebinding protection). Every call is written to the audit trail.
 */
@RestController
@ConditionalOnProperty(name = "platform.mcp.server.enabled", havingValue = "true")
public class McpServerController {
    private final McpProperties props;
    private final ToolGateway gateway;
    private final AuditService auditService;
    private final ObjectMapper om;

    public McpServerController(McpProperties props, ToolGateway gateway, AuditService auditService, ObjectMapper om) {
        this.props = props;
        this.gateway = gateway;
        this.auditService = auditService;
        this.om = om;
    }

    @PostMapping("/mcp")
    public ResponseEntity<String> handle(@RequestBody String body,
                                         @RequestHeader(value = "Authorization", required = false) String auth,
                                         @RequestHeader(value = "Origin", required = false) String origin) {
        McpProperties.Server cfg = props.getServer();
        if (cfg.getToken() == null || cfg.getToken().isBlank())
            return ResponseEntity.status(503).body("MCP server token is not configured");
        if (!tokenMatches(auth, cfg.getToken()))
            return ResponseEntity.status(401).header("WWW-Authenticate", "Bearer").body("Unauthorized");
        if (origin != null && !cfg.getAllowedOrigins().contains(origin))
            return ResponseEntity.status(403).body("Origin not allowed");

        String runId = "mcp-" + UUID.randomUUID();
        McpServerHandler handler = new McpServerHandler(gateway::all, cfg.isAllowWrites(), om,
                (type, detail) -> auditService.log(runId, "mcp-client", type, detail), "consenza-agentic-platform");
        JsonNode req;
        try {
            req = om.readTree(body);
        } catch (Exception e) {
            return json(400, handler.error(null, -32700, "Parse error").toString());
        }
        Optional<JsonNode> resp = handler.handle(req);
        if (resp.isEmpty()) return ResponseEntity.accepted().build();
        return json(200, resp.get().toString());
    }

    private ResponseEntity<String> json(int status, String body) {
        return ResponseEntity.status(status).header("Content-Type", "application/json").body(body);
    }

    private static boolean tokenMatches(String header, String expected) {
        if (header == null || !header.startsWith("Bearer ")) return false;
        byte[] given = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, expected.getBytes(StandardCharsets.UTF_8));
    }
}
