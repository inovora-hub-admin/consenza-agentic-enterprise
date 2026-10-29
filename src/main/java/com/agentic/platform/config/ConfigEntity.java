package com.agentic.platform.config;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "config_item", uniqueConstraints = @UniqueConstraint(columnNames = {"kind", "name", "version"}))
public class ConfigEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String kind;      // "agent" (extend: "policy", "workflow")
    public String name;
    public int version;
    public String status;    // DRAFT | PUBLISHED | ARCHIVED
    @Column(columnDefinition = "TEXT")
    public String spec;
    public Instant createdAt = Instant.now();
}
