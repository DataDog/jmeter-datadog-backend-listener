/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;
import org.datadog.jmeter.plugins.metrics.DatadogMetricContext;

/**
 * Thread-safe aggregator for interval-based JMeter metrics.
 * Aggregates counters, gauges, and histograms until flushed, at which it's reset.
 */
public class IntervalAggregator {
    private final Supplier<StatsCollector> statsFactory;
    private Map<DatadogMetricContext, Long> counters = new HashMap<>();
    private Map<DatadogMetricContext, Double> gauges = new HashMap<>();
    private Map<DatadogMetricContext, StatsCollector> histograms = new HashMap<>();
    private Lock lock = new ReentrantLock();
    Semaphore testOnlyBlocker = null;

    /**
     * Create aggregator with pluggable stats collector.
     * @param statsFactory factory for creating StatsCollector instances for histograms
     */
    public IntervalAggregator(Supplier<StatsCollector> statsFactory) {
        this.statsFactory = statsFactory;
    }

    public void incrementCounter(String name, List<String> tags, long incrementValue) {
        incrementCounter(new DatadogMetricContext(name, tags), incrementValue);
    }

    public void incrementCounter(DatadogMetricContext context, long incrementValue) {
        lock.lock();
        try {
            Long previousValue = counters.getOrDefault(context, (long) 0);
            if(testOnlyBlocker != null) {
                testOnlyBlocker.acquireUninterruptibly();
            }
            counters.put(context, previousValue + incrementValue);
        } finally {
            lock.unlock();
        }
    }

    public void addGauge(String name, List<String> tags, double value) {
        addGauge(new DatadogMetricContext(name, tags), value);
    }

    public void addGauge(DatadogMetricContext context, double value) {
        lock.lock();
        try {
            if(testOnlyBlocker != null) {
                testOnlyBlocker.acquireUninterruptibly();
            }
            gauges.put(context, value);
        } finally {
            lock.unlock();
        }
    }

    public void histogram(String name, List<String> tags, double value) {
        histogram(new DatadogMetricContext(name, tags), value);
    }

    public void histogram(DatadogMetricContext context, double value) {
        lock.lock();
        try {
            StatsCollector collector = histograms.get(context);
            if (collector == null) {
                collector = statsFactory.get();
                histograms.put(context, collector);
            }
            if(testOnlyBlocker != null) {
                testOnlyBlocker.acquireUninterruptibly();
            }
            collector.addValue(value);
        } finally {
            lock.unlock();
        }
    }

    public List<DatadogMetric> flushMetrics() {
        lock.lock();
        Map<DatadogMetricContext, Long> countersPtr;
        Map<DatadogMetricContext, Double> gaugesPtr;
        Map<DatadogMetricContext, StatsCollector> histogramsPtr;

        try {
            countersPtr = counters;
            gaugesPtr = gauges;
            histogramsPtr = histograms;

            counters = new HashMap<>();
            gauges = new HashMap<>();
            histograms = new HashMap<>();
        } finally {
            lock.unlock();
        }

        List<DatadogMetric> metrics = new ArrayList<>();
        for(Map.Entry<DatadogMetricContext, Long> entry : countersPtr.entrySet()) {
            metrics.add(new DatadogMetric(
                entry.getKey().getName(),
                "count",
                entry.getValue(),
                entry.getKey().getTags()
            ));
        }
        for(Map.Entry<DatadogMetricContext, Double> entry : gaugesPtr.entrySet()) {
            metrics.add(new DatadogMetric(
                entry.getKey().getName(),
                "gauge",
                entry.getValue(),
                entry.getKey().getTags()
            ));
        }
        for(Map.Entry<DatadogMetricContext, StatsCollector> entry : histogramsPtr.entrySet()) {
            HistogramMetrics.emit(
                entry.getKey().getName(),
                entry.getKey().getTags(),
                entry.getValue(),
                true,
                metrics
            );
        }

        return metrics;
    }
}

