package com.agentic.platform.tools.adapters;

import com.agentic.platform.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ValidationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;



@Component
public class DatabaseConnectorAdapter implements ToolHandler {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper om;

    // Query ID -> SQL template (parameterized only)
    private final Map<String, String> queryCatalog = Map.of(
            "find_customer_by_id", "select id, name, email from customers where id = :id",
            "orders_by_customer", "select id, status, total from orders where customer_id = :customerId limit :limit"
    );

    public DatabaseConnectorAdapter(NamedParameterJdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @Override
    public String name() { return "db_lookup_readonly"; }

    @Override
    public String description() { return "Execute allowlisted read-only query by queryId with parameters."; }

    @Override
    public JsonNode inputSchema() {
        ObjectNode s = om.createObjectNode();
        s.put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("queryId").put("type", "string");
        p.putObject("params").put("type", "object");
        s.putArray("required").add("queryId").add("params");
        return s;
    }

    @Override
    public boolean writes() { return false; }

    @Override
    public JsonNode execute(JsonNode input) {
        String queryId = input.path("queryId").asText("");
        JsonNode paramsNode = input.path("params");

        String sql = queryCatalog.get(queryId);
        if (sql == null) {
            throw new ValidationException("DB_QUERY_DENIED: unknown queryId");
        }

        Map<String, Object> params = om.convertValue(paramsNode, om.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);

        ObjectNode out = om.createObjectNode();
        out.put("rowCount", rows.size());
        out.set("rows", om.valueToTree(rows));
        return out;
    }
}