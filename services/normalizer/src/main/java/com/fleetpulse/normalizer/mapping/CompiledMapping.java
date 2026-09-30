package com.fleetpulse.normalizer.mapping;

import java.util.List;

/**
 * §5.2 Compiled OEM schema mapping.
 */
public record CompiledMapping(
    String oem,
    int schemaVer,
    String format,
    String delimiter,
    String vinField,
    List<FieldRule> rules,
    String status
) {
    public String key() {
        return oem.toUpperCase() + ":" + schemaVer;
    }
}
