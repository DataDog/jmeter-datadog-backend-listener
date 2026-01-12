/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable context for aggregating Datadog metrics.
 * Tags are defensively copied and wrapped as unmodifiable at construction.
 */
public class DatadogMetricContext {
    private final String name;
    private final List<String> tags;

    /**
     * Creates a new metric context.
     * @param name Metric name
     * @param tags List of tags
     */
    public DatadogMetricContext(String name, List<String> tags){
        this.name = name;
        this.tags = Collections.unmodifiableList(new ArrayList<>(tags));
    }

    public String getName() {
        return name;
    }

    /**
     * Returns the immutable list of tags.
     */
    public List<String> getTags() {
        return tags;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        DatadogMetricContext context = (DatadogMetricContext) obj;
        if (!context.name.equals(this.name)) return false;
        return tags.equals(context.tags);
    }

    @Override
    public int hashCode() {
        int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        result = prime * result + ((tags == null) ? 0 : tags.hashCode());
        return result;
    }
}
