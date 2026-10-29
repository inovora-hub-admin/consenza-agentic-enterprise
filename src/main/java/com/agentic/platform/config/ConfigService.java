package com.agentic.platform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ConfigService {
    private static final Set<String> KINDS = Set.of("agent");
    private static final Set<String> AUTONOMY = Set.of("READ_ONLY", "RECOMMEND", "ACT_WITH_APPROVAL", "AUTONOMOUS");

    private final ConfigRepository repo;
    private final ObjectMapper om;

    public ConfigService(ConfigRepository repo, ObjectMapper om) {
        this.repo = repo;
        this.om = om;
    }

    @Transactional
    public ConfigView createDraft(String kind, String name, JsonNode spec) {
        if (!KINDS.contains(kind)) throw bad("Unknown kind: " + kind);
        ObjectNode copy = spec.deepCopy();
        copy.put("name", name);
        validateAgent(copy);
        int next = repo.findByKindAndNameOrderByVersionDesc(kind, name).stream()
                .findFirst().map(c -> c.version + 1).orElse(1);
        ConfigEntity e = new ConfigEntity();
        e.kind = kind; e.name = name; e.version = next; e.status = "DRAFT"; e.spec = copy.toString();
        return view(repo.save(e));
    }

    /** Publishing any version (including an old one) is also how rollback works. */
    @Transactional
    public ConfigView publish(String kind, String name, int version) {
        List<ConfigEntity> all = repo.findByKindAndNameOrderByVersionDesc(kind, name);
        ConfigEntity target = all.stream().filter(c -> c.version == version).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such version"));
        all.stream().filter(c -> "PUBLISHED".equals(c.status)).forEach(c -> c.status = "ARCHIVED");
        target.status = "PUBLISHED";
        repo.saveAll(all);
        return view(target);
    }

    public List<ConfigView> latest(String kind) {
        return repo.findByKind(kind).stream()
                .collect(Collectors.toMap(c -> c.name, c -> c, (a, b) -> a.version >= b.version ? a : b))
                .values().stream().map(this::view).toList();
    }

    public List<ConfigView> versions(String kind, String name) {
        return repo.findByKindAndNameOrderByVersionDesc(kind, name).stream().map(this::view).toList();
    }

    public Optional<AgentSpec> publishedAgent(String name) {
        return repo.findFirstByKindAndNameAndStatus("agent", name, "PUBLISHED").map(this::toAgent);
    }

    public List<AgentSpec> publishedAgents() {
        return repo.findByKindAndStatus("agent", "PUBLISHED").stream().map(this::toAgent).toList();
    }

    private void validateAgent(JsonNode spec) {
        AgentSpec a = toAgent(spec);
        if (a.instructions() == null || a.instructions().isBlank()) throw bad("instructions is required");
        if (!AUTONOMY.contains(a.autonomy())) throw bad("autonomy must be one of " + AUTONOMY);
    }

    private AgentSpec toAgent(ConfigEntity e) {
        try { return toAgent(om.readTree(e.spec)); } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private AgentSpec toAgent(JsonNode n) {
        try { return om.treeToValue(n, AgentSpec.class); } catch (Exception ex) { throw bad("Invalid agent spec: " + ex.getMessage()); }
    }

    private ConfigView view(ConfigEntity e) {
        try { return new ConfigView(e.kind, e.name, e.version, e.status, om.readTree(e.spec), e.createdAt); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private ResponseStatusException bad(String msg) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg); }
}
