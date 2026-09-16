/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import net.minidev.json.JSONObject;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(PowerMockRunner.class)
@PrepareForTest({DatadogHttpClient.class, LoggerFactory.class})
@PowerMockIgnore({
    "javax.management.*",
    "javax.script.*"
})
public class DatadogHttpClientTest {
    private static final int TIMEOUT_MS = 60 * 1000;

    private static Logger logger;
    private HttpURLConnection conn;

    @Before
    public void setUp() throws Exception {
        if (logger == null) {
            logger = org.mockito.Mockito.mock(Logger.class);
        }
        org.mockito.Mockito.reset(logger);
        PowerMockito.mockStatic(LoggerFactory.class);
        when(LoggerFactory.getLogger(DatadogHttpClient.class)).thenReturn(logger);

        conn = PowerMockito.mock(HttpURLConnection.class);
        URL url = PowerMockito.mock(URL.class);
        PowerMockito.whenNew(URL.class).withAnyArguments().thenReturn(url);
        when(url.openConnection()).thenReturn(conn);
        when(conn.getOutputStream()).thenReturn(new ByteArrayOutputStream());
    }

    private void stubResponse(int code, String body, boolean error) throws Exception {
        when(conn.getResponseCode()).thenReturn(code);
        when(conn.getErrorStream()).thenReturn(
            error ? new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)) : null);
        when(conn.getInputStream()).thenReturn(
            new ByteArrayInputStream(error ? new byte[0] : body.getBytes(StandardCharsets.UTF_8)));
    }

    private DatadogHttpClient newClient() {
        return new DatadogHttpClient("key", "http://example.com/", "http://example.com/logs/");
    }

    @Test
    public void submitLogsTreats2xxWithNonEmptyBodyAsSuccess() throws Exception {
        stubResponse(202, "{ }", false);

        newClient().submitLogs(
            Collections.singletonList(new JSONObject()),
            Collections.singletonList("env:test"));

        verify(logger).info("Sent '1' logs to Datadog");
        verify(logger, never()).error(anyString());
    }

    @Test
    public void submitLogsTreatsNon2xxAsFailure() throws Exception {
        stubResponse(500, "boom", true);

        newClient().submitLogs(
            Collections.singletonList(new JSONObject()),
            Collections.singletonList("env:test"));

        verify(logger).error(anyString());
        verify(logger, never()).info(anyString());
    }

    @Test
    public void submitMetricsAppliesTimeouts() throws Exception {
        stubResponse(200, "{\"status\":\"ok\"}", false);

        newClient().submitMetrics(Collections.singletonList(
            new DatadogMetric("m", "count", 1.0, Collections.emptyList())));

        verify(conn).setConnectTimeout(TIMEOUT_MS);
        verify(conn).setReadTimeout(TIMEOUT_MS);
        verify(conn).disconnect();
    }

    @Test
    public void submitLogsAppliesTimeoutsAndHeaders() throws Exception {
        stubResponse(200, "{}", false);

        newClient().submitLogs(
            Collections.singletonList(new JSONObject()),
            Collections.singletonList("env:test"));

        verify(conn).setConnectTimeout(TIMEOUT_MS);
        verify(conn).setReadTimeout(TIMEOUT_MS);
        verify(conn).setRequestProperty("DD-API-KEY", "key");
        verify(conn).setRequestProperty("User-Agent", "Datadog/jmeter-plugin");
        verify(conn).disconnect();
    }

    @Test
    public void submitEventAppliesTimeouts() throws Exception {
        stubResponse(200, "{\"status\":\"ok\"}", false);

        newClient().submitEvent("title", "text", "info", "agg",
            Collections.singletonList("env:test"), "jmeter");

        verify(conn).setConnectTimeout(TIMEOUT_MS);
        verify(conn).setReadTimeout(TIMEOUT_MS);
        verify(conn).disconnect();
    }
}
