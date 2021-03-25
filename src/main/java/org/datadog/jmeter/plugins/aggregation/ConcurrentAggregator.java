/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import com.datadoghq.sketch.ddsketch.mapping.CubicallyInterpolatedMapping;
import com.datadoghq.sketch.ddsketch.store.UnboundedSizeDenseStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;
import org.datadog.jmeter.plugins.metrics.DatadogMetricContext;

public class ConcurrentAggregator {
    private static final double RELATIVE_ACCURACY = 0.01;
    private Map<DatadogMetricContext, Long> counters = new HashMap<>();
    private Map<DatadogMetricContext, Double> gauges = new HashMap<>();
    private Map<DatadogMetricContext, DatadogSketch> histograms = new HashMap<>();
    private Lock lock = new ReentrantLock();
    Semaphore testOnlyBlocker;

    public void incrementCounter(String name, String[] tags, int incrementValue) {
        incrementCounter(new DatadogMetricContext(name, tags), incrementValue);
    }
    public void incrementCounter(DatadogMetricContext context, int incrementValue) {
        lock.lock();
        Long previousValue = counters.getOrDefault(context, (long) 0);
        if(testOnlyBlocker != null) {
            testOnlyBlocker.acquireUninterruptibly();
        }
        counters.put(context, previousValue + incrementValue);

        lock.unlock();
    }

    public void addGauge(String name, String[] tags, double value) {
        addGauge(new DatadogMetricContext(name, tags), value);
    }
    public void addGauge(DatadogMetricContext context, double value) {
        lock.lock();

        gauges.put(context, value);

        lock.unlock();
    }

    public void histogram(String name, String[] tags, double value) {
        histogram(new DatadogMetricContext(name, tags), value);
    }
    public void histogram(DatadogMetricContext context, double value) {
        lock.lock();
        DatadogSketch sketch = histograms.get(context);
        if (sketch == null) {
            sketch = new DatadogSketch(new CubicallyInterpolatedMapping(RELATIVE_ACCURACY), UnboundedSizeDenseStore::new);
            histograms.put(context, sketch);
        }
        if(testOnlyBlocker != null) {
            testOnlyBlocker.acquireUninterruptibly();
        }

        sketch.accept(value);
        lock.unlock();
    }

    public List<DatadogMetric> flushMetrics() {
        lock.lock();
        Map<DatadogMetricContext, Long> countersPtr = counters;
        Map<DatadogMetricContext, Double> gaugesPtr = gauges;
        Map<DatadogMetricContext, DatadogSketch> histrogramsPtr = histograms;

        counters = new HashMap<>();
        gauges = new HashMap<>();
        histograms = new HashMap<>();
        lock.unlock();

        List<DatadogMetric> metrics = new ArrayList<>();
        for(DatadogMetricContext context : countersPtr.keySet()) {
            Long counterValue = countersPtr.get(context);
            metrics.add(new DatadogMetric(context.getName(), "count", counterValue, context.getTags()));
        }
        for(DatadogMetricContext context : gaugesPtr.keySet()) {
            Double counterValue = gaugesPtr.get(context);
            metrics.add(new DatadogMetric(context.getName(), "gauge", counterValue, context.getTags()));
        }
        for(DatadogMetricContext context : histrogramsPtr.keySet()) {
            DatadogSketch sketch = histrogramsPtr.get(context);
            metrics.add(new DatadogMetric(context.getName() + ".max", "gauge", sketch.getMaxValue(), context.getTags()));
            metrics.add(new DatadogMetric(context.getName() + ".min", "gauge", sketch.getMinValue(), context.getTags()));
            metrics.add(new DatadogMetric(context.getName() + ".p99", "gauge", sketch.getValueAtQuantile(0.99), context.getTags()));
            metrics.add(new DatadogMetric(context.getName() + ".p95", "gauge", sketch.getValueAtQuantile(0.95), context.getTags()));
            metrics.add(new DatadogMetric(context.getName() + ".p90", "gauge", sketch.getValueAtQuantile(0.90), context.getTags()));
            metrics.add(new DatadogMetric(context.getName() + ".avg", "gauge", sketch.getAverageValue(), context.getTags()));
            metrics.add(new DatadogMetric(context.getName() + ".count", "count", sketch.getCountValue(), context.getTags()));
        }

        return metrics;
    }


}
