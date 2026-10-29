package com.agentic.platform.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record ConfigView(String kind, String name, int version, String status, JsonNode spec, Instant createdAt) {}
