/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Compares two versions. The comparison is case-insensitive.
 * <p>
 * Some qualifiers map to other qualifiers. Below is a table of those mappings.
 * <table border="1">
 * <tr>
 * <th>Qualifier</th>
 * <th>Mapping</th>
 * </tr>
 * <tr>
 * <td>GA</td>
 * <td>Final</td>
 * </tr>
 * <tr>
 * <td>a</td>
 * <td>Alpha</td>
 * </tr>
 * <tr>
 * <td>b</td>
 * <td>Beta</td>
 * </tr>
 * <tr>
 * <td>m</td>
 * <td>Milestone</td>
 * </tr>
 * <tr>
 * <td>cr</td>
 * <td>rc</td>
 * </tr>
 * </table>
 * </p>
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class VersionComparator implements Comparator<String> {
    private static final VersionComparator INSTANCE = new VersionComparator();
    private static final VersionComparator IGNORE_INSTANCE = new VersionComparator(true);

    private final boolean ignoreSnapshots;

    /**
     * Creates a new version comparator.
     */
    public VersionComparator() {
        this(false);
    }

    private VersionComparator(final boolean ignoreSnapshots) {
        this.ignoreSnapshots = ignoreSnapshots;
    }

    /**
     * Returns an instance of a version comparator.
     *
     * @return a version comparator instance
     */
    public static VersionComparator getInstance() {
        return getInstance(false);
    }

    /**
     * Returns an instance of a version comparator which optionally ignore the SNAPSHOT release extension. This can
     * be useful for cases where you want to compare a version is at least a base version, not caring if it's a
     * SNAPSHOT.
     *
     * @param ignoreSnapshots {@code true} to ignore the SNAPSHOT release extension, otherwise {@code false} which
     *                            values a SNAPSHOT dependency less than a non-SNAPSHOT of the same version
     *
     * @return a version comparator instance
     */
    public static VersionComparator getInstance(final boolean ignoreSnapshots) {
        return ignoreSnapshots ? IGNORE_INSTANCE : INSTANCE;
    }

    /**
     * Compares the first version against the second version.
     *
     * @param v1 first version
     * @param v2 second version
     *
     * @return {@code 0} if the versions are equal, {@code -1} if version first version is less than the second version
     *             or {@code 1} if the first version is greater than the second version
     *
     * @see Comparator#compare(Object, Object)
     */
    public static int compareVersion(final String v1, final String v2) {
        return compareVersion(false, v1, v2);
    }

    /**
     * Compares the first version against the second version optionally ignoring if either version has a SNAPSHOT
     * release extension. This can be useful for cases where you want to compare a version is at least a base version,
     * not caring if it's a SNAPSHOT.
     *
     * <p>
     * If {@code ignoreSnapshots} is {@code true}, the version {@code 1.0.0.Final} and {@code 1.0.0.Final-SNAPSHOT} are
     * said to be equal. If set to {@code false}, {@code 1.0.0.Final} is greater than {@code 1.0.0.Final-SNAPSHOT}.
     * </p>
     *
     * @param ignoreSnapshots {@code true} to ignore the SNAPSHOT release extension, otherwise {@code false} which
     *                            values a SNAPSHOT dependency less than a non-SNAPSHOT of the same version
     * @param v1              the first version
     * @param v2              the second version
     *
     * @return {@code 0} if the versions are equal, {@code -1} if version first version is less than the second version
     *             or {@code 1} if the first version is greater than the second version
     */
    public static int compareVersion(final boolean ignoreSnapshots, final String v1, final String v2) {
        // If the strings are equal ignoring the case, we can assume these are equal
        if (Objects.requireNonNull(v1).equalsIgnoreCase(v2)) {
            return 0;
        }
        final Version version1 = Version.parse(v1, ignoreSnapshots);
        final Version version2 = Version.parse(Objects.requireNonNull(v2), ignoreSnapshots);
        // Ensures the result is always 0, 1 or -1
        return Integer.compare(version1.compareTo(version2), 0);
    }

    @Override
    public int compare(final String o1, final String o2) {
        return compareVersion(ignoreSnapshots, o1, o2);
    }

    private enum ReleaseType {
        UNKNOWN(null),
        SNAPSHOT("snapshot"),
        ALPHA("alpha", "a"),
        BETA("beta", "b"),
        MILESTONE("milestone", "m"),
        RELEASE_CANDIDATE("rc", "cr"),
        FINAL("final", "", "ga"),
        ;

        private static final Map<String, ReleaseType> ENTRIES;

        static {
            final Map<String, ReleaseType> map = new HashMap<>();
            for (ReleaseType r : values()) {
                if (r == UNKNOWN)
                    continue;
                map.put(r.type, r);
                map.put("-" + r.type, r);
                for (String alias : r.aliases) {
                    map.put(alias, r);
                }
            }
            ENTRIES = Map.copyOf(map);
        }

        private final String type;
        private final List<String> aliases;

        ReleaseType(final String type, final String... aliases) {
            this.type = type;
            this.aliases = List.of(aliases);
        }

        static ReleaseType find(final String s) {
            return ENTRIES.getOrDefault(s, UNKNOWN);
        }
    }

    private static class Version implements Comparable<Version> {
        private final List<Part> parts;
        private final String original;

        private Version(final String original, final List<Part> parts) {
            this.original = original;
            this.parts = parts;
        }

        static Version parse(final String version, final boolean ignoreSnapshot) {
            final List<Part> parts = new ArrayList<>();
            final StringBuilder sb = new StringBuilder();
            boolean isDigit = false;
            for (char c : version.toCharArray()) {
                switch (c) {
                    case '-':
                    case '.': {
                        if (isDigit) {
                            parts.add(new IntegerPart(Integer.parseInt(sb.toString())));
                        } else {
                            addStringPart(parts, sb, ignoreSnapshot);
                        }
                        sb.setLength(0);
                        isDigit = false;
                        continue;
                    }
                    default: {
                        if (Character.isDigit(c)) {
                            if (!isDigit && sb.length() > 0) {
                                addStringPart(parts, sb, ignoreSnapshot);
                                sb.setLength(0);
                            }
                            isDigit = true;
                        } else {
                            if (isDigit && sb.length() > 0) {
                                parts.add(new IntegerPart(Integer.parseInt(sb.toString())));
                                sb.setLength(0);
                            }
                            isDigit = false;
                        }
                        sb.append(c);
                    }
                }
            }
            if (sb.length() > 0) {
                if (isDigit) {
                    parts.add(new IntegerPart(Integer.parseInt(sb.toString())));
                } else {
                    addStringPart(parts, sb, ignoreSnapshot);
                }
            }
            return new Version(version, List.copyOf(parts));
        }

        private static void addStringPart(final Collection<Part> parts, final StringBuilder sb, final boolean ignoreSnapshot) {
            final var value = sb.toString();
            if (!(ignoreSnapshot && ReleaseType.SNAPSHOT.type.equalsIgnoreCase(value))) {
                parts.add(new StringPart(value));
            }
        }

        @Override
        public int compareTo(final Version o) {
            final Iterator<Part> left = parts.iterator();
            final Iterator<Part> right = o.parts.iterator();
            int result = 0;
            while (left.hasNext() || right.hasNext()) {
                if (left.hasNext() && right.hasNext()) {
                    result = left.next().compareTo(right.next());
                } else if (left.hasNext()) {
                    result = left.next().compareTo(NULL_PART);
                } else {
                    // Need the inverse of the comparison
                    result = (-1 * right.next().compareTo(NULL_PART));
                }
                if (result != 0) {
                    break;
                }
            }
            return result;
        }

        @Override
        public int hashCode() {
            return 33 * (17 + original.hashCode());
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj == this)
                return true;
            if (!(obj instanceof Version)) {
                return false;
            }
            final Version other = (Version) obj;
            return Objects.equals(original, other.original);
        }

        @Override
        public String toString() {
            return original;
        }

        @FunctionalInterface
        private interface Part extends Comparable<Part> {
        }

        private static final Part NULL_PART = o -> {
            throw new UnsupportedOperationException();
        };

        private static class IntegerPart implements Part {
            private final Integer value;

            private IntegerPart(final Integer value) {
                this.value = value;
            }

            @Override
            public int compareTo(final Part o) {
                if (o == NULL_PART) {
                    return value.compareTo(0);
                }
                if (o instanceof IntegerPart) {
                    return value.compareTo(((IntegerPart) o).value);
                }
                return 1;
            }

            @Override
            public String toString() {
                return value.toString();
            }
        }

        private static class StringPart implements Part {
            private final String originalValue;
            private final String value;
            private final ReleaseType releaseType;

            private StringPart(final String value) {
                originalValue = value;
                this.value = value.toLowerCase(Locale.ROOT);
                releaseType = ReleaseType.find(this.value);
            }

            @Override
            public int compareTo(final Part o) {
                if (o == NULL_PART) {
                    return releaseType.compareTo(ReleaseType.FINAL);
                }
                if (o instanceof StringPart) {
                    if (releaseType == ReleaseType.UNKNOWN && ((StringPart) o).releaseType == ReleaseType.UNKNOWN) {
                        return value.compareTo(((StringPart) o).value);
                    }
                    return releaseType.compareTo(((StringPart) o).releaseType);
                }
                return -1;
            }

            @Override
            public String toString() {
                return originalValue;
            }
        }
    }
}
