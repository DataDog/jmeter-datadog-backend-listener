/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.apache.commons.math3.stat.descriptive.rank.Percentile.EstimationType;
import org.apache.jmeter.report.config.ReportGeneratorConfiguration;
import org.apache.jmeter.util.JMeterUtils;
import java.util.Optional;

/**
 * StatsCollector implementation that mimics the HTML Dashboard's percentile calculation.
 * 
 * Uses Apache Commons Math's DescriptiveStatistics with a sliding window, matching
 * JMeter's HTML Report Generator behavior.
 */
public class DashboardCompatibleStatsCollector implements StatsCollector {

    // JMeter property: jmeter.reportgenerator.statistic_window
    // Reference: https://github.com/apache/jmeter/blob/b1843c2a0aa0bc8292cc504e2a0cea53ca373234/src/core/src/main/java/org/apache/jmeter/report/processor/PercentileAggregator.java#L30
    private static final String WINDOW_SIZE_PROPERTY = ReportGeneratorConfiguration.REPORT_GENERATOR_KEY_PREFIX
            + ReportGeneratorConfiguration.KEY_DELIMITER + "statistic_window";
    private static final int DEFAULT_WINDOW_SIZE = 20000;
    
    // JMeter property: backend_metrics_percentile_estimator
    // Reference: https://github.com/apache/jmeter/blob/b1843c2a0aa0bc8292cc504e2a0cea53ca373234/src/core/src/main/java/org/apache/jmeter/report/processor/DescriptiveStatisticsFactory.java#L28
    private static final String ESTIMATOR_PROPERTY = "backend_metrics_percentile_estimator";
    private static final EstimationType DEFAULT_ESTIMATOR = EstimationType.LEGACY;

    private final DescriptiveStatistics statistics;

    /**
     * Creates a DashboardCompatibleStatsCollector with explicit configuration.
     * 
     * @param windowSize the sliding window size for statistics collection
     * @param estimationType the percentile estimation algorithm to use
     */
    public DashboardCompatibleStatsCollector(int windowSize, EstimationType estimationType) {
        this.statistics = new DescriptiveStatistics(windowSize);
        this.statistics.setPercentileImpl(new Percentile().withEstimationType(estimationType));
    }

    /**
     * Creates a DashboardCompatibleStatsCollector using default configuration.
     * Delegates to {@link #createFromJMeterConfig()}.
     */
    public DashboardCompatibleStatsCollector() {
        this(getWindowSizeFromConfig(), getEstimationTypeFromConfig());
    }
    
    /**
     * Factory method that creates a DashboardCompatibleStatsCollector by reading
     * configuration from JMeter properties.
     * 
     * @return a new collector configured from JMeter properties
     */
    public static DashboardCompatibleStatsCollector createFromJMeterConfig() {
        return new DashboardCompatibleStatsCollector(
            getWindowSizeFromConfig(),
            getEstimationTypeFromConfig()
        );
    }
    
    /**
     * Read sliding window size from JMeter properties.
     * @return the configured window size, or DEFAULT_WINDOW_SIZE if not set
     */
    private static int getWindowSizeFromConfig() {
        return JMeterUtils.getPropDefault(WINDOW_SIZE_PROPERTY, DEFAULT_WINDOW_SIZE);
    }
    
    /**
     * Read percentile estimation type from JMeter properties.
     * @return the configured EstimationType, or LEGACY if not set or invalid
     */
    private static EstimationType getEstimationTypeFromConfig() {
        String estimatorProp = JMeterUtils.getProperty(ESTIMATOR_PROPERTY);
        if (estimatorProp != null && !estimatorProp.trim().isEmpty()) {
            try {
                return EstimationType.valueOf(estimatorProp.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                // Invalid estimation type, use default
            }
        }
        return DEFAULT_ESTIMATOR;
    }
    
    @Override
    public void addValue(double valueSeconds) {
        statistics.addValue(valueSeconds);
    }

    @Override
    public Optional<AggregationSnapshot> getSnapshot() {
        if (statistics.getN() == 0) {
            return Optional.empty();
        }

        double min = statistics.getMin();
        double max = statistics.getMax();
        double mean = statistics.getMean();
        
        double p50 = statistics.getPercentile(50.0);
        double p90 = statistics.getPercentile(90.0);
        double p95 = statistics.getPercentile(95.0);
        double p99 = statistics.getPercentile(99.0);

        return Optional.of(new AggregationSnapshot(min, max, mean, p50, p90, p95, p99));
    }

    @Override
    public long getCount() {
        return statistics.getN();
    }
}
