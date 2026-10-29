package com.agentic.platform.runtime;

import java.util.List;

public record WorkflowResult(Long id, String status, List<Step> plan, int currentStep,
                             List<StepOutput> steps, String output, Long approvalId) {
    public record Step(String agent, String task) {}
    public record StepOutput(String agent, String task, String runId, String status, String output) {}
}
