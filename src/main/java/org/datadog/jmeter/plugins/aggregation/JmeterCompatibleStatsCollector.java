/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import java.util.Optional;
import org.apache.jorphan.math.StatCalculatorLong;

/**
 * StatsCollector implementation that delegates to JMeter's native {@link StatCalculatorLong}.
 */
public class JmeterCompatibleStatsCollector implements StatsCollector {

    private final StatCalculatorLong calculator = new StatCalculatorLong();

    @Override
    public void addValue(double valueSeconds) {
        calculator.addValue(toMs(valueSeconds));
    }

    @Override
    public Optional<AggregationSnapshot> getSnapshot() {
        if (calculator.getCount() == 0) {
            return Optional.empty();
        }

        double min = toSeconds(calculator.getMin());
        double max = toSeconds(calculator.getMax());

        // Match JMeter's Aggregate Report behavior, which truncates the mean to a long.
        // Reference: https://github.com/apache/jmeter/blob/34a2785748e9e0b14702595e8682c387869deda3/src/core/src/main/java/org/apache/jmeter/visualizers/SamplingStatCalculator.java#L198-L200
        double meanSeconds = toSeconds(Math.floor(calculator.getMean()));

        double p50 = toSeconds(calculator.getPercentPoint(0.50).doubleValue());
        double p90 = toSeconds(calculator.getPercentPoint(0.90).doubleValue());
        double p95 = toSeconds(calculator.getPercentPoint(0.95).doubleValue());
        double p99 = toSeconds(calculator.getPercentPoint(0.99).doubleValue());

        return Optional.of(new AggregationSnapshot(min, max, meanSeconds, p50, p90, p95, p99));
    }

    @Override
    public long getCount() {
        return calculator.getCount();
    }

    private static long toMs(double seconds) {
        return Math.round(seconds * 1000.0);
    }

    private static double toSeconds(long ms) {
        return ms / 1000.0;
    }

    private static double toSeconds(double ms) {
        return ms / 1000.0;
    }
}
