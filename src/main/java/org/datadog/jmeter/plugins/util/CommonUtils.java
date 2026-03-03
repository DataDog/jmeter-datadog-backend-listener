package org.datadog.jmeter.plugins.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CommonUtils {

    /**
     * Maximum length for Datadog tags (key:value combined).
     * Reference: https://docs.datadoghq.com/getting_started/tagging/#defining-tags
     */
    private static final int MAX_TAG_LENGTH = 200;

    /**
     * Combine metric-specific tags with common base tags into a single list.
     * Result order: [tags..., baseTags...].
     *
     * @param baseTags common tags added to all metrics (e.g., runner tags, custom tags)
     * @param tags metric-specific tags
     * @return a new mutable list containing all tags
     */
    public static List<String> combineTags(List<String> baseTags, String... tags) {
        List<String> result = new ArrayList<>(tags.length + baseTags.size());
        Collections.addAll(result, tags);
        result.addAll(baseTags);
        return result;
    }

    /**
     * Sanitize a tag key-value pair for Datadog
     * Reference: https://docs.datadoghq.com/getting_started/tagging/#defining-tags
     *
     * @param key The tag key (without colon)
     * @param value The tag value to sanitize
     * @return A complete tag string "key:sanitized_value" 
     */
    public static String sanitizeTagPair(String key, String value) {
        if (value == null || value.isEmpty()) {
            return key + ":";
        }

        // key + ":" takes up key.length() + 1 characters
        int maxValueLength = Math.max(1, MAX_TAG_LENGTH - key.length() - 1);
        int limit = Math.min(value.length(), maxValueLength);
        StringBuilder out = new StringBuilder(key.length() + 1 + limit);
        out.append(key).append(':');
        
        boolean lastWasUnderscore = false;

        for (int i = 0; i < limit; i++) {
            char original = value.charAt(i);
            char sanitized = sanitizeTagChar(original);

            if (sanitized == '_') {
                // Collapse consecutive underscores, but preserve leading underscore
                if (!lastWasUnderscore) {
                    out.append('_');
                    lastWasUnderscore = true;
                }
            } else {
                out.append(sanitized);
                lastWasUnderscore = false;
            }
        }

        // Remove trailing underscores from value portion
        while (out.length() > key.length() + 1 && out.charAt(out.length() - 1) == '_') {
            out.setLength(out.length() - 1);
        }

        return out.toString();
    }

    /**
     * Sanitize a single character for Datadog tag values.
     * Reference: https://docs.datadoghq.com/getting_started/tagging/#defining-tags
     * @param c The character to sanitize
     * @return The sanitized character
     */
    private static char sanitizeTagChar(char c) {
        if (Character.isLetter(c)) {
            return Character.toLowerCase(c);
        }
        if (Character.isDigit(c)) {
            return c;
        }
        if (c == '_' || c == '-' || c == ':' || c == '.' || c == '/') {
            return c;
        }
        return '_';
    }

    public static String parseThreadGroup(String threadName) {
        // https://github.com/apache/jmeter/pull/622
        return threadName.substring(0, Math.max(0, threadName.lastIndexOf(" ")));
    }

}
