package org.datadog.jmeter.plugins.aggregation;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.mapping.IndexMapping;
import com.datadoghq.sketch.ddsketch.store.Store;
import java.util.function.Supplier;

public class DatadogSketch extends DDSketch {

    private long count = 0;
    private double sum = 0;

    public DatadogSketch(IndexMapping indexMapping, Supplier<Store> storeSupplier) {
        super(indexMapping, storeSupplier);
    }

    @Override
    public void accept(double value) {
        this.count += 1;
        this.sum += value;
        super.accept(value);
    }

    public long getCountValue() {
        return this.count;
    }

    public double getAverageValue() {
        return this.sum / this.count;
    }
}
