package com.agentic.platform.runtime;

public record RunResult(String runId, String status, String output, Long approvalId) {}
