package com.agentic.platform.temporal;

import com.agentic.platform.runtime.RunResult;
import com.agentic.platform.runtime.WorkflowResult.Step;
import com.agentic.platform.runtime.WorkflowResult.StepOutput;
import com.agentic.platform.runtime.WorkflowStatuses;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic orchestration: for each planned step run the agent; if it pauses for approval,
 * park until the reviewer's outcome arrives as a signal (no timeout - approvals may take days),
 * then continue. All side effects go through activities.
 */
public class AgentProcessWorkflowImpl implements AgentProcessWorkflow {
    /** Bump when the orchestration logic changes incompatibly (see Workflow.getVersion). */
    private static final String CHANGE_ID = "agent-process-v1";

    private final AgentProcessActivities acts = Workflow.newActivityStub(
            AgentProcessActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setBackoffCoefficient(2.0)
                            .setMaximumAttempts(3)
                            .setDoNotRetry(IllegalArgumentException.class.getName())
                            .build())
                    .build());

    private String status = WorkflowStatuses.RUNNING.name();
    private Long waitingOn;
    private ApprovalOutcome outcome;

    @Override
    public String run(StartRequest req) {
        Workflow.getVersion(CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
        List<StepOutput> outs = new ArrayList<>();
        try {
            for (int i = 0; i < req.plan().size(); i++) {
                Step s = req.plan().get(i);
                RunResult r = acts.runStep(req.runId(), s.agent(), s.task(), outs);

                while (WorkflowStatuses.PENDING_APPROVAL.name().equals(r.status())) {
                    waitingOn = r.approvalId();
                    status = WorkflowStatuses.PENDING_APPROVAL.name();
                    outcome = null;
                    acts.markPending(req.runId(), waitingOn);
                    Workflow.await(() -> outcome != null && waitingOn.equals(outcome.approvalId()));
                    r = outcome.result();
                    status = WorkflowStatuses.RUNNING.name();
                }
                waitingOn = null;

                StepOutput out = new StepOutput(s.agent(), s.task(), r.runId(), r.status(), r.output());
                outs.add(out);

                if (isTerminalFailure(r.status())) {
                    acts.recordStep(req.runId(), i, out);
                    String failedStatus = normalizeFailureStatus(r.status());
                    acts.markFailed(req.runId(), String.valueOf(WorkflowStatuses.valueOf(failedStatus)), r.output());
                    return status = failedStatus;
                }

                acts.recordStep(req.runId(), i, out);
            }

            acts.markCompleted(req.runId(), outs);
            return status = WorkflowStatuses.COMPLETED.name();
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            acts.markFailed(req.runId(), String.valueOf(WorkflowStatuses.FAILED_SYSTEM), reason);
            status = WorkflowStatuses.FAILED_SYSTEM.name();
            throw e;
        }
    }

    private boolean isTerminalFailure(String st) {
        return WorkflowStatuses.BLOCKED_POLICY.name().equals(st)
                || WorkflowStatuses.FAILED_SYSTEM.name().equals(st)
                || "FAILED".equals(st); // backward compatibility
    }

    private String normalizeFailureStatus(String st) {
        if (WorkflowStatuses.BLOCKED_POLICY.name().equals(st)) return st;
        if (WorkflowStatuses.FAILED_SYSTEM.name().equals(st)) return st;
        return WorkflowStatuses.FAILED_SYSTEM.name();
    }

    @Override
    public void approvalDecided(ApprovalOutcome o) {
        if (waitingOn != null && waitingOn.equals(o.approvalId())) outcome = o;
    }

    @Override
    public String status() {
        return status;
    }
}
