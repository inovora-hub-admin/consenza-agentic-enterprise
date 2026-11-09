package com.agentic.platform.runtime;


import com.fasterxml.jackson.databind.JsonNode;

/**
 * decision envelope:
 * - riskLevel: LOW | MEDIUM | HIGH
 * - policyResult: ALLOW | DENY | NEEDS_APPROVAL
 * - requiresApproval: true when current/next action needs reviewer decision
 * - effectiveAutonomyMode: from agent config at runtime
 * - evidence: optional tool/policy evidence payload (e.g., sanctions request_id, match count)
 */
public record RunResult(
        String runId,
        String status,
        String output,
        Long approvalId,
        String riskLevel,
        String policyResult,
        boolean requiresApproval,
        String effectiveAutonomyMode,
        JsonNode evidence
) {
    // Backward-compatible constructor used by existing call sites.
    public RunResult(String runId, String status, String output, Long approvalId) {
        this(runId, status, output, approvalId, "LOW", "ALLOW", false, null, null);
    }
}
