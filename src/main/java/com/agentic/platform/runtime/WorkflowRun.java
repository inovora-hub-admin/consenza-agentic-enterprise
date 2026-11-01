package com.agentic.platform.runtime;

import jakarta.persistence.*;
import java.time.Instant;

/** One execution (instance) of a process. definitionName/Version are null for ad-hoc runs. */
@Entity
public class WorkflowRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(columnDefinition = "TEXT") public String input;
    @Column(columnDefinition = "TEXT") public String plan;       // JSON array of steps
    @Column(columnDefinition = "TEXT") public String outputs;    // JSON array of finished step outputs
    public int stepIndex;
    public String status;                                        // RUNNING | PENDING_APPROVAL | COMPLETED | FAILED
    public Long pendingApprovalId;
    public String definitionName;
    public Integer definitionVersion;
    @Column(columnDefinition = "TEXT") public String finalOutput;
    public Instant createdAt = Instant.now();
}
