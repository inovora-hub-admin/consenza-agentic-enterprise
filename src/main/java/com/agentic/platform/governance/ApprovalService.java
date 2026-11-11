package com.agentic.platform.governance;

import com.agentic.platform.config.AgentSpec;
import com.agentic.platform.config.ConfigService;
import com.agentic.platform.runtime.AgentRunner;
import com.agentic.platform.runtime.RunResult;
import com.agentic.platform.runtime.WorkflowEngine;
import com.agentic.platform.runtime.WorkflowResult;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already decided");
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

        RunResult run = runner.resume(ap, agent, approve);
        WorkflowResult wf = workflows.onApprovalResult(id, run).orElse(null);

        return new DecisionResponse(run, wf);
    }
}