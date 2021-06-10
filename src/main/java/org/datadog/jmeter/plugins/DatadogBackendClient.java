/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins;

import java.util.Arrays;
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
import org.datadog.jmeter.plugins.aggregation.ConcurrentAggregator;
import org.datadog.jmeter.plugins.exceptions.DatadogApiException;
import org.datadog.jmeter.plugins.exceptions.DatadogConfigurationException;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;
import org.datadog.jmeter.plugins.util.CommonUtils;
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
     * An instance of {@link DatadogConfiguration}.
     * Instantiated during the test set up phase and contains the plugin configuration.
     */
    private DatadogConfiguration configuration;

    /**
     * An instance of {@link ConcurrentAggregator}.
     * Instantiated upon creation of the DatadogBackendClient class. Aggregates metrics until instructed to flush.
     * Flushing occurs at a fixed schedule rate, @see {@link #timerHandle} and {@link #METRICS_SEND_INTERVAL}.
     */
    private ConcurrentAggregator aggregator = new ConcurrentAggregator();

    /**
     * An list of JSON log payloads to buffer calls to the Datadog API. Unlike metrics, logs are not aggregated before being sent. Thus
     * flushing of logs doesn't occur at a fixed time interval but rather once the buffer is bigger than {@link DatadogConfiguration#getLogsBatchSize()}.
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
        return DatadogConfiguration.getPluginArguments();
    }

    /**
     * Called before starting the test.
     * @param context An object used to fetch user configuration.
     * @throws DatadogConfigurationException If the configuration is invalid.
     * @throws DatadogApiException If the plugin can't connect to Datadog.
     */
    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        this.configuration = DatadogConfiguration.parseConfiguration(context);

        datadogClient = new DatadogHttpClient(configuration.getApiKey(), configuration.getApiUrl(), configuration.getLogIntakeUrl());
        boolean valid = datadogClient.validateConnection();
        if(!valid) {
            throw new DatadogApiException("Invalid apiKey");
        }

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
            Matcher matcher = configuration.getSamplersRegex().matcher(sampleResult.getSampleLabel());
            if(!matcher.find()) {
                continue;
            }
            if(configuration.shouldIncludeSubResults()) {
                for (SampleResult subResult : sampleResult.getSubResults()) {
                    this.extractData(subResult);
                }
            } else {
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
        if(configuration.shouldSendResultsAsLogs()) {
            this.extractLogs(sampleResult);
            if(logsBuffer.size() >= configuration.getLogsBatchSize()) {
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

        String threadGroup = CommonUtils.parseThreadGroup(sampleResult.getThreadName());

        List<String> allTags = new ArrayList<>(Arrays.asList("response_code:" + sampleResult.getResponseCode(), "sample_label:" + sampleResult.getSampleLabel(), "thread_group:" + threadGroup, "result:" + resultStatus));
        allTags.addAll(this.configuration.getCustomTags());
        String[] tags = allTags.toArray(new String[allTags.size()]);

        if(sampleResult.isSuccessful()) {
            aggregator.incrementCounter("jmeter.responses_count", tags, sampleResult.getSampleCount() - sampleResult.getErrorCount());
        } else {
            aggregator.incrementCounter("jmeter.responses_count", tags, sampleResult.getErrorCount());
        }

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

        String threadName = sampleResult.getThreadName();
        String threadGroup = CommonUtils.parseThreadGroup(threadName);

        if(sampleResult instanceof HTTPSampleResult) {
            payload.put("http_method", ((HTTPSampleResult) sampleResult).getHTTPMethod());
        }
        payload.put("thread_name", threadName);
        payload.put("thread_group", threadGroup);
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
        metrics.stream().collect(Collectors.groupingBy(it -> counter.getAndIncrement() / configuration.getMetricsMaxBatchSize())).values().forEach(
                x -> datadogClient.submitMetrics(x)
        );
    }
}
