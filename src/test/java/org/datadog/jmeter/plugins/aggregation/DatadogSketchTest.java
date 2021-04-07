/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import com.datadoghq.sketch.ddsketch.mapping.CubicallyInterpolatedMapping;
import com.datadoghq.sketch.ddsketch.store.UnboundedSizeDenseStore;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class DatadogSketchTest {
    private static final double RELATIVE_ACCURACY = 0.01;

    @Test
    public void testSketch(){
        DatadogSketch sketch = new DatadogSketch(new CubicallyInterpolatedMapping(RELATIVE_ACCURACY), UnboundedSizeDenseStore::new);

        for(int i = -10; i < 120; i++) {
            sketch.accept(i);
        }

        assertEquals(130, sketch.getCountValue());
        assertEquals(54.5, sketch.getAverageValue(), RELATIVE_ACCURACY);
    }
}
