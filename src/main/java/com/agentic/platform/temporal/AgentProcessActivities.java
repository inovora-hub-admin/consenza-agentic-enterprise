package com.agentic.platform.temporal;

import com.agentic.platform.runtime.RunResult;
import com.agentic.platform.runtime.WorkflowResult.StepOutput;
import io.temporal.activity.ActivityInterface;

import java.util.List;

/**
 * Everything non-deterministic lives here: LLM/agent calls and read-model writes.
 * Each method must be safe to retry.
 */
@ActivityInterface
public interface AgentProcessActivities {
    /** Runs one agent step. May return PENDING_APPROVAL with an approvalId. */
    RunResult runStep(Long runId, String agentName, String task, List<StepOutput> prior);

    /** Read-model: run is parked on an approval. */
    void markPending(Long runId, Long approvalId);

    /** Read-model: append a finished step output and advance stepIndex (idempotent on stepIndex). */
    void recordStep(Long runId, int stepIndex, StepOutput output);

    void markCompleted(Long runId, List<StepOutput> outputs);

    void markFailed(Long runId, String reason);
}
