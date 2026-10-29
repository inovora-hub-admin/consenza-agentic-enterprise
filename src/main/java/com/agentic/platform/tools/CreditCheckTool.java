package com.agentic.platform.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class CreditCheckTool implements ToolHandler {
    private final ObjectMapper om;
    public CreditCheckTool(ObjectMapper om) { this.om = om; }

    public String name() { return "credit-check"; }
    public String description() { return "Retrieve an applicant's credit score and debt-to-income ratio."; }
    public boolean writes() { return false; }
    public JsonNode inputSchema() {
        return ToolHandler.schema("""
            {"type":"object","properties":{"applicantName":{"type":"string"},"amount":{"type":"number"}},
             "required":["applicantName"]}""");
    }
    public JsonNode execute(JsonNode in) {
        // TODO: replace with a credit bureau adapter
        int h = Math.abs(in.path("applicantName").asText("").hashCode());
        return om.createObjectNode().put("score", 600 + h % 200)
                .put("debtToIncome", 0.2 + (h % 20) / 100.0).put("source", "mock-bureau");
    }
}
