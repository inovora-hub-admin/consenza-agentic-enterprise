package com.agentic.platform.runtime;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
//Test
public interface WorkflowRepository extends JpaRepository<WorkflowRun, Long> {
    Optional<WorkflowRun> findByPendingApprovalId(Long approvalId);
    List<WorkflowRun> findTop50ByOrderByIdDesc();
}
