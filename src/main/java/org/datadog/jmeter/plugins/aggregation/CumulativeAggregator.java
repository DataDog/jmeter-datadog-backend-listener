/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.apache.jmeter.samplers.SampleResult;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;
import org.datadog.jmeter.plugins.util.CommonUtils;

/**
 * Tracks per-label cumulative statistics from JMeter SampleResults.
 */
public class CumulativeAggregator {

    /**
     * Tag key used for sample labels.
     */
    private static final String SAMPLE_LABEL_KEY = "sample_label";

    /**
     * Reserved tag for aggregate totals across all samplers.
     */
    private static final String TOTAL_LABEL_TAG = CommonUtils.sanitizeTagPair(SAMPLE_LABEL_KEY, "total");

    /**
     * Escaped tag used when a sampler is named "total".
     */
    private static final String ESCAPED_TOTAL_LABEL_TAG = CommonUtils.sanitizeTagPair(SAMPLE_LABEL_KEY, "_escaped_total");

    /**
     * Per-label statistics container.
     */
    private static class LabelStats {
        final StatsCollector responseTimeStats;
        long sampleCount = 0;
        long errorCount = 0;
        long firstStartTimeMs = Long.MAX_VALUE;
        long lastEndTimeMs = Long.MIN_VALUE;
        long totalBytesReceived = 0;
        long totalBytesSent = 0;

        LabelStats(StatsCollector responseTimeStats) {
            this.responseTimeStats = responseTimeStats;
        }

        void addSample(SampleResult sampleResult, boolean countSubsamplesAsSingle) {
            long count = sampleResult.getSampleCount();
            long errors = sampleResult.getErrorCount();
            if (count > 0) {
                if (countSubsamplesAsSingle) {
                    responseTimeStats.addValue(sampleResult.getTime() / 1000.0);
                } else {
                    double responseTimeSeconds = (sampleResult.getTime() / 1000.0) / count;
                    for (int i = 0; i < count; i++) {
                        responseTimeStats.addValue(responseTimeSeconds);
                    }
                }
            }

            if (countSubsamplesAsSingle) {
                sampleCount += 1;
                errorCount += sampleResult.isSuccessful() ? 0 : 1;
            } else {
                sampleCount += count;
                errorCount += errors;
            }

            firstStartTimeMs = Math.min(firstStartTimeMs, sampleResult.getStartTime());
            lastEndTimeMs = Math.max(lastEndTimeMs, sampleResult.getEndTime());
            totalBytesReceived += sampleResult.getBytesAsLong();
            totalBytesSent += sampleResult.getSentBytes();
        }

        /**
         * Calculate the effective duration of the test in milliseconds.
         *
         * The duration is computed as the time span from the first sample's start time
         * to the last sample's end time. 
         * Reference: https://github.com/apache/jmeter/blob/34a2785748e9e0b14702595e8682c387869deda3/src/core/src/main/java/org/apache/jmeter/visualizers/RunningSample.java#L115
         */
        long getEffectiveTestDurationMs() {
            if (sampleCount == 0) {
                return 0L;  // No samples recorded yet
            }

            long durationMs = lastEndTimeMs - firstStartTimeMs;
            if (durationMs < 0) {
                return 0L;  // Invalid time range
            }

            return durationMs;
        }
    }

    private final Supplier<StatsCollector> statsFactory;
    private final Lock lock = new ReentrantLock();
    /**
     * A boolean indicating how to treat JMeter SampleResults.
     * If true, each SampleResult is treated as a single data point, which is how the JMeter HTML Dashboard works.
     * If false, the total time of the SampleResult is divided by its sample count, which is how the JMeter Aggregate Report works.
     */
    private final boolean countSubsamplesAsSingle;
    /**
     * Map keyed by the full sanitized tag pair (e.g., "sample_label:my_request").
     */
    private final Map<String, LabelStats> labelTagToStats = new HashMap<>();
    
    /**
     * Separate TOTAL tracker that receives all samples directly.
     */
    private final LabelStats totalStats;

    
    /**
     * Create a new CumulativeAggregator.
     * 
     * @param statsFactory factory for creating StatsCollector instances
     * @param countSubsamplesAsSingle If true, each SampleResult is treated as a single data point (for HTML Dashboard compatibility).
     *                                  If false, the total time is averaged across the sample count (for Aggregate Report compatibility).
     */
    public CumulativeAggregator(Supplier<StatsCollector> statsFactory, boolean countSubsamplesAsSingle) {
        this.statsFactory = statsFactory;
        this.countSubsamplesAsSingle = countSubsamplesAsSingle;
        this.totalStats = new LabelStats(statsFactory.get());
    }

    /**
     * Add a sample result to the tracker.
     * 
     * @param sampleResult the JMeter sample result
     */
    public void addSample(SampleResult sampleResult) {
        String labelTag = CommonUtils.sanitizeTagPair(SAMPLE_LABEL_KEY, sampleResult.getSampleLabel());

        // Escape if the sanitized tag matches our reserved TOTAL tag
        if (TOTAL_LABEL_TAG.equals(labelTag)) {
            labelTag = ESCAPED_TOTAL_LABEL_TAG;
        }

        lock.lock();
        try {
            // Add to per-label stats
            LabelStats stats = labelTagToStats.get(labelTag);
            if (stats == null) {
                stats = new LabelStats(statsFactory.get());
                labelTagToStats.put(labelTag, stats);
            }
            stats.addSample(sampleResult, countSubsamplesAsSingle);

            // Also add to TOTAL
            totalStats.addSample(sampleResult, countSubsamplesAsSingle);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Build aggregate metrics for all labels and the total.
     *
     * @param baseTags base tags to include on all metrics (statistics_mode, final, custom tags)
     * @return list of aggregate metrics
     */
    public List<DatadogMetric> buildMetrics(List<String> baseTags) {
        return buildMetricsWithPrefix(baseTags, "jmeter.cumulative.");
    }

    /**
     * Build final result metrics for all labels and the total.
     * These metrics use the "jmeter.final_result." prefix and are emitted only once at test end.
     *
     * @param baseTags base tags to include on all metrics (statistics_mode, custom tags)
     * @return list of final result metrics
     */
    public List<DatadogMetric> buildFinalMetrics(List<String> baseTags) {
        return buildMetricsWithPrefix(baseTags, "jmeter.final_result.");
    }

    /**
     * Build metrics for all labels and the total with a specified prefix.
     *
     * @param baseTags base tags to include on all metrics
     * @param metricPrefix the metric name prefix (e.g., "jmeter.cumulative." or "jmeter.final_result.")
     * @return list of metrics
     */
    private List<DatadogMetric> buildMetricsWithPrefix(List<String> baseTags, String metricPrefix) {
        lock.lock();
        try {
            List<DatadogMetric> out = new ArrayList<>();

            // Append per-label metrics
            for (Map.Entry<String, LabelStats> entry : labelTagToStats.entrySet()) {
                appendLabelMetrics(out, entry.getKey(), entry.getValue(), baseTags, metricPrefix);
            }

            // Append total metrics (aggregate across all samplers)
            appendLabelMetrics(out, TOTAL_LABEL_TAG, totalStats, baseTags, metricPrefix);

            return out;
        } finally {
            lock.unlock();
        }
    }

    private void appendLabelMetrics(List<DatadogMetric> out, String labelTag, LabelStats stats,
                                    List<String> baseTags, String metricPrefix) {
        List<String> tags = new ArrayList<>(baseTags);
        tags.add(labelTag); // Already a complete sanitized tag pair (e.g., "sample_label:my_request")

        // Response time percentiles
        HistogramMetrics.emit(metricPrefix + "response_time", tags, stats.responseTimeStats, false, out);

        // Sample count
        out.add(new DatadogMetric(metricPrefix + "responses_count", "gauge", (double) stats.sampleCount, tags));

        // Error percentage
        if (stats.sampleCount > 0) {
            double errorPercent = (((double) stats.errorCount) / ((double) stats.sampleCount)) * 100.0;
            out.add(new DatadogMetric(metricPrefix + "responses.error_percent", "gauge", errorPercent, tags));
        }

        // Rate metrics
        long testDurationMs = stats.getEffectiveTestDurationMs();
        if (testDurationMs > 0) {
            // Reference: https://github.com/apache/jmeter/blob/34a2785748e9e0b14702595e8682c387869deda3/src/core/src/main/java/org/apache/jmeter/visualizers/RunningSample.java#L141
            double requestsPerSecond = (double) stats.sampleCount / testDurationMs * 1000.0;
            out.add(new DatadogMetric(metricPrefix + "throughput.rps", "gauge", requestsPerSecond, tags));

            // Network rates (bytes per second)
            double bytesReceivedPerSecond = (double) stats.totalBytesReceived / testDurationMs * 1000.0;
            double bytesSentPerSecond = (double) stats.totalBytesSent / testDurationMs * 1000.0;
            out.add(new DatadogMetric(metricPrefix + "bytes_received.rate", "gauge", bytesReceivedPerSecond, tags));
            out.add(new DatadogMetric(metricPrefix + "bytes_sent.rate", "gauge", bytesSentPerSecond, tags));
        }
    }
}
