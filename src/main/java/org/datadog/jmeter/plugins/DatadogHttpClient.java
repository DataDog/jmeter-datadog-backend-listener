/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import org.apache.http.client.utils.URIBuilder;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The type Datadog http client.
 */
public class DatadogHttpClient {
    private String apiKey;
    private static final Logger logger = LoggerFactory.getLogger(DatadogHttpClient.class);
    private static final String METRIC = "v1/series";
    private static final String VALIDATE = "v1/validate";
    private static final String EVENTS = "v1/events";
    private String apiUrl = null;
    private String logIntakeUrl = null;
    private static final int timeoutMS = 60 * 1000;

    /**
     * Instantiates a new Datadog http client.
     *
     * @param apiKey the api key
     * @param apiUrl the api url
     * @param logIntakeUrl the log intake url
     */
    public DatadogHttpClient(String apiKey, String apiUrl, String logIntakeUrl) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.logIntakeUrl = logIntakeUrl;
    }

    /**
     * Validate connection boolean.
     *
     * @return the boolean
     */
    public boolean validateConnection() {
        String urlParameters = "?api_key=" + this.apiKey;
        HttpURLConnection conn = null;

        try {
            URL url = new URL(this.apiUrl + VALIDATE + urlParameters);
            conn = (HttpURLConnection) url.openConnection();
            logger.debug("Connecting to " + this.apiUrl + VALIDATE);
            conn.setConnectTimeout(timeoutMS);
            conn.setReadTimeout(timeoutMS);
            conn.setRequestMethod("GET");
            String result = readResponse(conn);
            if (conn.getResponseCode() != 200) {
                logger.error("Invalid api key");
                logger.debug("The api endpoint returned: " + result);
                return false;
            }
            return true;
        } catch (Exception e){
            logger.error(e.getLocalizedMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Submit metrics boolean.
     *
     * @param datadogMetrics the datadog metrics
     */
    public void submitMetrics(List<DatadogMetric> datadogMetrics) {

        // Place metric as item of series list
        JSONArray series = new JSONArray();

        for (DatadogMetric datadogMetric : datadogMetrics) {
            JSONArray points = new JSONArray();
            JSONArray point = new JSONArray();
            point.add(System.currentTimeMillis() / 1000);
            point.add(datadogMetric.getValue());
            points.add(point);

            JSONArray tags = new JSONArray();
            tags.addAll(datadogMetric.getTags());

            JSONObject metric = new JSONObject();
            metric.put("metric", datadogMetric.getName());
            metric.put("points", points);
            metric.put("type", datadogMetric.getType());
            metric.put("tags", tags);

            series.add(metric);
        }

        // Add series to payload
        JSONObject payload = new JSONObject();
        payload.put("series", series);

        String urlParameters = "?api_key=" + this.apiKey;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(this.apiUrl + METRIC + urlParameters);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setUseCaches(false);
            conn.setDoInput(true);
            conn.setDoOutput(true);

            try (OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                logger.debug("Writing to OutputStreamWriter...");
                wr.write(payload.toString());
            }

            String result = readResponse(conn);
            JSONObject json = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(result);
            if ("ok".equals(json.getAsString("status"))) {
                logger.info(String.format("'%s' metrics were sent to Datadog", datadogMetrics.size()));
                logger.debug(String.format("Payload: %s", payload));
            } else {
                logger.error(String.format("Unable to send '%s' metrics to Datadog!", datadogMetrics.size()));
                logger.debug(String.format("Payload: %s", payload));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Submit logs.
     *
     * @param payload the payload
     */
    public void submitLogs(List<JSONObject> payload, List<String> tags) {
        JSONArray logsArray = new JSONArray();
        logsArray.addAll(payload);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(buildLogsUrl(this.logIntakeUrl, tags));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("DD-API-KEY", this.apiKey);
            conn.setRequestProperty("User-Agent", "Datadog/jmeter-plugin");
            conn.setUseCaches(false);
            conn.setDoInput(true);
            conn.setDoOutput(true);

            try (OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                wr.write(logsArray.toString());
            }

            String result = readResponse(conn);
            if ("{}".equals(result)) {
                logger.info(String.format("Sent '%s' logs to Datadog", payload.size()));
            } else {
                logger.error(String.format("Unable to send '%s' logs to Datadog", payload.size()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String buildLogsUrl(String logsUrl, List<String> tags) throws URISyntaxException {
        if (tags.isEmpty()) {
            return logsUrl;
        }
        return new URIBuilder(logsUrl)
            .addParameter("ddtags", String.join(",", tags))
            .toString();
    }

    /**
     * Submit event.
     *
     * @param title the event title
     * @param text the event text
     * @param alertType the alert type
     * @param aggregationKey the aggregation key
     * @param tags the event tags
     * @param sourceTypeName the source type name
     */
    public void submitEvent(String title, String text, String alertType, String aggregationKey, List<String> tags, String sourceTypeName) {
        JSONObject payload = new JSONObject();
        payload.put("title", title);
        payload.put("text", text);
        payload.put("alert_type", alertType);
        payload.put("aggregation_key", aggregationKey);
        payload.put("source_type_name", sourceTypeName);
        payload.put("priority", "normal");
        
        if (tags != null && !tags.isEmpty()) {
            JSONArray tagsArray = new JSONArray();
            tagsArray.addAll(tags);
            payload.put("tags", tagsArray);
        }

        String urlParameters = "?api_key=" + this.apiKey;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(this.apiUrl + EVENTS + urlParameters);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(timeoutMS);
            conn.setReadTimeout(timeoutMS);
            conn.setUseCaches(false);
            conn.setDoInput(true);
            conn.setDoOutput(true);

            try (OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                wr.write(payload.toString());
            }

            String result = readResponse(conn);
            JSONObject json = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(result);
            if ("ok".equals(json.getAsString("status"))) {
                logger.info("Event '" + title + "' sent to Datadog");
            } else {
                logger.error("Unable to send event '" + title + "' to Datadog!");
            }
        } catch (Exception e) {
            logger.error("Failed to submit event to Datadog: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
    
    private String readResponse(HttpURLConnection conn) throws IOException {
        InputStream inputStream = conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder result = new StringBuilder();
        try (BufferedReader rd = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = rd.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }
}
