package com.agentic.platform.tools;

import com.agentic.platform.governance.PolicyEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Lets the UI build dropdowns: which tools, autonomy levels and guardrails exist. */
@RestController
@RequestMapping("/api")
public class CatalogController {
    private final ToolGateway gateway;
    private final ObjectMapper om;

    public CatalogController(ToolGateway gateway, ObjectMapper om) { this.gateway = gateway; this.om = om; }

    @GetMapping("/tools")
    public ArrayNode tools() {
        ArrayNode out = om.createArrayNode();
        gateway.all().forEach(t -> out.addObject().put("name", t.name()).put("description", t.description())
                .put("writes", t.writes()).set("inputSchema", t.inputSchema()));
        return out;
    }

    @GetMapping("/meta")
    public Map<String, List<String>> meta() {
        return Map.of("autonomy", List.of("READ_ONLY", "RECOMMEND", "ACT_WITH_APPROVAL", "AUTONOMOUS"),
                "guardrails", PolicyEngine.GUARDRAILS);
    }
}
