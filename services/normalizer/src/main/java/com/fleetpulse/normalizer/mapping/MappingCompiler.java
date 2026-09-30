package com.fleetpulse.normalizer.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * §5.2 Mapping DSL Compiler.
 *
 * Compiles declarative JSON OEM mapping configs into fast in-memory CompiledMapping structures.
 */
public class MappingCompiler {

    private static final Logger log = LoggerFactory.getLogger(MappingCompiler.class);
    private final ObjectMapper objectMapper;

    public MappingCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CompiledMapping compile(String jsonConfig) {
        try {
            JsonNode root = objectMapper.readTree(jsonConfig);
            return compile(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to compile mapping JSON: " + e.getMessage(), e);
        }
    }

    public CompiledMapping compile(JsonNode root) {
        String oem = root.path("oem").asText().trim().toUpperCase();
        int schemaVer = root.path("schema_ver").asInt(1);
        String format = root.path("format").asText("json").trim().toLowerCase();
        String delimiter = root.path("delimiter").asText("|");
        String vinField = root.path("vin_field").asText("vin");
        String status = root.path("status").asText("ACTIVE").trim().toUpperCase();

        List<FieldRule> rules = new ArrayList<>();
        JsonNode fieldsNode = root.path("fields");
        if (fieldsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = fieldsNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String canonicalName = entry.getKey();
                JsonNode fNode = entry.getValue();

                String source = fNode.path("source").asText();
                String transform = fNode.path("transform").asText("direct");
                boolean required = fNode.path("required").asBoolean(false);

                Object defaultVal = null;
                if (fNode.has("default")) {
                    JsonNode defNode = fNode.get("default");
                    if (defNode.isArray()) {
                        List<String> list = new ArrayList<>();
                        defNode.forEach(item -> list.add(item.asText()));
                        defaultVal = list;
                    } else if (defNode.isBoolean()) {
                        defaultVal = defNode.asBoolean();
                    } else if (defNode.isInt() || defNode.isLong()) {
                        defaultVal = defNode.asLong();
                    } else if (defNode.isDouble()) {
                        defaultVal = defNode.asDouble();
                    } else if (defNode.isTextual()) {
                        defaultVal = defNode.asText();
                    }
                }

                Double factor = fNode.has("factor") ? fNode.path("factor").asDouble() : null;
                double offset = fNode.path("offset").asDouble(0.0);

                Map<String, Object> enumMapping = new HashMap<>();
                if (fNode.has("mapping") && fNode.get("mapping").isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> mIt = fNode.get("mapping").fields();
                    while (mIt.hasNext()) {
                        Map.Entry<String, JsonNode> mEntry = mIt.next();
                        JsonNode mVal = mEntry.getValue();
                        if (mVal.isBoolean()) {
                            enumMapping.put(mEntry.getKey(), mVal.asBoolean());
                        } else if (mVal.isNumber()) {
                            enumMapping.put(mEntry.getKey(), mVal.numberValue());
                        } else {
                            enumMapping.put(mEntry.getKey(), mVal.asText());
                        }
                    }
                }

                String separator = fNode.path("separator").asText(";");
                Integer index = fNode.has("index") ? fNode.path("index").asInt() : null;

                rules.add(new FieldRule(
                    canonicalName, source, transform, required, defaultVal, factor, offset, enumMapping, separator, index
                ));
            }
        }

        log.info("Compiled mapping for OEM: {} v{} [format: {}, rules: {}, status: {}]",
            oem, schemaVer, format, rules.size(), status);

        return new CompiledMapping(oem, schemaVer, format, delimiter, vinField, rules, status);
    }
}
