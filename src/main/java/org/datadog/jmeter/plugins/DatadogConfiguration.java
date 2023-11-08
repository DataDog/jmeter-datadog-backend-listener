/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.datadog.jmeter.plugins.exceptions.DatadogConfigurationException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


public class DatadogConfiguration {

    /**
     * The Datadog api key to use for submitting metrics and logs.
     */
    private String apiKey;

    /**
     * The Datadog api url to use for submitting metrics.
     */
    private String apiUrl;

    /**
     * The Datadog api url to use for submitting logs.
     */
    private String logIntakeUrl;

    /**
     * This option configures how many metrics are sent in the same http request to Datadog.
     * NOTE: Metrics are always sent at a fixed interval. This field configures how many API calls will be perfomed at the end of
     * said interval.
     */
    private int metricsMaxBatchSize;

    /**
     * This option configures the size of the logs buffer. The bigger the value, the more logs will be kept in memory
     * before being sent to Datadog and the bigger the resulting api call will be. Once that buffer size is reached, all pending log events
     * are sent.
     */
    private int logsBatchSize;

    /**
     * This options configures whether or not the plugin should submit individual results as Datadog logs.
     */
    private boolean sendResultsAsLogs;

    /**
     * User configurable. This options configures which Datadog logs to exclude from submission using a regex
     * that matches on the response code.
     */
    private Pattern excludeLogsResponseCodeRegex = null;

    /**
     * This options configures whether or not to collect metrics and logs for jmeter subresults.
     */
    private boolean includeSubResults;

    /**
     * User configurable. This options configures which samplers to include in monitoring results using a regex
     * that matches on the sampler name.
     */
    private Pattern samplersRegex = null;

    /**
     * User configurable. This options configures which tags that are needed during a performance test
     */
    private List<String> customTags;


    /* The names of configuration options that are shown in JMeter UI */
    private static final String API_URL_PARAM = "datadogUrl";
    private static final String LOG_INTAKE_URL_PARAM = "logIntakeUrl";
    private static final String API_KEY_PARAM = "apiKey";
    private static final String METRICS_MAX_BATCH_SIZE = "metricsMaxBatchSize";
    private static final String LOGS_BATCH_SIZE = "logsBatchSize";
    private static final String SEND_RESULTS_AS_LOGS = "sendResultsAsLogs";
    private static final String INCLUDE_SUB_RESULTS = "includeSubresults";
    private static final String EXCLUDE_LOGS_RESPONSE_CODE_REGEX = "excludeLogsResponseCodeRegex";
    private static final String SAMPLERS_REGEX = "samplersRegex";
    private static final String CUSTOM_TAGS ="customTags";

    /* The default values for all configuration options */
    private static final String DEFAULT_API_URL = "https://api.datadoghq.com/api/";
    private static final String DEFAULT_LOG_INTAKE_URL = "https://http-intake.logs.datadoghq.com/v1/input/";
    private static final int DEFAULT_METRICS_MAX_BATCH_SIZE = 200;
    private static final int DEFAULT_LOGS_BATCH_SIZE = 500;
    private static final boolean DEFAULT_SEND_RESULTS_AS_LOGS = true;
    private static final boolean DEFAULT_INCLUDE_SUB_RESULTS = false;
    private static final String DEFAULT_EXCLUDE_LOGS_RESPONSE_CODE_REGEX = "";
    private static final String DEFAULT_SAMPLERS_REGEX = "";
    private static final String DEFAULT_CUSTOM_TAGS = "";

    private DatadogConfiguration(){}

    public static Arguments getPluginArguments() {
        Arguments arguments = new Arguments();
        arguments.addArgument(API_KEY_PARAM, null);
        arguments.addArgument(API_URL_PARAM, DEFAULT_API_URL);
        arguments.addArgument(LOG_INTAKE_URL_PARAM, DEFAULT_LOG_INTAKE_URL);
        arguments.addArgument(METRICS_MAX_BATCH_SIZE, String.valueOf(DEFAULT_METRICS_MAX_BATCH_SIZE));
        arguments.addArgument(LOGS_BATCH_SIZE, String.valueOf(DEFAULT_LOGS_BATCH_SIZE));
        arguments.addArgument(SEND_RESULTS_AS_LOGS, String.valueOf(DEFAULT_SEND_RESULTS_AS_LOGS));
        arguments.addArgument(INCLUDE_SUB_RESULTS, String.valueOf(DEFAULT_INCLUDE_SUB_RESULTS));
        arguments.addArgument(EXCLUDE_LOGS_RESPONSE_CODE_REGEX, DEFAULT_EXCLUDE_LOGS_RESPONSE_CODE_REGEX);
        arguments.addArgument(SAMPLERS_REGEX, DEFAULT_SAMPLERS_REGEX);
        arguments.addArgument(CUSTOM_TAGS, DEFAULT_CUSTOM_TAGS);
        return arguments;
    }

    public static DatadogConfiguration parseConfiguration(BackendListenerContext context) throws DatadogConfigurationException {
        DatadogConfiguration configuration = new DatadogConfiguration();

        String apiKey = context.getParameter(API_KEY_PARAM);
        if (apiKey == null) {
            throw new DatadogConfigurationException("apiKey needs to be configured.");
        }
        configuration.apiKey = apiKey;

        configuration.apiUrl = context.getParameter(API_URL_PARAM, DEFAULT_API_URL);
        configuration.logIntakeUrl = context.getParameter(LOG_INTAKE_URL_PARAM, DEFAULT_LOG_INTAKE_URL);


        String metricsMaxBatchSize = context.getParameter(METRICS_MAX_BATCH_SIZE, String.valueOf(DEFAULT_METRICS_MAX_BATCH_SIZE));
        try {
            configuration.metricsMaxBatchSize = Integer.parseUnsignedInt(metricsMaxBatchSize);
        } catch (NumberFormatException e) {
            throw new DatadogConfigurationException("Invalid 'metricsMaxBatchSize'. Value '" + metricsMaxBatchSize + "' is not an integer.");
        }

        String logsBatchSize = context.getParameter(LOGS_BATCH_SIZE, String.valueOf(DEFAULT_LOGS_BATCH_SIZE));
        try {
            configuration.logsBatchSize = Integer.parseUnsignedInt(logsBatchSize);
        } catch (NumberFormatException e) {
            throw new DatadogConfigurationException("Invalid 'logsBatchSize'. Value '" + logsBatchSize + "' is not an integer.");
        }

        String sendResultsAsLogs = context.getParameter(SEND_RESULTS_AS_LOGS, String.valueOf(DEFAULT_SEND_RESULTS_AS_LOGS));
        if(!sendResultsAsLogs.toLowerCase().equals("false") && !sendResultsAsLogs.toLowerCase().equals("true")) {
            throw new DatadogConfigurationException("Invalid 'sendResultsAsLogs'. Value '" + sendResultsAsLogs + "' is not a boolean.");
        }
        configuration.sendResultsAsLogs = Boolean.parseBoolean(sendResultsAsLogs);

        String includeSubResults = context.getParameter(INCLUDE_SUB_RESULTS, String.valueOf(DEFAULT_INCLUDE_SUB_RESULTS));
        if(!includeSubResults.toLowerCase().equals("false") && !includeSubResults.toLowerCase().equals("true")) {
            throw new DatadogConfigurationException("Invalid 'includeSubResults'. Value '" + includeSubResults + "' is not a boolean.");
        }
        configuration.includeSubResults = Boolean.parseBoolean(includeSubResults);

        configuration.samplersRegex = Pattern.compile(context.getParameter(SAMPLERS_REGEX, DEFAULT_SAMPLERS_REGEX));

        configuration.excludeLogsResponseCodeRegex = Pattern.compile(context.getParameter(EXCLUDE_LOGS_RESPONSE_CODE_REGEX, DEFAULT_EXCLUDE_LOGS_RESPONSE_CODE_REGEX));

        String customTagsString = context.getParameter(CUSTOM_TAGS, String.valueOf(DEFAULT_CUSTOM_TAGS));
        List<String> customTags = new ArrayList<>();
        if(customTagsString.contains(",")){
            for (String item:customTagsString.split(",")) {
                customTags.add(item);
            }
        }else if(!customTagsString.equals("")){
            customTags.add(customTagsString);
        }

        configuration.customTags = customTags;

        return configuration;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getLogIntakeUrl() {
        return logIntakeUrl;
    }

    public int getMetricsMaxBatchSize() {
        return metricsMaxBatchSize;
    }

    public int getLogsBatchSize() {
        return logsBatchSize;
    }

    public boolean shouldSendResultsAsLogs() {
        return sendResultsAsLogs;
    }

    public boolean shouldIncludeSubResults() {
        return includeSubResults;
    }

    public Pattern getExcludeLogsResponseCodeRegex() {
        return excludeLogsResponseCodeRegex;
    }

    public Pattern getSamplersRegex() {
        return samplersRegex;
    }

    public List<String> getCustomTags(){
        return customTags;
    }
}
