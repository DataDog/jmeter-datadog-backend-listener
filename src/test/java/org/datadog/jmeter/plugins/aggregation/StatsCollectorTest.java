/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import static org.datadog.jmeter.plugins.aggregation.StatsCollectorTestData.*;

import java.util.Collection;
import java.util.function.Supplier;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Parameterized unit tests for all {@link StatsCollector} implementations.
 *
 * <p>This test class verifies that each collector implementation (DDSketch, JmeterCompatible,
 * DashboardCompatible) correctly computes statistical aggregations. It uses
 * {@link StatsCollectorTestData#collectors()} to run the same tests against every implementation.
 * @see StatsCollectorTestData
 */
@RunWith(Parameterized.class)
public class StatsCollectorTest {

    @Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        return collectors();
    }

    private final Supplier<StatsCollector> factory;
    private final double tolerance;

    public StatsCollectorTest(String name, Supplier<StatsCollector> factory, double tolerance) {
        this.factory = factory;
        this.tolerance = tolerance;
    }

    @Test
    public void testHundredValues() {
        StatsCollector collector = factory.get();
        for (int i = 1; i <= 100; i++) {
            collector.addValue(i / 100.0);
        }

        Assert.assertEquals(100, collector.getCount());
        assertSnapshot(collector.getSnapshot().get(), tolerance);
    }

    @Test
    public void testEmpty() {
        StatsCollector collector = factory.get();
        Assert.assertEquals(0, collector.getCount());
        Assert.assertFalse(collector.getSnapshot().isPresent());
    }

    @Test
    public void testSingleValue() {
        StatsCollector collector = factory.get();
        collector.addValue(0.42);

        AggregationSnapshot s = collector.getSnapshot().get();
        Assert.assertEquals(1, collector.getCount());
        Assert.assertEquals(0.42, s.getAvg(), tolerance);
        Assert.assertEquals(0.42, s.getMin(), tolerance);
        Assert.assertEquals(0.42, s.getMax(), tolerance);
    }
}
