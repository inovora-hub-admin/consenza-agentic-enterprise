package com.agentic.platform.config;

import java.util.List;

/** The agent definition your UI edits. Stored as JSON, versioned. */
public record AgentSpec(
        String name,
        String description,
        String model,                       // null = platform default
        String instructions,
        List<String> tools,
        String autonomy,                    // READ_ONLY | RECOMMEND | ACT_WITH_APPROVAL | AUTONOMOUS
        List<String> approvalRequiredFor,   // tool names that always need a human
        List<String> guardrails) {          // e.g. "pii-mask"

    public AgentSpec {
        description = description == null ? "" : description;
        tools = tools == null ? List.of() : tools;
        approvalRequiredFor = approvalRequiredFor == null ? List.of() : approvalRequiredFor;
        guardrails = guardrails == null ? List.of() : guardrails;
        autonomy = autonomy == null ? "READ_ONLY" : autonomy.toUpperCase();
    }
}
