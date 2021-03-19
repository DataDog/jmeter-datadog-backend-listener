package org.datadog.jmeter.plugins.metrics;

import java.util.Arrays;

public class DatadogMetricContext {
    private String name;
    private String[] tags;

    public DatadogMetricContext(String name, String[] tags){
        this.name = name;
        this.tags = tags;
    }

    public String getName() {
        return name;
    }

    public String[] getTags() {
        return tags;
    }


    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        DatadogMetricContext context = (DatadogMetricContext) obj;
        if (!context.name.equals(this.name)) return false;

        return Arrays.equals(context.tags, this.tags);
    }

    @Override
    public int hashCode() {
        int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        result = prime * result + ((tags == null) ? 0 : Arrays.hashCode(tags));
        return result;
    }
}
