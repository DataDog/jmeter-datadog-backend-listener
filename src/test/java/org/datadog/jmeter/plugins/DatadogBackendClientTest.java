/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins;

import static org.mockito.ArgumentMatchers.any;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minidev.json.JSONObject;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.datadog.jmeter.plugins.aggregation.ConcurrentAggregator;
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
 * Unit test for simple App.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(DatadogBackendClient.class)
@PowerMockIgnore({
        "javax.management.*",
        "javax.script.*"
})
public class DatadogBackendClientTest
{
    private DatadogBackendClient client;
    private Map<String, String> DEFAULT_VALID_TEST_CONFIG = new HashMap<String, String>() {
        {
            put("apiKey", "123456");
            put("datadogUrl", "datadogUrl");
            put("logIntakeUrl", "logIntakeUrl");
            put("metricsMaxBatchSize", "10");
            put("logsBatchSize", "0");
            put("sendResultsAsLogs", "true");
            put("includeSubresults", "false");
            put("samplersRegex", "^foo\\d*$");
            put("customTags", "key:value");
        }
    };
    private ConcurrentAggregator aggregator = new ConcurrentAggregator();
    private BackendListenerContext context = new BackendListenerContext(DEFAULT_VALID_TEST_CONFIG);
    private List<JSONObject> logsBuffer;
    @Before
    public void setUpMocks() throws Exception {
        logsBuffer = new ArrayList<>();
        DatadogHttpClient httpClientMock = PowerMockito.mock(DatadogHttpClient.class);
        PowerMockito.whenNew(ConcurrentAggregator.class).withAnyArguments().thenReturn(aggregator);
        PowerMockito.whenNew(DatadogHttpClient.class).withAnyArguments().thenReturn(httpClientMock);
        PowerMockito.when(httpClientMock.validateConnection()).thenReturn(true);
        PowerMockito.doAnswer((e) -> logsBuffer.addAll(e.getArgument(0))).when(httpClientMock).submitLogs(any());
        client = new DatadogBackendClient();
        client.setupTest(context);
    }

    @After
    public void teardownMocks() throws Exception {
        client.teardownTest(context);
        logsBuffer.clear();
    }



    private SampleResult createDummySampleResult(String sampleLabel) {
        SampleResult result = SampleResult.createTestSample(1, 126);
        result.setSuccessful(true);
        result.setResponseCode("123");
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
        List<DatadogMetric> metrics = this.aggregator.flushMetrics();
        String[] expectedTags = new String[] {"response_code:123", "sample_label:foo", "thread_group:bar", "result:ok", "key:value"};
        Map<String, Double> expectedMetrics = new HashMap<String, Double>() {
            {
                put("jmeter.responses_count", 10.0);
                put("jmeter.latency.max", 0.01195256210245945);
                put("jmeter.latency.min", 0.01195256210245945);
                put("jmeter.latency.p99", 0.01195256210245945);
                put("jmeter.latency.p95", 0.01195256210245945);
                put("jmeter.latency.p90", 0.01195256210245945);
                put("jmeter.latency.avg", 0.012000000104308128);
                put("jmeter.latency.count", 1.0);
                put("jmeter.response_time.max", 0.12624150202599055);
                put("jmeter.response_time.min", 0.12624150202599055);
                put("jmeter.response_time.p99", 0.12624150202599055);
                put("jmeter.response_time.p95", 0.12624150202599055);
                put("jmeter.response_time.p90", 0.12624150202599055);
                put("jmeter.response_time.avg", 0.125);
                put("jmeter.response_time.count", 1.0);
                put("jmeter.bytes_received.max", 12291.916561360777);
                put("jmeter.bytes_received.min", 12291.916561360777);
                put("jmeter.bytes_received.p99", 12291.916561360777);
                put("jmeter.bytes_received.p95", 12291.916561360777);
                put("jmeter.bytes_received.p90", 12291.916561360777);
                put("jmeter.bytes_received.avg", 12345.0);
                put("jmeter.bytes_received.count", 1.0);
                put("jmeter.bytes_sent.max", 124.37724692430666);
                put("jmeter.bytes_sent.min", 124.37724692430666);
                put("jmeter.bytes_sent.p99", 124.37724692430666);
                put("jmeter.bytes_sent.p95", 124.37724692430666);
                put("jmeter.bytes_sent.p90", 124.37724692430666);
                put("jmeter.bytes_sent.avg", 124.0);
                put("jmeter.bytes_sent.count", 1.0);
            }
        };

        for(DatadogMetric metric : metrics) {
            Assert.assertTrue(expectedMetrics.containsKey(metric.getName()));
            Double expectedMetricValue = expectedMetrics.get(metric.getName());
            Assert.assertArrayEquals(expectedTags, metric.getTags());
            if(metric.getName().endsWith("count")) {
                Assert.assertEquals("count", metric.getType());
            } else {
                Assert.assertEquals("gauge", metric.getType());
            }
            Assert.assertEquals(expectedMetricValue, metric.getValue(), 1e-12);
        }
    }

    @Test
    public void testExtractLogs() {
        SampleResult result = createDummySampleResult("foo");
        this.client.handleSampleResults(Collections.singletonList(result), context);
        Assert.assertEquals(1, this.logsBuffer.size());
        String expectedPayload = "{\"sample_start_time\":1.0,\"response_code\":\"123\",\"headers_size\":0.0,\"sample_label\":\"foo\",\"latency\":12.0,\"group_threads\":0.0,\"idle_time\":0.0,\"error_count\":0.0,\"message\":\"\",\"url\":\"\",\"ddsource\":\"jmeter\",\"sent_bytes\":124.0,\"body_size\":0.0,\"content_type\":\"\",\"load_time\":125.0,\"thread_name\":\"bar baz\",\"sample_end_time\":126.0,\"bytes\":12345.0,\"connect_time\":0.0,\"sample_count\":10.0,\"data_type\":\"\",\"all_threads\":0.0,\"data_encoding\":null}";
        Assert.assertEquals(this.logsBuffer.get(0).toString(), expectedPayload);
    }

    @Test
    public void testRegexNotMatching() {
        SampleResult result1 = createDummySampleResult("foo1");
        SampleResult resultA = createDummySampleResult("fooA");

        this.client.handleSampleResults(Arrays.asList(result1, resultA), context);
        String[] expectedTagsResult1 = new String[] {"response_code:123", "sample_label:foo1", "thread_group:bar", "result:ok", "key:value"};
        for(DatadogMetric metric : this.aggregator.flushMetrics()){
            Assert.assertArrayEquals(expectedTagsResult1, metric.getTags());
        }
        Assert.assertEquals(1, this.logsBuffer.size());
        Assert.assertEquals("foo1", this.logsBuffer.get(0).getAsString("sample_label"));
    }

}
