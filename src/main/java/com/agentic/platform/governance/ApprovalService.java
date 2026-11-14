package com.agentic.platform.governance;

import com.agentic.platform.config.AgentSpec;
import com.agentic.platform.config.ConfigService;
import com.agentic.platform.runtime.*;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
@Slf4j
@Service

public class ApprovalService {

    public record DecisionResponse(RunResult run, WorkflowResult workflow) {}

    private final ApprovalRepository repo;
    private final ConfigService config;
    private final AgentRunner runner;
    private final AuditService audit;
    private final WorkflowEngine workflows;

    public ApprovalService(
            ApprovalRepository repo,
            ConfigService config,
            AgentRunner runner,
            AuditService audit,
            WorkflowEngine workflows ) {
        this.repo = repo;
        this.config = config;
        this.runner = runner;
        this.audit = audit;
        this.workflows = workflows;
    }

    @Transactional public DecisionResponse decide(Long id, boolean approve, String reviewer) throws Exception {
        ApprovalEntity ap = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such approval"));
        if (!"PENDING".equals(ap.status)) {
            // Idempotent behavior for duplicate UI submissions: return current state instead of error.
            WorkflowResult wf = workflows.onApprovalResult(id, new RunResult(
                    ap.runId,
                    "FAILED",
                    "Approval already decided",
                    ap.id,
                    ap.riskLevel,
                    ap.policyResult,
                    false,
                    null,
                    null
            )).orElse(null);
            return new DecisionResponse(new RunResult(
                    ap.runId,
                    ap.status,
                    "Already decided",
                    ap.id,
                    ap.riskLevel,
                    ap.policyResult,
                    false,
                    null,
                    null
            ), wf);
        }

        AgentSpec agent = config.publishedAgent(ap.agentName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Agent no longer published"));

        ap.status = approve ? "APPROVED" : "REJECTED";
        ap.reviewer = reviewer;
        repo.save(ap);

        audit.log(ap.runId, ap.agentName, "APPROVAL_" + ap.status,
                "tool=" + ap.toolName +
                        ", reviewer=" + ap.reviewer +
                        ", risk=" + (ap.riskLevel == null ? "" : ap.riskLevel) +
                        ", policy=" + (ap.policyResult == null ? "" : ap.policyResult) +
                        ", request_id=" + (ap.requestId == null ? "" : ap.requestId));

        RunResult run;
        if (!approve) {
            run = new RunResult(
                    ap.runId,
                    WorkflowStatuses.BLOCKED_POLICY.name(),
                    "Approval rejected by reviewer",
                    ap.id,
                    ap.riskLevel,
                    "DENY",
                    false,
                    agent.autonomy(),
                    null
            );
        } else {
            run = runner.resume(ap, agent, true);
        }

        WorkflowResult wf = workflows.onApprovalResult(id, run).orElse(null);

        return new DecisionResponse(run, wf);
    }
}