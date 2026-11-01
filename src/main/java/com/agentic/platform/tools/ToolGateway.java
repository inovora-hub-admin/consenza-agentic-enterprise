package com.agentic.platform.tools;

import com.agentic.platform.config.AgentSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Single choke point between agents and enterprise systems. Tools come from Spring beans and from MCP servers. */
@Component
public class ToolGateway {
    private final Map<String, ToolHandler> handlers = new ConcurrentHashMap<>();

    public ToolGateway(List<ToolHandler> list) {
        for (ToolHandler h : list) handlers.put(h.name(), h);
    }

    /** Adds a tool at runtime (used for tools discovered from MCP servers). Returns false if the name is taken. */
    public boolean register(ToolHandler handler) { return handlers.putIfAbsent(handler.name(), handler) == null; }

    public void unregister(String name) { handlers.remove(name); }

    public Optional<ToolHandler> get(String name) { return Optional.ofNullable(handlers.get(name)); }

    public Collection<ToolHandler> all() { return List.copyOf(handlers.values()); }

    public List<ToolHandler> forAgent(AgentSpec agent) {
        return agent.tools().stream().map(handlers::get).filter(Objects::nonNull).toList();
    }

    public JsonNode invoke(AgentSpec agent, String tool, JsonNode input) {
        if (!agent.tools().contains(tool)) throw new SecurityException("Tool not granted to agent: " + tool);
        ToolHandler h = handlers.get(tool);
        if (h == null) throw new IllegalArgumentException("Unknown tool: " + tool);
        return h.execute(input);
    }
}
