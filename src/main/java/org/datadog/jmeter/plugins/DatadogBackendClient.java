/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import net.minidev.json.JSONObject;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.jmeter.visualizers.backend.UserMetric;
import org.datadog.jmeter.plugins.aggregation.CumulativeAggregator;
import org.datadog.jmeter.plugins.aggregation.IntervalAggregator;
import org.datadog.jmeter.plugins.aggregation.DDSketchStatsCollector;
import org.datadog.jmeter.plugins.aggregation.DashboardCompatibleStatsCollector;
import org.datadog.jmeter.plugins.aggregation.JmeterCompatibleStatsCollector;
import org.datadog.jmeter.plugins.aggregation.StatsCollector;
import org.datadog.jmeter.plugins.exceptions.DatadogApiException;
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
     * Base custom tags extended with runner identity (if available).
     */
    private List<String> customTagsWithRunner = new ArrayList<>();


    /**
     * An instance of {@link IntervalAggregator}.
     * Instantiated upon creation of the DatadogBackendClient class. Aggregates metrics, flushes them and resets them at every interval.
     * Flushing occurs at a fixed schedule rate, @see {@link #timerHandle} and {@link #METRICS_SEND_INTERVAL_SECONDS}.
     */
    private IntervalAggregator intervalAggregator;

    /**
     * Cumulative aggregator for computing cumulative metrics per label and total.
     * These metrics are sent periodically during the test and at the end. The metrics are only reset at the end of the test.
     */
    private CumulativeAggregator cumulativeAggregator;

    /**
     * A list of JSON log payloads to buffer calls to the Datadog API. Unlike metrics, logs are not aggregated before being sent. Thus
     * flushing of logs doesn't occur at a fixed time interval but rather once the buffer is bigger than {@link DatadogConfiguration#getLogsBatchSize()}.
     */
    private List<JSONObject> logsBuffer = new ArrayList<>();


    /**
     * How often to send metrics (in seconds). During this interval metrics are aggregated (i.e multiple counts values are added together, gauge
     * replaces the previous value etc.). At the end of that interval the result of aggregation is sent to Datadog.
     */
    private static final long METRICS_SEND_INTERVAL_SECONDS = JMeterUtils.getPropDefault("datadog.send_interval", 10);

    /**
     * Used to schedule flushing of metrics every {@link #METRICS_SEND_INTERVAL_SECONDS} seconds.
     */
    private ScheduledExecutorService scheduler;

    private long testStartTimestamp;

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
     * @throws Exception If the configuration is invalid or the plugin can't connect to Datadog.
     */
    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        this.configuration = DatadogConfiguration.parseConfiguration(context);
        this.testStartTimestamp = System.currentTimeMillis();
        initializeRunnerTags();

        datadogClient = new DatadogHttpClient(configuration.getApiKey(), configuration.getApiUrl(), configuration.getLogIntakeUrl());

        boolean valid = datadogClient.validateConnection();
        if(!valid) {
            throw new DatadogApiException("Invalid apiKey");
        }

        scheduler = Executors.newScheduledThreadPool(1);
        this.timerHandle = scheduler.scheduleAtFixedRate(this, METRICS_SEND_INTERVAL_SECONDS, METRICS_SEND_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // Choose StatsCollector implementation based on configuration
        Supplier<StatsCollector> statsFactory;
        boolean countSubsamplesAsSingle;
        switch (configuration.getStatisticsCalculationMode()) {
            case DDSKETCH:
                statsFactory = DDSketchStatsCollector::new;
                countSubsamplesAsSingle = false;
                break;
            case DASHBOARD:
                statsFactory = DashboardCompatibleStatsCollector::new;
                countSubsamplesAsSingle = true;
                break;
            case AGGREGATE_REPORT:
                statsFactory = JmeterCompatibleStatsCollector::new;
                countSubsamplesAsSingle = false;
                break;
            default:
                throw new IllegalStateException("Unknown statistics mode: " + configuration.getStatisticsCalculationMode());
        }

        this.intervalAggregator = new IntervalAggregator(statsFactory);
        this.cumulativeAggregator = new CumulativeAggregator(statsFactory, countSubsamplesAsSingle);
        
        submitIntegrationEvent("JMeter Test Started", "info");
        
        super.setupTest(context);
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
            this.extractData(sampleResult);
        }
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

        submitIntegrationEvent("JMeter Test Ended", "success");

        if (this.cumulativeAggregator != null) {
            List<DatadogMetric> finalMetrics = this.cumulativeAggregator.buildMetrics(
                CommonUtils.combineTags(this.customTagsWithRunner, "final_result:true")
            );

            // Add final_result metrics (emitted only at test end, without final_result tag)
            List<DatadogMetric> finalResultMetrics = this.cumulativeAggregator.buildFinalMetrics(
                this.customTagsWithRunner
            );
            finalMetrics.addAll(finalResultMetrics);

            if (log.isInfoEnabled()) {
                log.info("Sending {} final aggregate metrics to Datadog", finalMetrics.size());
            }
            if (log.isDebugEnabled()) {
                for (DatadogMetric m : finalMetrics) {
                    log.debug("  METRIC: {} = {} [tags: {}]", m.getName(), m.getValue(), String.join(", ", m.getTags()));
                }
            }

            AtomicInteger counterFinal = new AtomicInteger();
            finalMetrics.stream().collect(Collectors.groupingBy(it -> counterFinal.getAndIncrement() / configuration.getMetricsMaxBatchSize())).values().forEach(
                    x -> datadogClient.submitMetrics(x)
            );
        }

        if (this.logsBuffer.size() > 0) {
            this.datadogClient.submitLogs(this.logsBuffer, this.customTagsWithRunner);
            this.logsBuffer.clear();
        }
        this.datadogClient = null;
        super.teardownTest(context);
    }

    private void initializeRunnerTags() {
        // Use the distributed prefix (thread-group prefix) when available.
        // JMeter sets this in distributed mode as "host:port" (or sometimes host-only).
        String distributedPrefix = JMeterUtils.getPropDefault(
            JMeterUtils.THREAD_GROUP_DISTRIBUTED_PREFIX_PROPERTY_NAME, "");

        String runnerHost = JMeterUtils.getLocalHostName();
        String runnerMode = distributedPrefix.isEmpty() ? "local" : "distributed";

        customTagsWithRunner = new ArrayList<>(configuration.getCustomTags());
        
        // Auto-generate test_run_id if not provided by user
        addTagIfMissing(customTagsWithRunner, "test_run_id", generateTestRunId(distributedPrefix, runnerHost));
        
        // Add raw distributed prefix if present
        if (!distributedPrefix.isEmpty()) {
            addTagIfMissing(customTagsWithRunner, "runner_id", distributedPrefix);
        }

        addTagIfMissing(customTagsWithRunner, "runner_host", runnerHost);
        addTagIfMissing(customTagsWithRunner, "runner_mode", runnerMode);
        addTagIfMissing(customTagsWithRunner, "runner_host_ip", JMeterUtils.getLocalHostIP());
        addTagIfMissing(customTagsWithRunner, "runner_host_fqdn", JMeterUtils.getLocalHostFullName());
        addTagIfMissing(customTagsWithRunner, "jmeter_version", JMeterUtils.getJMeterVersion());
        
        // Add statistics mode - determined once at setup and used across all metrics
        customTagsWithRunner.add("statistics_mode:" + configuration.getStatisticsCalculationMode().getValue());
    }

    /**
     * Generate a unique test run ID using runner/host prefix and timestamp.
     * Format: {ISO-8601 timestamp}-{prefix}-{random8chars}
     * Example: 2026-01-24T14:30:25Z-myhost-a1b2c3d4
     *
     * @param runnerId The distributed runner ID, if available
     * @param hostname The hostname to use when runner ID is absent
     * @return A raw (unsanitized) test run ID - caller should use sanitizeTagPair
     */
    private String generateTestRunId(String runnerId, String hostname) {
        String prefix = runnerId == null || runnerId.isEmpty() ? hostname : runnerId;

        // Generate timestamp in ISO-8601 format (UTC)
        DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;
        String timestamp = formatter.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        
        // Add random suffix for uniqueness
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        
        return timestamp + "-" + prefix + "-" + randomSuffix;
    }

    /**
     * Add a sanitized tag to the list if a tag with the same key doesn't already exist.
     * @param tags the tag list to add to
     * @param key the tag key (without colon)
     * @param value the tag value (will be sanitized)
     */
    private void addTagIfMissing(List<String> tags, String key, String value) {
        String keyPrefix = key + ":";
        boolean alreadyTagged = tags.stream().anyMatch(existing -> existing.startsWith(keyPrefix));
        if (!alreadyTagged) {
            tags.add(CommonUtils.sanitizeTagPair(key, value));
        }
    }

    /**
     * Called for each individual result. It calls {@link #extractIntervalMetrics(SampleResult)} and {@link #extractLogs(SampleResult)}.
     * @param sampleResult the result
     */
    private void extractData(SampleResult sampleResult) {
        UserMetric userMetrics = this.getUserMetrics();
        userMetrics.add(sampleResult);

        this.cumulativeAggregator.addSample(sampleResult);

        this.extractIntervalMetrics(sampleResult);

        if(configuration.shouldSendResultsAsLogs() && !shouldExcludeSampleResultAsLogs(sampleResult)) {
            this.extractLogs(sampleResult);

            if (logsBuffer.size() >= configuration.getLogsBatchSize()) {
                datadogClient.submitLogs(logsBuffer, this.customTagsWithRunner);
                logsBuffer.clear();
            }
        }

        if(configuration.shouldIncludeSubResults()) {
            for (SampleResult subResult : sampleResult.getSubResults()) {
                this.extractData(subResult);
            }
        }
    }

    /**
     * Called for each individual result. It checks if logs for the sample result should be excluded
     * @param sampleResult the result
     */
    private boolean shouldExcludeSampleResultAsLogs(SampleResult sampleResult) {
        return configuration.getExcludeLogsResponseCodeRegex().matcher(sampleResult.getResponseCode()).matches();
    }

    /**
     * Called for each individual result. It extracts metrics and give them to the {@link IntervalAggregator} instance for aggregation.
     * @param sampleResult the result
     */
    private void extractIntervalMetrics(SampleResult sampleResult) {
        String resultStatus = sampleResult.isSuccessful() ? "ok" : "ko";

        String threadGroup = CommonUtils.parseThreadGroup(sampleResult.getThreadName());

        List<String> allTags = CommonUtils.combineTags(this.customTagsWithRunner,
            CommonUtils.sanitizeTagPair("response_code", sampleResult.getResponseCode()),
            CommonUtils.sanitizeTagPair("sample_label", sampleResult.getSampleLabel()),
            CommonUtils.sanitizeTagPair("thread_group", threadGroup),
            "result:" + resultStatus
        );

        if(sampleResult.isSuccessful()) {
            intervalAggregator.incrementCounter("jmeter.responses_count", allTags, sampleResult.getSampleCount() - sampleResult.getErrorCount());
        } else {
            intervalAggregator.incrementCounter("jmeter.responses_count", allTags, sampleResult.getErrorCount());
        }

        intervalAggregator.histogram("jmeter.response_time", allTags, sampleResult.getTime() / 1000f);
        intervalAggregator.histogram("jmeter.bytes_sent", allTags, sampleResult.getSentBytes());
        intervalAggregator.incrementCounter("jmeter.bytes_sent.total", allTags, sampleResult.getSentBytes());
        intervalAggregator.histogram("jmeter.bytes_received", allTags, sampleResult.getBytesAsLong());
        intervalAggregator.incrementCounter("jmeter.bytes_received.total", allTags, sampleResult.getBytesAsLong());
        intervalAggregator.histogram("jmeter.latency", allTags, sampleResult.getLatency() / 1000f);

        extractAssertionMetrics(sampleResult, threadGroup);
    }

    /**
     * Extracts assertion metrics from a sample result and adds them to the interval aggregator.
     * @param sampleResult the sample result containing assertions
     * @param threadGroup the thread group name
     */
    private void extractAssertionMetrics(SampleResult sampleResult, String threadGroup) {
        AssertionResult[] assertions = sampleResult.getAssertionResults();
        for (AssertionResult assertion : assertions) {
            String assertionName = assertion.getName();
            if (assertionName == null || assertionName.isEmpty()) {
                assertionName = "unnamed";
            }
            
            List<String> assertionTags = CommonUtils.combineTags(this.customTagsWithRunner,
                CommonUtils.sanitizeTagPair("assertion_name", assertionName),
                CommonUtils.sanitizeTagPair("sample_label", sampleResult.getSampleLabel()),
                CommonUtils.sanitizeTagPair("thread_group", threadGroup)
            );

            intervalAggregator.incrementCounter("jmeter.assertions.count", assertionTags, 1);
            if (assertion.isFailure()) {
                intervalAggregator.incrementCounter("jmeter.assertions.failed", assertionTags, 1);
            } else if (assertion.isError()) {
                intervalAggregator.incrementCounter("jmeter.assertions.error", assertionTags, 1);
            }
        }
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
     * Computes thread related metrics and adds them to the aggregator.
     */
    public void addGlobalMetrics() {
        UserMetric userMetrics = getUserMetrics();

        intervalAggregator.addGauge("jmeter.active_threads.min", this.customTagsWithRunner, userMetrics.getMinActiveThreads());
        intervalAggregator.addGauge("jmeter.active_threads.max", this.customTagsWithRunner, userMetrics.getMaxActiveThreads());
        intervalAggregator.addGauge("jmeter.active_threads.avg", this.customTagsWithRunner, userMetrics.getMeanActiveThreads());
        intervalAggregator.addGauge("jmeter.threads.finished", this.customTagsWithRunner, userMetrics.getFinishedThreads());
        intervalAggregator.addGauge("jmeter.threads.started", this.customTagsWithRunner, userMetrics.getStartedThreads());
    }

    /**
     * Called on a fixed schedule. Resets the aggregator, and sends all metrics to Datadog in batches.
     */
    private void sendMetrics() {
        this.addGlobalMetrics();

        List<DatadogMetric> metrics = intervalAggregator.flushMetrics();

        // Add cumulative metrics (cumulative, without reset)
        if (this.cumulativeAggregator != null) {
            List<DatadogMetric> cumulativeMetrics = this.cumulativeAggregator.buildMetrics(
                CommonUtils.combineTags(this.customTagsWithRunner, "final_result:false")
            );
            metrics.addAll(cumulativeMetrics);
        }

        AtomicInteger counter = new AtomicInteger();
        metrics.stream().collect(Collectors.groupingBy(it -> counter.getAndIncrement() / configuration.getMetricsMaxBatchSize())).values().forEach(
                x -> datadogClient.submitMetrics(x)
        );
    }

    private void submitIntegrationEvent(String title, String alertType) {
        String alertString = alertType.equals("info") ? "info" : "success";
        String text = "JMeter test plan " + alertString + " on " + JMeterUtils.getLocalHostName();
        datadogClient.submitEvent(
            title,
            text,
            alertType,
            "jmeter_test_" + this.testStartTimestamp,
            this.customTagsWithRunner,
            "JMeter"
        );
    }
}
