package com.agentic.platform.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OpenAccountTool implements ToolHandler {
    private final ObjectMapper om;
    public OpenAccountTool(ObjectMapper om) { this.om = om; }

    public String name() { return "open-account"; }
    public String description() { return "Open a new bank account for a verified customer."; }
    public boolean writes() { return true; }
    public JsonNode inputSchema() {
        return ToolHandler.schema("""
            {"type":"object","properties":{"customerName":{"type":"string"},"product":{"type":"string"}},
             "required":["customerName","product"]}""");
    }
    public JsonNode execute(JsonNode in) {
        // TODO: call core banking through an adapter
        return om.createObjectNode().put("accountId", "ACC-" + UUID.randomUUID().toString().substring(0, 8))
                .put("status", "OPENED");
    }
}
