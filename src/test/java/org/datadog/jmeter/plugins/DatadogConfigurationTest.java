/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.PatternSyntaxException;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.datadog.jmeter.plugins.exceptions.DatadogConfigurationException;
import org.junit.Test;
import org.junit.Assert;

public class DatadogConfigurationTest {
    private static final String API_URL_PARAM = "datadogUrl";
    private static final String LOG_INTAKE_URL_PARAM = "logIntakeUrl";
    private static final String API_KEY_PARAM = "apiKey";
    private static final String METRICS_MAX_BATCH_SIZE = "metricsMaxBatchSize";
    private static final String LOGS_BATCH_SIZE = "logsBatchSize";
    private static final String SEND_RESULTS_AS_LOGS = "sendResultsAsLogs";
    private static final String INCLUDE_SUB_RESULTS = "includeSubresults";
    private static final String SAMPLERS_REGEX = "samplersRegex";

    @Test
    public void testArguments(){
        Arguments args = DatadogConfiguration.getPluginArguments();
        Assert.assertEquals(8, args.getArgumentCount());

        Map<String, String> argumentsMap = args.getArgumentsAsMap();
        Assert.assertTrue(argumentsMap.containsKey(API_URL_PARAM));
        Assert.assertTrue(argumentsMap.containsKey(LOG_INTAKE_URL_PARAM));
        Assert.assertTrue(argumentsMap.containsKey(API_KEY_PARAM));
        Assert.assertTrue(argumentsMap.containsKey(METRICS_MAX_BATCH_SIZE));
        Assert.assertTrue(argumentsMap.containsKey(LOGS_BATCH_SIZE));
        Assert.assertTrue(argumentsMap.containsKey(SEND_RESULTS_AS_LOGS));
        Assert.assertTrue(argumentsMap.containsKey(INCLUDE_SUB_RESULTS));
        Assert.assertTrue(argumentsMap.containsKey(SAMPLERS_REGEX));
    }

    @Test
    public void testValidConfiguration() throws DatadogConfigurationException {
        Map<String, String> config = new HashMap<String, String>() {
            {
                put(API_KEY_PARAM, "123456");
                put(API_URL_PARAM, "datadogUrl");
                put(LOG_INTAKE_URL_PARAM, "logIntakeUrl");
                put(METRICS_MAX_BATCH_SIZE, "10");
                put(LOGS_BATCH_SIZE, "11");
                put(SEND_RESULTS_AS_LOGS, "true");
                put(INCLUDE_SUB_RESULTS, "false");
                put(SAMPLERS_REGEX, "false");
            }
        };

        BackendListenerContext context = new BackendListenerContext(config);
        DatadogConfiguration datadogConfiguration = DatadogConfiguration.parseConfiguration(context);

        Assert.assertEquals("123456", datadogConfiguration.getApiKey());
        Assert.assertEquals("datadogUrl", datadogConfiguration.getApiUrl());
        Assert.assertEquals("logIntakeUrl", datadogConfiguration.getLogIntakeUrl());
        Assert.assertEquals(10, datadogConfiguration.getMetricsMaxBatchSize());
        Assert.assertEquals(11, datadogConfiguration.getLogsBatchSize());
        Assert.assertTrue(datadogConfiguration.shouldSendResultsAsLogs());
        Assert.assertFalse(datadogConfiguration.shouldIncludeSubResults());
    }

    @Test(expected = DatadogConfigurationException.class)
    public void testMissingApiKey() throws DatadogConfigurationException {
        Map<String, String> config = new HashMap<>();
        DatadogConfiguration.parseConfiguration(new BackendListenerContext(config));
    }

    @Test
    public void testApiKeyIsTheOnlyRequiredParam() throws DatadogConfigurationException {
        Map<String, String> config = new HashMap<String, String>() {
            {
                put("apiKey", "123456");
            }
        };
        DatadogConfiguration.parseConfiguration(new BackendListenerContext(config));
    }

    @Test(expected = DatadogConfigurationException.class)
    public void testMetricsBatchSizeNotInt() throws DatadogConfigurationException {
        Map<String, String> config = new HashMap<String, String>() {
            {
                put("apiKey", "123456");
                put("metricsMaxBatchSize", "foo");
            }
        };
        DatadogConfiguration.parseConfiguration(new BackendListenerContext(config));
    }

    @Test(expected = DatadogConfigurationException.class)
    public void testLogsBatchSizeNotInt() throws DatadogConfigurationException {
        Map<String, String> config = new HashMap<String, String>() {
            {
                put("apiKey", "123456");
                put("logsBatchSize", "foo");
            }
        };
        DatadogConfiguration.parseConfiguration(new BackendListenerContext(config));
    }

    @Test(expected = DatadogConfigurationException.class)
    public void testSendResultsAsLogsNotBoolean() throws DatadogConfigurationException {
        Map<String, String> config = new HashMap<String, String>() {
            {
                put("apiKey", "123456");
                put("sendResultsAsLogs", "foo");
            }
        };
        DatadogConfiguration.parseConfiguration(new BackendListenerContext(config));
    }

    @Test(expected = DatadogConfigurationException.class)
    public void testIncludeSubresultsNotBoolean() throws DatadogConfigurationException {
        Map<String, String> config = new HashMap<String, String>() {
            {
                put("apiKey", "123456");
                put("includeSubresults", "foo");
            }
        };
        DatadogConfiguration.parseConfiguration(new BackendListenerContext(config));
    }

    @Test(expected = PatternSyntaxException.class)
    public void testInvalidRegex() throws DatadogConfigurationException {
        Map<String, String> config = new HashMap<String, String>() {
            {
                put("apiKey", "123456");
                put(SAMPLERS_REGEX, "[");
            }
        };
        DatadogConfiguration.parseConfiguration(new BackendListenerContext(config));
    }

    @Test
    public void testValidRegex() throws DatadogConfigurationException {
        Map<String, String> config = new HashMap<String, String>() {
            {
                put("apiKey", "123456");
                put(SAMPLERS_REGEX, "[asd]\\d+");
            }
        };
        DatadogConfiguration.parseConfiguration(new BackendListenerContext(config));
    }
}
