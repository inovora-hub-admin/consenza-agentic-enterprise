package com.agentic.platform.runtime;

import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Runs (instances). /api/process-runs is an alias of /api/workflows. */
@RestController
@RequestMapping({"/api/workflows", "/api/process-runs"})
public class WorkflowController {
    /** definition = name of a published workflow. Otherwise steps (fixed pipeline) or, if both omitted, the planner. */
    public record WorkflowRequest(String input, String definition, List<WorkflowResult.Step> steps) {}

    private final WorkflowEngine svc;
    public WorkflowController(WorkflowEngine svc) { this.svc = svc; }

    @PostMapping
    public WorkflowResult start(@RequestBody WorkflowRequest req) {
        return svc.start(req.input(), req.steps(), req.definition());
    }

    @GetMapping
    public List<WorkflowResult> list() { return svc.list(); }

    @GetMapping("/{id}")
    public WorkflowResult get(@PathVariable Long id) { return svc.get(id); }
}
