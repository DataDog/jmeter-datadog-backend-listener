/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

/**
 * Snapshot of aggregated statistics. Getter names match metric suffixes.
 */
public class AggregationSnapshot {


    private final double min;
    private final double max;
    private final double avg;
    private final double median;
    private final double p90;
    private final double p95;
    private final double p99;

    public AggregationSnapshot(double min, double max, double avg, double median, double p90, double p95, double p99) {
        this.min = min;
        this.max = max;
        this.avg = avg;
        this.median = median;
        this.p90 = p90;
        this.p95 = p95;
        this.p99 = p99;
    }

    public static final String[] FIELDS = { "min", "max", "avg", "median", "p90", "p95", "p99" };
    
    /**
     * Get field value by suffix string.
     * @throws IllegalArgumentException if suffix is unknown
     */
    public double get(String suffix) {
        switch (suffix) {
            case "min":    return min;
            case "max":    return max;
            case "avg":    return avg;
            case "median": return median;
            case "p90":    return p90;
            case "p95":    return p95;
            case "p99":    return p99;
            default:
                throw new IllegalArgumentException("Unknown field: " + suffix);
        }
    }

    public double getMin() { return min; }
    public double getMax() { return max; }
    public double getAvg() { return avg; }
    public double getMedian() { return median; }
    public double getP90() { return p90; }
    public double getP95() { return p95; }
    public double getP99() { return p99; }
}
