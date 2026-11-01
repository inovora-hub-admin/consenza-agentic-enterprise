package com.agentic.platform.tools.adapters;


import com.agentic.platform.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import java.util.Set;

@Component
public class ExternalApiToolAdapter implements ToolHandler {

    private final ObjectMapper om;
    private final RestTemplate restTemplate;
    private final Set<String> allowedHosts;
    private final Set<String> allowedMethods;
    private final Set<String> allowedPathPrefixes;

    public ExternalApiToolAdapter(
            ObjectMapper om,
            RestTemplateBuilder builder,
            @Value("${tools.external.allowed-hosts}") Set<String> allowedHosts,
            @Value("${tools.external.allowed-methods:GET,POST}") Set<String> allowedMethods,
            @Value("${tools.external.allowed-path-prefixes:/}") Set<String> allowedPathPrefixes,
            @Value("${tools.external.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${tools.external.read-timeout-ms:5000}") long readTimeoutMs
    ) {
        this.om = om;
        this.allowedHosts = allowedHosts;
        this.allowedMethods = allowedMethods;
        this.allowedPathPrefixes = allowedPathPrefixes;
        this.restTemplate = new RestTemplateBuilder()
                .setConnectTimeout(java.time.Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(java.time.Duration.ofMillis(readTimeoutMs))
                .build();
    }

    @Override
    public String name() { return "external_api_call"; }

    @Override
    public String description() { return "Call approved external APIs with allowlist and SSRF protections."; }

    @Override
    public JsonNode inputSchema() {
        ObjectNode s = om.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("url").put("type", "string");
        p.putObject("method").put("type", "string");
        p.putObject("body").put("type", "object");
        s.putArray("required").add("url").add("method");
        return s;
    }

    @Override
    public boolean writes() { return true; }

    @Override
    @Retry(name = "toolExternalApi")
    @CircuitBreaker(name = "toolExternalApi")
    public JsonNode execute(JsonNode input) {
        URI uri = URI.create(input.path("url").asText(""));
        String method = input.path("method").asText("GET").toUpperCase();

        validateMethod(method);
        validateUri(uri);

        HttpMethod httpMethod = HttpMethod.valueOf(method);
        String body = input.has("body") ? input.get("body").toString() : null;

        ResponseEntity<String> resp = restTemplate.exchange(
                uri,
                httpMethod,
                new HttpEntity<>(body == null ? "" : body, new HttpHeaders() {{ setContentType(MediaType.APPLICATION_JSON); }}),
                String.class
        );

        return om.valueToTree(Map.of(
                "status", resp.getStatusCode().value(),
                "body", resp.getBody() == null ? "" : resp.getBody()
        ));
    }

    private void validateMethod(String method) {
        if (!allowedMethods.contains(method)) {
            throw new ValidationException("EXT_METHOD_DENIED: method not allowlisted");
        }
    }

    private void validateUri(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ValidationException("EXT_SCHEME_DENIED: only https allowed");
        }
        String host = uri.getHost();
        if (host == null || !allowedHosts.contains(host)) {
            throw new ValidationException("EXT_HOST_DENIED: host not allowlisted");
        }
        String path = uri.getPath() == null ? "/" : uri.getPath();
        boolean ok = allowedPathPrefixes.stream().anyMatch(path::startsWith);
        if (!ok) {
            throw new ValidationException("EXT_PATH_DENIED: path not allowlisted");
        }
        // SSRF guard: block private/link-local/loopback targets
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                throw new ValidationException("EXT_SSRF_BLOCKED: private/local address denied");
            }
        } catch (Exception e) {
            throw new ValidationException("EXT_DNS_INVALID: host resolution failed");
        }
    }
}