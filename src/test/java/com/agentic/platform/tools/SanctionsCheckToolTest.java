package com.agentic.platform.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SanctionsCheckToolTest {
    private final ObjectMapper om = new ObjectMapper();
    private final String apiKey = UUID.randomUUID().toString();
    private HttpServer server;
    private SanctionsCheckTool tool;
    private volatile String method;
    private volatile String path;
    private volatile String contentType;
    private volatile String receivedKey;
    private volatile String requestBody;
    private volatile String response = "{\"matches\":[]}";
    private volatile int status = 200;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().toString();
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            receivedKey = exchange.getRequestHeaders().getFirst("X-Api-Key");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 302) exchange.getResponseHeaders().set("Location", baseUrl() + "/redirected");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        tool = newTool(baseUrl(), apiKey);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void screensPersonAndPreservesCompleteResponse() throws Exception {
        response = """
            {"request_id":"request-1","dataset_version":"test-data","threshold_used":75,
             "matches":[
               {"profile_id":"5135208","matched_name":"Paul MAKONDA","name_type":"primary","score":100,
                "categories":["INDIVIDUAL"],"countries":["TANZANIA"],"reasons":["name 100","country match"],
                "details":{"profile_id":"5135208","entity_type":"person","name":"Paul MAKONDA",
                  "pep_status":"INACTIVE","linked_to":["5135211"],"external_sources":["https://example.org/"],
                  "further_information":"Biography"}},
               {"profile_id":"7159706","matched_name":"MAKANZA,Paulo","name_type":"alias","score":83}
             ],"took_ms":198}
            """;
        JsonNode input = om.readTree("""
            {"name":"Paul Makonda","entity_type":"person","country":"Tanzania"}""");

        JsonNode result = tool.execute(input);

        assertEquals("POST", method);
        assertEquals("/v1/screen", path);
        assertEquals("application/json", contentType);
        assertEquals(apiKey, receivedKey);
        assertEquals(input, om.readTree(requestBody));
        assertEquals(om.readTree(response), ((ObjectNode) result.deepCopy()).without(List.of("match", "source")));
        assertTrue(result.path("match").asBoolean());
        assertEquals("workcheck", result.path("source").asText());
        assertEquals("sanctions-check", tool.name());
        assertFalse(tool.writes());
    }

    @Test
    void nameOnlyDefaultsToPersonAndEmptyMatchesAreClear() throws Exception {
        JsonNode result = tool.execute(om.readTree("{\"name\":\"Jane Doe\"}"));

        assertEquals(om.readTree("{\"name\":\"Jane Doe\",\"entity_type\":\"person\"}"), om.readTree(requestBody));
        assertFalse(result.path("match").asBoolean());
    }

    @Test
    void forwardsExplicitEntityType() throws Exception {
        tool.execute(om.readTree("{\"name\":\"Example Ltd\",\"entity_type\":\"company\"}"));

        assertEquals("company", om.readTree(requestBody).path("entity_type").asText());
    }

    @Test
    void retrievesProfileIncludingAliasesWithoutScreening() throws Exception {
        response = """
            {"profile_id":"5135208","dataset_version":"test-data",
             "details":{"profile_id":"5135208","name":"Paul MAKONDA","linked_to":["5135211"]},
             "aliases":[{"name":"MAKONDA,Paul Christian","type":"alias"}]}
            """;

        JsonNode result = tool.execute(om.readTree("{\"profile_id\":\"5135208\"}"));

        assertEquals("GET", method);
        assertEquals("/v1/profiles/5135208", path);
        assertEquals(apiKey, receivedKey);
        assertEquals("", requestBody);
        assertEquals(om.readTree(response), result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "[]", "{}", "{\"name\":\" \"}", "{\"name\":123}",
            "{\"name\":\"Jane\",\"country\":null}", "{\"name\":\"Jane\",\"entity_type\":\"\"}",
            "{\"profile_id\":\"\"}", "{\"profile_id\":\"../screen\"}", "{\"profile_id\":\"https://example.org\"}",
            "{\"name\":\"Jane\",\"profile_id\":\"5135208\"}", "{\"profile_id\":\"5135208\",\"country\":\"Tanzania\"}"
    })
    void rejectsInvalidInputBeforeSendingRequest(String input) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> tool.execute(om.readTree(input)));
        assertNull(method);
    }

    @Test
    void missingConfigurationFailsClosed() throws Exception {
        JsonNode input = om.readTree("{\"name\":\"Jane\"}");
        assertThrows(IllegalStateException.class, () -> newTool("", apiKey).execute(input));
        assertThrows(IllegalStateException.class, () -> newTool(baseUrl(), "").execute(input));
        assertNull(method);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 404, 429, 500})
    void upstreamErrorsDoNotReturnClearResultsOrExposeResponseBodies(int errorStatus) throws Exception {
        status = errorStatus;
        response = "{\"error\":\"upstream private information\"}";
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> tool.execute(om.readTree("{\"name\":\"Jane\"}")));
        assertEquals("WorkCheck request failed", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "null", "[]", "{}", "{\"matches\":null}", "{\"matches\":{}}"})
    void malformedScreeningResponsesDoNotBecomeClearResults(String body) throws Exception {
        response = body;
        assertThrows(IllegalStateException.class, () -> tool.execute(om.readTree("{\"name\":\"Jane\"}")));
    }

    @Test
    void malformedProfileFails() throws Exception {
        response = "{\"profile_id\":\"5135208\"}";
        assertThrows(IllegalStateException.class, () -> tool.execute(om.readTree("{\"profile_id\":\"5135208\"}")));
    }

    @Test
    void doesNotFollowRedirectsWithApiKey() throws Exception {
        status = 302;
        response = "{\"details\":{},\"matches\":[]}";
        assertThrows(IllegalStateException.class, () -> tool.execute(om.readTree("{\"profile_id\":\"5135208\"}")));
        assertEquals("/v1/profiles/5135208", path);
    }

    @Test
    void connectionFailureDoesNotBecomeClearResult() throws Exception {
        server.stop(0);
        assertThrows(IllegalStateException.class, () -> tool.execute(om.readTree("{\"name\":\"Jane\"}")));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private SanctionsCheckTool newTool(String baseUrl, String key) {
        return new SanctionsCheckTool(om, RestClient.builder(), baseUrl, key, 1000, 1000);
    }
}
