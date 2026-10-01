package com.fleetpulse.normalizer.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetpulse.normalizer.mapping.CompiledMapping;
import com.fleetpulse.normalizer.mapping.FieldRule;
import com.fleetpulse.normalizer.mapping.MappingRegistry;
import com.fleetpulse.normalizer.model.CanonicalTelemetryEvent;
import com.fleetpulse.normalizer.model.DlqEvent;
import com.fleetpulse.normalizer.model.DlqReason;
import com.fleetpulse.normalizer.model.VehicleMetadata;
import com.fleetpulse.normalizer.registry.VehicleRegistry;
import com.fleetpulse.normalizer.validation.DtcValidator;
import com.fleetpulse.normalizer.validation.VinValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * §4.5 & §5.2 High-Throughput Normalizer Engine.
 *
 * Compiles and executes declarative OEM mappings, validates VINs and DTCs,
 * resolves vehicle pseudonyms and tenants, computes deterministic event IDs,
 * and routes failures to DLQ.
 */
@Service
public class NormalizerEngine {

    private static final Logger log = LoggerFactory.getLogger(NormalizerEngine.class);

    public static final int QUALITY_GPS_SUSPECT = 1;
    public static final int QUALITY_LATE = 2;
    public static final int QUALITY_IMPUTED = 4;
    public static final int QUALITY_VIN_WARN = 8;
    public static final int QUALITY_CLOCK_SKEW = 16;

    private final MappingRegistry mappingRegistry;
    private final VehicleRegistry vehicleRegistry;
    private final ObjectMapper objectMapper;
    private final boolean strictVin;

    @org.springframework.beans.factory.annotation.Autowired
    public NormalizerEngine(
        MappingRegistry mappingRegistry,
        VehicleRegistry vehicleRegistry,
        ObjectMapper objectMapper,
        @Value("${normalizer.strict-vin:false}") boolean strictVin
    ) {
        this.mappingRegistry = mappingRegistry;
        this.vehicleRegistry = vehicleRegistry;
        this.objectMapper = objectMapper;
        this.strictVin = strictVin;
    }

    public NormalizerEngine(
        MappingRegistry mappingRegistry,
        VehicleRegistry vehicleRegistry,
        ObjectMapper objectMapper
    ) {
        this(mappingRegistry, vehicleRegistry, objectMapper, false);
    }

    public NormalizationResult normalize(
        byte[] rawBytes,
        String oemHeader,
        Integer schemaVerHeader,
        long recvTs,
        Map<String, String> headers
    ) {
        String rawString = new String(rawBytes, StandardCharsets.UTF_8);
        return normalize(rawString, oemHeader, schemaVerHeader, recvTs, headers);
    }

    public NormalizationResult normalize(
        String rawPayload,
        String oemHeader,
        Integer schemaVerHeader,
        long recvTs,
        Map<String, String> headers
    ) {
        if (headers == null) {
            headers = Collections.emptyMap();
        }

        // 1. Detect OEM and Schema Version
        String oem = oemHeader != null ? oemHeader.trim().toUpperCase() : null;
        Integer schemaVer = schemaVerHeader;

        // Auto-detect Draco pipe format
        if (oem == null && rawPayload.startsWith("D1|")) {
            oem = "D";
            schemaVer = 1;
        }

        // Auto-detect JSON schema version (e.g. Echo {"v": 1} or {"v": 2})
        JsonNode jsonNode = null;
        if (rawPayload.startsWith("{")) {
            try {
                jsonNode = objectMapper.readTree(rawPayload);
                if (schemaVer == null && jsonNode.has("v")) {
                    schemaVer = jsonNode.get("v").asInt();
                }
            } catch (Exception e) {
                return NormalizationResult.dlq(new DlqEvent(
                    "unknown", oem != null ? oem : "UNKNOWN",
                    DlqReason.MALFORMED, "Malformed JSON payload: " + e.getMessage(),
                    rawPayload, headers, recvTs
                ));
            }
        }

        if (oem == null || oem.isBlank()) {
            return NormalizationResult.dlq(new DlqEvent(
                "unknown", "UNKNOWN",
                DlqReason.UNKNOWN_OEM, "Missing OEM identifier header or prefix",
                rawPayload, headers, recvTs
            ));
        }

        Optional<CompiledMapping> mappingOpt = mappingRegistry.getActive(oem, schemaVer);
        if (mappingOpt.isEmpty()) {
            return NormalizationResult.dlq(new DlqEvent(
                "unknown", oem,
                DlqReason.UNKNOWN_OEM, "No active mapping configuration for OEM: " + oem + " v" + schemaVer,
                rawPayload, headers, recvTs
            ));
        }
        CompiledMapping mapping = mappingOpt.get();

        // 2. Extract VIN
        String rawVin = extractVin(rawPayload, jsonNode, mapping);
        if (rawVin == null || rawVin.isBlank()) {
            return NormalizationResult.dlq(new DlqEvent(
                "unknown", oem,
                DlqReason.MALFORMED, "Failed to extract VIN with vin_field: " + mapping.vinField(),
                rawPayload, headers, recvTs
            ));
        }
        String vin = rawVin.trim().toUpperCase();

        // 3. Validate VIN (§7.1)
        VinValidator.Status vinStatus = VinValidator.validate(vin);
        int quality = 0;
        if (vinStatus == VinValidator.Status.INVALID_FORMAT) {
            return NormalizationResult.dlq(new DlqEvent(
                vin, oem,
                DlqReason.INVALID_VIN, "Invalid VIN format: " + vin,
                rawPayload, headers, recvTs
            ));
        } else if (vinStatus == VinValidator.Status.CHECK_DIGIT_WARN) {
            if (strictVin) {
                return NormalizationResult.dlq(new DlqEvent(
                    vin, oem,
                    DlqReason.INVALID_VIN, "VIN check digit mismatch (strict mode): " + vin,
                    rawPayload, headers, recvTs
                ));
            }
            quality |= QUALITY_VIN_WARN;
        }

        // 4. Resolve Vehicle Identity from Registry (§4.3, §5.3)
        Optional<VehicleMetadata> vehicleOpt = vehicleRegistry.resolve(vin);
        if (vehicleOpt.isEmpty()) {
            return NormalizationResult.dlq(new DlqEvent(
                vin, oem,
                DlqReason.UNKNOWN_VEHICLE, "Vehicle VIN not found in inventory registry: " + vin,
                rawPayload, headers, recvTs
            ));
        }
        VehicleMetadata vehicle = vehicleOpt.get();

        // 5. Apply Field Mapping Rules
        Map<String, Object> canonicalFields = new HashMap<>();
        String[] delimitedParts = null;
        if ("delimited".equalsIgnoreCase(mapping.format())) {
            delimitedParts = rawPayload.split("\\" + mapping.delimiter());
        }

        for (FieldRule rule : mapping.rules()) {
            Object rawVal = extractRawValue(rule, rawPayload, jsonNode, delimitedParts, mapping);

            if (rawVal == null && rule.required()) {
                return NormalizationResult.dlq(new DlqEvent(
                    vin, oem,
                    DlqReason.MAPPING_ERROR, "Required field missing: " + rule.canonicalName(),
                    rawPayload, headers, recvTs
                ));
            }

            Object transformed = transformValue(rawVal, rule);
            if (transformed != null) {
                canonicalFields.put(rule.canonicalName(), transformed);
            }
        }

        // 6. Mandatory Canonical Fields
        Long tsEvent = (Long) canonicalFields.get("ts_event");
        if (tsEvent == null) {
            return NormalizationResult.dlq(new DlqEvent(
                vin, oem,
                DlqReason.MAPPING_ERROR, "Missing canonical ts_event after transformation",
                rawPayload, headers, recvTs
            ));
        }

        // Check clock skew (§7.7): event > 10 min in future or > 7 days in past
        long diff = Math.abs(recvTs - tsEvent);
        if (tsEvent > recvTs + 600_000L || tsEvent < recvTs - 7L * 86_400_000L) {
            quality |= QUALITY_CLOCK_SKEW;
        }

        // 7. Extract & Validate DTCs
        @SuppressWarnings("unchecked")
        List<String> rawDtcs = (List<String>) canonicalFields.get("dtc");
        List<String> validDtcs = DtcValidator.sanitizeAndValidate(rawDtcs);

        // 8. Deterministic Event ID (§4.5)
        Long seq = canonicalFields.containsKey("seq") ? ((Number) canonicalFields.get("seq")).longValue() : null;
        String eventId = computeEventId(vehicle.vehiclePid(), tsEvent, seq, rawPayload);

        // 9. Build CanonicalTelemetryEvent
        CanonicalTelemetryEvent event = new CanonicalTelemetryEvent(
            eventId,
            vehicle.vehiclePid(),
            vehicle.tenantId(),
            oem,
            mapping.schemaVer(),
            tsEvent,
            recvTs,
            seq,
            (Integer) canonicalFields.get("lat_e6"),
            (Integer) canonicalFields.get("lon_e6"),
            (Integer) canonicalFields.get("heading_deg"),
            toFloat(canonicalFields.get("speed_kmh")),
            toDouble(canonicalFields.get("odo_km")),
            (Boolean) canonicalFields.get("ignition"),
            toFloat(canonicalFields.get("fuel_pct")),
            toFloat(canonicalFields.get("soc_pct")),
            toFloat(canonicalFields.get("batt_voltage_v")),
            (String) canonicalFields.get("charge_state"),
            toFloat(canonicalFields.get("charge_kw")),
            toFloat(canonicalFields.get("coolant_c")),
            (Integer) canonicalFields.get("rpm"),
            validDtcs,
            (String) canonicalFields.get("evt"),
            quality
        );

        // 10. Shadow mode evaluation (§5.2)
        Optional<CompiledMapping> shadowOpt = mappingRegistry.getShadow(oem, mapping.schemaVer());
        if (shadowOpt.isPresent()) {
            CompiledMapping shadowMapping = shadowOpt.get();
            try {
                // Execute shadow mapping for validation and metrics without emitting
                Map<String, Object> shadowFields = new HashMap<>();
                for (FieldRule sRule : shadowMapping.rules()) {
                    Object sRaw = extractRawValue(sRule, rawPayload, jsonNode, null, shadowMapping);
                    Object sTrans = transformValue(sRaw, sRule);
                    if (sTrans != null) shadowFields.put(sRule.canonicalName(), sTrans);
                }
                log.debug("Shadow evaluation OEM {} v{} completed with {} fields", oem, shadowMapping.schemaVer(), shadowFields.size());
            } catch (Exception ex) {
                log.warn("Shadow mapping execution failed for OEM {}: {}", oem, ex.getMessage());
            }
        }

        return NormalizationResult.success(event);
    }

    private String extractVin(String rawPayload, JsonNode jsonNode, CompiledMapping mapping) {
        if ("delimited".equalsIgnoreCase(mapping.format())) {
            String[] parts = rawPayload.split("\\" + mapping.delimiter());
            try {
                int idx = Integer.parseInt(mapping.vinField());
                if (idx >= 0 && idx < parts.length) {
                    return parts[idx].trim();
                }
            } catch (NumberFormatException ignored) {}
            return null;
        }

        if (jsonNode != null) {
            JsonNode vNode = getNestedNode(jsonNode, mapping.vinField());
            if (vNode != null && !vNode.isNull()) {
                return vNode.asText();
            }
        }
        return null;
    }

    private Object extractRawValue(FieldRule rule, String rawPayload, JsonNode jsonNode, String[] delimitedParts, CompiledMapping mapping) {
        if ("delimited".equalsIgnoreCase(mapping.format())) {
            if (delimitedParts == null) {
                delimitedParts = rawPayload.split("\\" + mapping.delimiter());
            }
            try {
                int idx = Integer.parseInt(rule.source());
                if (idx >= 0 && idx < delimitedParts.length) {
                    String part = delimitedParts[idx].trim();
                    return part.isEmpty() ? null : part;
                }
            } catch (NumberFormatException ignored) {}
            return null;
        }

        if ("signal_list".equalsIgnoreCase(mapping.format()) && jsonNode != null) {
            // First check top-level properties (e.g. tsMs, deviceId)
            JsonNode topNode = jsonNode.get(rule.source());
            if (topNode != null && !topNode.isNull()) {
                return extractJsonValue(topNode);
            }

            // Next check signals array
            JsonNode signalsNode = jsonNode.get("signals");
            if (signalsNode != null && signalsNode.isArray()) {
                for (JsonNode item : signalsNode) {
                    if (item.has("n") && rule.source().equals(item.get("n").asText())) {
                        return extractJsonValue(item.get("v"));
                    }
                }
            }

            // Special flags array (e.g. ["HB"])
            if ("flags".equals(rule.source())) {
                JsonNode flagsNode = jsonNode.get("flags");
                if (flagsNode != null && flagsNode.isArray() && !flagsNode.isEmpty()) {
                    return flagsNode.get(0).asText();
                }
            }
            return null;
        }

        // Standard JSON format
        if (jsonNode != null) {
            JsonNode valNode = getNestedNode(jsonNode, rule.source());
            if (valNode != null && !valNode.isNull()) {
                return extractJsonValue(valNode);
            }
        }

        return null;
    }

    private Object extractJsonValue(JsonNode node) {
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble()) return node.asDouble();
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(extractJsonValue(item)));
            return list;
        }
        return node.asText();
    }

    private JsonNode getNestedNode(JsonNode root, String path) {
        if (root == null || path == null || path.isEmpty()) return null;
        String[] parts = path.split("\\.");
        JsonNode curr = root;
        for (String part : parts) {
            if (curr == null || !curr.isObject()) return null;
            curr = curr.get(part);
        }
        return curr;
    }

    private Object transformValue(Object rawVal, FieldRule rule) {
        if (rawVal == null) {
            return rule.defaultVal();
        }

        String t = rule.transform();
        switch (t) {
            case "direct" -> {
                if (rawVal instanceof String s) {
                    if (rule.canonicalName().equals("ts_event") || rule.canonicalName().equals("seq") || rule.canonicalName().equals("rpm")) {
                        try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
                    } else if (rule.canonicalName().equals("speed_kmh") || rule.canonicalName().equals("odo_km") || rule.canonicalName().equals("fuel_pct") || rule.canonicalName().equals("soc_pct")) {
                        try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
                    }
                }
                return rawVal;
            }
            case "scale" -> {
                try {
                    double num = Double.parseDouble(rawVal.toString());
                    double f = rule.factor() != null ? rule.factor() : 1.0;
                    double res = num * f + rule.offset();
                    if (rule.canonicalName().endsWith("_e6")) {
                        return (int) Math.round(res);
                    }
                    if (rule.canonicalName().equals("odo_km")) {
                        return res;
                    }
                    return (float) res;
                } catch (Exception e) {
                    return rule.defaultVal();
                }
            }
            case "iso8601_to_ms" -> {
                try {
                    String str = rawVal.toString().trim();
                    Instant instant = Instant.parse(str);
                    return instant.toEpochMilli();
                } catch (DateTimeParseException e) {
                    return rule.defaultVal();
                }
            }
            case "epoch_s_to_ms" -> {
                try {
                    double sec = Double.parseDouble(rawVal.toString());
                    return Math.round(sec * 1000.0);
                } catch (Exception e) {
                    return rule.defaultVal();
                }
            }
            case "enum_map" -> {
                Object valToMap = rawVal;
                if (valToMap instanceof List<?> list) {
                    if (list.isEmpty()) return rule.defaultVal();
                    valToMap = list.get(0);
                }
                String key = valToMap.toString();
                if (rule.mapping().containsKey(key)) {
                    return rule.mapping().get(key);
                }
                return rule.defaultVal();
            }
            case "split_list" -> {
                if (rawVal instanceof List<?> list) {
                    return list.stream().map(Object::toString).toList();
                }
                String s = rawVal.toString();
                String[] items = s.split(rule.separator());
                List<String> list = new ArrayList<>();
                for (String item : items) {
                    String trimmed = item.trim();
                    if (!trimmed.isEmpty()) list.add(trimmed);
                }
                return list;
            }
            case "array_index" -> {
                if (rawVal instanceof List<?> list && rule.index() != null) {
                    int idx = rule.index();
                    if (idx >= 0 && idx < list.size()) {
                        Object item = list.get(idx);
                        if (rule.factor() != null) {
                            try {
                                double num = Double.parseDouble(item.toString());
                                double res = num * rule.factor() + rule.offset();
                                if (rule.canonicalName().endsWith("_e6")) {
                                    return (int) Math.round(res);
                                }
                                return res;
                            } catch (Exception ignored) {}
                        }
                        return item;
                    }
                }
                return rule.defaultVal();
            }
            case "negative_to_charge_kw" -> {
                try {
                    double kw = Double.parseDouble(rawVal.toString());
                    return kw < 0.0 ? (float) Math.abs(kw) : 0.0f;
                } catch (Exception e) {
                    return 0.0f;
                }
            }
            case "negative_to_charge_state" -> {
                try {
                    double kw = Double.parseDouble(rawVal.toString());
                    return kw < 0.0 ? "CHARGING" : "NONE";
                } catch (Exception e) {
                    return "NONE";
                }
            }
            default -> {
                return rawVal;
            }
        }
    }

    private Float toFloat(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Float f) return f;
        if (obj instanceof Number n) return n.floatValue();
        try { return Float.parseFloat(obj.toString()); } catch (Exception ignored) {}
        return null;
    }

    private Double toDouble(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Double d) return d;
        if (obj instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(obj.toString()); } catch (Exception ignored) {}
        return null;
    }

    private String computeEventId(String vehiclePid, long tsEvent, Long seq, String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String key = vehiclePid + ":" + tsEvent + ":" + (seq != null ? seq : content.hashCode());
            byte[] hash = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) { // 64-bit hex prefix
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString().substring(0, 16);
        }
    }
}
