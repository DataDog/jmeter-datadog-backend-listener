/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.aggregation;

import java.util.Optional;

/**
 * Interface for statistical collectors that aggregate numeric values and compute
 * summary statistics
 */
public interface StatsCollector {

    /**
     * Add a value to the collector.
     * @param value the value to add
     */
    void addValue(double value);

    /**
     * Get a snapshot of the current statistics.
     * @return an Optional containing the snapshot if values have been added, or empty if count is 0.
     */
    Optional<AggregationSnapshot> getSnapshot();

    /**
     * Get the count of values added.
     * @return the number of values
     */
    long getCount();
}
