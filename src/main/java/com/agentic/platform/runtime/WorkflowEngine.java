package com.agentic.platform.runtime;

import com.agentic.platform.runtime.WorkflowResult.Step;

import java.util.List;
import java.util.Optional;

/**
 * Multi-agent process engine. Two implementations, selected by platform.workflow.engine:
 *   local    - in-process loop (WorkflowService), default
 *   temporal - durable execution on Temporal (TemporalWorkflowEngine)
 * Both keep WorkflowRun as the read model, so controllers and the UI are engine-agnostic.
 */
public interface WorkflowEngine {
    WorkflowResult start(String input, List<Step> steps, String definition);
    WorkflowResult get(Long id);
    List<WorkflowResult> list();
    /** Called after a reviewer decides and the agent step was resumed. Empty if the approval did not belong to a run. */
    Optional<WorkflowResult> onApprovalResult(Long approvalId, RunResult r);
}
