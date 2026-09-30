package com.fleetpulse.normalizer.mapping;

import java.util.Map;

/**
 * §5.2 Compiled field transformation rule.
 */
public record FieldRule(
    String canonicalName,
    String source,
    String transform,
    boolean required,
    Object defaultVal,
    Double factor,
    double offset,
    Map<String, Object> mapping,
    String separator,
    Integer index
) {}
