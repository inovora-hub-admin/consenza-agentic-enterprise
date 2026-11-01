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
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Multi-agent orchestration: plan -> run agents in sequence, passing earlier results forward.
 * A step that needs human approval pauses the whole run; the approval decision resumes it.
 */
@Service
public class WorkflowService {
    private final ConfigService config;
    private final AgentRunner runner;
    private final ModelClient model;
    private final WorkflowRepository repo;
    private final ObjectMapper om;

    public WorkflowService(ConfigService config, AgentRunner runner, ModelClient model,
                           WorkflowRepository repo, ObjectMapper om) {
        this.config = config; this.runner = runner; this.model = model; this.repo = repo; this.om = om;
    }

    /**
     * Starts a run (an instance). Source of the steps, in priority order:
     * a published workflow definition, an explicit fixed pipeline, or the planner.
     */
    public WorkflowResult start(String input, List<Step> steps, String definition) {
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
        WorkflowRun w = new WorkflowRun();
        w.input = input; w.plan = json(plan); w.outputs = "[]"; w.status = "RUNNING";
        w.definitionName = defName; w.definitionVersion = defVersion;
        repo.save(w);
        return advance(w);
    }

    public WorkflowResult get(Long id) {
        return toResult(repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    public List<WorkflowResult> list() { return repo.findTop50ByOrderByIdDesc().stream().map(this::toResult).toList(); }

    /** Called after a reviewer decides. Empty if the approval did not belong to a run. */
    public Optional<WorkflowResult> onApprovalResult(Long approvalId, RunResult r) {
        Optional<WorkflowRun> found = repo.findByPendingApprovalId(approvalId);
        if (found.isEmpty()) return Optional.empty();
        WorkflowRun w = found.get();
        if ("PENDING_APPROVAL".equals(r.status())) {          // the same step needs another approval
            w.pendingApprovalId = r.approvalId();
            repo.save(w);
            return Optional.of(toResult(w));
        }
        Step step = planOf(w).get(w.stepIndex);
        List<StepOutput> outs = outputsOf(w);
        outs.add(new StepOutput(step.agent(), step.task(), r.runId(), r.status(), r.output()));
        w.outputs = json(outs);
        w.pendingApprovalId = null;
        if ("FAILED".equals(r.status())) return Optional.of(fail(w, r.output()));
        w.stepIndex++;
        w.status = "RUNNING";
        repo.save(w);
        return Optional.of(advance(w));
    }

    private WorkflowResult advance(WorkflowRun w) {
        List<Step> plan = planOf(w);
        List<StepOutput> outs = outputsOf(w);
        while (w.stepIndex < plan.size()) {
            Step s = plan.get(w.stepIndex);
            AgentSpec agent = config.publishedAgent(s.agent()).orElse(null);
            if (agent == null) return fail(w, "Agent no longer published: " + s.agent());
            RunResult r = runner.start(agent, buildPrompt(w.input, s.task(), outs));
            if ("PENDING_APPROVAL".equals(r.status())) {
                w.status = "PENDING_APPROVAL";
                w.pendingApprovalId = r.approvalId();
                repo.save(w);
                return toResult(w);
            }
            outs.add(new StepOutput(s.agent(), s.task(), r.runId(), r.status(), r.output()));
            w.outputs = json(outs);
            if ("FAILED".equals(r.status())) return fail(w, r.output());
            w.stepIndex++;
            repo.save(w);
        }
        w.status = "COMPLETED";
        w.finalOutput = outs.stream().map(o -> o.agent() + ": " + o.output()).collect(Collectors.joining("\n\n"));
        repo.save(w);
        return toResult(w);
    }

    private WorkflowResult fail(WorkflowRun w, String reason) {
        w.status = "FAILED";
        w.finalOutput = reason;
        repo.save(w);
        return toResult(w);
    }

    private String buildPrompt(String request, String task, List<StepOutput> prior) {
        StringBuilder sb = new StringBuilder("Overall request: ").append(request)
                .append("\n\nYour task: ").append(task);
        if (!prior.isEmpty()) {
            sb.append("\n\nResults from earlier steps:");
            prior.forEach(o -> sb.append("\n- ").append(o.agent()).append(": ").append(o.output()));
        }
        return sb.toString();
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

    private WorkflowResult toResult(WorkflowRun w) {
        return new WorkflowResult(w.id, w.status, planOf(w), w.stepIndex, outputsOf(w), w.finalOutput,
                w.pendingApprovalId, w.definitionName, w.definitionVersion);
    }

    private List<Step> planOf(WorkflowRun w) { return read(w.plan, new TypeReference<List<Step>>() {}); }
    private List<StepOutput> outputsOf(WorkflowRun w) { return read(w.outputs, new TypeReference<List<StepOutput>>() {}); }

    private String json(Object o) {
        try { return om.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private <T> T read(String s, TypeReference<T> t) {
        try { return om.readValue(s, t); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
