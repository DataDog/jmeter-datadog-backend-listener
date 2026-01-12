/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import org.apache.commons.math3.stat.descriptive.rank.Percentile.EstimationType;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;
import java.util.Properties;

/**
 * Tests for DashboardCompatibleStatsCollector configuration and JMeter property integration.
 */
public class DashboardCompatibleStatsCollectorPropertyTest {

    @Before
    public void setUp() throws Exception {
        // Initialize JMeter properties
        if (JMeterUtils.getJMeterProperties() == null) {
            java.io.File tempProps = java.io.File.createTempFile("jmeter", ".properties");
            tempProps.deleteOnExit();
            JMeterUtils.loadJMeterProperties(tempProps.getAbsolutePath());
        }
    }

    @Test
    public void testEstimationTypeDefault() {
        DashboardCompatibleStatsCollector collector = new DashboardCompatibleStatsCollector(20000, EstimationType.LEGACY);
        
        collector.addValue(100.0);
        collector.addValue(200.0);
        collector.addValue(300.0);
        
        Optional<AggregationSnapshot> snapshot = collector.getSnapshot();
        Assert.assertTrue(snapshot.isPresent());
        Assert.assertEquals(100.0, snapshot.get().getMin(), 0.01);
        Assert.assertEquals(300.0, snapshot.get().getMax(), 0.01);
    }

    @Test
    public void testEstimationTypeR3() {
        DashboardCompatibleStatsCollector collector = new DashboardCompatibleStatsCollector(20000, EstimationType.R_3);
        
        collector.addValue(100.0);
        collector.addValue(200.0);
        collector.addValue(300.0);
        
        Optional<AggregationSnapshot> snapshot = collector.getSnapshot();
        Assert.assertTrue(snapshot.isPresent());
        Assert.assertEquals(100.0, snapshot.get().getMin(), 0.01);
        Assert.assertEquals(300.0, snapshot.get().getMax(), 0.01);
    }

    @Test
    public void testWindowSizeConfig() {
        DashboardCompatibleStatsCollector collector = new DashboardCompatibleStatsCollector(3, EstimationType.LEGACY);
        
        collector.addValue(10.0);
        collector.addValue(20.0);
        collector.addValue(30.0);
        collector.addValue(40.0);
        collector.addValue(50.0);
        
        Assert.assertEquals(3, collector.getCount());
    }

    @Test
    public void testFactoryWithProperties() {
        Properties props = JMeterUtils.getJMeterProperties();
        if (props != null) {
            props.setProperty("jmeter.reportgenerator.statistic_window", "5000");
            props.setProperty("backend_metrics_percentile_estimator", "R_7");
            
            DashboardCompatibleStatsCollector collector = DashboardCompatibleStatsCollector.createFromJMeterConfig();
            
            collector.addValue(100.0);
            collector.addValue(200.0);
            
            Optional<AggregationSnapshot> snapshot = collector.getSnapshot();
            Assert.assertTrue(snapshot.isPresent());
            Assert.assertEquals(2, collector.getCount());
            
            props.remove("jmeter.reportgenerator.statistic_window");
            props.remove("backend_metrics_percentile_estimator");
        }
    }

    @Test
    public void testNoArgConstructorDelegates() {
        DashboardCompatibleStatsCollector collector = new DashboardCompatibleStatsCollector();
        
        collector.addValue(100.0);
        collector.addValue(200.0);
        
        Optional<AggregationSnapshot> snapshot = collector.getSnapshot();
        Assert.assertTrue(snapshot.isPresent());
        Assert.assertEquals(2, collector.getCount());
    }
}

