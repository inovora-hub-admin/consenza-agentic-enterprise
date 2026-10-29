package com.agentic.platform.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Control-plane API: this is what your UI calls. */
@RestController
@RequestMapping("/api/config")
public class ConfigController {
    private final ConfigService svc;

    public ConfigController(ConfigService svc) { this.svc = svc; }

    @GetMapping("/{kind}")
    public List<ConfigView> list(@PathVariable String kind) { return svc.latest(kind); }

    @GetMapping("/{kind}/{name}/versions")
    public List<ConfigView> versions(@PathVariable String kind, @PathVariable String name) { return svc.versions(kind, name); }

    @PostMapping("/{kind}/{name}")
    public ConfigView createDraft(@PathVariable String kind, @PathVariable String name, @RequestBody JsonNode spec) {
        return svc.createDraft(kind, name, spec);
    }

    @PostMapping("/{kind}/{name}/versions/{version}/publish")
    public ConfigView publish(@PathVariable String kind, @PathVariable String name, @PathVariable int version) {
        return svc.publish(kind, name, version);
    }
}
