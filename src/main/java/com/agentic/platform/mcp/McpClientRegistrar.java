package com.agentic.platform.mcp;

import com.agentic.platform.tools.ToolGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Connects to the configured remote MCP servers and registers their tools in the tool gateway. */
@Component
public class McpClientRegistrar implements ApplicationRunner {
    public record Status(String name, String url, boolean connected, int tools, String error) {}

    private static final Logger log = LoggerFactory.getLogger(McpClientRegistrar.class);

    private final McpProperties props;
    private final ToolGateway gateway;
    private final ObjectMapper om;
    private final List<String> registered = new ArrayList<>();
    private List<Status> statuses = List.of();

    public McpClientRegistrar(McpProperties props, ToolGateway gateway, ObjectMapper om) {
        this.props = props;
        this.gateway = gateway;
        this.om = om;
    }

    @Override
    public void run(ApplicationArguments args) { refresh(); }

    public synchronized List<Status> statuses() { return statuses; }

    /** Drops previously discovered tools and reconnects to every configured server. A failing server never stops startup. */
    public synchronized List<Status> refresh() {
        registered.forEach(gateway::unregister);
        registered.clear();
        List<Status> out = new ArrayList<>();
        for (McpProperties.Remote r : props.getServers()) {
            try {
                McpClient client = new McpClient(r.getUrl(), r.getToken(), Duration.ofSeconds(30), om);
                client.initialize();
                int added = 0;
                for (JsonNode def : client.listTools()) {
                    McpRemoteTool tool = new McpRemoteTool(client, r.getPrefix(), def, om);
                    if (gateway.register(tool)) { registered.add(tool.name()); added++; }
                    else log.warn("MCP tool '{}' from '{}' skipped: name already in use", tool.name(), r.getName());
                }
                out.add(new Status(r.getName(), r.getUrl(), true, added, null));
                log.info("MCP server '{}' connected, {} tools registered", r.getName(), added);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                out.add(new Status(r.getName(), r.getUrl(), false, 0, "interrupted"));
            } catch (Exception e) {
                out.add(new Status(r.getName(), r.getUrl(), false, 0, e.getMessage()));
                log.warn("MCP server '{}' unavailable: {}", r.getName(), e.getMessage());
            }
        }
        statuses = List.copyOf(out);
        return statuses;
    }
}
