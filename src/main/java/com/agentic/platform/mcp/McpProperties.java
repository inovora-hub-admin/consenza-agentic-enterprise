package com.agentic.platform.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * platform.mcp.server.*  - expose this platform's tools as an MCP server at POST /mcp (off by default)
 * platform.mcp.servers[] - remote MCP servers whose tools are added to the tool gateway
 */
@Component
@ConfigurationProperties(prefix = "platform.mcp")
public class McpProperties {
    private Server server = new Server();
    private List<Remote> servers = new ArrayList<>();

    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
    public List<Remote> getServers() { return servers; }
    public void setServers(List<Remote> servers) { this.servers = servers; }

    public static class Server {
        private boolean enabled = false;
        private String token = "";
        private boolean allowWrites = false;
        private List<String> allowedOrigins = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public boolean isAllowWrites() { return allowWrites; }
        public void setAllowWrites(boolean allowWrites) { this.allowWrites = allowWrites; }
        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }

    public static class Remote {
        private String name;
        private String url;
        private String token = "";
        private String prefix = "";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
    }
}
