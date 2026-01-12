/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.mapping.CubicallyInterpolatedMapping;
import com.datadoghq.sketch.ddsketch.store.UnboundedSizeDenseStore;
import java.util.Optional;

/**
 * StatsCollector implementation using Datadog's DDSketch algorithm.
 *
 * DDSketch provides guaranteed relative accuracy for percentile estimation.
 * This implementation uses 1% relative accuracy which is suitable for most
 * monitoring use cases.
 */
public class DDSketchStatsCollector implements StatsCollector {

    /**
     * Relative accuracy for DDSketch. 0.01 means percentile values are accurate within 1%.
     */
    public static final double RELATIVE_ACCURACY = 0.01;

    private DDSketch sketch;
    private long count;
    private double sum;

    public DDSketchStatsCollector() {
        this.sketch = createSketch();
    }

    private static DDSketch createSketch() {
        return new DDSketch(
            new CubicallyInterpolatedMapping(RELATIVE_ACCURACY),
            // Reference: https://github.com/DataDog/sketches-java/blob/29ac31297cace959ddcff0745d6d29ac663329a6/src/main/java/com/datadoghq/sketch/ddsketch/DDSketches.java#L41
            UnboundedSizeDenseStore::new
        );
    }

    @Override
    public void addValue(double value) {
        this.count++;
        this.sum += value;
        sketch.accept(value);
    }

    @Override
    public Optional<AggregationSnapshot> getSnapshot() {
        if (count == 0) {
            return Optional.empty();
        }
        
        return Optional.of(new AggregationSnapshot(
            sketch.getMinValue(),
            sketch.getMaxValue(),
            sum / count,
            sketch.getValueAtQuantile(0.50),
            sketch.getValueAtQuantile(0.90),
            sketch.getValueAtQuantile(0.95),
            sketch.getValueAtQuantile(0.99)
        ));
    }

    @Override
    public long getCount() {
        return count;
    }
}
