/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.minidev.json.JSONObject;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.jmeter.visualizers.backend.UserMetric;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of AbstractBackendListenerClient that aggregates and forwards metrics and log events
 * to Datadog.
 */
@SuppressWarnings("unused")
public class DatadogBackendClient extends AbstractBackendListenerClient implements Runnable {

    /**
     * The logger. Anything written with it appears on JMeter console tab.
     */
    private static final Logger log = LoggerFactory.getLogger(DatadogBackendClient.class);

    /**
     * An instance of {@link DatadogHttpClient}.
     * Instantiated during the test set up phase, and used to send metrics and logs.
     */
    private DatadogHttpClient datadogClient;

    /**
     * An instance of {@link ConcurrentAggregator}.
     * Instantiated upon creation of the DatadogBackendClient class. Aggregates metrics until instructed to flush.
     * Flushing occurs at a fixed schedule rate, @see {@link #timerHandle} and {@link #METRICS_SEND_INTERVAL}.
     */
    private ConcurrentAggregator aggregator = new ConcurrentAggregator();

    /**
     * An list of JSON log payloads to buffer calls to the Datadog API. Unlike metrics, logs are not aggregated before being sent. Thus
     * flushing of logs doesn't occur at a fixed time interval but rather once the buffer is bigger than {@link #logsBatchSize}.
     */
    private List<JSONObject> logsBuffer = new ArrayList<>();


    /**
     * How often to send metrics. During this interval metrics are aggregated (i.e multiple counts values are added together, gauge
     * replaces the previous value etc.). At the end of that interval the result of aggregation is sent to Datadog.
     */
    private static final long METRICS_SEND_INTERVAL = JMeterUtils.getPropDefault("datadog.send_interval", 10);

    /**
     * Used to schedule flushing of metrics every {@link #METRICS_SEND_INTERVAL} seconds.
     */
    private ScheduledExecutorService scheduler;

    /**
     * The resulting future object after scheduling. Keeping the reference as an instance variable to be able to cancel it.
     */
    private ScheduledFuture<?> timerHandle;


    /**
     * User configurable. This option configures how many metrics are sent in the same http request to Datadog.
     * NOTE: Metrics are always sent at a fixed interval. This field configures how many API calls will be perfomed at the end of
     * said interval.
     */
    private int metricsBatchSize = DEFAULT_METRICS_BATCH_SIZE;

    /**
     * User configurable. This option configures the size of the logs buffer. The bigger the value, the more logs will be kept in memory
     * before being sent to Datadog and the bigger the resulting api call will be. Once that buffer size is reached, all pending log events
     * are sent.
     */
    private int logsBatchSize = DEFAULT_LOGS_BATCH_SIZE;

    /**
     * User configurable. This options configures whether or not the plugin should submit individual results as Datadog logs.
     */
    private boolean sendResultsAsLogs = DEFAULT_SEND_RESULTS_AS_LOGS;

    /**
     * User configurable. This options configures whether or not to collect metrics and logs for jmeter subresults.
     */
    private boolean includeSubResults = DEFAULT_INCLUDE_SUB_RESULTS;

    /**
     * User configurable. This options configures which samplers to include in monitoring results using a regex
     * that matches on the sampler name.
     */
    private Pattern samplersRegex = null;

    /* The names of configuration options that are shown in JMeter UI */
    private static final String API_URL_PARAM = "datadogUrl";
    private static final String LOG_INTAKE_URL_PARAM = "logIntakeUrl";
    private static final String API_KEY_PARAM = "apiKey";
    private static final String METRICS_BATCH_SIZE = "metricsMaxBatchSize";
    private static final String LOGS_BATCH_SIZE = "logsBatchSize";
    private static final String SEND_RESULTS_AS_LOGS = "sendResultsAsLogs";
    private static final String INCLUDE_SUB_RESULTS = "includeSubresults";
    private static final String SAMPLERS_REGEX = "samplersRegex";

    /* The default values for all configuration options */
    private static final String DEFAULT_API_URL = "https://api.datadoghq.com/api/";
    private static final String DEFAULT_LOG_INTAKE_URL = "https://http-intake.logs.datadoghq.com/v1/input/";
    private static final int DEFAULT_METRICS_BATCH_SIZE = 200;
    private static final int DEFAULT_LOGS_BATCH_SIZE = 500;
    private static final boolean DEFAULT_SEND_RESULTS_AS_LOGS = true;
    private static final boolean DEFAULT_INCLUDE_SUB_RESULTS = false;
    private static final String DEFAULT_SAMPLERS_REGEX = "";

    /**
     * Calls at a fixed schedule and sends metrics to Datadog.
     */
    @Override
    public void run() {
        sendMetrics();
    }

    /**
     * Used by JMeter to know the list of parameters to show in the UI.
     * @return the parameters as an Arguments object.
     */
    @Override
    public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
        arguments.addArgument(API_KEY_PARAM, null);
        arguments.addArgument(API_URL_PARAM, DEFAULT_API_URL);
        arguments.addArgument(LOG_INTAKE_URL_PARAM, DEFAULT_LOG_INTAKE_URL);
        arguments.addArgument(METRICS_BATCH_SIZE, String.valueOf(DEFAULT_METRICS_BATCH_SIZE));
        arguments.addArgument(LOGS_BATCH_SIZE, String.valueOf(DEFAULT_LOGS_BATCH_SIZE));
        arguments.addArgument(SEND_RESULTS_AS_LOGS, String.valueOf(DEFAULT_SEND_RESULTS_AS_LOGS));
        arguments.addArgument(INCLUDE_SUB_RESULTS, String.valueOf(DEFAULT_INCLUDE_SUB_RESULTS));
        arguments.addArgument(SAMPLERS_REGEX, DEFAULT_SAMPLERS_REGEX);

        return arguments;
    }

    /**
     * Called before starting the test.
     * @param context An object used to fetch user configuration.
     * @throws Exception If the configuration is invalid or if the plugin can't connect to Datadog.
     */
    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        String apiKey = context.getParameter(API_KEY_PARAM);
        String apiUrl = context.getParameter(API_URL_PARAM, DEFAULT_API_URL);
        String logIntakeUrl = context.getParameter(LOG_INTAKE_URL_PARAM, DEFAULT_LOG_INTAKE_URL);
        String metricsBatchSize = context.getParameter(METRICS_BATCH_SIZE, String.valueOf(DEFAULT_METRICS_BATCH_SIZE));
        String logsBatchSize = context.getParameter(LOGS_BATCH_SIZE, String.valueOf(DEFAULT_LOGS_BATCH_SIZE));
        String sendResultsAsLogs = context.getParameter(SEND_RESULTS_AS_LOGS, String.valueOf(DEFAULT_SEND_RESULTS_AS_LOGS));
        String samplersRegex = context.getParameter(SAMPLERS_REGEX, DEFAULT_SAMPLERS_REGEX);

        if (apiKey == null) {
            throw new Exception("apiKey needs to be configured.");
        }

        datadogClient = new DatadogHttpClient(apiKey, apiUrl, logIntakeUrl);
        boolean valid = datadogClient.validateConnection();
        if(!valid) {
            throw new Exception("Invalid apiKey");
        }

        try {
            this.metricsBatchSize = Integer.parseUnsignedInt(metricsBatchSize);
        } catch (NumberFormatException e) {
            throw new Exception("Invalid 'metricsBatchSize'. Value '" + metricsBatchSize + "' is not an integer.");
        }

        try {
            this.logsBatchSize = Integer.parseUnsignedInt(logsBatchSize);
        } catch (NumberFormatException e) {
            throw new Exception("Invalid 'metricsBatchSize'. Value '" + metricsBatchSize + "' is not an integer.");
        }

        if(!sendResultsAsLogs.toLowerCase().equals("false") && !sendResultsAsLogs.toLowerCase().equals("true")) {
            throw new Exception("Invalid 'sendResultsAsLogs'. Value '" + sendResultsAsLogs + "' is not a boolean.");
        }
        this.sendResultsAsLogs = Boolean.parseBoolean(sendResultsAsLogs);
        this.samplersRegex = Pattern.compile(samplersRegex);

        scheduler = Executors.newScheduledThreadPool(1);
        this.timerHandle = scheduler.scheduleAtFixedRate(this, METRICS_SEND_INTERVAL, METRICS_SEND_INTERVAL, TimeUnit.SECONDS);
        super.setupTest(context);
    }

    /**
     * Called after completion of the test.
     * @param context unused - An object used to fetch user configuration.
     * @throws Exception If something goes wrong while stopping
     */
    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        this.timerHandle.cancel(false);
        this.scheduler.shutdown();
        try {
            scheduler.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.error("Error waiting for end of scheduler");
            Thread.currentThread().interrupt();
        }

        this.sendMetrics();

        if (this.logsBuffer.size() > 0) {
            this.datadogClient.submitLogs(this.logsBuffer);
            this.logsBuffer.clear();
        }
        this.datadogClient = null;
        super.teardownTest(context);
    }

    /**
     * Main entry point, this method is called when new results are computed.
     * @param list The results to parse.
     * @param backendListenerContext unused - An object used to fetch user configuration.
     */
    @Override
    public void handleSampleResults(List<SampleResult> list, BackendListenerContext backendListenerContext) {
        for (SampleResult sampleResult : list) {
            Matcher matcher = samplersRegex.matcher(sampleResult.getSampleLabel());
            if(!matcher.find()) {
                continue;
            }
            if(this.includeSubResults) {
                for (SampleResult subResult : sampleResult.getSubResults()) {
                    this.extractData(subResult);
                }
            } else {
                // TODO: Should we also include this one even when includeSubResults is set?
                this.extractData(sampleResult);
            }
        }
    }

    /**
     * Called for each individual result. It calls {@link #extractMetrics(SampleResult)} and {@link #extractLogs(SampleResult)}.
     * @param sampleResult the result
     */
    private void extractData(SampleResult sampleResult) {
        UserMetric userMetrics = this.getUserMetrics();
        userMetrics.add(sampleResult);
        this.extractMetrics(sampleResult);
        if(this.sendResultsAsLogs) {
            this.extractLogs(sampleResult);
            if(logsBuffer.size() >= this.logsBatchSize) {
                datadogClient.submitLogs(logsBuffer);
                logsBuffer.clear();
            }
        }
    }

    /**
     * Called for each individual result. It extracts metrics and give them to the {@link ConcurrentAggregator} instance for aggregation.
     * @param sampleResult the result
     */
    private void extractMetrics(SampleResult sampleResult) {
        String resultStatus = sampleResult.isSuccessful() ? "ok" : "ko";
        String[] tags = new String[] {
                "response_code:" + sampleResult.getResponseCode(), "sample_label:" + sampleResult.getSampleLabel(), "result:" + resultStatus
        };

        if(sampleResult.isSuccessful()) {
            aggregator.incrementCounter("jmeter.responses_count", tags, sampleResult.getSampleCount() - sampleResult.getErrorCount());
        } else {
            aggregator.incrementCounter("jmeter.responses_count", tags, sampleResult.getErrorCount());
        }

        for(SampleResult r : sampleResult.getSubResults()) {
            if(sampleResult.isSuccessful()) {
                aggregator.incrementCounter("jmeter.responses_count", tags, sampleResult.getSampleCount() - sampleResult.getErrorCount());
            } else {
                aggregator.incrementCounter("jmeter.responses_count", tags, sampleResult.getErrorCount());
            }
        }
        // TODO: Add responses count for each sub result
        // TODO: Add request per second
        aggregator.histogram("jmeter.response_time", tags, sampleResult.getTime() / 1000f);
        aggregator.histogram("jmeter.bytes_sent", tags, sampleResult.getSentBytes());
        aggregator.histogram("jmeter.bytes_received", tags, sampleResult.getBytesAsLong());
        aggregator.histogram("jmeter.latency", tags, sampleResult.getLatency() / 1000f);

    }

    /**
     * Called for each individual result. It extracts logs and append them to the {@link #logsBuffer} buffer.
     * @param sampleResult the result
     */
    private void extractLogs(SampleResult sampleResult) {
        JSONObject payload = new JSONObject();

        if(sampleResult instanceof HTTPSampleResult) {
            payload.put("http_method", ((HTTPSampleResult) sampleResult).getHTTPMethod());
        }
        payload.put("thread_name", sampleResult.getThreadName());
        payload.put("sample_start_time", (double) sampleResult.getStartTime());
        payload.put("sample_end_time", (double) sampleResult.getEndTime());
        payload.put("load_time", (double) sampleResult.getTime());
        payload.put("connect_time", (double) sampleResult.getConnectTime());
        payload.put("latency", (double) sampleResult.getLatency());
        payload.put("bytes", (double) sampleResult.getBytesAsLong());
        payload.put("sent_bytes", (double) sampleResult.getSentBytes());
        payload.put("headers_size", (double) sampleResult.getHeadersSize());
        payload.put("body_size", (double) sampleResult.getBodySizeAsLong());
        payload.put("sample_count", (double) sampleResult.getSampleCount());
        payload.put("error_count", (double) sampleResult.getErrorCount());
        payload.put("data_type", sampleResult.getDataType());
        payload.put("response_code", sampleResult.getResponseCode());
        payload.put("url", sampleResult.getUrlAsString());
        payload.put("sample_label", sampleResult.getSampleLabel());
        payload.put("idle_time", (double) sampleResult.getIdleTime());
        payload.put("group_threads", (double) sampleResult.getGroupThreads());
        payload.put("all_threads", (double) sampleResult.getAllThreads());

        payload.put("ddsource", "jmeter");
        payload.put("message", sampleResult.getResponseMessage());
        payload.put("content_type", sampleResult.getContentType());
        payload.put("data_encoding", sampleResult.getDataEncodingNoDefault());

        // NOTE: Headers are not extracted as they might contain secrets.

        this.logsBuffer.add(payload);
    }

    /**
     * Called on a fixed schedule. Computes thread related metrics, collects and reset the aggregator and send all metrics to Datadog
     * in batches.
     */
    private void sendMetrics() {
        UserMetric userMetrics = getUserMetrics();
        String[] tags = new String[]{};
        aggregator.addGauge("jmeter.active_threads.min", tags, userMetrics.getMinActiveThreads());
        aggregator.addGauge("jmeter.active_threads.max", tags, userMetrics.getMaxActiveThreads());
        aggregator.addGauge("jmeter.active_threads.avg", tags, userMetrics.getMeanActiveThreads());
        aggregator.addGauge("jmeter.threads.finished", tags, userMetrics.getFinishedThreads());
        aggregator.addGauge("jmeter.threads.started", tags, userMetrics.getStartedThreads());

        List<DatadogMetric> metrics = aggregator.flushMetrics();

        AtomicInteger counter = new AtomicInteger();
        metrics.stream().collect(Collectors.groupingBy(it -> counter.getAndIncrement() / this.metricsBatchSize)).values().forEach(
                x -> datadogClient.submitMetrics(x)
        );
    }
}
