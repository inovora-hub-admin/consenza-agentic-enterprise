package com.agentic.platform.temporal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** platform.workflow.temporal.* — only read when platform.workflow.engine=temporal. */
@ConfigurationProperties(prefix = "platform.workflow.temporal")
public class TemporalProperties {
    /** Frontend gRPC address, e.g. localhost:7233 or temporal:7233 in compose. */
    private String target = "localhost:7233";
    private String namespace = "default";
    private String taskQueue = "agentic-workflows";
    /** Embedded worker on/off: turn off on API-only replicas. */
    private boolean workerEnabled = true;
    /** Max wall time for one agent step (LLM + tools). */
    private Duration stepTimeout = Duration.ofMinutes(5);
    /** How long REST calls wait for the workflow to reach a stable state before returning the read model. */
    private Duration syncWait = Duration.ofSeconds(30);

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getTaskQueue() { return taskQueue; }
    public void setTaskQueue(String taskQueue) { this.taskQueue = taskQueue; }
    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean workerEnabled) { this.workerEnabled = workerEnabled; }
    public Duration getStepTimeout() { return stepTimeout; }
    public void setStepTimeout(Duration stepTimeout) { this.stepTimeout = stepTimeout; }
    public Duration getSyncWait() { return syncWait; }
    public void setSyncWait(Duration syncWait) { this.syncWait = syncWait; }
}
