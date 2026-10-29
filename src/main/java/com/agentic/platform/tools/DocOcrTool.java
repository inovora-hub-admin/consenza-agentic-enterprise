package com.agentic.platform.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class DocOcrTool implements ToolHandler {
    private final ObjectMapper om;
    public DocOcrTool(ObjectMapper om) { this.om = om; }

    public String name() { return "doc-ocr"; }
    public String description() { return "Extract fields from an uploaded identity document."; }
    public boolean writes() { return false; }
    public JsonNode inputSchema() {
        return ToolHandler.schema("""
            {"type":"object","properties":{"documentId":{"type":"string"}},"required":["documentId"]}""");
    }
    public JsonNode execute(JsonNode in) {
        // TODO: replace with a real document-processing service
        return om.createObjectNode().put("fullName", "Jane Doe").put("dob", "1990-04-12")
                .put("idNumber", "X1234567").put("expiry", "2030-01-01");
    }
}
