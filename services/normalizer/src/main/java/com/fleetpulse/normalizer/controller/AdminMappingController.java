package com.fleetpulse.normalizer.controller;

import com.fleetpulse.normalizer.mapping.CompiledMapping;
import com.fleetpulse.normalizer.mapping.MappingCompiler;
import com.fleetpulse.normalizer.mapping.MappingRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

/**
 * §9 & §15.6 Admin endpoints for hot-deploying and inspecting OEM mappings without restart.
 */
@RestController
@RequestMapping("/admin/oem-mappings")
public class AdminMappingController {

    private final MappingRegistry mappingRegistry;
    private final MappingCompiler mappingCompiler;

    public AdminMappingController(MappingRegistry mappingRegistry, MappingCompiler mappingCompiler) {
        this.mappingRegistry = mappingRegistry;
        this.mappingCompiler = mappingCompiler;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listMappings() {
        Collection<CompiledMapping> active = mappingRegistry.getAllActive();
        Collection<CompiledMapping> shadow = mappingRegistry.getAllShadow();

        return ResponseEntity.ok(Map.of(
            "active_count", active.size(),
            "active", active,
            "shadow_count", shadow.size(),
            "shadow", shadow
        ));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> registerMapping(@RequestBody String jsonConfig) {
        CompiledMapping compiled = mappingCompiler.compile(jsonConfig);
        mappingRegistry.register(compiled);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "status", "REGISTERED",
            "oem", compiled.oem(),
            "schema_ver", compiled.schemaVer(),
            "mapping_status", compiled.status(),
            "rules_count", compiled.rules().size()
        ));
    }
}
