package com.agentic.platform.temporal;

import com.agentic.platform.runtime.RunResult;
import com.agentic.platform.runtime.WorkflowResult.Step;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

/** One durable multi-agent process. Workflow id = "wf-{WorkflowRun.id}". */
@WorkflowInterface
public interface AgentProcessWorkflow {
    record StartRequest(Long runId, String input, List<Step> plan) {}
    /** Outcome of AgentRunner.resume(...) for the approval the workflow is waiting on. */
    record ApprovalOutcome(Long approvalId, RunResult result) {}

    /** Returns the final status: COMPLETED | FAILED. */
    @WorkflowMethod
    String run(StartRequest req);

    /** Delivered by TemporalWorkflowEngine.onApprovalResult after the reviewer's decision was applied. */
    @SignalMethod
    void approvalDecided(ApprovalOutcome outcome);

    @QueryMethod
    String status();
}
