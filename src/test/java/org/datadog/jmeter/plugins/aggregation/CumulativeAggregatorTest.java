/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import static org.datadog.jmeter.plugins.aggregation.StatsCollectorTestData.*;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.jmeter.samplers.SampleResult;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests for {@link CumulativeAggregator}.
 */
@RunWith(Enclosed.class)
public class CumulativeAggregatorTest {

    private static final String P = "jmeter.cumulative.";

    /**
     * Parameterized tests that verify statistical accuracy for each collector implementation.
     *
     * <p>These tests use {@link StatsCollectorTestData#collectors()} to run against all
     * implementations, ensuring that response time percentiles, counts, and other statistical
     * metrics are computed correctly regardless of the underlying algorithm.
     */
    @RunWith(Parameterized.class)
    public static class PerCollectorTests {

        @Parameters(name = "{0}")
        public static Collection<Object[]> data() {
            return collectors();
        }

        private final Supplier<StatsCollector> factory;
        private final double tol;

        public PerCollectorTests(String name, Supplier<StatsCollector> factory, double tolerance) {
            this.factory = factory;
            this.tol = tolerance;
        }

        @Test
        public void testHundredSamples() {
            CumulativeAggregator tracker = new CumulativeAggregator(factory, false);

            long t = 1000;
            for (int i = 1; i <= 100; i++) {
                long durationMs = i * 10;
                tracker.addSample(sample("test", true, t, t + durationMs));
                t += durationMs;
            }

            Map<String, Double> m = metricsForLabel(tracker, "test");

            assertEquals(100, m.get(P + "responses_count").longValue());
            assertMetrics(m, P + "response_time.", tol);
        }
    }

    /**
     * Tests for aggregator-specific behavior that is independent of the collector implementation.
     *
     * <p>These tests use {@code DDSketchStatsCollector} directly (not parameterized) because
     * they test aggregator logic, not statistical accuracy.
     */
    public static class AggregatorBehaviorTests {

        @Test
        public void testCollectsMetricsPerLabel() {
            CumulativeAggregator tracker = new CumulativeAggregator(DDSketchStatsCollector::new, false);

            tracker.addSample(sample("label-a", true, 1, 101));
            tracker.addSample(sample("label-a", true, 101, 301));
            tracker.addSample(sample("label-b", false, 301, 451));

            Map<String, List<DatadogMetric>> byLabel = groupByLabel(tracker.buildMetrics(new ArrayList<>()));
            assertTrue("Should have label-a metrics", byLabel.containsKey("label-a"));
            assertTrue("Should have label-b metrics", byLabel.containsKey("label-b"));
            assertTrue("Should have total metrics", byLabel.containsKey("total"));
        }

        @Test
        public void testResponseCountAndErrorRate() {
            CumulativeAggregator tracker = new CumulativeAggregator(DDSketchStatsCollector::new, false);

            long t = 1;
            for (int i = 0; i < 3; i++) {
                tracker.addSample(sample("test", true, t, t += 100));
            }
            for (int i = 0; i < 2; i++) {
                tracker.addSample(sample("test", false, t, t += 100));
            }

            Map<String, Double> m = metricsForLabel(tracker, "test");

            assertEquals("Should count 5 responses", 5, m.get(P + "responses_count").longValue());
            assertEquals("Error percent should be 40%", 40.0, m.get(P + "responses.error_percent"), 0.01);
        }

        @Test
        public void testExpandedCountUsesAvgTime() {
            // With countSubsamplesAsSingle=false, aggregated samples expand by sampleCount and
            // should use average response time per subsample.
            CumulativeAggregator tracker = new CumulativeAggregator(JmeterCompatibleStatsCollector::new, false);

            SampleResult aggregated = sample("test", true, 1, 5001);
            aggregated.setSampleCount(5);
            tracker.addSample(aggregated);

            SampleResult single = sample("test", true, 5001, 7001);
            tracker.addSample(single);

            Map<String, Double> m = metricsForLabel(tracker, "test");

            assertEquals("Should count 6 responses", 6, m.get(P + "responses_count").longValue());
            assertEquals(1.0, m.get(P + "response_time.min"), 0.0001);
            assertEquals(2.0, m.get(P + "response_time.max"), 0.0001);
            assertEquals(1.0, m.get(P + "response_time.median"), 0.0001);
            assertEquals(1.0, m.get(P + "response_time.p90"), 0.0001);
            assertEquals(2.0, m.get(P + "response_time.p95"), 0.0001);
            assertEquals(2.0, m.get(P + "response_time.p99"), 0.0001);
            assertEquals(1.166, m.get(P + "response_time.avg"), 0.001);
        }

        @Test
        public void testCountSubsamplesCountsResults() {
            // countSubsamplesAsSingle=true should count each SampleResult as 1 sample,
            // regardless of getSampleCount().
            CumulativeAggregator tracker = new CumulativeAggregator(DashboardCompatibleStatsCollector::new, true);

            // Add an aggregated sample with sampleCount=5
            SampleResult aggregated = sample("test", true, 1, 5001);
            aggregated.setSampleCount(5);
            tracker.addSample(aggregated);

            // Add a single sample
            SampleResult single = sample("test", true, 5001, 7001);
            tracker.addSample(single);

            Map<String, Double> m = metricsForLabel(tracker, "test");

            // Should count 2 responses (2 SampleResults), not 6 (5+1)
            assertEquals("countSubsamplesAsSingle should count 2 SampleResults, not 6",
                    2, m.get(P + "responses_count").longValue());
        }

        @Test
        public void testCountSubsamplesUsesSuccess() {
            // countSubsamplesAsSingle=true counts each SampleResult once,
            CumulativeAggregator tracker = new CumulativeAggregator(DashboardCompatibleStatsCollector::new, true);

            // Add a failed aggregated sample with sampleCount=5, errorCount=3
            SampleResult aggregatedFailed = sample("test", false, 1, 5001);
            aggregatedFailed.setSampleCount(5);
            aggregatedFailed.setErrorCount(3);
            tracker.addSample(aggregatedFailed);

            // Add a successful sample
            SampleResult success = sample("test", true, 5001, 7001);
            tracker.addSample(success);

            Map<String, Double> m = metricsForLabel(tracker, "test");

            assertEquals("countSubsamplesAsSingle should count 2 samples",
                    2, m.get(P + "responses_count").longValue());
            assertEquals("countSubsamplesAsSingle error rate should be 50%",
                    50.0, m.get(P + "responses.error_percent"), 0.01);
        }

        @Test
        public void testThroughputCalculation() {
            CumulativeAggregator tracker = new CumulativeAggregator(DDSketchStatsCollector::new, false);

            for (int i = 0; i < 10; i++) {
                tracker.addSample(sample("test", true, 1000 + i * 100, 1100 + i * 100));
            }

            Map<String, Double> m = metricsForLabel(tracker, "test");

            assertMetric(m, "throughput.rps", 10.0, 0.01);
            assertMetric(m, "bytes_received.rate", 10000.0, 0.01);
            assertMetric(m, "bytes_sent.rate", 5000.0, 0.01);
        }

        @Test
        public void testBaseTagsAreIncluded() {
            CumulativeAggregator tracker = new CumulativeAggregator(DDSketchStatsCollector::new, false);
            tracker.addSample(sample("test", true, 1, 101));

            List<String> baseTags = Arrays.asList(
                    "statistics_mode:ddsketch", "final_result:false", "env:prod");

            for (DatadogMetric metric : tracker.buildMetrics(baseTags)) {
                List<String> tags = metric.getTags();
                assertTrue("statistics_mode", tags.contains("statistics_mode:ddsketch"));
                assertTrue("final_result", tags.contains("final_result:false"));
                assertTrue("env", tags.contains("env:prod"));
            }
        }

        @Test
        public void testFinalResultTagPassThrough() {
            CumulativeAggregator tracker = new CumulativeAggregator(DDSketchStatsCollector::new, false);
            tracker.addSample(sample("test", true, 1, 101));

            for (DatadogMetric m : tracker.buildMetrics(Arrays.asList("final_result:false"))) {
                assertTrue(m.getTags().contains("final_result:false"));
            }
            for (DatadogMetric m : tracker.buildMetrics(Arrays.asList("final_result:true"))) {
                assertTrue(m.getTags().contains("final_result:true"));
            }
        }

        /**
         * Verifies that a sampler named "TOTAL" is escaped to "_escaped_total" to avoid collision
         * with the aggregate totals row.
         * Note: We use "_escaped_total" because "__total" gets collapsed to "_total" by sanitization.
         */
        @Test
        public void testSamplerNamedTotalEscaped() {
            CumulativeAggregator tracker = new CumulativeAggregator(DDSketchStatsCollector::new, false);

            tracker.addSample(sample("TOTAL", true, 1, 101));
            tracker.addSample(sample("other", true, 101, 201));

            Map<String, List<DatadogMetric>> byLabel = groupByLabel(tracker.buildMetrics(new ArrayList<>()));

            assertTrue("Sampler named 'TOTAL' should be escaped to '_escaped_total'",
                    byLabel.containsKey("_escaped_total"));

            assertTrue("Should have 'other' metrics", byLabel.containsKey("other"));

            assertTrue("Should have aggregate 'total' metrics", byLabel.containsKey("total"));

            Map<String, Double> escapedTotalMetrics = metricsForLabel(tracker, "_escaped_total");
            Map<String, Double> aggregateTotalMetrics = metricsForLabel(tracker, "total");

            assertEquals("Escaped _escaped_total should have 1 sample",
                    1, escapedTotalMetrics.get(P + "responses_count").longValue());

            assertEquals("Aggregate total should have 2 samples",
                    2, aggregateTotalMetrics.get(P + "responses_count").longValue());
        }

        @Test
        public void testConcurrentAddSample() throws InterruptedException {
            CumulativeAggregator tracker = new CumulativeAggregator(DDSketchStatsCollector::new, false);
            int nThreads = 50;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(nThreads);

            ExecutorService pool = Executors.newFixedThreadPool(nThreads);
            for (int i = 0; i < nThreads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        tracker.addSample(sample("test", true, 1, 101));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            done.await(5, TimeUnit.SECONDS);
            pool.shutdown();

            Map<String, Double> m = metricsForLabel(tracker, "test");
            assertEquals(nThreads, m.get(P + "responses_count").longValue());
        }

        /**
         * Verifies emitted metrics match the StatsCollector snapshot exactly (catches
         * wiring bugs).
         */
        @Test
        public void testEmittedMetricsMatchSnapshot() {
            StatsCollector collector = new JmeterCompatibleStatsCollector();
            CumulativeAggregator tracker = new CumulativeAggregator(() -> collector, false);

            tracker.addSample(sample("test", true, 1000, 1100));
            tracker.addSample(sample("test", true, 1100, 1300));
            tracker.addSample(sample("test", true, 1300, 1600));

            Map<String, Double> emitted = metricsForLabel(tracker, "test");
            AggregationSnapshot snap = collector.getSnapshot().get();

            assertMetrics(snap, emitted, P + "response_time.", 0);
        }
    }

    private static final String PF = "jmeter.final_result.";

    /**
     * Tests for the final result metrics (emitted once at test end).
     *
     * <p>Uses a single collector since these tests verify metric structure and naming,
     * not statistical accuracy. They ensure that {@code buildFinalMetrics()} produces
     * the expected metric names, tags, and relationships to cumulative metrics.
     */
    public static class FinalResultMetricsTests {

        @Test
        public void testFinalMetricsContainsExpected() {
            CumulativeAggregator tracker = new CumulativeAggregator(DDSketchStatsCollector::new, false);

            for (int i = 0; i < 10; i++) {
                tracker.addSample(sample("test", i % 3 == 0, 1000 + i * 100, 1100 + i * 100));
            }

            Map<String, Double> m = finalMetricsForLabel(tracker, "test");

            // Verify all expected metrics are present
            String[] expectedSuffixes = {
                "responses_count",
                "responses.error_percent",
                "throughput.rps",
                "bytes_received.rate",
                "bytes_sent.rate",
                "response_time.min",
                "response_time.max",
                "response_time.avg",
                "response_time.median",
                "response_time.p90",
                "response_time.p95",
                "response_time.p99"
            };

            for (String suffix : expectedSuffixes) {
                assertTrue("Should have " + suffix, m.containsKey(PF + suffix));
            }
        }

        @Test
        public void testFinalMetricsIncludeTotal() {
            CumulativeAggregator tracker = new CumulativeAggregator(DDSketchStatsCollector::new, false);

            tracker.addSample(sample("label-a", true, 1, 101));
            tracker.addSample(sample("label-b", true, 101, 201));

            Map<String, List<DatadogMetric>> byLabel = groupByLabel(tracker.buildFinalMetrics(new ArrayList<>()));

            assertTrue("Should have label-a metrics", byLabel.containsKey("label-a"));
            assertTrue("Should have label-b metrics", byLabel.containsKey("label-b"));
            assertTrue("Should have total metrics", byLabel.containsKey("total"));

            // Verify total count is sum of all labels
            Map<String, Double> totalMetrics = finalMetricsForLabel(tracker, "total");
            assertEquals("Total should have 2 samples", 2, totalMetrics.get(PF + "responses_count").longValue());
        }

        @Test
        public void testFinalMetricsSkipFinalResultTag() {
            CumulativeAggregator tracker = new CumulativeAggregator(DDSketchStatsCollector::new, false);
            tracker.addSample(sample("test", true, 1, 101));

            List<String> baseTags = Arrays.asList(
                    "statistics_mode:ddsketch", "env:prod");

            for (DatadogMetric metric : tracker.buildFinalMetrics(baseTags)) {
                List<String> tags = metric.getTags();
                // Verify that no tag starts with "final_result:"
                for (String tag : tags) {
                    assertFalse("Should not have final_result tag: " + tag,
                            tag.startsWith("final_result:"));
                }
                // Verify expected tags are present
                assertTrue("statistics_mode", tags.contains("statistics_mode:ddsketch"));
                assertTrue("env", tags.contains("env:prod"));
            }
        }

        @Test
        public void testFinalMetricsMatchCumulative() {
            CumulativeAggregator tracker = new CumulativeAggregator(DDSketchStatsCollector::new, false);

            for (int i = 0; i < 100; i++) {
                tracker.addSample(sample("test", i % 5 != 0, 1000 + i * 100, 1100 + i * 100));
            }

            Map<String, Double> cumulative = metricsForLabel(tracker, new ArrayList<>(), "test");
            Map<String, Double> finalResult = finalMetricsForLabel(tracker, "test");

            // Values should be identical (same underlying data)
            String[] suffixes = {
                "responses_count",
                "responses.error_percent",
                "throughput.rps",
                "response_time.min",
                "response_time.max",
                "response_time.avg",
                "response_time.median",
                "response_time.p90",
                "response_time.p95",
                "response_time.p99",
                "bytes_received.rate",
                "bytes_sent.rate"
            };

            for (String suffix : suffixes) {
                assertEquals(suffix + " should match",
                        cumulative.get(P + suffix),
                        finalResult.get(PF + suffix),
                        0.01);
            }
        }
    }

    private static Map<String, Double> finalMetricsForLabel(CumulativeAggregator tracker, String label) {
        Map<String, Double> result = new HashMap<>();
        for (DatadogMetric m : tracker.buildFinalMetrics(new ArrayList<>())) {
            if (m.getTags().contains("sample_label:" + label)) {
                result.put(m.getName(), m.getValue());
            }
        }
        return result;
    }

    private static SampleResult sample(String label, boolean success, long startMs, long endMs) {
        SampleResult result = SampleResult.createTestSample(startMs, endMs);
        result.setSampleLabel(label);
        result.setSuccessful(success);
        result.setResponseCode(success ? "200" : "500");
        result.setSampleCount(1);
        result.setErrorCount(success ? 0 : 1);
        result.setBytes(1000L);
        result.setSentBytes(500L);
        return result;
    }

    private static void assertMetric(
            Map<String, Double> m,
            String name,
            double expected,
            double tolerance) {
        assertEquals(expected, m.get(P + name), tolerance);
    }

    private static Map<String, Double> metricsForLabel(CumulativeAggregator tracker, String label) {
        return metricsForLabel(tracker, new ArrayList<>(), label);
    }

    private static Map<String, Double> metricsForLabel(
            CumulativeAggregator tracker,
            List<String> baseTags,
            String label) {
        Map<String, Double> result = new HashMap<>();
        for (DatadogMetric m : tracker.buildMetrics(baseTags)) {
            if (m.getTags().contains("sample_label:" + label)) {
                result.put(m.getName(), m.getValue());
            }
        }
        return result;
    }

    private static Map<String, List<DatadogMetric>> groupByLabel(List<DatadogMetric> metrics) {
        Map<String, List<DatadogMetric>> byLabel = new HashMap<>();
        for (DatadogMetric m : metrics) {
            String label = m.getTags().stream()
                    .filter(t -> t.startsWith("sample_label:"))
                    .map(t -> t.substring("sample_label:".length()))
                    .findFirst().orElse("");
            byLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(m);
        }
        return byLabel;
    }
}
