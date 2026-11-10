package com.agentic.platform.temporal;

import com.agentic.platform.config.AgentSpec;
import com.agentic.platform.config.ConfigService;
import com.agentic.platform.runtime.*;
import com.agentic.platform.runtime.WorkflowResult.StepOutput;
import io.temporal.failure.ApplicationFailure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/** Spring-managed activity implementation; registered on the embedded worker by TemporalConfig. */
@Component@ConditionalOnProperty(name = "platform.workflow.engine", havingValue = "temporal")
public class AgentProcessActivitiesImpl implements AgentProcessActivities {
    private static final int MAX_TEXT_LEN =16000;
    private static final String TRUNCATED_SUFFIX = "\n...[TRUNCATED]";

    private final ConfigService config;
    private final AgentRunner runner;
    private final WorkflowRepository repo;
    private final WorkflowPlanner planner;

    public AgentProcessActivitiesImpl(ConfigService config, AgentRunner runner, WorkflowRepository repo, WorkflowPlanner planner) {
        this.config = config;
        this.runner = runner;
        this.repo = repo;
        this.planner = planner;
    }

    @Override @Transactional
    public RunResult runStep(Long runId, String agentName, String task, List<StepOutput> prior) {
        WorkflowRun w = load(runId);
        rejectIfTerminal(w);
        AgentSpec agent = config.publishedAgent(agentName).orElseThrow(() ->
                ApplicationFailure.newNonRetryableFailure("Agent no longer published: " + agentName, "AGENT_UNPUBLISHED"));
        return runner.start(agent, planner.buildPrompt(w.input, task, prior));
    }

    @Override @Transactional public void markPending(Long runId, Long approvalId) {
        WorkflowRun w = load(runId);
        rejectIfTerminal(w);
        w.status = WorkflowStatuses.PENDING_APPROVAL.name();
        w.pendingApprovalId = approvalId;
        repo.save(w);
    }

    @Override @Transactional public void recordStep(Long runId, int stepIndex, StepOutput output) {
        WorkflowRun w = load(runId);
        rejectIfTerminal(w);

        // idempotent replay/out-of-order guard if (w.stepIndex >= stepIndex +1) return;

        List<StepOutput> outs = planner.outputsOf(w);
        outs.add(output);
        w.outputs = bound(planner.json(outs));
        w.stepIndex = stepIndex +1;
        w.pendingApprovalId = null;
        w.status = WorkflowStatuses.RUNNING.name();
        repo.save(w);
    }

    @Override @Transactional public void markCompleted(Long runId, List<StepOutput> outputs) {
        WorkflowRun w = load(runId);
        rejectIfTerminal(w);
        w.status = WorkflowStatuses.COMPLETED.name();
        w.pendingApprovalId = null;

        List<StepOutput> safeOutputs = (outputs == null ? List.<StepOutput>of() : outputs);
        w.outputs = bound(planner.json(safeOutputs));

        String joined = safeOutputs.stream()
                .map(o -> o.agent() + ": " + o.output())
                .collect(Collectors.joining("\n\n"));
        w.finalOutput = bound(joined);
        repo.save(w);
    }

    @Override @Transactional public void markFailed(Long runId, String status, String reason) {
        WorkflowRun w = load(runId);
        rejectIfTerminal(w);

        WorkflowStatuses s = parseFailureStatus(status);
        w.status = s.name();
        w.pendingApprovalId = null;
        w.finalOutput = bound(reason);
        repo.save(w);
    }

    private WorkflowStatuses parseFailureStatus(String status) {
        try {
            WorkflowStatuses parsed = WorkflowStatuses.valueOf(status);
            if (parsed != WorkflowStatuses.FAILED_SYSTEM) {
                throw ApplicationFailure.newNonRetryableFailure("Invalid failure status: " + status, "INVALID_TRANSITION");
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw ApplicationFailure.newNonRetryableFailure("Unknown status: " + status, "INVALID_STATUS");
        }
    }

    private void rejectIfTerminal(WorkflowRun w) {
        if (WorkflowStatuses.COMPLETED.name().equals(w.status) || WorkflowStatuses.FAILED_SYSTEM.name().equals(w.status)) {
            throw ApplicationFailure.newNonRetryableFailure("Run already terminal: " + w.id, "STATE_CONFLICT");
        }
    }

    private String bound(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_TEXT_LEN) return s;
        return s.substring(0, MAX_TEXT_LEN - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
    }

    private WorkflowRun load(Long runId) {
        return repo.findById(runId).orElseThrow(() ->
                ApplicationFailure.newNonRetryableFailure("WorkflowRun not found: " + runId, "RUN_NOT_FOUND"));
    }
}
