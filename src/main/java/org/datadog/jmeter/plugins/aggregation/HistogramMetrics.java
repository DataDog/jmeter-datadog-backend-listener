/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import java.util.List;
import java.util.Optional;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;

/**
 * Utility class for emitting standard histogram metrics from a StatsCollector.
 */
public final class HistogramMetrics {

    private HistogramMetrics() {
    }

    /**
     * Emit standard histogram metrics from a StatsCollector.
     * @param baseName the base metric name (e.g., "jmeter.response_time")
     * @param tags the tags to apply to all metrics
     * @param stats the StatsCollector containing the aggregated data
     * @param includeCount whether to include the count metric in the output
     * @param metrics the list to add the metrics to
     */
    public static void emit(String baseName, List<String> tags, StatsCollector stats, boolean includeCount, List<DatadogMetric> metrics) {
        Optional<AggregationSnapshot> snapshot = stats.getSnapshot();
        
        if (snapshot.isPresent()) {
            AggregationSnapshot s = snapshot.get();
            for (String field : AggregationSnapshot.FIELDS) {
                metrics.add(new DatadogMetric(baseName + "." + field, "gauge", s.get(field), tags));
            }
        }

        if (includeCount) {
            metrics.add(new DatadogMetric(baseName + ".count", "count", stats.getCount(), tags));
        }
    }
}
