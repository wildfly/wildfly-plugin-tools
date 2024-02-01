/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author jdenise@redhat.com
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class Utils {
    private static final Pattern WHITESPACE_IF_NOT_QUOTED = Pattern.compile("(\\S+\"[^\"]+\")|\\S+");

    /**
     * Splits the arguments into a list. The arguments are split based on
     * whitespace while ignoring whitespace that is within quotes.
     *
     * @param arguments the arguments to split
     *
     * @return the list of the arguments
     */
    public static List<String> splitArguments(final CharSequence arguments) {
        final List<String> args = new ArrayList<>();
        final Matcher m = WHITESPACE_IF_NOT_QUOTED.matcher(arguments);
        while (m.find()) {
            final String value = m.group();
            if (!value.isBlank()) {
                args.add(value);
            }
        }
        return args;
    }
}
