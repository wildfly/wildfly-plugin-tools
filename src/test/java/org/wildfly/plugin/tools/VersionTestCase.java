/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class VersionTestCase {

    @Test
    public void checkCompare() {
        Assertions.assertEquals(-1, VersionComparator.compareVersion("1.0.0.Final", "1.0.1.Final"));
        Assertions.assertEquals(0, VersionComparator.compareVersion("1.0.1.Final", "1.0.1.Final"));
        Assertions.assertEquals(1, VersionComparator.compareVersion("1.0.2.Final", "1.0.1.Final"));
    }

    @Test
    public void testGetLatest() {
        compareLatest("10.0.2.Final", "10.0.2.Final", "9.0.1.Final", "8.0.0.Final", "1.0.0.Final", "10.0.0.Final");
        compareLatest("20.0.4.Alpha4", "2.0.3.Final", "20.0.4.Alpha3", "20.0.4.Alpha4", "10.0.5.Final");
        compareLatest("7.5.Final", "7.1.1.Final", "7.1.3.Final", "7.5.Final", "7.4.Final", "7.5.Final-SNAPSHOT");
    }

    @Test
    public void ignoreSnapshot() {
        Assertions.assertEquals(0, VersionComparator.compareVersion(true, "1.0.0.Final-SNAPSHOT", "1.0.0.Final"));
        Assertions.assertEquals(0, VersionComparator.compareVersion(true, "10.11.0.Alpha1-SNAPSHOT", "10.11.0.a1-SNAPSHOT"));
        Assertions.assertEquals(0, VersionComparator.compareVersion(true, "15.0.0.ga", "15.0.0.Final-SNAPSHOT"));
        Assertions.assertEquals(1, VersionComparator.compareVersion(true, "1.0.1.Final-SNAPSHOT", "1.0.0.Final"));
        Assertions.assertEquals(1, VersionComparator.compareVersion(true, "12.0.2.Final-SNAPSHOT", "12.0.2.Beta1"));
        Assertions.assertEquals(-1, VersionComparator.compareVersion(true, "12.0.1.Final-SNAPSHOT", "12.0.2.Alpha1-SNAPSHOT"));
    }

    @Test
    public void testSortOrder() {
        // Define a list in the expected order
        final List<String> orderedVersions = List.of(
                "1.0.0.a1-SNAPSHOT",
                "1.0.0.Alpha1",
                "1.0.0.Beta1",
                "1.0.0.b2",
                "1.0.0.Final",
                "1.0.1.Alpha3",
                "1.0.1.Alpha20",
                "1.7.0_6",
                "1.7.0_07-b06",
                "1.7.0_07-b07",
                "1.7.0_07",
                "1.7.0_09-a06",
                "10.1.0.Beta1",
                "10.1.0.GA-SNAPSHOT",
                "10.1.0",
                "10.1.1.Final",
                "11.0.0.Alpha5",
                "11.0.0.GA");

        final List<String> versions = new ArrayList<>(orderedVersions);
        Collections.shuffle(versions);

        // All entries should in the same order
        Assertions.assertTrue(orderedVersions.containsAll(versions));
        versions.sort(VersionComparator.getInstance());
        Assertions.assertEquals(orderedVersions, versions);
    }

    private void compareLatest(final String expected, final String... versions) {
        final SortedSet<String> set = new TreeSet<>(VersionComparator.getInstance());
        set.addAll(Arrays.asList(versions));
        Assertions.assertEquals(expected, set.last());
    }
}
