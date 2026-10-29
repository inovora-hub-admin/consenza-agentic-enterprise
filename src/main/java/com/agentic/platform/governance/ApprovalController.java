package com.agentic.platform.governance;

import com.agentic.platform.config.AgentSpec;
import com.agentic.platform.config.ConfigService;
import com.agentic.platform.runtime.AgentRunner;
import com.agentic.platform.runtime.RunResult;
import com.agentic.platform.runtime.WorkflowResult;
import com.agentic.platform.runtime.WorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Human-in-the-loop queue: reviewers approve or reject pending tool calls. */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {
    public record DecisionRequest(boolean approve, String reviewer) {}
    /** workflow is null when the approval belonged to a single-agent run. */
    public record DecisionResult(RunResult run, WorkflowResult workflow) {}

    private final ApprovalRepository repo;
    private final ConfigService config;
    private final AgentRunner runner;
    private final AuditService audit;
    private final WorkflowService workflows;

    public ApprovalController(ApprovalRepository repo, ConfigService config, AgentRunner runner,
                              AuditService audit, WorkflowService workflows) {
        this.repo = repo; this.config = config; this.runner = runner; this.audit = audit; this.workflows = workflows;
    }

    @GetMapping
    public List<ApprovalEntity> list(@RequestParam(defaultValue = "PENDING") String status) {
        return repo.findByStatus(status);
    }

    @PostMapping("/{id}/decision")
    public DecisionResult decide(@PathVariable Long id, @RequestBody DecisionRequest body) throws Exception {
        ApprovalEntity ap = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such approval"));
        if (!"PENDING".equals(ap.status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Already decided");
        AgentSpec agent = config.publishedAgent(ap.agentName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Agent no longer published"));
        ap.status = body.approve() ? "APPROVED" : "REJECTED";
        ap.reviewer = body.reviewer();
        repo.save(ap);
        audit.log(ap.runId, ap.agentName, "APPROVAL_" + ap.status, ap.toolName + " by " + ap.reviewer);
        RunResult run = runner.resume(ap, agent, body.approve());
        return new DecisionResult(run, workflows.onApprovalResult(id, run).orElse(null));
    }
}
