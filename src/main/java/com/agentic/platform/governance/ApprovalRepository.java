package com.agentic.platform.governance;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ApprovalRepository extends JpaRepository<ApprovalEntity, Long> {
    List<ApprovalEntity> findByStatus(String status);
}
