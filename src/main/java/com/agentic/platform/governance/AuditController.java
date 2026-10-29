package com.agentic.platform.governance;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditRepository repo;
    public AuditController(AuditRepository repo) { this.repo = repo; }

    @GetMapping
    public List<AuditEvent> list(@RequestParam(required = false) String runId) {
        return runId == null ? repo.findTop100ByOrderByIdDesc() : repo.findByRunIdOrderByIdAsc(runId);
    }
}
