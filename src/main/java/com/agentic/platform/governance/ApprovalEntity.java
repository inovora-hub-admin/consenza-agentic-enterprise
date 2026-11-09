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

    // for policy/evidence visibility.
    public String riskLevel;     // LOW | MEDIUM | HIGH
    public String policyResult;  // NEEDS_APPROVAL, etc.
    public String requestId;     // upstream screening correlation id
    @Column(columnDefinition = "TEXT")
    public String evidenceJson;  // compact evidence payload for reviewer/audit
    public Instant createdAt = Instant.now();
}
