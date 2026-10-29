package com.agentic.platform.governance;

import com.agentic.platform.config.AgentSpec;
import com.agentic.platform.tools.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PolicyEngine {
    public enum Decision { ALLOW, DENY, NEEDS_APPROVAL }

    public static final List<String> GUARDRAILS = List.of("pii-mask");

    /** Tiered autonomy: read-only -> recommend -> act with approval -> autonomous. */
    public Decision checkTool(AgentSpec agent, ToolHandler tool) {
        if (!agent.tools().contains(tool.name())) return Decision.DENY;
        if (agent.approvalRequiredFor().contains(tool.name())) return Decision.NEEDS_APPROVAL;
        if (!tool.writes()) return Decision.ALLOW;
        return switch (agent.autonomy()) {
            case "ACT_WITH_APPROVAL" -> Decision.NEEDS_APPROVAL;
            case "AUTONOMOUS" -> Decision.ALLOW;
            default -> Decision.DENY;          // READ_ONLY, RECOMMEND
        };
    }

    public String applyGuardrails(AgentSpec agent, String text) {
        if (text == null) return "";
        if (agent.guardrails().contains("pii-mask")) {
            text = text.replaceAll("[\\w.+-]+@[\\w-]+\\.[\\w.]+", "[EMAIL]")
                       .replaceAll("\\b\\d{9,}\\b", "[NUMBER]");
        }
        return text;
    }
}
