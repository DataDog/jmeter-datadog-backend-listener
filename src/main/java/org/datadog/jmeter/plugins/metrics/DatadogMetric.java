package org.datadog.jmeter.plugins.metrics;

public class DatadogMetric {
    private DatadogMetricContext context;
    private String type;
    private double value;

    public DatadogMetric(String name, String type, double value, String[] tags) {
        this.context = new DatadogMetricContext(name, tags);
        this.type = type;
        this.value = value;
    }

    public String getName() {
        return this.context.getName();
    }

    public String[] getTags() {
        return this.context.getTags();
    }

    public double getValue() {
        return value;
    }

    public String getType() {
        return type;
    }
}
