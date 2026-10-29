package com.agentic.platform.runtime;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {
    /** steps optional: omit and the planner picks agents; provide for a fixed pipeline. */
    public record WorkflowRequest(String input, List<WorkflowResult.Step> steps) {}

    private final WorkflowService svc;
    public WorkflowController(WorkflowService svc) { this.svc = svc; }

    @PostMapping
    public WorkflowResult start(@RequestBody WorkflowRequest req) { return svc.start(req.input(), req.steps()); }

    @GetMapping
    public List<WorkflowResult> list() { return svc.list(); }

    @GetMapping("/{id}")
    public WorkflowResult get(@PathVariable Long id) { return svc.get(id); }
}
