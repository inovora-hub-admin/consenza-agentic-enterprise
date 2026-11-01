package com.agentic.platform.temporal;

import com.agentic.platform.config.AgentSpec;
import com.agentic.platform.config.ConfigService;
import com.agentic.platform.runtime.AgentRunner;
import com.agentic.platform.runtime.RunResult;
import com.agentic.platform.runtime.WorkflowPlanner;
import com.agentic.platform.runtime.WorkflowRepository;
import com.agentic.platform.runtime.WorkflowResult.StepOutput;
import com.agentic.platform.runtime.WorkflowRun;
import io.temporal.failure.ApplicationFailure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/** Spring-managed activity implementation; registered on the embedded worker by TemporalConfig. */
@Component
@ConditionalOnProperty(name = "platform.workflow.engine", havingValue = "temporal")
public class AgentProcessActivitiesImpl implements AgentProcessActivities {
    private final ConfigService config;
    private final AgentRunner runner;
    private final WorkflowRepository repo;
    private final WorkflowPlanner planner;

    public AgentProcessActivitiesImpl(ConfigService config, AgentRunner runner, WorkflowRepository repo, WorkflowPlanner planner) {
        this.config = config; this.runner = runner; this.repo = repo; this.planner = planner;
    }

    @Override
    public RunResult runStep(Long runId, String agentName, String task, List<StepOutput> prior) {
        WorkflowRun w = load(runId);
        AgentSpec agent = config.publishedAgent(agentName).orElseThrow(() ->
                ApplicationFailure.newNonRetryableFailure("Agent no longer published: " + agentName, "AGENT_UNPUBLISHED"));
        // AgentRunner never throws: FAILED is returned as a RunResult, so retries only cover infra errors.
        return runner.start(agent, planner.buildPrompt(w.input, task, prior));
    }

    @Override @Transactional
    public void markPending(Long runId, Long approvalId) {
        WorkflowRun w = load(runId);
        w.status = "PENDING_APPROVAL";
        w.pendingApprovalId = approvalId;
        repo.save(w);
    }

    @Override @Transactional
    public void recordStep(Long runId, int stepIndex, StepOutput output) {
        WorkflowRun w = load(runId);
        if (w.stepIndex > stepIndex) return;                 // already recorded (activity retry)
        List<StepOutput> outs = planner.outputsOf(w);
        outs.add(output);
        w.outputs = planner.json(outs);
        w.stepIndex = stepIndex + 1;
        w.pendingApprovalId = null;
        w.status = "RUNNING";
        repo.save(w);
    }

    @Override @Transactional
    public void markCompleted(Long runId, List<StepOutput> outputs) {
        WorkflowRun w = load(runId);
        w.status = "COMPLETED";
        w.pendingApprovalId = null;
        w.finalOutput = outputs.stream().map(o -> o.agent() + ": " + o.output()).collect(Collectors.joining("\n\n"));
        repo.save(w);
    }

    @Override @Transactional
    public void markFailed(Long runId, String reason) {
        WorkflowRun w = load(runId);
        w.status = "FAILED";
        w.pendingApprovalId = null;
        w.finalOutput = reason;
        repo.save(w);
    }

    private WorkflowRun load(Long runId) {
        return repo.findById(runId).orElseThrow(() ->
                ApplicationFailure.newNonRetryableFailure("WorkflowRun not found: " + runId, "RUN_NOT_FOUND"));
    }
}
