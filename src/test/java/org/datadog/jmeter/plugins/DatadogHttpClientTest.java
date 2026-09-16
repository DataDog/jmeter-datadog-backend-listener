/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest(DatadogHttpClient.class)
public class DatadogHttpClientTest {
    private static final int TIMEOUT_MS = 60 * 1000;

    private HttpURLConnection stubPost(String response) throws Exception {
        HttpURLConnection conn = PowerMockito.mock(HttpURLConnection.class);
        URL url = PowerMockito.mock(URL.class);
        PowerMockito.whenNew(URL.class).withAnyArguments().thenReturn(url);
        when(url.openConnection()).thenReturn(conn);
        when(conn.getErrorStream()).thenReturn(null);
        when(conn.getInputStream())
            .thenReturn(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
        when(conn.getOutputStream()).thenReturn(new java.io.ByteArrayOutputStream());
        return conn;
    }

    private DatadogHttpClient newClient() {
        return new DatadogHttpClient("key", "http://example.com/", "http://example.com/logs/");
    }

    @Test
    public void submitMetricsAppliesTimeouts() throws Exception {
        HttpURLConnection conn = stubPost("{\"status\":\"ok\"}");

        newClient().submitMetrics(Collections.singletonList(
            new DatadogMetric("m", "count", 1.0, Collections.emptyList())));

        verify(conn).setConnectTimeout(TIMEOUT_MS);
        verify(conn).setReadTimeout(TIMEOUT_MS);
    }

    @Test
    public void submitLogsAppliesTimeoutsAndHeaders() throws Exception {
        HttpURLConnection conn = stubPost("{}");

        newClient().submitLogs(
            Collections.singletonList(new net.minidev.json.JSONObject()),
            Collections.singletonList("env:test"));

        verify(conn).setConnectTimeout(TIMEOUT_MS);
        verify(conn).setReadTimeout(TIMEOUT_MS);
        verify(conn).setRequestProperty("DD-API-KEY", "key");
        verify(conn).setRequestProperty("User-Agent", "Datadog/jmeter-plugin");
    }

    @Test
    public void submitEventAppliesTimeouts() throws Exception {
        HttpURLConnection conn = stubPost("{\"status\":\"ok\"}");

        newClient().submitEvent("title", "text", "info", "agg",
            Collections.singletonList("env:test"), "jmeter");

        verify(conn).setConnectTimeout(TIMEOUT_MS);
        verify(conn).setReadTimeout(TIMEOUT_MS);
    }
}
