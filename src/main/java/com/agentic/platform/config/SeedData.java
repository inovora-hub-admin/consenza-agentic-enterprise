package com.agentic.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** Demo agents so the platform works end to end on first start. */
@Component
public class SeedData implements CommandLineRunner {
    private final ConfigService svc;
    private final ObjectMapper om;

    public SeedData(ConfigService svc, ObjectMapper om) { this.svc = svc; this.om = om; }

    @Override
    public void run(String... args) {
        seed("kyc-agent", "Verifies identity, screens sanctions, and opens accounts.",
                "You are a KYC onboarding agent for a bank. Extract identity data with doc-ocr, screen the "
                + "customer with sanctions-check, and only open an account if there is no match. Be concise.",
                "ACT_WITH_APPROVAL", "doc-ocr", "sanctions-check", "open-account");
        seed("credit-agent", "Assesses creditworthiness and recommends approve, decline or refer for loans.",
                "You are a credit analyst. Use credit-check to get the applicant's score and debt-to-income, "
                + "then recommend approve, decline or refer with reasons. You only recommend; you never decide.",
                "RECOMMEND", "credit-check");
    }

    private void seed(String name, String description, String instructions, String autonomy, String... tools) {
        if (!svc.versions("agent", name).isEmpty()) return;
        ObjectNode s = om.createObjectNode();
        s.put("description", description).put("instructions", instructions).put("autonomy", autonomy);
        var arr = s.putArray("tools");
        for (String t : tools) arr.add(t);
        s.putArray("guardrails").add("pii-mask");
        svc.publish("agent", name, svc.createDraft("agent", name, s).version());
    }
}
