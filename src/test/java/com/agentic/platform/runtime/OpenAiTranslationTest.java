package com.agentic.platform.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiTranslationTest {
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void toolUseRoundTripBecomesOpenAiToolCallsAndToolMessages() throws Exception {
        ArrayNode msgs = (ArrayNode) om.readTree("""
            [{"role":"user","content":"Onboard Jane"},
             {"role":"assistant","content":[{"type":"text","text":"checking"},
               {"type":"tool_use","id":"call_1","name":"sanctions-check","input":{"name":"Jane"}}]},
             {"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"{\\"match\\":false}"}]}]""");
        ArrayNode out = OpenAiModelClient.toOpenAiMessages(om, "be brief", msgs);
        assertEquals(4, out.size());   // system, user, assistant(tool_calls), tool
        assertEquals("system", out.get(0).path("role").asText());
        assertEquals("user", out.get(1).path("role").asText());
        JsonNode assistant = out.get(2);
        assertEquals("checking", assistant.path("content").asText());
        assertEquals("call_1", assistant.path("tool_calls").get(0).path("id").asText());
        assertEquals("{\"name\":\"Jane\"}", assistant.path("tool_calls").get(0).path("function").path("arguments").asText());
        assertEquals("tool", out.get(3).path("role").asText());
        assertEquals("call_1", out.get(3).path("tool_call_id").asText());
    }

    @Test
    void toolCallResponseBecomesToolUseBlocks() throws Exception {
        JsonNode resp = om.readTree("""
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_9","type":"function","function":{"name":"credit-check","arguments":"{\\"applicantName\\":\\"Jane\\"}"}}]}}]}""");
        JsonNode out = OpenAiModelClient.toAnthropicShape(om, resp);
        assertEquals("tool_use", out.path("stop_reason").asText());
        assertEquals("credit-check", out.path("content").get(0).path("name").asText());
        assertEquals("Jane", out.path("content").get(0).path("input").path("applicantName").asText());
    }

    @Test
    void plainAnswerBecomesTextAndEndTurn() throws Exception {
        JsonNode out = OpenAiModelClient.toAnthropicShape(om,
                om.readTree("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Done.\"}}]}"));
        assertEquals("end_turn", out.path("stop_reason").asText());
        assertEquals("Done.", AgentRunner.textOf(out.path("content")));
    }

    @Test
    void malformedToolArgumentsDoNotCrash() throws Exception {
        JsonNode resp = om.readTree("""
            {"choices":[{"message":{"content":null,"tool_calls":[
              {"id":"c","type":"function","function":{"name":"x","arguments":"{not json"}}]}}]}""");
        assertTrue(OpenAiModelClient.toAnthropicShape(om, resp).path("content").get(0).path("input").isObject());
    }
}
