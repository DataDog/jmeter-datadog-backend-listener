/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2026-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.util;

import java.util.Arrays;
import java.util.Collection;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Enclosed.class)
public class CommonUtilsTest {

    private static String repeat(String str, int count) {
        StringBuilder sb = new StringBuilder(str.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    /** Helper to extract value from "key:value" pair. */
    private static String extractValue(String pair) {
        return pair.split(":", 2)[1];
    }

    @RunWith(Parameterized.class)
    public static class SanitizeTagPairValueTest {

        @Parameters(name = "\"{0}\" -> \"{1}\"")
        public static Collection<Object[]> data() {
            return Arrays.asList(new Object[][] {
                { null, "" },
                { "", "" },
                { "abcxyz", "abcxyz" },
                { "12345", "12345" },
                { "env:production", "env:production" },
                { "/path/to/resource", "/path/to/resource" },
                { "ABCXYZ", "abcxyz" },
                { "hello world", "hello_world" },
                { "key=value", "key_value" },
                { "hello\tworld", "hello_world" },
                { "café", "café" },
                { "日本語", "日本語" },
                { "Ωmega", "ωmega" },  // Greek uppercase → lowercase
                { "/api/users?id=123&name=test", "/api/users_id_123_name_test" },
                { "http://example.com", "http://example.com" },
                { "HTTP Request /api/v1/users", "http_request_/api/v1/users" },
                { "Thread Group 1-5", "thread_group_1-5" },
                { "test🎉emoji", "test_emoji" },
                { "price$100", "price_100" },
                { "50%off", "50_off" },
                { "Failing Request (404)", "failing_request_404" },
                { "Very Slow Request (1s)", "very_slow_request_1s" },
                // Leading underscore preserved (single)
                { "_hello", "_hello" },
                { "___hello", "_hello" },
                // Trailing underscore removed
                { "hello_", "hello" },
                { "hello___", "hello" },
                // Consecutive underscores collapsed
                { "hello__world", "hello_world" },
                { "hello____world", "hello_world" },
                // Only underscores empty
                { "___", "" },
                { "_", "" },
            });
        }

        private final String input;
        private final String expected;

        public SanitizeTagPairValueTest(String input, String expected) {
            this.input = input;
            this.expected = expected;
        }

        @Test
        public void testSanitizeTagPairValue() {
            // Use short key "t" so truncation is at 198 chars (practically no truncation for these tests)
            String result = CommonUtils.sanitizeTagPair("t", input);
            Assert.assertEquals("t:" + expected, result);
        }
    }

    public static class SanitizeTagPairTest {
        private static final int MAX_TAG_LENGTH = 200;

        @Test
        public void testBasicPair() {
            Assert.assertEquals("sample_label:hello_world",
                CommonUtils.sanitizeTagPair("sample_label", "Hello World"));
        }

        @Test
        public void testPairWithSpecialChars() {
            Assert.assertEquals("response_code:200",
                CommonUtils.sanitizeTagPair("response_code", "200"));
        }
        
        @Test
        public void testOneBelowLimit() {
            // mykey (5 chars) + colon (1 char) = 6 chars
            // Value of 193 chars → total = 199 (1 below limit, no truncation)
            String key = "mykey";
            String value = repeat("a", 193);
            String result = CommonUtils.sanitizeTagPair(key, value);

            Assert.assertEquals(199, result.length());
            Assert.assertEquals("mykey:" + value, result);
        }

        @Test
        public void testExactlyAtLimit() {
            // mykey (5 chars) + colon (1 char) = 6 chars
            // Value of 194 chars → total = 200 (exactly at limit, no truncation)
            String key = "mykey";
            String value = repeat("b", 194);
            String result = CommonUtils.sanitizeTagPair(key, value);

            Assert.assertEquals(MAX_TAG_LENGTH, result.length());
            Assert.assertEquals("mykey:" + value, result);
        }

        @Test
        public void testOneAboveLimit() {
            // mykey (5 chars) + colon (1 char) = 6 chars
            // Value of 195 chars → total = 201 (1 above limit, truncated to 200)
            String key = "mykey";
            String value = repeat("c", 195);
            String result = CommonUtils.sanitizeTagPair(key, value);

            Assert.assertEquals(MAX_TAG_LENGTH, result.length());
            Assert.assertEquals("mykey:" + repeat("c", 194), result);
        }

        @Test
        public void testValueSanitization() {
            // Verify value is sanitized (uppercase, spaces, special chars)
            Assert.assertEquals("assertion_name:my_test_assertion",
                CommonUtils.sanitizeTagPair("assertion_name", "My Test Assertion"));
        }

    }
}
