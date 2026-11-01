package com.agentic.platform.runtime;

import com.agentic.platform.config.AgentSpec;
import com.agentic.platform.config.ConfigService;
import com.agentic.platform.runtime.WorkflowResult.Step;
import com.agentic.platform.runtime.WorkflowResult.StepOutput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Engine-neutral helpers shared by the local and Temporal engines:
 * resolving the step plan, prompt construction and read-model (de)serialisation.
 */
@Service
public class WorkflowPlanner {
    public record ResolvedPlan(List<Step> plan, String definitionName, Integer definitionVersion) {}

    private final ConfigService config;
    private final ModelClient model;
    private final ObjectMapper om;

    public WorkflowPlanner(ConfigService config, ModelClient model, ObjectMapper om) {
        this.config = config; this.model = model; this.om = om;
    }

    /** Source of the steps, in priority order: published definition, explicit pipeline, LLM planner. */
    public ResolvedPlan resolve(String input, List<Step> steps, String definition) {
        String defName = null;
        Integer defVersion = null;
        List<Step> plan;
        if (definition != null && !definition.isBlank()) {
            var d = config.publishedWorkflow(definition).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not published: " + definition));
            plan = d.spec().steps().stream()
                    .map(s -> new Step(s.agent(), s.task().isBlank() ? input : s.task())).toList();
            defName = definition;
            defVersion = d.version();
        } else {
            plan = steps != null && !steps.isEmpty() ? steps : generatePlan(input);
        }
        for (Step s : plan) {
            if (config.publishedAgent(s.agent()).isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent not published: " + s.agent());
        }
        return new ResolvedPlan(plan, defName, defVersion);
    }

    public String buildPrompt(String request, String task, List<StepOutput> prior) {
        StringBuilder sb = new StringBuilder("Overall request: ").append(request)
                .append("\n\nYour task: ").append(task);
        if (!prior.isEmpty()) {
            sb.append("\n\nResults from earlier steps:");
            prior.forEach(o -> sb.append("\n- ").append(o.agent()).append(": ").append(o.output()));
        }
        return sb.toString();
    }

    public WorkflowResult toResult(WorkflowRun w) {
        return new WorkflowResult(w.id, w.status, planOf(w), w.stepIndex, outputsOf(w), w.finalOutput,
                w.pendingApprovalId, w.definitionName, w.definitionVersion);
    }

    public List<Step> planOf(WorkflowRun w) { return read(w.plan, new TypeReference<List<Step>>() {}); }
    public List<StepOutput> outputsOf(WorkflowRun w) { return read(w.outputs, new TypeReference<List<StepOutput>>() {}); }

    public String json(Object o) {
        try { return om.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private List<Step> generatePlan(String input) {
        List<AgentSpec> agents = config.publishedAgents();
        if (agents.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No published agents");
        String catalog = agents.stream().map(a -> a.name() + ": " + a.description()).collect(Collectors.joining("\n"));
        String system = "You are a planner for a bank's agent platform. Break the request into the fewest ordered "
                + "steps, each handled by exactly one agent from the list. Reply with ONLY a JSON array like "
                + "[{\"agent\":\"name\",\"task\":\"what that agent must do\"}].\nAgents:\n" + catalog;
        ArrayNode msgs = om.createArrayNode();
        msgs.addObject().put("role", "user").put("content", input);
        String text = AgentRunner.textOf(model.complete(null, system, msgs, List.of()).get("content"));
        int a = text.indexOf('['), b = text.lastIndexOf(']');
        if (a < 0 || b < a) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Planner returned no plan");
        List<Step> plan = read(text.substring(a, b + 1), new TypeReference<List<Step>>() {});
        List<String> known = agents.stream().map(AgentSpec::name).toList();
        List<Step> valid = plan.stream().filter(s -> known.contains(s.agent())).toList();
        if (valid.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Planner chose no known agent");
        return valid;
    }

    private <T> T read(String s, TypeReference<T> t) {
        try { return om.readValue(s, t); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
