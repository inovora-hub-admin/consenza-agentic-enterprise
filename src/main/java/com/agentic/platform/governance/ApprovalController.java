package com.agentic.platform.governance;
import com.agentic.platform.runtime.RunResult;
import com.agentic.platform.runtime.WorkflowResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController@RequestMapping("/api/approvals")
public class ApprovalController {
    public record DecisionRequest(boolean approve, String reviewer) {
    }

    public record DecisionResult(RunResult run, WorkflowResult workflow) {
    }

    private final ApprovalRepository repo;
    private final ApprovalService approvalService;

    public ApprovalController(ApprovalRepository repo, ApprovalService approvalService) {
        this.repo = repo;
        this.approvalService = approvalService;
    }

    @GetMapping
    public List<ApprovalEntity> list(@RequestParam(defaultValue = "PENDING") String status) {
        return repo.findByStatus(status);
    }

    @PostMapping("/{id}/decision")
    public DecisionResult decide(@PathVariable Long id, @RequestBody DecisionRequest body) throws Exception {
        ApprovalService.DecisionResponse res =
                approvalService.decide(id, body.approve(), body.reviewer());
        return new DecisionResult(res.run(), res.workflow());
    }

}