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
