package com.agentic.platform.runtime;

import com.agentic.platform.config.AgentSpec;
import com.agentic.platform.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/** MVP orchestrator: pick the right published agent, then run it. Extend with multi-step plans / agent-to-agent calls. */
@Service
public class Orchestrator {
    private final ConfigService config;
    private final AgentRunner runner;
    private final ModelClient model;
    private final ObjectMapper om;

    public Orchestrator(ConfigService config, AgentRunner runner, ModelClient model, ObjectMapper om) {
        this.config = config; this.runner = runner; this.model = model; this.om = om;
    }

    public RunResult run(String agentName, String input) {
        AgentSpec agent = agentName != null && !agentName.isBlank()
                ? config.publishedAgent(agentName).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not published: " + agentName))
                : route(input);
        return runner.start(agent, input);
    }

    private AgentSpec route(String input) {
        List<AgentSpec> agents = config.publishedAgents();
        if (agents.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No published agents");
        if (agents.size() == 1) return agents.get(0);
        String catalog = agents.stream().map(a -> a.name() + ": " + a.description()).collect(Collectors.joining("\n"));
        ArrayNode msgs = om.createArrayNode();
        msgs.addObject().put("role", "user").put("content", input);
        String pick = AgentRunner.textOf(model.complete(null,
                "Pick the single best agent for the request. Reply with only the agent name.\n" + catalog,
                msgs, List.of()).get("content")).trim();
        return agents.stream().filter(a -> a.name().equals(pick)).findFirst().orElse(agents.get(0));
    }
}
