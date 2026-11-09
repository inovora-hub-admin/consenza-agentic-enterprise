package com.agentic.platform.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class SanctionsCheckTool implements ToolHandler {
    private final ObjectMapper om;
    private final RestClient restClient;
    private final String apiKey;
    private final int threshold;

    public SanctionsCheckTool(
            ObjectMapper om,
            RestClient.Builder restClientBuilder,
            @Value("${workcheck.base-url:http://13.140.229.92:8030}") String baseUrl,
            @Value("${workcheck.api-key:dev-key}") String apiKey,
            @Value("${workcheck.threshold:75}") int threshold
    ) {
        this.om = om;
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.threshold = threshold;
    }

    public String name() {
        return "sanctions-check";
    }

    public String description() {
        return "Screen a person or company name against sanctions watchlists.";
    }

    public boolean writes() {
        return false;
    }

    public JsonNode inputSchema() {
        return ToolHandler.schema("""
            {
              "type":"object",
              "properties":{
                "name":{"type":"string"},
                "entity_type":{"type":"string","enum":["person","company"],"default":"person"},
                "country":{"type":"string"}
              },
              "required":["name"]
            }""");
    }

    public JsonNode execute(JsonNode in) {
        String name = in.path("name").asText("").trim();
        if (name.isEmpty()) {
            return om.createObjectNode()
                    .put("match", false)
                    .put("status", "ERROR")
                    .put("error", "name is required")
                    .put("source", "workcheck");
        }

        String entityType = in.path("entity_type").asText("person");
        String country = in.path("country").asText("");

        ObjectNode req = om.createObjectNode();
        req.put("name", name);
        req.put("entity_type", entityType);
        if (!country.isBlank()) {
            req.put("country", country);
        }
        req.put("threshold", threshold);

        try {
            JsonNode response = restClient.post()
                    .uri("/v1/screen")
                    .header("X-Api-Key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(req)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                return om.createObjectNode()
                        .put("match", false)
                        .put("status", "ERROR")
                        .put("error", "Empty response from screening service")
                        .put("source", "workcheck");
            }

            ArrayNode matchesIn = response.has("matches") && response.get("matches").isArray()
                    ? (ArrayNode) response.get("matches")
                    : om.createArrayNode();

            ArrayNode matchesOut = om.createArrayNode();
            boolean hit = false;

            for (JsonNode m : matchesIn) {
                int score = m.path("score").asInt(0);
                if (score >= threshold) {
                    hit = true;
                }

                ObjectNode normalized = om.createObjectNode();
                normalized.put("profile_id", m.path("profile_id").asText(""));
                normalized.put("matched_name", m.path("matched_name").asText(""));
                normalized.put("score", score);
                normalized.set("categories", m.path("categories"));
                normalized.set("countries", m.path("countries"));
                normalized.set("reasons", m.path("reasons"));
                normalized.set("details", m.path("details"));
                matchesOut.add(normalized);
            }

            return om.createObjectNode()
                    .put("match", hit)
                    .put("status", "OK")
                    .put("source", "workcheck")
                    .put("request_id", response.path("request_id").asText(""))
                    .put("dataset_version", response.path("dataset_version").asText(""))
                    .put("threshold_used", response.path("threshold_used").asInt(threshold))
                    .set("matches", matchesOut);

        } catch (RestClientException ex) {
            return om.createObjectNode()
                    .put("match", false)
                    .put("status", "ERROR")
                    .put("error", "Screening service call failed: " + ex.getMessage())
                    .put("source", "workcheck");
        }
    }
}
