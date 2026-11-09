package com.agentic.platform.runtime;

import com.agentic.platform.config.AgentSpec;
import com.agentic.platform.governance.ApprovalEntity;
import com.agentic.platform.governance.ApprovalRepository;
import com.agentic.platform.governance.AuditService;
import com.agentic.platform.governance.PolicyEngine;
import com.agentic.platform.tools.ToolGateway;
import com.agentic.platform.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRunnerDecisionEnvelopeTest {
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void sanctionsHitEscalatesToPendingApprovalWithDecisionEnvelope() throws Exception {
        ModelClient model = mock(ModelClient.class);
        ToolGateway gateway = mock(ToolGateway.class);
        PolicyEngine policy = mock(PolicyEngine.class);
        AuditService audit = mock(AuditService.class);
        ApprovalRepository approvals = mock(ApprovalRepository.class);

        AgentRunner runner = new AgentRunner(model, gateway, policy, audit, approvals, om);

        AgentSpec agent = mock(AgentSpec.class);
        when(agent.name()).thenReturn("kyc-agent");
        when(agent.instructions()).thenReturn("instr");
        when(agent.model()).thenReturn("gpt-4.1-mini");
        when(agent.tools()).thenReturn(List.of("sanctions-check"));
        when(agent.autonomy()).thenReturn("READ_ONLY");
        when(agent.approvalRequiredFor()).thenReturn(List.of());
        when(agent.guardrails()).thenReturn(List.of());

        ToolHandler sanctions = mock(ToolHandler.class);
        when(sanctions.name()).thenReturn("sanctions-check");

        when(gateway.forAgent(agent)).thenReturn(List.of(sanctions));
        when(gateway.get("sanctions-check")).thenReturn(Optional.of(sanctions));
        when(policy.applyGuardrails(eq(agent), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(policy.checkTool(eq(agent), eq(sanctions))).thenReturn(PolicyEngine.Decision.ALLOW);

        ObjectNode modelResp = om.createObjectNode();
        ArrayNode content = modelResp.putArray("content");
        ObjectNode toolUse = content.addObject();
        toolUse.put("type", "tool_use");
        toolUse.put("id", "call_1");
        toolUse.put("name", "sanctions-check");
        toolUse.set("input", om.readTree("{\"name\":\"Paul Makonda\",\"country\":\"Tanzania\"}"));
        modelResp.put("stop_reason", "tool_use");
        when(model.complete(anyString(), anyString(), any(ArrayNode.class), anyList())).thenReturn(modelResp);

        JsonNode toolOut = om.readTree("""
            {
              "match": true,
              "status": "OK",
              "source": "workcheck",
              "request_id": "req-123",
              "dataset_version": "dev-test-data",
              "threshold_used": 75,
              "matches": [{"profile_id":"5135208","score":100}]
            }
            """);
        when(gateway.invoke(eq(agent), eq("sanctions-check"), any(JsonNode.class))).thenReturn(toolOut);

        when(approvals.save(any(ApprovalEntity.class))).thenAnswer(inv -> {
            ApprovalEntity ap = inv.getArgument(0);
            ap.id = 42L;
            return ap;
        });

        RunResult r = runner.start(agent, "screen customer");

        assertEquals("PENDING_APPROVAL", r.status());
        assertEquals(42L, r.approvalId());
        assertTrue(r.requiresApproval());
        assertEquals("HIGH", r.riskLevel());
        assertEquals("NEEDS_APPROVAL", r.policyResult());
        assertEquals("READ_ONLY", r.effectiveAutonomyMode());
        assertNotNull(r.evidence());
        assertEquals("req-123", r.evidence().path("request_id").asText());
        assertEquals(1, r.evidence().path("match_count").asInt());

        ArgumentCaptor<ApprovalEntity> cap = ArgumentCaptor.forClass(ApprovalEntity.class);
        verify(approvals).save(cap.capture());
        ApprovalEntity saved = cap.getValue();
        assertEquals("PENDING", saved.status);
        assertEquals("HIGH", saved.riskLevel);
        assertEquals("NEEDS_APPROVAL", saved.policyResult);
        assertEquals("req-123", saved.requestId);
        assertNotNull(saved.evidenceJson);
        assertTrue(saved.evidenceJson.contains("\"match_count\":1"));

        verify(audit, atLeastOnce()).log(eq(r.runId()), eq("kyc-agent"), eq("APPROVAL_REQUESTED"), contains("request_id=req-123"));
    }

    @Test
    void noToolUseCompletesWithLowRiskDecisionEnvelope() {
        ModelClient model = mock(ModelClient.class);
        ToolGateway gateway = mock(ToolGateway.class);
        PolicyEngine policy = mock(PolicyEngine.class);
        AuditService audit = mock(AuditService.class);
        ApprovalRepository approvals = mock(ApprovalRepository.class);

        AgentRunner runner = new AgentRunner(model, gateway, policy, audit, approvals, om);

        AgentSpec agent = mock(AgentSpec.class);
        when(agent.name()).thenReturn("kyc-agent");
        when(agent.instructions()).thenReturn("instr");
        when(agent.model()).thenReturn("gpt-4.1-mini");
        when(agent.tools()).thenReturn(List.of());
        when(agent.autonomy()).thenReturn("RECOMMEND");
        when(agent.approvalRequiredFor()).thenReturn(List.of());
        when(agent.guardrails()).thenReturn(List.of());

        when(gateway.forAgent(agent)).thenReturn(List.of());
        when(policy.applyGuardrails(eq(agent), anyString())).thenAnswer(inv -> inv.getArgument(1));

        ObjectNode modelResp = om.createObjectNode();
        ArrayNode content = modelResp.putArray("content");
        content.addObject().put("type", "text").put("text", "Done.");
        modelResp.put("stop_reason", "end_turn");
        when(model.complete(anyString(), anyString(), any(ArrayNode.class), anyList())).thenReturn(modelResp);

        RunResult r = runner.start(agent, "hello");

        assertEquals("COMPLETED", r.status());
        assertFalse(r.requiresApproval());
        assertEquals("LOW", r.riskLevel());
        assertEquals("ALLOW", r.policyResult());
        assertEquals("RECOMMEND", r.effectiveAutonomyMode());
    }
}
