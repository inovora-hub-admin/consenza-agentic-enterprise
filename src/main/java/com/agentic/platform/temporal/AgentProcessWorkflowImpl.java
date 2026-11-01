package com.agentic.platform.temporal;

import com.agentic.platform.runtime.RunResult;
import com.agentic.platform.runtime.WorkflowResult.Step;
import com.agentic.platform.runtime.WorkflowResult.StepOutput;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic orchestration: for each planned step run the agent; if it pauses for approval,
 * park until the reviewer's outcome arrives as a signal (no timeout — approvals may take days),
 * then continue. All side effects go through activities.
 */
public class AgentProcessWorkflowImpl implements AgentProcessWorkflow {
    /** Bump when the orchestration logic changes incompatibly (see Workflow.getVersion). */
    private static final String CHANGE_ID = "agent-process-v1";

    private final AgentProcessActivities acts = Workflow.newActivityStub(
            AgentProcessActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(10))      // overridden per-call below for LLM steps
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setBackoffCoefficient(2.0)
                            .setMaximumAttempts(3)
                            .setDoNotRetry(IllegalArgumentException.class.getName())
                            .build())
                    .build());

    private String status = "RUNNING";
    private Long waitingOn;                 // approvalId we're parked on
    private ApprovalOutcome outcome;        // set by signal

    @Override
    public String run(StartRequest req) {
        Workflow.getVersion(CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
        List<StepOutput> outs = new ArrayList<>();
        try {
            for (int i = 0; i < req.plan().size(); i++) {
                Step s = req.plan().get(i);
                RunResult r = acts.runStep(req.runId(), s.agent(), s.task(), outs);

                while ("PENDING_APPROVAL".equals(r.status())) {
                    waitingOn = r.approvalId();
                    status = "PENDING_APPROVAL";
                    outcome = null;
                    acts.markPending(req.runId(), waitingOn);
                    Workflow.await(() -> outcome != null && waitingOn.equals(outcome.approvalId()));
                    r = outcome.result();           // may itself be PENDING_APPROVAL -> loop again
                    status = "RUNNING";
                }
                waitingOn = null;

                StepOutput out = new StepOutput(s.agent(), s.task(), r.runId(), r.status(), r.output());
                outs.add(out);
                if ("FAILED".equals(r.status())) {
                    acts.recordStep(req.runId(), i, out);
                    acts.markFailed(req.runId(), r.output());
                    return status = "FAILED";
                }
                acts.recordStep(req.runId(), i, out);
            }
            acts.markCompleted(req.runId(), outs);
            return status = "COMPLETED";
        } catch (RuntimeException e) {
            acts.markFailed(req.runId(), e.getMessage() == null ? e.toString() : e.getMessage());
            status = "FAILED";
            throw e;
        }
    }

    @Override
    public void approvalDecided(ApprovalOutcome o) {
        // Ignore stale/duplicate signals for approvals we're not waiting on.
        if (waitingOn != null && waitingOn.equals(o.approvalId())) outcome = o;
    }

    @Override
    public String status() { return status; }
}
