package com.agentic.platform.config;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ConfigRepository extends JpaRepository<ConfigEntity, Long> {
    List<ConfigEntity> findByKind(String kind);
    List<ConfigEntity> findByKindAndStatus(String kind, String status);
    List<ConfigEntity> findByKindAndNameOrderByVersionDesc(String kind, String name);
    Optional<ConfigEntity> findFirstByKindAndNameAndStatus(String kind, String name, String status);
}
