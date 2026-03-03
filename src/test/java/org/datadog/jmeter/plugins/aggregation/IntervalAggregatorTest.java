/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import static org.datadog.jmeter.plugins.aggregation.StatsCollectorTestData.*;
import static org.junit.Assert.*;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;
import org.datadog.jmeter.plugins.metrics.DatadogMetricContext;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests for {@link IntervalAggregator}.
 */
@RunWith(Enclosed.class)
public class IntervalAggregatorTest {

    /**
     * Parameterized tests that verify histogram accuracy for each collector implementation.
     *
     * <p>These tests use {@link StatsCollectorTestData#collectors()} to run against all
     * implementations, ensuring that percentile metrics emitted by histograms are correct.
     */
    @RunWith(Parameterized.class)
    public static class PerCollectorTests {

        @Parameters(name = "{0}")
        public static Collection<Object[]> data() {
            return collectors();
        }

        private final Supplier<StatsCollector> factory;
        private final double tolerance;

        public PerCollectorTests(String name, Supplier<StatsCollector> factory, double tolerance) {
            this.factory = factory;
            this.tolerance = tolerance;
        }

        @Test
        public void testHundredValues() {
            IntervalAggregator aggregator = new IntervalAggregator(factory);
            DatadogMetricContext ctx = new DatadogMetricContext("test", Collections.emptyList());

            for (int i = 1; i <= 100; i++) {
                aggregator.histogram(ctx, i / 100.0);
            }

            Map<String, Double> m = new HashMap<>();
            for (DatadogMetric metric : aggregator.flushMetrics()) {
                m.put(metric.getName(), metric.getValue());
            }

            assertEquals(100, m.get("test.count").longValue());
            assertMetrics(m, "test.", tolerance);
        }
    }

    /**
     * Tests for thread safety under concurrent access.
     *
     * <p>Uses {@code DDSketchStatsCollector} directly (not parameterized) because these tests
     * verify that counters, gauges, and histograms are thread-safe - behavior that doesn't
     * depend on the collector implementation. The tests use a semaphore-based synchronization
     * to ensure all threads start simultaneously, maximizing contention.
     */
    public static class ConcurrencyTests {
        private static final int N_THREADS = 50;
        private IntervalAggregator aggregator;

        @Before
        public void setUp() {
            aggregator = new IntervalAggregator(DDSketchStatsCollector::new);
            aggregator.testOnlyBlocker = new Semaphore(0);
        }

        @Test
        public void testCounter() throws InterruptedException {
            DatadogMetricContext ctx = new DatadogMetricContext("foo", Collections.emptyList());

            ExecutorService service = Executors.newFixedThreadPool(N_THREADS);
            try {
                for (int i = 0; i < N_THREADS; i++) {
                    service.execute(() -> aggregator.incrementCounter(ctx, 1));
                }
                aggregator.testOnlyBlocker.release(N_THREADS);
                service.shutdown();
                service.awaitTermination(2, TimeUnit.SECONDS);
            } finally {
                service.shutdownNow();
            }

            List<DatadogMetric> metrics = aggregator.flushMetrics();
            assertEquals(1, metrics.size());
            assertEquals("foo", metrics.get(0).getName());
            assertEquals("count", metrics.get(0).getType());
            assertEquals(N_THREADS, (int) metrics.get(0).getValue());
        }

        @Test
        public void testGauge() throws InterruptedException {
            DatadogMetricContext ctx = new DatadogMetricContext("foo", Collections.emptyList());

            ExecutorService service = Executors.newFixedThreadPool(N_THREADS);
            try {
                for (int i = 0; i < N_THREADS; i++) {
                    service.execute(() -> aggregator.addGauge(ctx, 55));
                }
                aggregator.testOnlyBlocker.release(N_THREADS);
                service.shutdown();
                service.awaitTermination(2, TimeUnit.SECONDS);
            } finally {
                service.shutdownNow();
            }

            List<DatadogMetric> metrics = aggregator.flushMetrics();
            assertEquals(1, metrics.size());
            assertEquals("foo", metrics.get(0).getName());
            assertEquals("gauge", metrics.get(0).getType());
            assertEquals(55, (int) metrics.get(0).getValue());
        }

        @Test
        public void testHistogram() throws InterruptedException {
            DatadogMetricContext ctx = new DatadogMetricContext("foo", Collections.emptyList());

            ExecutorService service = Executors.newFixedThreadPool(N_THREADS);
            try {
                for (int i = 0; i < N_THREADS; i++) {
                    final int x = i + 1;
                    service.execute(() -> aggregator.histogram(ctx, x));
                }
                aggregator.testOnlyBlocker.release(N_THREADS);
                service.shutdown();
                service.awaitTermination(2, TimeUnit.SECONDS);
            } finally {
                service.shutdownNow();
            }

            List<DatadogMetric> metrics = aggregator.flushMetrics();
            assertEquals(8, metrics.size());

            Map<String, Double> byName = new HashMap<>();
            for (DatadogMetric metric : metrics) {
                byName.put(metric.getName(), metric.getValue());
            }
            assertEquals(8, byName.size());

            // Expected values for DDSketch with inputs 1..N_THREADS.
            Map<String, Double> expected = new HashMap<>();
            expected.put("foo.min", 1.0);
            expected.put("foo.max", 50.0);
            expected.put("foo.avg", 25.5);
            expected.put("foo.median", 25.0);
            expected.put("foo.p90", 45.0);
            expected.put("foo.p95", 48.0);
            expected.put("foo.p99", 49.0);
            expected.put("foo.count", (double) N_THREADS);

            for (Map.Entry<String, Double> e : expected.entrySet()) {
                String name = e.getKey();
                Double actual = byName.get(name);
                assertNotNull("missing metric: " + name, actual);
                assertEquals(name, e.getValue(), actual, 1.0);
            }
        }
    }
}
