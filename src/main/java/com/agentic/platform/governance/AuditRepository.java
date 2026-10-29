package com.agentic.platform.governance;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByRunIdOrderByIdAsc(String runId);
    List<AuditEvent> findTop100ByOrderByIdDesc();
}
