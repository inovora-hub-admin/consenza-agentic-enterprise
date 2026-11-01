package com.agentic.platform.config;

import java.util.List;

/** A designed business process: an ordered list of agent steps. Versioned like agents. */
public record WorkflowSpec(String name, String description, List<StepSpec> steps) {
    public record StepSpec(String agent, String task) {
        public StepSpec { task = task == null ? "" : task; }   // blank task = use the run's input
    }
    public record Published(WorkflowSpec spec, int version) {}

    public WorkflowSpec {
        description = description == null ? "" : description;
        steps = steps == null ? List.of() : steps;
    }
}
