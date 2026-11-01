package com.agentic.platform.runtime;

import com.agentic.platform.config.AgentSpec;
import com.agentic.platform.config.ConfigService;
import com.agentic.platform.runtime.WorkflowPlanner.ResolvedPlan;
import com.agentic.platform.runtime.WorkflowResult.Step;
import com.agentic.platform.runtime.WorkflowResult.StepOutput;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * In-process engine (platform.workflow.engine=local, the default):
 * plan -> run agents in sequence, passing earlier results forward.
 * A step that needs human approval pauses the whole run; the approval decision resumes it.
 */
@Service
@ConditionalOnProperty(name = "platform.workflow.engine", havingValue = "local", matchIfMissing = true)
public class WorkflowService implements WorkflowEngine {
    private final ConfigService config;
    private final AgentRunner runner;
    private final WorkflowRepository repo;
    private final WorkflowPlanner planner;

    public WorkflowService(ConfigService config, AgentRunner runner, WorkflowRepository repo, WorkflowPlanner planner) {
        this.config = config; this.runner = runner; this.repo = repo; this.planner = planner;
    }

    @Override
    public WorkflowResult start(String input, List<Step> steps, String definition) {
        ResolvedPlan rp = planner.resolve(input, steps, definition);
        WorkflowRun w = new WorkflowRun();
        w.input = input; w.plan = planner.json(rp.plan()); w.outputs = "[]"; w.status = "RUNNING";
        w.definitionName = rp.definitionName(); w.definitionVersion = rp.definitionVersion();
        repo.save(w);
        return advance(w);
    }

    @Override
    public WorkflowResult get(Long id) {
        return planner.toResult(repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @Override
    public List<WorkflowResult> list() { return repo.findTop50ByOrderByIdDesc().stream().map(planner::toResult).toList(); }

    @Override
    public Optional<WorkflowResult> onApprovalResult(Long approvalId, RunResult r) {
        Optional<WorkflowRun> found = repo.findByPendingApprovalId(approvalId);
        if (found.isEmpty()) return Optional.empty();
        WorkflowRun w = found.get();
        if ("PENDING_APPROVAL".equals(r.status())) {          // the same step needs another approval
            w.pendingApprovalId = r.approvalId();
            repo.save(w);
            return Optional.of(planner.toResult(w));
        }
        Step step = planner.planOf(w).get(w.stepIndex);
        List<StepOutput> outs = planner.outputsOf(w);
        outs.add(new StepOutput(step.agent(), step.task(), r.runId(), r.status(), r.output()));
        w.outputs = planner.json(outs);
        w.pendingApprovalId = null;
        if ("FAILED".equals(r.status())) return Optional.of(fail(w, r.output()));
        w.stepIndex++;
        w.status = "RUNNING";
        repo.save(w);
        return Optional.of(advance(w));
    }

    private WorkflowResult advance(WorkflowRun w) {
        List<Step> plan = planner.planOf(w);
        List<StepOutput> outs = planner.outputsOf(w);
        while (w.stepIndex < plan.size()) {
            Step s = plan.get(w.stepIndex);
            AgentSpec agent = config.publishedAgent(s.agent()).orElse(null);
            if (agent == null) return fail(w, "Agent no longer published: " + s.agent());
            RunResult r = runner.start(agent, planner.buildPrompt(w.input, s.task(), outs));
            if ("PENDING_APPROVAL".equals(r.status())) {
                w.status = "PENDING_APPROVAL";
                w.pendingApprovalId = r.approvalId();
                repo.save(w);
                return planner.toResult(w);
            }
            outs.add(new StepOutput(s.agent(), s.task(), r.runId(), r.status(), r.output()));
            w.outputs = planner.json(outs);
            if ("FAILED".equals(r.status())) return fail(w, r.output());
            w.stepIndex++;
            repo.save(w);
        }
        w.status = "COMPLETED";
        w.finalOutput = outs.stream().map(o -> o.agent() + ": " + o.output()).collect(Collectors.joining("\n\n"));
        repo.save(w);
        return planner.toResult(w);
    }

    private WorkflowResult fail(WorkflowRun w, String reason) {
        w.status = "FAILED";
        w.finalOutput = reason;
        repo.save(w);
        return planner.toResult(w);
    }
}
