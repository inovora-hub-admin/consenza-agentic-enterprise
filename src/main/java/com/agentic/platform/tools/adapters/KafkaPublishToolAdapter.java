package com.agentic.platform.tools.adapters;


import com.agentic.platform.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.ValidationException;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component

public class KafkaPublishToolAdapter implements ToolHandler {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper om;
    private final Set<String> allowedTopics;
    private final int maxPayloadBytes;
    private final Duration sendTimeout;


    public KafkaPublishToolAdapter(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper om,
            @Value("${tools.kafka.allowed-topics}") Set<String> allowedTopics,
            @Value("${tools.kafka.max-payload-bytes:262144}") int maxPayloadBytes,
            @Value("${tools.kafka.send-timeout-ms:5000}") long sendTimeoutMs
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.om = om;
        this.allowedTopics = allowedTopics;
        this.maxPayloadBytes = maxPayloadBytes;
        this.sendTimeout = Duration.ofMillis(sendTimeoutMs);
    }

    @Override
    public String name() { return "kafka_publish"; }

    @Override
    public String description() { return "Publish JSON payload to an allowlisted Kafka topic."; }

    @Override
    public JsonNode inputSchema() {
        ObjectNode s = om.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("topic").put("type", "string");
        p.putObject("key").put("type", "string");
        p.putObject("payload").put("type", "object");
        s.putArray("required").add("topic").add("payload");
        return s;
    }

    @Override
    public boolean writes() { return true; }

    @Override
    public JsonNode execute(JsonNode input) {
        String topic = input.path("topic").asText("");
        String key = input.path("key").asText("");
        JsonNode payload = input.path("payload");

        if (!allowedTopics.contains(topic)) {
            throw toolError("KAFKA_TOPIC_DENIED", "Topic not allowlisted");
        }
        if (payload.isMissingNode() || payload.isNull() || !payload.isObject()) {
            throw toolError("KAFKA_INVALID_PAYLOAD", "Payload must be a JSON object");
        }

        String json = payload.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw toolError("KAFKA_PAYLOAD_TOO_LARGE", "Payload exceeds size limit");
        }

        // Optional per-topic schema checks can be plugged here.
        try {
            SendResult<String, String> result = key.isBlank()
                    ? kafkaTemplate.send(topic, json).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    : kafkaTemplate.send(topic, key, json).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);

            return om.valueToTree(Map.of(
                    "status", "accepted",
                    "topic", topic,
                    "partition", result.getRecordMetadata().partition(),
                    "offset", result.getRecordMetadata().offset()
            ));
        } catch (Exception e) {
            throw toolError("KAFKA_DELIVERY_FAILED", "Kafka delivery failed or timed out");
        }
    }

    private RuntimeException toolError(String code, String message) {
        return new ValidationException(code + ": " + message);
    }
}
