package com.agentic.platform.mcp;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** For the portal's Tools & Integrations page: connection status of remote MCP servers, and reconnect. */
@RestController
@RequestMapping("/api/mcp/servers")
public class McpAdminController {
    private final McpClientRegistrar registrar;

    public McpAdminController(McpClientRegistrar registrar) { this.registrar = registrar; }

    @GetMapping
    public List<McpClientRegistrar.Status> status() { return registrar.statuses(); }

    @PostMapping("/refresh")
    public List<McpClientRegistrar.Status> refresh() { return registrar.refresh(); }
}
