/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.commons.math3.stat.descriptive.rank.Percentile.EstimationType;

/**
 * Shared test data and utilities for StatsCollector parameterized tests.
 *
 * <p>This class provides parameters for testing all {@link StatsCollector}
 * implementations. It provides:
 *
 * <ul>
 *   <li><b>Collector configurations</b> ({@link #collectors()}): A list of all collector
 *       implementations with their specific error tolerances. Multiple test classes use this
 *       method to run the same "standard battery" of tests against every implementation.</li>
 *   <li><b>Expected data</b> ({@link #EXPECTED}): Pre-calculated expected values for a standard
 *       input set of 100 values (0.01 to 1.0 seconds).</li>
 * </ul>
 */
public final class StatsCollectorTestData {

    private StatsCollectorTestData() {}

    /** Expected percentiles for 100 values: 0.01, 0.02, ..., 1.0 (seconds). */
    public static final AggregationSnapshot EXPECTED = new AggregationSnapshot(
        /* min  */ 0.01,
        /* max  */ 1.0,
        /* mean */ 0.505,
        /* p50  */ 0.5,
        /* p90  */ 0.9,
        /* p95  */ 0.95,
        /* p99  */ 0.99
    );

    /** Collector configurations: {name, factory, tolerance} */
    public static Collection<Object[]> collectors() {
        return Arrays.asList(new Object[][] {
            { "JmeterCompatible", sup(JmeterCompatibleStatsCollector::new), 0.0 },
            { "DashboardCompatible(R_3)", sup(() -> new DashboardCompatibleStatsCollector(20000, EstimationType.R_3)), 0.0 },
            { "DashboardCompatible(LEGACY)", sup(() -> new DashboardCompatibleStatsCollector(20000, EstimationType.LEGACY)), 0.01 },
            { "DDSketch", sup(DDSketchStatsCollector::new), 0.02 },
        });
    }

    /** Assert actual snapshot matches EXPECTED within tolerance. */
    public static void assertSnapshot(AggregationSnapshot actual, double tolerance) {
        assertSnapshot(EXPECTED, actual, tolerance);
    }

    /** Assert actual snapshot matches expected snapshot within tolerance. */
    public static void assertSnapshot(AggregationSnapshot expected, AggregationSnapshot actual, double tolerance) {
        for (String field : AggregationSnapshot.FIELDS) {
            assertEquals(field, expected.get(field), actual.get(field), tolerance);
        }
    }

    /** Assert metrics map matches EXPECTED within tolerance. */
    public static void assertMetrics(Map<String, Double> m, String prefix, double tolerance) {
        assertMetrics(EXPECTED, m, prefix, tolerance);
    }

    /** Assert metrics map matches expected snapshot within tolerance. */
    public static void assertMetrics(AggregationSnapshot expected, Map<String, Double> m, String prefix, double tolerance) {
        for (String field : AggregationSnapshot.FIELDS) {
            assertEquals(field, expected.get(field), m.get(prefix + field), tolerance);
        }
    }

    private static Supplier<StatsCollector> sup(Supplier<StatsCollector> s) { return s; }
}
