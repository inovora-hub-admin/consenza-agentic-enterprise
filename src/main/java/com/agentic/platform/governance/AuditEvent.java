package com.agentic.platform.governance;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String runId;
    public String agent;
    public String type;     // RUN_STARTED, TOOL_CALL, TOOL_DENIED, APPROVAL_REQUESTED, ...
    @Column(columnDefinition = "TEXT")
    public String detail;
    public Instant ts = Instant.now();
}
