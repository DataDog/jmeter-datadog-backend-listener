package org.datadog.jmeter.plugins;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;
import org.datadog.jmeter.plugins.metrics.DatadogMetricContext;

import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;


public class ConcurrentAggregatorTest {
    private ConcurrentAggregator aggregator;
    private static final int N_THREADS = 50;

    @Before
    public void setUp() {
        aggregator = new ConcurrentAggregator();

        aggregator.testOnlyBlocker = new Semaphore(0);

        // All tests should fail if you uncomment this
        //aggregator.lock = mock(ReentrantLock.class);
        //doNothing().when(aggregator.lock).lock();
        //doNothing().when(aggregator.lock).unlock();
    }

    @Test
    public void testCounterIncrement() throws InterruptedException {
        String metricName = "foo";
        String[] tags = new String[]{};
        DatadogMetricContext ctx = new DatadogMetricContext(metricName, tags);

        ExecutorService service = Executors.newFixedThreadPool(N_THREADS);
        for(int i = 0; i < N_THREADS; i++){
            service.execute(() -> aggregator.incrementCounter(ctx, 1));
        }
        aggregator.testOnlyBlocker.release(N_THREADS);
        service.awaitTermination(2, TimeUnit.SECONDS);

        List<DatadogMetric> metrics = aggregator.flushMetrics();
        assertEquals(1, metrics.size());
        assertEquals(metricName, metrics.get(0).getName());
        assertEquals("count", metrics.get(0).getType());
        assertEquals(N_THREADS, (int)metrics.get(0).getValue());
    }

    @Test
    public void testGauge() throws InterruptedException {
        String metricName = "foo";
        String[] tags = new String[]{};
        DatadogMetricContext ctx = new DatadogMetricContext(metricName, tags);

        ExecutorService service = Executors.newFixedThreadPool(N_THREADS);
        for(int i = 0; i < N_THREADS; i++){
            service.execute(() -> aggregator.addGauge(ctx, 55));
        }
        aggregator.testOnlyBlocker.release(N_THREADS);
        service.awaitTermination(2, TimeUnit.SECONDS);

        List<DatadogMetric> metrics = aggregator.flushMetrics();
        assertEquals(1, metrics.size());
        assertEquals(metricName, metrics.get(0).getName());
        assertEquals("gauge", metrics.get(0).getType());
        assertEquals(55, (int)metrics.get(0).getValue());
    }

    @Test
    public void testSketch() throws InterruptedException {
        String metricName = "foo";
        String[] tags = new String[]{};
        DatadogMetricContext ctx = new DatadogMetricContext(metricName, tags);

        ExecutorService service = Executors.newFixedThreadPool(N_THREADS);
        for(int i = 0; i < N_THREADS; i++){
            final int x = i + 1;
            service.execute(() -> aggregator.histogram(ctx, x));
        }
        aggregator.testOnlyBlocker.release(N_THREADS);
        service.awaitTermination(2, TimeUnit.SECONDS);

        List<DatadogMetric> metrics = aggregator.flushMetrics();
        assertEquals(6, metrics.size());

        String[] suffixes = new String[] {".max", ".min", ".p99", ".p95", ".p90", ".p50"};
        double[] values = new double[] {49, 1, 48, 47, 45, 24};
        for(int i = 0; i < suffixes.length; i++) {
            assertEquals(metricName + suffixes[i], metrics.get(i).getName());
            assertEquals("gauge", metrics.get(i).getType());
            assertEquals(suffixes[i], values[i], (int)metrics.get(i).getValue(), 1e-10);
        }
    }
}
