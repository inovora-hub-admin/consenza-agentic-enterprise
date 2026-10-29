package com.agentic.platform.runtime;

import com.agentic.platform.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;

/** Model gateway seam: add routing, fallbacks, cost caps or on-prem models behind this interface. */
public interface ModelClient {
    JsonNode complete(String model, String system, ArrayNode messages, List<ToolHandler> tools);
}
