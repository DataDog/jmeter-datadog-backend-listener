/* Unless explicitly stated otherwise all files in this repository are licensed under the Apache License 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2021-present Datadog, Inc.
 */

package org.datadog.jmeter.plugins.metrics;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class DatadogMetricContextTest {


    private boolean contextAreEquals(DatadogMetricContext ctx1, DatadogMetricContext ctx2) {
        if(ctx1 == ctx2) {
            return true;
        }
        if(ctx1.hashCode() != ctx2.hashCode()) {
            return false;
        }
        if(!ctx1.equals(ctx2) || !ctx2.equals(ctx1)) {
            return false;
        }

        Set<DatadogMetricContext> ctxSet = new HashSet<>();
        ctxSet.add(ctx1);
        ctxSet.add(ctx2);
        return ctxSet.size() == 1;
    }

    @Test
    public void allRefsAreUnique()
    {
        // Create 6 references:
        // - 2 strings of value 'foo'
        // - 2 strings of value 'bar', of ref X1 and X2
        // - 2 array of 1 string, the strings refs being X1 and X2
        String foo1 = new String("foo");
        String foo2 = new String("foo");
        String bar1 = new String("bar");
        String bar2 = new String("bar");
        List<String> tags1 = Arrays.asList(bar1);
        List<String> tags2 = Arrays.asList(bar2);

        assertNotSame(foo1, foo2);
        assertNotSame(tags1, tags2);
        assertSame(tags1.get(0), bar1);
        assertSame(tags2.get(0), bar2);
        assertNotSame(tags1.get(0), tags2.get(0));
        DatadogMetricContext ctx1 = new DatadogMetricContext(foo1, tags1);
        DatadogMetricContext ctx2 = new DatadogMetricContext(foo2, tags2);

        assertTrue(contextAreEquals(ctx1, ctx2));
    }

    @Test
    public void singleTagStringRef()
    {
        String foo1 = new String("foo");
        String foo2 = new String("foo");
        String bar = new String("bar");
        List<String> tags1 = Arrays.asList(bar);
        List<String> tags2 = Arrays.asList(bar);

        assertNotSame(foo1, foo2);
        assertNotSame(tags1, tags2);
        assertSame(tags1.get(0), tags2.get(0));

        DatadogMetricContext ctx1 = new DatadogMetricContext(foo1, tags1);
        DatadogMetricContext ctx2 = new DatadogMetricContext(foo2, tags2);

        assertTrue(contextAreEquals(ctx1, ctx2));
    }

    @Test
    public void singleTagSingleNameRefs()
    {
        String foo = new String("foo");
        String bar = new String("bar");
        List<String> tags1 = Arrays.asList(bar);
        List<String> tags2 = Arrays.asList(bar);

        assertNotSame(tags1, tags2);
        assertSame(tags1.get(0), bar);
        assertSame(tags2.get(0), bar);
        assertSame(tags1.get(0), tags2.get(0));

        DatadogMetricContext ctx1 = new DatadogMetricContext(foo, tags1);
        DatadogMetricContext ctx2 = new DatadogMetricContext(foo, tags2);

        assertTrue(contextAreEquals(ctx1, ctx2));
    }

    @Test
    public void singleTagArrayRef()
    {
        String foo1 = new String("foo");
        String foo2 = new String("foo");

        String bar = new String("bar");
        List<String> tags = Arrays.asList(bar);

        assertNotSame(foo1, foo2);
        assertSame(tags.get(0), bar);

        DatadogMetricContext ctx1 = new DatadogMetricContext(foo1, tags);
        DatadogMetricContext ctx2 = new DatadogMetricContext(foo2, tags);

        assertTrue(contextAreEquals(ctx1, ctx2));
    }

    @Test
    public void allRefsAreTheSame()
    {
        String foo = new String("foo");

        String bar = new String("bar");
        List<String> tags = Arrays.asList(bar);

        assertSame(tags.get(0), bar);

        DatadogMetricContext ctx1 = new DatadogMetricContext(foo, tags);
        DatadogMetricContext ctx2 = new DatadogMetricContext(foo, tags);

        assertTrue(contextAreEquals(ctx1, ctx2));
    }

    @Test
    public void differentNamesMakesDifferentObjects()
    {
        List<String> tags = Arrays.asList();
        DatadogMetricContext ctx1 = new DatadogMetricContext("foo1", tags);
        DatadogMetricContext ctx2 = new DatadogMetricContext("foo2", tags);

        assertFalse(contextAreEquals(ctx1, ctx2));
    }

    @Test
    public void differentTagsMakesDifferentObjects()
    {
        List<String> tags1 = Arrays.asList("bar1");
        List<String> tags2 = Arrays.asList("bar2");
        DatadogMetricContext ctx1 = new DatadogMetricContext("foo", tags1);
        DatadogMetricContext ctx2 = new DatadogMetricContext("foo", tags2);

        assertFalse(contextAreEquals(ctx1, ctx2));
    }

}
