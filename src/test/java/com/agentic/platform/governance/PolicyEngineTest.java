package com.agentic.platform.governance;

import com.agentic.platform.config.AgentSpec;
import com.agentic.platform.governance.PolicyEngine.Decision;
import com.agentic.platform.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyEngineTest {
    private final PolicyEngine policy = new PolicyEngine();

    private static ToolHandler tool(String name, boolean writes) {
        return new ToolHandler() {
            public String name() { return name; }
            public String description() { return name; }
            public JsonNode inputSchema() { return ToolHandler.schema("{\"type\":\"object\"}"); }
            public boolean writes() { return writes; }
            public JsonNode execute(JsonNode in) { return in; }
        };
    }

    private static AgentSpec agent(String autonomy, List<String> approvalFor, List<String> guardrails) {
        return new AgentSpec("a", "", null, "do things", List.of("lookup", "open"), autonomy, approvalFor, guardrails);
    }

    @Test
    void toolNotGrantedIsDenied() {
        assertEquals(Decision.DENY, policy.checkTool(agent("AUTONOMOUS", List.of(), List.of()), tool("other", false)));
    }

    @Test
    void readToolsAreAllowedAtEveryLevel() {
        for (String level : List.of("READ_ONLY", "RECOMMEND", "ACT_WITH_APPROVAL", "AUTONOMOUS"))
            assertEquals(Decision.ALLOW, policy.checkTool(agent(level, List.of(), List.of()), tool("lookup", false)));
    }

    @Test
    void writeToolsFollowTheAutonomyTier() {
        ToolHandler write = tool("open", true);
        assertEquals(Decision.DENY, policy.checkTool(agent("READ_ONLY", List.of(), List.of()), write));
        assertEquals(Decision.DENY, policy.checkTool(agent("RECOMMEND", List.of(), List.of()), write));
        assertEquals(Decision.NEEDS_APPROVAL, policy.checkTool(agent("ACT_WITH_APPROVAL", List.of(), List.of()), write));
        assertEquals(Decision.ALLOW, policy.checkTool(agent("AUTONOMOUS", List.of(), List.of()), write));
    }

    @Test
    void explicitApprovalListOverridesAutonomy() {
        assertEquals(Decision.NEEDS_APPROVAL,
                policy.checkTool(agent("AUTONOMOUS", List.of("lookup"), List.of()), tool("lookup", false)));
    }

    @Test
    void piiMaskHidesEmailsAndLongNumbers() {
        AgentSpec masked = agent("RECOMMEND", List.of(), List.of("pii-mask"));
        assertEquals("Contact [EMAIL] acct [NUMBER]", policy.applyGuardrails(masked, "Contact jane@bank.com acct 1234567890"));
        assertEquals("jane@bank.com", policy.applyGuardrails(agent("RECOMMEND", List.of(), List.of()), "jane@bank.com"));
    }
}
