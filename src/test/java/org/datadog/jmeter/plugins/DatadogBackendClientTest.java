/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.datadog.jmeter.plugins.aggregation.IntervalAggregator;
import org.datadog.jmeter.plugins.aggregation.DDSketchStatsCollector;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

/**
 * Unit test for DatadogBackendClient.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({DatadogBackendClient.class, JMeterUtils.class})
@PowerMockIgnore({
        "javax.management.*",
        "javax.script.*"
})
public class DatadogBackendClientTest
{
    /** Assert that metric has all expected tags (order-independent) */
    private static void assertTagsMatch(List<String> actual, String... expected) {
        Assert.assertEquals("Tags mismatch", new HashSet<String>(Arrays.asList(expected)), new HashSet<String>(actual));
    }

    private DatadogBackendClient client;
    private static final String TEST_RUN_ID = "test";
    private Map<String, String> DEFAULT_VALID_TEST_CONFIG = new HashMap<String, String>() {
        {
            put("apiKey", "123456");
            put("datadogUrl", "datadogUrl");
            put("logIntakeUrl", "logIntakeUrl");
            put("metricsMaxBatchSize", "10");
            put("logsBatchSize", "0");
            put("sendResultsAsLogs", "true");
            put("includeSubresults", "false");
            put("excludeLogsResponseCodeRegex", "");
            put("customTags", "key:value,test_run_id:" + TEST_RUN_ID);
        }
    };
    private IntervalAggregator aggregator;
    private List<DatadogMetric> submittedMetrics;
    private BackendListenerContext context = new BackendListenerContext(DEFAULT_VALID_TEST_CONFIG);
    private List<JSONObject> logsBuffer;
    private List<String> logsTags;

    private static final String TEST_RUNNER_HOST = "test-runner";
    private static final String TEST_RUNNER_IP = "192.0.2.10";
    private static final String TEST_RUNNER_FQDN = "test-runner.example.local";
    private static final String TEST_JMETER_VERSION =
        System.getProperty("jmeter.version.test", "5.6.2");

    @Before
    public void setUpMocks() throws Exception {
        logsBuffer = new ArrayList<>();
        aggregator = new IntervalAggregator(DDSketchStatsCollector::new);

        // Mock JMeterUtils static methods to return predictable runner identity
        PowerMockito.mockStatic(JMeterUtils.class);
        
        PowerMockito.when(JMeterUtils.getPropDefault(any(String.class), anyInt())).thenReturn(20000);
        PowerMockito.when(JMeterUtils.getPropDefault(eq("datadog.send_interval"), anyInt())).thenReturn(10);
        PowerMockito.when(JMeterUtils.getPropDefault(eq(JMeterUtils.THREAD_GROUP_DISTRIBUTED_PREFIX_PROPERTY_NAME), any(String.class))).thenReturn("");
        
        PowerMockito.when(JMeterUtils.getLocalHostName()).thenReturn(TEST_RUNNER_HOST);
        PowerMockito.when(JMeterUtils.getLocalHostIP()).thenReturn(TEST_RUNNER_IP);
        PowerMockito.when(JMeterUtils.getLocalHostFullName()).thenReturn(TEST_RUNNER_FQDN);
        PowerMockito.when(JMeterUtils.getJMeterVersion()).thenReturn(TEST_JMETER_VERSION);
        PowerMockito.when(JMeterUtils.getProperty("testMode")).thenReturn(null);
        PowerMockito.when(JMeterUtils.getProperty("backend_metrics_percentile_estimator")).thenReturn(null);

        DatadogHttpClient httpClientMock = PowerMockito.mock(DatadogHttpClient.class);
        PowerMockito.whenNew(IntervalAggregator.class).withAnyArguments().thenReturn(aggregator);
        PowerMockito.whenNew(DatadogHttpClient.class).withAnyArguments().thenReturn(httpClientMock);
        PowerMockito.when(httpClientMock.validateConnection()).thenReturn(true);
        submittedMetrics = new ArrayList<>();
        PowerMockito.doAnswer((e) -> {
            submittedMetrics.addAll(e.getArgument(0));
            return null;
        }).when(httpClientMock).submitMetrics(any());
        PowerMockito.doAnswer((e) -> {
            logsBuffer.addAll(e.getArgument(0));
            logsTags = (List<String>) e.getArgument(1, List.class);
            return null;
        }).when(httpClientMock).submitLogs(any(), any());
        client = new DatadogBackendClient();
        client.setupTest(context);
    }

    @After
    public void teardownMocks() throws Exception {
        client.teardownTest(context);
        logsBuffer.clear();
    }

    private SampleResult createDummySampleResult(String sampleLabel) {
        return createDummySampleResult(sampleLabel, "123");
    }

    private SampleResult createDummySampleResult(String sampleLabel, String responseCode) {
        SampleResult result = SampleResult.createTestSample(1, 126);
        result.setSuccessful(true);
        result.setResponseCode(responseCode);
        result.setSampleLabel(sampleLabel);
        result.setSampleCount(10);
        result.setErrorCount(1);
        result.setSentBytes(124);
        result.setBytes((long)12345);
        result.setLatency(12);
        result.setThreadName("bar baz");
        return result;
    }

    @Test
    public void testExtractMetrics() {
        SampleResult result = createDummySampleResult("foo");
        this.client.handleSampleResults(Collections.singletonList(result), context);
        List<DatadogMetric> metrics = flushAggregator();
        String[] expectedTags = new String[] {
            "response_code:123",
            "sample_label:foo",
            "thread_group:bar",
            "result:ok",
            "statistics_mode:ddsketch",
            "key:value",
            "test_run_id:" + TEST_RUN_ID,
            "runner_host:" + TEST_RUNNER_HOST,
            "runner_mode:local",
            "runner_host_ip:" + TEST_RUNNER_IP,
            "runner_host_fqdn:" + TEST_RUNNER_FQDN,
            "jmeter_version:" + TEST_JMETER_VERSION
        };
        Map<String, Double> expectedMetrics = new HashMap<String, Double>() {
            {
                put("jmeter.responses_count", 10.0);
                put("jmeter.latency.max", 0.01195256210245945);
                put("jmeter.latency.min", 0.01195256210245945);
                put("jmeter.latency.p99", 0.01195256210245945);
                put("jmeter.latency.p95", 0.01195256210245945);
                put("jmeter.latency.p90", 0.01195256210245945);
                put("jmeter.latency.median", 0.01195256210245945);
                put("jmeter.latency.avg", 0.012000000104308128);
                put("jmeter.latency.count", 1.0);
                put("jmeter.response_time.max", 0.12624150202599055);
                put("jmeter.response_time.min", 0.12624150202599055);
                put("jmeter.response_time.p99", 0.12624150202599055);
                put("jmeter.response_time.p95", 0.12624150202599055);
                put("jmeter.response_time.p90", 0.12624150202599055);
                put("jmeter.response_time.median", 0.12624150202599055);
                put("jmeter.response_time.avg", 0.125);
                put("jmeter.response_time.count", 1.0);
                put("jmeter.bytes_received.max", 12291.916561360777);
                put("jmeter.bytes_received.min", 12291.916561360777);
                put("jmeter.bytes_received.p99", 12291.916561360777);
                put("jmeter.bytes_received.p95", 12291.916561360777);
                put("jmeter.bytes_received.p90", 12291.916561360777);
                put("jmeter.bytes_received.median", 12291.916561360777);
                put("jmeter.bytes_received.avg", 12345.0);
                put("jmeter.bytes_received.count", 1.0);
                put("jmeter.bytes_received.total", 12345.0);
                put("jmeter.bytes_sent.max", 124.37724692430666);
                put("jmeter.bytes_sent.min", 124.37724692430666);
                put("jmeter.bytes_sent.p99", 124.37724692430666);
                put("jmeter.bytes_sent.p95", 124.37724692430666);
                put("jmeter.bytes_sent.p90", 124.37724692430666);
                put("jmeter.bytes_sent.median", 124.37724692430666);
                put("jmeter.bytes_sent.avg", 124.0);
                put("jmeter.bytes_sent.count", 1.0);
                put("jmeter.bytes_sent.total", 124.0);
            }
        };

        for(DatadogMetric metric : metrics) {
            Assert.assertTrue("Unexpected metric found: " + metric.getName(), expectedMetrics.containsKey(metric.getName()));
            Double expectedMetricValue = expectedMetrics.get(metric.getName());
            assertTagsMatch(metric.getTags(), expectedTags);
            if(metric.getName().endsWith("count") || metric.getName().endsWith("total")) {
                Assert.assertEquals("count", metric.getType());
            } else {
                Assert.assertEquals("gauge", metric.getType());
            }
            Assert.assertEquals(expectedMetricValue, metric.getValue(), 1e-12);
        }

        this.client.addGlobalMetrics();
        List<DatadogMetric> globalMetrics = flushAggregator();
        String[] expectedGlobalTags = new String[] {
            "statistics_mode:ddsketch",
            "key:value",
            "test_run_id:" + TEST_RUN_ID,
            "runner_host:" + TEST_RUNNER_HOST,
            "runner_mode:local",
            "runner_host_ip:" + TEST_RUNNER_IP,
            "runner_host_fqdn:" + TEST_RUNNER_FQDN,
            "jmeter_version:" + TEST_JMETER_VERSION
        };
        Map<String, Double> expectedThreadMetrics = new HashMap<String, Double>() {
            {
                put("jmeter.active_threads.max", 0.0);
                put("jmeter.active_threads.min", 0.0);
                put("jmeter.active_threads.avg", 0.0);
                put("jmeter.threads.finished", 0.0);
                put("jmeter.threads.started", 0.0);
            }
        };

        // Check thread metrics have expected values
        for(DatadogMetric metric : globalMetrics) {
            if (expectedThreadMetrics.containsKey(metric.getName())) {
                Double expectedMetricValue = expectedThreadMetrics.get(metric.getName());
                assertTagsMatch(metric.getTags(), expectedGlobalTags);
                Assert.assertEquals("gauge", metric.getType());
                Assert.assertEquals(expectedMetricValue, metric.getValue(), 1e-12);
            }
        }

        // Verify all expected thread metrics are present
        for (String expectedMetricName : expectedThreadMetrics.keySet()) {
            boolean found = globalMetrics.stream().anyMatch(m -> m.getName().equals(expectedMetricName));
            Assert.assertTrue("Expected metric " + expectedMetricName + " not found", found);
        }
    }

    @Test
    public void testExtractLogs() throws ParseException {
        SampleResult result = createDummySampleResult("foo");
        this.client.handleSampleResults(Collections.singletonList(result), context);
        Assert.assertEquals(1, this.logsBuffer.size());
        String expectedPayload = "{\"sample_start_time\":1.0,\"response_code\":\"123\",\"headers_size\":0.0,\"sample_label\":\"foo\",\"latency\":12.0,\"group_threads\":0.0,\"idle_time\":0.0,\"error_count\":0.0,\"message\":\"\",\"url\":\"\",\"ddsource\":\"jmeter\",\"sent_bytes\":124.0,\"thread_group\":\"bar\",\"body_size\":0.0,\"content_type\":\"\",\"load_time\":125.0,\"thread_name\":\"bar baz\",\"sample_end_time\":126.0,\"bytes\":12345.0,\"connect_time\":0.0,\"sample_count\":10.0,\"data_type\":\"\",\"all_threads\":0.0,\"data_encoding\":null}";
        JSONParser parser = new JSONParser(JSONParser.MODE_PERMISSIVE);
        Assert.assertEquals(this.logsBuffer.get(0), parser.parse(expectedPayload));
        Assert.assertEquals(Arrays.asList(
            "key:value",
            "test_run_id:" + TEST_RUN_ID,
            "runner_host:" + TEST_RUNNER_HOST,
            "runner_mode:local",
            "runner_host_ip:" + TEST_RUNNER_IP,
            "runner_host_fqdn:" + TEST_RUNNER_FQDN,
            "jmeter_version:" + TEST_JMETER_VERSION
        ), this.logsTags);
    }

    @Test
    public void testDistributedRunnerTags() throws Exception {
        PowerMockito.when(JMeterUtils.getPropDefault(
            JMeterUtils.THREAD_GROUP_DISTRIBUTED_PREFIX_PROPERTY_NAME, "")).thenReturn("runner.example:1099");

        DatadogBackendClient distributedClient = new DatadogBackendClient();
        BackendListenerContext distributedContext = new BackendListenerContext(DEFAULT_VALID_TEST_CONFIG);
        distributedClient.setupTest(distributedContext);

        try {
            SampleResult result = createDummySampleResult("foo");
            distributedClient.handleSampleResults(Collections.singletonList(result), distributedContext);
            List<DatadogMetric> metrics = flushAggregator();

            boolean foundRunnerHost = false;
            boolean foundRunnerMode = false;
            for (DatadogMetric metric : metrics) {
                for (String tag : metric.getTags()) {
                    if (tag.equals("runner_host:" + TEST_RUNNER_HOST)) {
                        foundRunnerHost = true;
                    }
                    if (tag.equals("runner_mode:distributed")) {
                        foundRunnerMode = true;
                    }
                }
            }

            Assert.assertTrue("runner_host tag missing for distributed mode", foundRunnerHost);
            Assert.assertTrue("runner_mode tag missing for distributed mode", foundRunnerMode);
        } finally {
            distributedClient.teardownTest(distributedContext);
        }
    }

    /**
     * Tests that aggregate metrics are properly submitted with correct labels and tags.
     * Math correctness is tested in CumulativeAggregatorTest.
     */
    @Test
    public void testSubmitFinalAggregateMetrics() throws Exception {
        HashMap<String, String> config = new HashMap<String, String>(DEFAULT_VALID_TEST_CONFIG);
        DatadogBackendClient client = new DatadogBackendClient();
        BackendListenerContext context = new BackendListenerContext(config);
        client.setupTest(context);

        SampleResult r1 = SampleResult.createTestSample(1000, 1100);
        r1.setSampleLabel("foo");
        r1.setSuccessful(true);
        r1.setResponseCode("200");
        r1.setSampleCount(1);

        SampleResult r2 = SampleResult.createTestSample(1100, 1300);
        r2.setSampleLabel("bar");
        r2.setSuccessful(false);
        r2.setResponseCode("500");
        r2.setSampleCount(1);
        r2.setErrorCount(1);

        client.handleSampleResults(Arrays.asList(r1, r2), context);
        client.teardownTest(context);

        // Group aggregate metrics by label
        Map<String, Set<String>> metricNamesByLabel = new HashMap<>();
        for (DatadogMetric m : submittedMetrics) {
            if (m.getName().startsWith("jmeter.cumulative.")) {
                String label = m.getTags().stream()
                    .filter(t -> t.startsWith("sample_label:"))
                    .map(t -> t.substring("sample_label:".length()))
                    .findFirst().orElse("");
                metricNamesByLabel.computeIfAbsent(label, k -> new HashSet<>()).add(m.getName());
            }
        }

        // Verify all labels (foo, bar, total) have all expected metrics
        String[] expectedMetrics = {
            "jmeter.cumulative.response_time.avg", "jmeter.cumulative.response_time.median",
            "jmeter.cumulative.response_time.min", "jmeter.cumulative.response_time.max",
            "jmeter.cumulative.response_time.p90", "jmeter.cumulative.response_time.p95",
            "jmeter.cumulative.response_time.p99", "jmeter.cumulative.responses_count",
            "jmeter.cumulative.responses.error_percent", "jmeter.cumulative.throughput.rps",
            "jmeter.cumulative.bytes_received.rate", "jmeter.cumulative.bytes_sent.rate"
        };
        for (String label : Arrays.asList("foo", "bar", "total")) {
            Set<String> names = metricNamesByLabel.get(label);
            Assert.assertNotNull("Should have metrics for label: " + label, names);
            for (String metricName : expectedMetrics) {
                Assert.assertTrue("Missing " + metricName + " for " + label, names.contains(metricName));
            }
        }

        // Verify aggregate metrics have required tags
        for (DatadogMetric m : submittedMetrics) {
            if (m.getName().startsWith("jmeter.cumulative.")) {
                Assert.assertTrue("final_result", m.getTags().stream().anyMatch(t -> t.startsWith("final_result:")));
                Assert.assertTrue("statistics_mode", m.getTags().stream().anyMatch(t -> t.startsWith("statistics_mode:")));
            }
        }
    }

    @Test
    public void testExtractMetricsWithSubresults() throws Exception {
        // Set up a client with the `includeSubresults` option set to `true`
        HashMap<String, String> config = new HashMap<String, String>(DEFAULT_VALID_TEST_CONFIG);
        config.put("includeSubresults", "true");
        DatadogBackendClient client = new DatadogBackendClient();
        BackendListenerContext context = new BackendListenerContext(config);
        client.setupTest(context);
        
        SampleResult result = createDummySampleResult("foo");
        // Add subresults (2 deep), as we want to ensure they're also included.
        // Note that subresults get re-labeled here to <parent>-<n>.
        SampleResult subresult = createDummySampleResult("subresult");
        result.addRawSubResult(subresult);
        subresult.addRawSubResult(createDummySampleResult("subresult"));
        
        client.handleSampleResults(Collections.singletonList(result), context);
        List<DatadogMetric> metrics = flushAggregator();
        Map<String, Double> expectedMetrics = new HashMap<String, Double>() {
            {
                put("jmeter.responses_count", 10.0);
                put("jmeter.latency.max", 0.01195256210245945);
                put("jmeter.latency.min", 0.01195256210245945);
                put("jmeter.latency.p99", 0.01195256210245945);
                put("jmeter.latency.p95", 0.01195256210245945);
                put("jmeter.latency.p90", 0.01195256210245945);
                put("jmeter.latency.median", 0.01195256210245945);
                put("jmeter.latency.avg", 0.012000000104308128);
                put("jmeter.latency.count", 1.0);
                put("jmeter.response_time.max", 0.12624150202599055);
                put("jmeter.response_time.min", 0.12624150202599055);
                put("jmeter.response_time.p99", 0.12624150202599055);
                put("jmeter.response_time.p95", 0.12624150202599055);
                put("jmeter.response_time.p90", 0.12624150202599055);
                put("jmeter.response_time.median", 0.12624150202599055);
                put("jmeter.response_time.avg", 0.125);
                put("jmeter.response_time.count", 1.0);
                put("jmeter.bytes_received.max", 12291.916561360777);
                put("jmeter.bytes_received.min", 12291.916561360777);
                put("jmeter.bytes_received.p99", 12291.916561360777);
                put("jmeter.bytes_received.p95", 12291.916561360777);
                put("jmeter.bytes_received.p90", 12291.916561360777);
                put("jmeter.bytes_received.median", 12291.916561360777);
                put("jmeter.bytes_received.avg", 12345.0);
                put("jmeter.bytes_received.count", 1.0);
                put("jmeter.bytes_sent.max", 124.37724692430666);
                put("jmeter.bytes_sent.min", 124.37724692430666);
                put("jmeter.bytes_sent.p99", 124.37724692430666);
                put("jmeter.bytes_sent.p95", 124.37724692430666);
                put("jmeter.bytes_sent.p90", 124.37724692430666);
                put("jmeter.bytes_sent.median", 124.37724692430666);
                put("jmeter.bytes_sent.avg", 124.0);
                put("jmeter.bytes_sent.count", 1.0);
            }
        };

        // We need to assert that the metrics of both the parent results as well as
        // those in the subresults are present.
        assertMetricsWithTag(metrics, expectedMetrics, "sample_label:foo");
        assertMetricsWithTag(metrics, expectedMetrics, "sample_label:foo-0");
        assertMetricsWithTag(metrics, expectedMetrics, "sample_label:foo-0-0");
    }

    private void assertMetricsWithTag(List<DatadogMetric> metrics, Map<String, Double> expectedMetrics, String tag) {
        Map<String, DatadogMetric> metricsMap = new HashMap<String, DatadogMetric>();
        for(DatadogMetric metric : metrics) {
            if (metric.getTags().contains(tag)) {
                metricsMap.put(metric.getName(), metric);
            }
        }

        for(Map.Entry<String, Double> expectedMetric : expectedMetrics.entrySet()) {
            Assert.assertTrue(metricsMap.containsKey(expectedMetric.getKey()));
            DatadogMetric metric = metricsMap.get(expectedMetric.getKey());

            if(metric.getName().endsWith("count")) {
                Assert.assertEquals("count", metric.getType());
            } else {
                Assert.assertEquals("gauge", metric.getType());
            }
            Assert.assertEquals(expectedMetric.getValue(), metric.getValue(), 1e-12);
        }
    }

    @Test
    public void testSamplersRegexNoMatch() throws Exception {
        HashMap<String, String> config = new HashMap<String, String>(DEFAULT_VALID_TEST_CONFIG);
        config.put("samplersRegex", "^foo\\d*$");
        DatadogBackendClient client = new DatadogBackendClient();
        BackendListenerContext context = new BackendListenerContext(config);
        client.setupTest(context);

        SampleResult result1 = createDummySampleResult("foo1");
        SampleResult resultA = createDummySampleResult("fooA");

        client.handleSampleResults(Arrays.asList(result1, resultA), context);
        String[] expectedTagsResult1 = new String[] {
            "response_code:123",
            "sample_label:foo1",
            "thread_group:bar",
            "result:ok",
            "statistics_mode:ddsketch",
            "key:value",
            "test_run_id:" + TEST_RUN_ID,
            "runner_host:" + TEST_RUNNER_HOST,
            "runner_mode:local",
            "runner_host_ip:" + TEST_RUNNER_IP,
            "runner_host_fqdn:" + TEST_RUNNER_FQDN,
            "jmeter_version:" + TEST_JMETER_VERSION
        };
        for(DatadogMetric metric : flushAggregator()){
            assertTagsMatch(metric.getTags(), expectedTagsResult1);
        }
        // Only foo1 should be logged (fooA doesn't match the regex)
        Assert.assertEquals(1, this.logsBuffer.size());
        Assert.assertEquals("foo1", this.logsBuffer.get(0).getAsString("sample_label"));
    }

    @Test
    public void testExcludeLogsRegexDefault() {
        SampleResult result1 = createDummySampleResult("foo1", "200");
        SampleResult result2 = createDummySampleResult("foo2", "301");
        SampleResult result3 = createDummySampleResult("foo3", "404");
        SampleResult result4 = createDummySampleResult("foo4", "Non HTTP response code: java.net.NoRouteToHostException");

        this.client.handleSampleResults(Arrays.asList(result1, result2, result3, result4), context);
        Assert.assertEquals(4, this.logsBuffer.size());
        Assert.assertEquals("foo1", this.logsBuffer.get(0).getAsString("sample_label"));
        Assert.assertEquals("foo2", this.logsBuffer.get(1).getAsString("sample_label"));
        Assert.assertEquals("foo3", this.logsBuffer.get(2).getAsString("sample_label"));
        Assert.assertEquals("foo4", this.logsBuffer.get(3).getAsString("sample_label"));
    }

    @Test
    public void testExcludeLogsRegexMatch() throws Exception {
        HashMap<String, String> config = new HashMap<>(DEFAULT_VALID_TEST_CONFIG);
        config.put("excludeLogsResponseCodeRegex", "^[23][0-5][0-9]$");
        DatadogBackendClient client = new DatadogBackendClient();
        BackendListenerContext context = new BackendListenerContext(config);
        client.setupTest(context);

        SampleResult result1 = createDummySampleResult("foo1", "200");
        SampleResult result2 = createDummySampleResult("foo2", "301");
        SampleResult result3 = createDummySampleResult("foo3", "404");
        SampleResult result4 = createDummySampleResult("foo4", "Non HTTP response code: java.net.NoRouteToHostException");

        client.handleSampleResults(Arrays.asList(result1, result2, result3, result4), context);
        Assert.assertEquals(2, this.logsBuffer.size());
        Assert.assertEquals("foo3", this.logsBuffer.get(0).getAsString("sample_label"));
        Assert.assertEquals("foo4", this.logsBuffer.get(1).getAsString("sample_label"));
    }

    @Test
    public void testExtractAssertionMetrics() {
        SampleResult result = createDummySampleResult("foo");
        
        AssertionResult passedAssertion = new AssertionResult("Response Code 200");
        passedAssertion.setFailure(false);
        result.addAssertionResult(passedAssertion);
        
        AssertionResult failedAssertion = new AssertionResult("Duration Assertion");
        failedAssertion.setFailure(true);
        result.addAssertionResult(failedAssertion);
        
        this.client.handleSampleResults(Collections.singletonList(result), context);
        List<DatadogMetric> metrics = flushAggregator();
        
        // Group by assertion name
        Map<String, Double> countByName = metrics.stream()
            .filter(m -> m.getName().equals("jmeter.assertions.count"))
            .collect(java.util.stream.Collectors.toMap(
                m -> m.getTags().stream().filter(t -> t.startsWith("assertion_name:")).findFirst().orElse(""),
                DatadogMetric::getValue));
        Map<String, Double> failedByName = metrics.stream()
            .filter(m -> m.getName().equals("jmeter.assertions.failed"))
            .collect(java.util.stream.Collectors.toMap(
                m -> m.getTags().stream().filter(t -> t.startsWith("assertion_name:")).findFirst().orElse(""),
                DatadogMetric::getValue));
        
        // Verify count metrics: 2 assertions, each with value 1
        Assert.assertEquals(2, countByName.size());
        Assert.assertEquals(1.0, countByName.get("assertion_name:response_code_200"), 0.01);
        Assert.assertEquals(1.0, countByName.get("assertion_name:duration_assertion"), 0.01);
        
        // Verify failed metrics: only Duration Assertion failed
        Assert.assertEquals(1, failedByName.size());
        Assert.assertEquals(1.0, failedByName.get("assertion_name:duration_assertion"), 0.01);
    }

    @Test
    public void testAssertionErrorMetric() {
        SampleResult result = createDummySampleResult("foo");
        
        // Add an assertion that has an error (not a failure, but an error during evaluation)
        AssertionResult errorAssertion = new AssertionResult("Script Assertion");
        errorAssertion.setError(true);
        errorAssertion.setFailure(false);
        errorAssertion.setFailureMessage("Script evaluation error: NullPointerException");
        result.addAssertionResult(errorAssertion);
        
        this.client.handleSampleResults(Collections.singletonList(result), context);
        List<DatadogMetric> metrics = flushAggregator();
        
        // Find assertion error metric
        boolean foundErrorMetric = false;
        for (DatadogMetric metric : metrics) {
            if (metric.getName().equals("jmeter.assertions.error")) {
                foundErrorMetric = true;
                // Verify it's tagged with Script Assertion (sanitized: lowercase, space replaced by underscore)
                boolean hasScriptTag = false;
                for (String tag : metric.getTags()) {
                    if (tag.equals("assertion_name:script_assertion")) {
                        hasScriptTag = true;
                    }
                }
                Assert.assertTrue("Error assertion should be script_assertion", hasScriptTag);
            }
        }
        
        Assert.assertTrue("Should have assertion error metric", foundErrorMetric);
    }

    @Test
    public void testAssertionNullName() {
        SampleResult result = createDummySampleResult("foo");
        
        // Add an assertion with null name (uses deprecated constructor)
        @SuppressWarnings("deprecation")
        AssertionResult assertion = new AssertionResult();
        assertion.setFailure(true);
        result.addAssertionResult(assertion);
        
        this.client.handleSampleResults(Collections.singletonList(result), context);
        List<DatadogMetric> metrics = flushAggregator();
        
        // Verify assertion metric is created with "unnamed" as the name
        boolean foundUnnamedAssertion = false;
        for (DatadogMetric metric : metrics) {
            if (metric.getName().equals("jmeter.assertions.failed")) {
                for (String tag : metric.getTags()) {
                    if (tag.equals("assertion_name:unnamed")) {
                        foundUnnamedAssertion = true;
                    }
                }
            }
        }
        
        Assert.assertTrue("Assertion with null name should use 'unnamed'", foundUnnamedAssertion);
    }

    /**
     * Helper method to flush metrics from the aggregator.
     */
    private List<DatadogMetric> flushAggregator() {
        return aggregator.flushMetrics();
    }
}
