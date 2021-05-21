/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import org.datadog.jmeter.plugins.metrics.DatadogMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The type Datadog http client.
 */
public class DatadogHttpClient {
    private String apiKey;
    private List<String> inputTags;
    private static final Logger logger = LoggerFactory.getLogger(DatadogHttpClient.class);
    private static final String METRIC = "v1/series";
    private static final String VALIDATE = "v1/validate";
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
    public DatadogHttpClient(String apiKey, String apiUrl, String logIntakeUrl, List<String> inputTags) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.logIntakeUrl = logIntakeUrl;
        this.inputTags = inputTags;
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

            BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) {
                result.append(line);
            }
            rd.close();
            if (conn.getResponseCode() != 200) {
                logger.error("Invalid api key");
                logger.debug("The api endpoint returned: " + result.toString());
                return false;
            }
            return true;
        } catch (Exception e){
            logger.error(e.getLocalizedMessage());
            return false;
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
            for (String item:this.inputTags) {
                tags.add(item);
            }
            tags.addAll(Arrays.asList(datadogMetric.getTags()));

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

            OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8);
            logger.debug("Writing to OutputStreamWriter...");
            wr.write(payload.toString());
            wr.close();

            // Get response
            BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) {
                result.append(line);
            }
            rd.close();

            JSONObject json = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(result.toString());
            if ("ok".equals(json.getAsString("status"))) {
                logger.info(String.format("'%s' metrics were sent to Datadog", datadogMetrics.size()));
                logger.debug(String.format("Payload: %s", payload));
            } else {
                logger.error(String.format("Unable to send '%s' metrics to Datadog!", datadogMetrics.size()));
                logger.debug(String.format("Payload: %s", payload));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Submit logs.
     *
     * @param payload the payload
     */
    public void submitLogs(List<JSONObject> payload) {
        JSONArray logsArray = new JSONArray();
        logsArray.addAll(payload);

        HttpURLConnection conn;
        try {
            URL url = new URL(this.logIntakeUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("DD-API-KEY", this.apiKey);
            conn.setRequestProperty("User-Agent", "Datadog/jmeter-plugin");
            conn.setUseCaches(false);
            conn.setDoInput(true);
            conn.setDoOutput(true);

            OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8);
            wr.write(logsArray.toString());
            wr.close();

            // Get response
            BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) {
                result.append(line);
            }
            rd.close();

            if ("{}".equals(result.toString())) {
                logger.info(String.format("Sent '%s' logs to Datadog", payload.size()));
            } else {
                logger.error(String.format("Unable to send '%s' logs to Datadog", payload.size()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
