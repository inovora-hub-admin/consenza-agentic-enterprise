package com.agentic.platform.runtime;

import com.agentic.platform.config.AgentSpec;
import com.agentic.platform.governance.ApprovalEntity;
import com.agentic.platform.governance.ApprovalRepository;
import com.agentic.platform.governance.AuditService;
import com.agentic.platform.governance.PolicyEngine;
import com.agentic.platform.governance.PolicyEngine.Decision;
import com.agentic.platform.tools.ToolGateway;
import com.agentic.platform.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The agent loop: model call -> governed tool calls -> repeat, with pause/resume for human approval. */
@Service
public class AgentRunner {
    private static final int MAX_STEPS = 8;

    private final ModelClient model;
    private final ToolGateway gateway;
    private final PolicyEngine policy;
    private final AuditService audit;
    private final ApprovalRepository approvals;
    private final ObjectMapper om;

    public AgentRunner(ModelClient model, ToolGateway gateway, PolicyEngine policy,
                       AuditService audit, ApprovalRepository approvals, ObjectMapper om) {
        this.model = model;
        this.gateway = gateway;
        this.policy = policy;
        this.audit = audit;
        this.approvals = approvals;
        this.om = om;
    }

    public RunResult start(AgentSpec agent, String input) {
        String runId = UUID.randomUUID().toString();
        String safeInput = policy.applyGuardrails(agent, input);
        audit.log(runId, agent.name(), "RUN_STARTED", safeInput);
        ArrayNode messages = om.createArrayNode();
        messages.addObject().put("role", "user").put("content", safeInput);
        return drive(runId, agent, messages, null, Map.of());
    }

    /** Continue a run that paused for approval. Read-only tools in the same step are re-executed (keep them idempotent). */
    public RunResult resume(ApprovalEntity ap, AgentSpec agent, boolean approved) throws Exception {
        ArrayNode messages = (ArrayNode) om.readTree(ap.messages);
        JsonNode pending = messages.get(messages.size() - 1).get("content");
        return drive(ap.runId, agent, messages, pending, Map.of(ap.toolUseId, approved));
    }

    private RunResult drive(String runId, AgentSpec agent, ArrayNode messages,
                            JsonNode pendingToolBlocks, Map<String, Boolean> decisions) {
        List<ToolHandler> tools = gateway.forAgent(agent);
        JsonNode toolBlocks = pendingToolBlocks;
        try {
            for (int step = 0; step < MAX_STEPS; step++) {
                if (toolBlocks == null) {
                    JsonNode resp = model.complete(agent.model(), agent.instructions(), messages, tools);
                    JsonNode content = resp.get("content");
                    ObjectNode assistant = messages.addObject().put("role", "assistant");
                    assistant.set("content", content);
                    if (!"tool_use".equals(resp.path("stop_reason").asText())) {
                        String text = policy.applyGuardrails(agent, textOf(content));
                        audit.log(runId, agent.name(), "RUN_COMPLETED", text);
                        return new RunResult(runId, "COMPLETED", text, null,
                                "LOW", "ALLOW", false, agent.autonomy(), null);
                    }
                    toolBlocks = content;
                }
                RunResult pause = executeTools(runId, agent, messages, toolBlocks, decisions);
                if (pause != null) return pause;
                toolBlocks = null;
                decisions = Map.of();
            }
            audit.log(runId, agent.name(), "RUN_FAILED", "Max steps exceeded");
            return new RunResult(runId, "FAILED", "Max steps exceeded", null,
                    "MEDIUM", "DENY", false, agent.autonomy(), null);
        } catch (Exception e) {
            audit.log(runId, agent.name(), "RUN_FAILED", e.toString());
            return new RunResult(runId, "FAILED", e.getMessage(), null,
                    "MEDIUM", "DENY", false, agent.autonomy(), null);
        }
    }

    private RunResult executeTools(String runId, AgentSpec agent, ArrayNode messages,
                                   JsonNode content, Map<String, Boolean> decisions) {
        ArrayNode results = om.createArrayNode();
        for (JsonNode block : content) {
            if (!"tool_use".equals(block.path("type").asText())) continue;

            String id = block.get("id").asText();
            String name = block.get("name").asText();
            JsonNode input = block.get("input");
            Optional<ToolHandler> handler = gateway.get(name);

            Boolean decided = decisions.get(id);
            Decision decision = decided != null
                    ? (decided ? Decision.ALLOW : Decision.DENY)
                    : handler.map(h -> policy.checkTool(agent, h)).orElse(Decision.DENY);

            JsonNode output = null;
            if (decision == Decision.ALLOW) {
                try {
                    output = gateway.invoke(agent, name, input);
                } catch (Exception e) {
                    audit.log(runId, agent.name(), "TOOL_ERROR", name + ": " + e.getMessage());
                    results.add(toolResult(id, "Error: " + e.getMessage(), true));
                    continue;
                }
            }

            // Dynamic risk escalation: sanctions hits become high-risk and require approval.
            if (decision == Decision.ALLOW && isHighRiskSanctionsHit(name, output)) {
                decision = Decision.NEEDS_APPROVAL;
            }

            switch (decision) {
                case NEEDS_APPROVAL -> {
                    ObjectNode evidence = evidenceForApproval(name, input, output);

                    ApprovalEntity ap = new ApprovalEntity();
                    ap.runId = runId;
                    ap.agentName = agent.name();
                    ap.toolName = name;
                    ap.toolUseId = id;
                    ap.toolInput = input == null ? "{}" : input.toString();
                    ap.messages = messages.toString();
                    ap.status = "PENDING";
                    ap.riskLevel = riskLevel(name, output);
                    ap.policyResult = "NEEDS_APPROVAL";
                    String reqId = evidence.path("request_id").asText("");
                    ap.requestId = reqId.isBlank() ? null : reqId;
                    ap.evidenceJson = evidence.toString();
                    approvals.save(ap);

                    audit.log(runId, agent.name(), "APPROVAL_REQUESTED",
                            "tool=" + name +
                                    ", policy=NEEDS_APPROVAL" +
                                    ", risk=" + ap.riskLevel +
                                    ", request_id=" + (ap.requestId == null ? "" : ap.requestId) +
                                    ", evidence=" + ap.evidenceJson);

                    return new RunResult(
                            runId,
                            "PENDING_APPROVAL",
                            "Awaiting approval for " + name,
                            ap.id,
                            ap.riskLevel,
                            "NEEDS_APPROVAL",
                            true,
                            agent.autonomy(),
                            evidence
                    );
                }
                case DENY -> {
                    audit.log(runId, agent.name(), "TOOL_DENIED", name);
                    results.add(toolResult(id, "Tool call denied by policy or reviewer.", true));
                }
                case ALLOW -> {
                    audit.log(runId, agent.name(), "TOOL_CALL", name + " " + input + " -> " + output);
                    results.add(toolResult(id, output == null ? "{}" : output.toString(), false));
                }
            }
        }

        ObjectNode userMsg = messages.addObject().put("role", "user");
        userMsg.set("content", results);
        return null;
    }

    private boolean isHighRiskSanctionsHit(String toolName, JsonNode out) {
        if (!"sanctions-check".equals(toolName) || out == null) return false;
        boolean match = out.path("match").asBoolean(false);
        int count = out.path("matches").isArray() ? out.path("matches").size() : 0;
        return match && count > 0;
    }

    private String riskLevel(String toolName, JsonNode out) {
        return isHighRiskSanctionsHit(toolName, out) ? "HIGH" : "LOW";
    }

    private ObjectNode evidenceForApproval(String toolName, JsonNode input, JsonNode out) {
        ObjectNode ev = om.createObjectNode();
        ev.put("tool", toolName);
        ev.set("input", input == null ? om.createObjectNode() : input.deepCopy());

        if (out != null) {
            ev.put("status", out.path("status").asText(""));
            ev.put("match", out.path("match").asBoolean(false));
            ev.put("request_id", out.path("request_id").asText(""));
            ev.put("threshold_used", out.path("threshold_used").asInt(0));
            ev.put("dataset_version", out.path("dataset_version").asText(""));
            ev.put("match_count", out.path("matches").isArray() ? out.path("matches").size() : 0);
        }
        return ev;
    }

    private ObjectNode toolResult(String id, String content, boolean error) {
        ObjectNode n = om.createObjectNode();
        n.put("type", "tool_result").put("tool_use_id", id).put("content", content);
        if (error) n.put("is_error", true);
        return n;
    }

    static String textOf(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : content) if ("text".equals(b.path("type").asText())) sb.append(b.path("text").asText());
        return sb.toString();
    }
}
