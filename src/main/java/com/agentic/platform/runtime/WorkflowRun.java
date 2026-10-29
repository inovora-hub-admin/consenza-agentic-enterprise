package com.agentic.platform.runtime;

import jakarta.persistence.*;
import java.time.Instant;

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
    @Column(columnDefinition = "TEXT") public String finalOutput;
    public Instant createdAt = Instant.now();
}
