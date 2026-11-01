package com.agentic.platform.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.time.Duration;

/**
 * Temporal client + embedded worker. Active only when platform.workflow.engine=temporal.
 * The worker starts after the Spring context is ready so activities can use fully-wired beans.
 */
@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@ConditionalOnProperty(name = "platform.workflow.engine", havingValue = "temporal")
public class TemporalConfig {
    private static final Logger log = LoggerFactory.getLogger(TemporalConfig.class);

    private WorkerFactory factory;

    @Bean(destroyMethod = "shutdownNow")
    public WorkflowServiceStubs workflowServiceStubs(TemporalProperties p) {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(p.getTarget())
                .setRpcTimeout(Duration.ofSeconds(10))
                .build());
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs, TemporalProperties p, ObjectMapper om) {
        // Reuse Spring's ObjectMapper so records / JavaTime serialise exactly as in the REST layer.
        return WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder()
                .setNamespace(p.getNamespace())
                .setDataConverter(DefaultDataConverter.newDefaultInstance()
                        .withPayloadConverterOverrides(new JacksonJsonPayloadConverter(om)))
                .build());
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client, TemporalProperties p, AgentProcessActivitiesImpl activities) {
        factory = WorkerFactory.newInstance(client);
        if (p.isWorkerEnabled()) {
            Worker worker = factory.newWorker(p.getTaskQueue());
            worker.registerWorkflowImplementationTypes(AgentProcessWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
        }
        return factory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWorker() {
        if (factory == null) return;
        try {
            factory.start();
            log.info("Temporal worker started");
        } catch (Exception e) {
            // Fail-open like the MCP client: API stays up, workflow starts will error until Temporal is reachable.
            log.warn("Temporal worker failed to start: {}", e.toString());
        }
    }

    @PreDestroy
    public void stopWorker() {
        if (factory != null) {
            factory.shutdown();
            factory.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
