package com.agentic.platform.runtime;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/runs")
public class RunController {
    public record RunRequest(String agent, String input) {}

    private final Orchestrator orchestrator;
    public RunController(Orchestrator orchestrator) { this.orchestrator = orchestrator; }

    /** agent is optional: omit it and the orchestrator routes the request. */
    @PostMapping
    public RunResult run(@RequestBody RunRequest req) { return orchestrator.run(req.agent(), req.input()); }
}
