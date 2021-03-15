package org.datadog.jmeter.plugins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minidev.json.JSONObject;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.datadog.jmeter.plugins.datadog.DatadogHttpClient;
import org.datadog.jmeter.plugins.datadog.DatadogMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatadogBackendClient extends AbstractBackendListenerClient {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(DatadogBackendClient.class);
    private DatadogHttpClient datadogClient;
    /**
     * Argument keys.
     */
    private static final String API_URL_PARAM = "datadogUrl";
    private static final String LOG_INTAKE_URL_PARAM = "logIntakeUrl";
    private static final String API_KEY_PARAM = "apiKey";

    private static final String DEFAULT_API_URL = "https://api.datadoghq.com/api/";
    private static final String DEFAULT_LOG_INTAKE_URL = "https://http-intake.logs.datadoghq.com/v1/input/";

    private List<DatadogMetric> metricsBuffer = new ArrayList<>();
    private List<JSONObject> logsBuffer = new ArrayList<>();


    @Override
    public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
        arguments.addArgument(API_KEY_PARAM, null);
        arguments.addArgument(API_URL_PARAM, DEFAULT_API_URL);
        arguments.addArgument(LOG_INTAKE_URL_PARAM, DEFAULT_LOG_INTAKE_URL);

        return arguments;
    }

    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        String apiKey = context.getParameter(API_KEY_PARAM);
        String apiUrl = context.getParameter(API_URL_PARAM, DEFAULT_API_URL);
        String logIntakeUrl = context.getParameter(LOG_INTAKE_URL_PARAM, DEFAULT_LOG_INTAKE_URL);

        if (apiKey == null) {
            throw new Exception("apiKey needs to be configured.");
        }
        datadogClient = new DatadogHttpClient(apiKey, apiUrl, logIntakeUrl);
        boolean valid = datadogClient.validateConnection();
        if(!valid) {
            throw new Exception("Invalid apiKey");
        }
    }

    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        if (this.metricsBuffer.size() > 0) {
            this.datadogClient.submitMetrics(metricsBuffer);
            metricsBuffer.clear();
        }
        if (this.logsBuffer.size() > 0) {
            this.datadogClient.submitLogs(logsBuffer);
            logsBuffer.clear();
        }
        super.teardownTest(context);
    }

    @Override
    public void handleSampleResults(List<SampleResult> list, BackendListenerContext backendListenerContext) {
        for (SampleResult sampleResult : list) {
            List<DatadogMetric> metrics = this.extractMetrics(sampleResult);
            metricsBuffer.addAll(metrics);
            logsBuffer.add(this.extractPayload(sampleResult));
        }
        if(metricsBuffer.size() > 500) {
            datadogClient.submitMetrics(metricsBuffer);
            metricsBuffer.clear();
        }
        if(logsBuffer.size() > 100) {
            datadogClient.submitLogs(logsBuffer);
            logsBuffer.clear();
        }

    }

    private List<DatadogMetric> extractMetrics(SampleResult sampleResult) {
        List<String> tagsList = new ArrayList<>();
        tagsList.add("response_code:" + sampleResult.getResponseCode());
        tagsList.add("thead_name:" + sampleResult.getThreadName());
        tagsList.add("url:" + sampleResult.getUrlAsString());
        tagsList.add("sample_label:" + sampleResult.getSampleLabel());
        String[] tags = tagsList.toArray(new String[0]);

        Map<String, Double> properties = new HashMap<>();
        properties.put("bytes", (double) sampleResult.getBytesAsLong());
        properties.put("sent_bytes", (double) sampleResult.getSentBytes());
        properties.put("connect_time", (double) sampleResult.getConnectTime());
        properties.put("error_count", (double) sampleResult.getErrorCount());
        properties.put("idle_time", (double) sampleResult.getIdleTime());
        properties.put("latency", (double) sampleResult.getLatency());
        properties.put("body_size", (double) sampleResult.getBodySizeAsLong());
        properties.put("test_start_time", (double) JMeterContextService.getTestStartTime());
        properties.put("sample_start_time", (double) sampleResult.getStartTime());
        properties.put("sample_end_time", (double) sampleResult.getEndTime());
        properties.put("grp_threads", (double) sampleResult.getGroupThreads());
        properties.put("all_threads", (double) sampleResult.getAllThreads());
        properties.put("sample_count", (double) sampleResult.getSampleCount());


        List<DatadogMetric> metrics = new ArrayList<>();
        for (String key : properties.keySet()) {
            String metricName = "jmeter." + key;
            metrics.add(new DatadogMetric(metricName, "gauge", properties.get(key), tags));
        }
        return metrics;
    }

    private JSONObject extractPayload(SampleResult sampleResult) {
        JSONObject payload = new JSONObject();
        payload.put("response_code", sampleResult.getResponseCode());
        payload.put("thead_name", sampleResult.getThreadName());
        payload.put("url", sampleResult.getUrlAsString());
        payload.put("sample_label", sampleResult.getSampleLabel());
        payload.put("bytes", (double) sampleResult.getBytesAsLong());
        payload.put("sent_bytes", (double) sampleResult.getSentBytes());
        payload.put("connect_time", (double) sampleResult.getConnectTime());
        payload.put("error_count", (double) sampleResult.getErrorCount());
        payload.put("idle_time", (double) sampleResult.getIdleTime());
        payload.put("latency", (double) sampleResult.getLatency());
        payload.put("body_size", (double) sampleResult.getBodySizeAsLong());
        payload.put("test_start_time", (double) JMeterContextService.getTestStartTime());
        payload.put("sample_start_time", (double) sampleResult.getStartTime());
        payload.put("sample_end_time", (double) sampleResult.getEndTime());
        payload.put("grp_threads", (double) sampleResult.getGroupThreads());
        payload.put("all_threads", (double) sampleResult.getAllThreads());
        payload.put("sample_count", (double) sampleResult.getSampleCount());
        payload.put("ddsource", "jmeter");
        return payload;
    }
}
