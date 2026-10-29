package com.agentic.platform.governance;

import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditRepository repo;
    public AuditService(AuditRepository repo) { this.repo = repo; }

    public void log(String runId, String agent, String type, String detail) {
        AuditEvent e = new AuditEvent();
        e.runId = runId; e.agent = agent; e.type = type;
        e.detail = detail != null && detail.length() > 4000 ? detail.substring(0, 4000) : detail;
        repo.save(e);
    }
}
