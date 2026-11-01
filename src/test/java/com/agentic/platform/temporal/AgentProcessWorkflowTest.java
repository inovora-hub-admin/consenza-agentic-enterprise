package com.agentic.platform.temporal;

import com.agentic.platform.runtime.RunResult;
import com.agentic.platform.runtime.WorkflowResult.Step;
import com.agentic.platform.temporal.AgentProcessWorkflow.ApprovalOutcome;
import com.agentic.platform.temporal.AgentProcessWorkflow.StartRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pause -> signal -> second approval on same step -> signal -> complete, with mocked activities. */
class AgentProcessWorkflowTest {
    private static final String TQ = "test-queue";
    private TestWorkflowEnvironment env;
    private WorkflowClient client;
    private AgentProcessActivities acts;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(TQ);
        worker.registerWorkflowImplementationTypes(AgentProcessWorkflowImpl.class);
        acts = mock(AgentProcessActivities.class);
        worker.registerActivitiesImplementations(acts);
        client = env.getWorkflowClient();
        env.start();
    }

    @AfterEach
    void tearDown() { env.close(); }

    @Test
    void pausesForApprovalAndResumesOnSignal() throws Exception {
        when(acts.runStep(eq(1L), eq("kyc"), anyString(), anyList()))
                .thenReturn(new RunResult("r1", "PENDING_APPROVAL", null, 7L));
        when(acts.runStep(eq(1L), eq("credit"), anyString(), anyList()))
                .thenReturn(new RunResult("r2", "COMPLETED", "score ok", null));

        AgentProcessWorkflow wf = client.newWorkflowStub(AgentProcessWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TQ).setWorkflowId("wf-1").build());
        WorkflowClient.start(wf::run, new StartRequest(1L, "open account",
                List.of(new Step("kyc", "verify"), new Step("credit", "score"))));

        awaitStatus(wf, "PENDING_APPROVAL");
        verify(acts, timeout(5000)).markPending(1L, 7L);

        // Reviewer approves, but the same step needs a second approval.
        wf.approvalDecided(new ApprovalOutcome(7L, new RunResult("r1", "PENDING_APPROVAL", null, 8L)));
        verify(acts, timeout(5000)).markPending(1L, 8L);

        // Stale signal for an approval we're no longer waiting on must be ignored.
        wf.approvalDecided(new ApprovalOutcome(7L, new RunResult("r1", "COMPLETED", "stale", null)));

        wf.approvalDecided(new ApprovalOutcome(8L, new RunResult("r1", "COMPLETED", "kyc ok", null)));

        String result = client.newUntypedWorkflowStub("wf-1").getResult(10, java.util.concurrent.TimeUnit.SECONDS, String.class);
        assertEquals("COMPLETED", result);
        verify(acts).recordStep(eq(1L), eq(0), argThat(o -> "kyc ok".equals(o.output())));
        verify(acts).recordStep(eq(1L), eq(1), argThat(o -> "score ok".equals(o.output())));
        verify(acts).markCompleted(eq(1L), argThat(l -> l.size() == 2));
        verify(acts, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void failedStepStopsTheProcess() throws Exception {
        when(acts.runStep(eq(2L), eq("kyc"), anyString(), anyList()))
                .thenReturn(new RunResult("r1", "FAILED", "boom", null));

        AgentProcessWorkflow wf = client.newWorkflowStub(AgentProcessWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TQ).setWorkflowId("wf-2").build());
        String result = wf.run(new StartRequest(2L, "x", List.of(new Step("kyc", "verify"), new Step("credit", "score"))));

        assertEquals("FAILED", result);
        verify(acts).markFailed(2L, "boom");
        verify(acts, never()).runStep(eq(2L), eq("credit"), anyString(), anyList());
    }

    private static void awaitStatus(AgentProcessWorkflow wf, String expected) throws InterruptedException {
        long end = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        while (System.currentTimeMillis() < end) {
            if (expected.equals(wf.status())) return;
            Thread.sleep(50);
        }
        assertEquals(expected, wf.status());
    }
}
