/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.exceptions;

public class DatadogConfigurationException extends Exception {
    public DatadogConfigurationException(String message){
        super(message);
    }
}
