package com.agentic.platform.tools;

import com.agentic.platform.config.AgentSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** Single choke point between agents and enterprise systems. */
@Component
public class ToolGateway {
    private final Map<String, ToolHandler> handlers;

    public ToolGateway(List<ToolHandler> list) {
        this.handlers = list.stream().collect(Collectors.toMap(ToolHandler::name, h -> h));
    }

    public Optional<ToolHandler> get(String name) { return Optional.ofNullable(handlers.get(name)); }

    public Collection<ToolHandler> all() { return handlers.values(); }

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
