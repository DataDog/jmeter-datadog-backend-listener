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
            put("inputTags","key:value");
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
        return result;
    }

    @Test
    public void testExtractMetrics() {
        SampleResult result = createDummySampleResult("foo");
        this.client.handleSampleResults(Collections.singletonList(result), context);
        List<DatadogMetric> metrics = this.aggregator.flushMetrics();
        String[] expectedTags = new String[] {"response_code:123", "sample_label:foo", "result:ok","key:value"};
        String[] expectedMetricNames = new String[] {
                "jmeter.responses_count",
                "jmeter.latency.max",
                "jmeter.latency.min",
                "jmeter.latency.p99",
                "jmeter.latency.p95",
                "jmeter.latency.p90",
                "jmeter.latency.avg",
                "jmeter.latency.count",
                "jmeter.response_time.max",
                "jmeter.response_time.min",
                "jmeter.response_time.p99",
                "jmeter.response_time.p95",
                "jmeter.response_time.p90",
                "jmeter.response_time.avg",
                "jmeter.response_time.count",
                "jmeter.bytes_received.max",
                "jmeter.bytes_received.min",
                "jmeter.bytes_received.p99",
                "jmeter.bytes_received.p95",
                "jmeter.bytes_received.p90",
                "jmeter.bytes_received.avg",
                "jmeter.bytes_received.count",
                "jmeter.bytes_sent.max",
                "jmeter.bytes_sent.min",
                "jmeter.bytes_sent.p99",
                "jmeter.bytes_sent.p95",
                "jmeter.bytes_sent.p90",
                "jmeter.bytes_sent.avg",
                "jmeter.bytes_sent.count",
        };
        double[] expectedMetricValues = new double[] {
                10.0,
                0.01195256210245945,
                0.01195256210245945,
                0.01195256210245945,
                0.01195256210245945,
                0.01195256210245945,
                0.012000000104308128,
                1.0,
                0.12624150202599055,
                0.12624150202599055,
                0.12624150202599055,
                0.12624150202599055,
                0.12624150202599055,
                0.125,
                1.0,
                12291.916561360777,
                12291.916561360777,
                12291.916561360777,
                12291.916561360777,
                12291.916561360777,
                12345.0,
                1.0,
                124.37724692430666,
                124.37724692430666,
                124.37724692430666,
                124.37724692430666,
                124.37724692430666,
                124.0,
                1.0,
        };

        for(int i = 0; i < expectedMetricNames.length; i++){
            DatadogMetric metric = metrics.get(i);
            Assert.assertEquals(expectedMetricNames[i], metric.getName());
            Assert.assertArrayEquals(expectedTags, metric.getTags());
            if(metric.getName().endsWith("count")) {
                Assert.assertEquals("count", metric.getType());
            } else {
                Assert.assertEquals("gauge", metric.getType());
            }
            Assert.assertEquals(expectedMetricValues[i], metric.getValue(), 1e-12);
        }
    }

    @Test
    public void testExtractLogs() {
        SampleResult result = createDummySampleResult("foo");
        this.client.handleSampleResults(Collections.singletonList(result), context);
        Assert.assertEquals(1, this.logsBuffer.size());
        String expectedPayload = "{\"sample_start_time\":1.0,\"response_code\":\"123\",\"headers_size\":0.0,\"sample_label\":\"foo\",\"latency\":12.0,\"group_threads\":0.0,\"idle_time\":0.0,\"error_count\":0.0,\"message\":\"\",\"url\":\"\",\"ddsource\":\"jmeter\",\"sent_bytes\":124.0,\"body_size\":0.0,\"content_type\":\"\",\"load_time\":125.0,\"thread_name\":\"\",\"sample_end_time\":126.0,\"bytes\":12345.0,\"connect_time\":0.0,\"sample_count\":10.0,\"data_type\":\"\",\"all_threads\":0.0,\"data_encoding\":null}";
        Assert.assertEquals(this.logsBuffer.get(0).toString(), expectedPayload);
    }

    @Test
    public void testRegexNotMatching() {
        SampleResult result1 = createDummySampleResult("foo1");
        SampleResult resultA = createDummySampleResult("fooA");

        this.client.handleSampleResults(Arrays.asList(result1, resultA), context);
        String[] expectedTagsResult1 = new String[] {"response_code:123", "sample_label:foo1", "result:ok"};
        for(DatadogMetric metric : this.aggregator.flushMetrics()){
            Assert.assertArrayEquals(expectedTagsResult1, metric.getTags());
        }
        Assert.assertEquals(1, this.logsBuffer.size());
        Assert.assertEquals("foo1", this.logsBuffer.get(0).getAsString("sample_label"));
    }

}
