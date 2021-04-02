package org.datadog.jmeter.plugins.exceptions;

public class DatadogApiException extends Exception {
    public DatadogApiException(String message){
        super(message);
    }

}
