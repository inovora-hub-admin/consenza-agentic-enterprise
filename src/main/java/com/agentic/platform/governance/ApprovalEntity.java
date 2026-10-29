package com.agentic.platform.governance;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class ApprovalEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String runId;
    public String agentName;
    public String toolName;
    public String toolUseId;
    @Column(columnDefinition = "TEXT")
    public String toolInput;
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    public String messages;      // frozen conversation so the run can resume after the decision
    public String status;        // PENDING | APPROVED | REJECTED
    public String reviewer;
    public Instant createdAt = Instant.now();
}
