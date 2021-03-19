package org.datadog.jmeter.plugins;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.DDSketches;
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
    private Map<DatadogMetricContext, DDSketch> histograms = new HashMap<>();
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
        DDSketch sketch = histograms.get(context);
        if (sketch == null) {
            sketch = DDSketches.unboundedDense(RELATIVE_ACCURACY);
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
        Map<DatadogMetricContext, DDSketch> histrogramsPtr = histograms;

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
            DDSketch sketch = histrogramsPtr.get(context);
            metrics.add(new DatadogMetric(context.getName() + ".max", "gauge", sketch.getMaxValue(), context.getTags()));
            metrics.add(new DatadogMetric(context.getName() + ".min", "gauge", sketch.getMinValue(), context.getTags()));
            metrics.add(new DatadogMetric(context.getName() + ".p99", "gauge", sketch.getValueAtQuantile(0.99), context.getTags()));
            metrics.add(new DatadogMetric(context.getName() + ".p95", "gauge", sketch.getValueAtQuantile(0.95), context.getTags()));
            metrics.add(new DatadogMetric(context.getName() + ".p90", "gauge", sketch.getValueAtQuantile(0.90), context.getTags()));
            metrics.add(new DatadogMetric(context.getName() + ".p50", "gauge", sketch.getValueAtQuantile(0.50), context.getTags()));
        }

        return metrics;
    }


}
