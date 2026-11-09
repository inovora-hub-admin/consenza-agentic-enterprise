package com.agentic.platform.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class SanctionsCheckTool implements ToolHandler {
    private final ObjectMapper om;
    private final RestClient client;
    private final boolean configured;

    public SanctionsCheckTool(ObjectMapper om, RestClient.Builder builder,
                              @Value("${tools.workcheck.base-url:}") String baseUrl,
                              @Value("${tools.workcheck.api-key:}") String apiKey,
                              @Value("${tools.workcheck.connect-timeout-ms:3000}") int connectTimeoutMs,
                              @Value("${tools.workcheck.read-timeout-ms:5000}") int readTimeoutMs) {
        this.om = om;
        this.configured = !baseUrl.isBlank() && !apiKey.isBlank();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.client = builder.baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Api-Key", apiKey)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String name() { return "sanctions-check"; }
    public String description() {
        return "Screen a person or company with WorkCheck using name, entity_type (default person), and optional country; "
                + "or retrieve a full profile and aliases using only profile_id.";
    }
    public boolean writes() { return false; }
    public JsonNode inputSchema() {
        return ToolHandler.schema("""
            {"type":"object","properties":{
              "name":{"type":"string","minLength":1},
              "entity_type":{"type":"string","minLength":1,"default":"person"},
              "country":{"type":"string","minLength":1},
              "profile_id":{"type":"string","pattern":"^[A-Za-z0-9_-]+$"}
            },"oneOf":[{"required":["name"]},{"required":["profile_id"]}],
            "additionalProperties":false}""");
    }
    public JsonNode execute(JsonNode in) {
        if (in == null || !in.isObject() || in.has("name") == in.has("profile_id")) {
            throw new IllegalArgumentException("Provide either name for screening or profile_id for lookup");
        }
        boolean lookup = in.has("profile_id");
        ObjectNode request = om.createObjectNode();
        String profileId = null;
        if (lookup) {
            profileId = text(in, "profile_id");
            if (!profileId.matches("[A-Za-z0-9_-]+") || in.has("country") || in.has("entity_type")) {
                throw new IllegalArgumentException("Profile lookup requires only a valid profile_id");
            }
        } else {
            request.put("name", text(in, "name"));
            request.put("entity_type", in.has("entity_type") ? text(in, "entity_type") : "person");
            if (in.has("country")) request.put("country", text(in, "country"));
        }
        if (!configured) {
            throw new IllegalStateException("WorkCheck base URL and API key must be configured");
        }
        JsonNode response;
        try {
            RestClient.RequestHeadersSpec<?> call = lookup
                    ? client.get().uri("/v1/profiles/{profileId}", profileId)
                    : client.post().uri("/v1/screen").contentType(MediaType.APPLICATION_JSON)
                            .body(request);
            response = call.retrieve().onStatus(status -> !status.is2xxSuccessful(), (requestSpec, upstream) -> {
                throw new IllegalStateException("WorkCheck request failed");
            }).body(JsonNode.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("WorkCheck request failed");
        }
        if (response == null || !response.isObject()
                || (lookup ? !response.path("details").isObject() : !response.path("matches").isArray())) {
            throw new IllegalStateException("Invalid WorkCheck response");
        }
        if (!lookup) {
            ((ObjectNode) response).put("match", !response.path("matches").isEmpty()).put("source", "workcheck");
        }
        return response;
    }

    private String text(JsonNode in, String field) {
        JsonNode value = in.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.asText().trim();
    }
}
