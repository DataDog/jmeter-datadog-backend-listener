/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Statistics calculation mode enum.
 */
public enum StatisticsMode {
    DDSKETCH("ddsketch"),
    AGGREGATE_REPORT("aggregate_report"),
    DASHBOARD("dashboard");

    private final String value;

    StatisticsMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Parse a string value to a StatisticsMode enum.
     * @param value the string value (case-insensitive)
     * @return the corresponding StatisticsMode
     * @throws IllegalArgumentException if the value is not valid
     */
    public static StatisticsMode fromStringValue(String value) {
        String normalized = value.trim().toLowerCase();
        return Arrays.stream(values())
            .filter(mode -> mode.value.equals(normalized))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Invalid statistics mode: '" + value + "'. Valid options: " + getValidModes()));
    }

    /**
     * Get a comma-separated list of all valid mode values.
     */
    public static String getValidModes() {
        return Arrays.stream(values())
            .map(mode -> mode.value)
            .collect(Collectors.joining(", "));
    }
}

