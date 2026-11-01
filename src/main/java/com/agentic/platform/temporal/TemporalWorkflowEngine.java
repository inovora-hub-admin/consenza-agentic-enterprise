package com.agentic.platform.temporal;

import com.agentic.platform.runtime.RunResult;
import com.agentic.platform.runtime.WorkflowEngine;
import com.agentic.platform.runtime.WorkflowPlanner;
import com.agentic.platform.runtime.WorkflowPlanner.ResolvedPlan;
import com.agentic.platform.runtime.WorkflowRepository;
import com.agentic.platform.runtime.WorkflowResult;
import com.agentic.platform.runtime.WorkflowResult.Step;
import com.agentic.platform.runtime.WorkflowRun;
import com.agentic.platform.temporal.AgentProcessWorkflow.ApprovalOutcome;
import com.agentic.platform.temporal.AgentProcessWorkflow.StartRequest;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Temporal-backed engine (platform.workflow.engine=temporal).
 * REST contract and WorkflowRun read model are identical to the local engine; the difference is that
 * step execution survives restarts and approval waits are durable timers in Temporal.
 */
@Service
@ConditionalOnProperty(name = "platform.workflow.engine", havingValue = "temporal")
public class TemporalWorkflowEngine implements WorkflowEngine {
    private static final Logger log = LoggerFactory.getLogger(TemporalWorkflowEngine.class);

    private final WorkflowClient client;
    private final WorkflowRepository repo;
    private final WorkflowPlanner planner;
    private final TemporalProperties props;

    public TemporalWorkflowEngine(WorkflowClient client, WorkflowRepository repo, WorkflowPlanner planner, TemporalProperties props) {
        this.client = client; this.repo = repo; this.planner = planner; this.props = props;
    }

    @Override
    public WorkflowResult start(String input, List<Step> steps, String definition) {
        // Validation + planning happen synchronously so the UI still gets 404/400 immediately.
        ResolvedPlan rp = planner.resolve(input, steps, definition);

        WorkflowRun w = new WorkflowRun();
        w.input = input; w.plan = planner.json(rp.plan()); w.outputs = "[]"; w.status = "RUNNING";
        w.definitionName = rp.definitionName(); w.definitionVersion = rp.definitionVersion();
        w.engine = "temporal";
        repo.save(w);
        w.temporalWorkflowId = workflowId(w.id);
        repo.save(w);

        try {
            AgentProcessWorkflow stub = client.newWorkflowStub(AgentProcessWorkflow.class, WorkflowOptions.newBuilder()
                    .setWorkflowId(w.temporalWorkflowId)
                    .setTaskQueue(props.getTaskQueue())
                    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                    .build());
            WorkflowClient.start(stub::run, new StartRequest(w.id, input, rp.plan()));
        } catch (Exception e) {
            log.error("Temporal start failed for {}: {}", w.temporalWorkflowId, e.toString());
            w.status = "FAILED"; w.finalOutput = "Workflow engine unavailable: " + e.getMessage();
            repo.save(w);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Workflow engine unavailable", e);
        }
        // Give short runs a chance to finish so the response is as informative as the local engine's.
        return waitFor(w.id, r -> !"RUNNING".equals(r.status()));
    }

    @Override
    public WorkflowResult get(Long id) {
        return planner.toResult(repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @Override
    public List<WorkflowResult> list() {
        return repo.findTop50ByOrderByIdDesc().stream().map(planner::toResult).toList();
    }

    @Override
    public Optional<WorkflowResult> onApprovalResult(Long approvalId, RunResult r) {
        Optional<WorkflowRun> found = repo.findByPendingApprovalId(approvalId);
        if (found.isEmpty()) return Optional.empty();
        WorkflowRun w = found.get();
        if (w.temporalWorkflowId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Run " + w.id + " was started by the local engine; switch platform.workflow.engine=local to resume it");
        }
        AgentProcessWorkflow stub = client.newWorkflowStub(AgentProcessWorkflow.class, w.temporalWorkflowId);
        stub.approvalDecided(new ApprovalOutcome(approvalId, r));
        // Wait until the workflow has moved off this approval (next step, next approval, done or failed).
        return Optional.of(waitFor(w.id, res -> !approvalId.equals(res.approvalId())));
    }

    private WorkflowResult waitFor(Long runId, Predicate<WorkflowResult> settled) {
        Instant deadline = Instant.now().plus(props.getSyncWait());
        WorkflowResult res = get(runId);
        while (!settled.test(res) && Instant.now().isBefore(deadline)) {
            try { Thread.sleep(Duration.ofMillis(300)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            res = get(runId);
        }
        return res;
    }

    static String workflowId(Long runId) { return "wf-" + runId; }
}
