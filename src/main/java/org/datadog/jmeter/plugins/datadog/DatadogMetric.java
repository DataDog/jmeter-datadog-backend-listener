package org.datadog.jmeter.plugins.datadog;

public class DatadogMetric {
    private String name;
    private String type;
    private double value;
    private String[] tags;

    public DatadogMetric(String name, String type, double value, String[] tags) {
        this.name = name;
        this.type = type;
        this.value = value;
        this.tags = tags;
    }

    public String getName() {
        return name;
    }

    public String[] getTags() {
        return tags;
    }

    public double getValue() {
        return value;
    }

    public String getType() {
        return type;
    }
}
