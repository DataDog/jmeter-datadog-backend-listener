package org.datadog.jmeter.plugins.util;

public class CommonUtils {

    public static String parseThreadGroup(@NonNull String threadName) {
        // https://github.com/apache/jmeter/pull/622
        return threadName.substring(0, Math.max(0, threadName.lastIndexOf(" ")));
    }

}
