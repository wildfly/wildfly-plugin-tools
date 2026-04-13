/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.util;

import java.util.Collection;

/**
 * A utility for assertions.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class Assertions {

    /**
     * Checks if the value is {@code null} and throws an {@link IllegalArgumentException} if it is. Otherwise, the value
     * itself is returned.
     *
     * @param parameterName the name of the parameter to check
     * @param value         the value to check
     * @param <T>           the type of the value
     *
     * @return the value if not {@code null}
     *
     * @throws IllegalArgumentException if the object representing the parameter is {@code null}
     */
    public static <T> T checkNotNullParam(final String parameterName, final T value) {
        if (value == null) {
            throw new IllegalArgumentException("Parameter %s cannot be null.".formatted(parameterName));
        }
        return value;
    }

    /**
     * Checks if the parameter is {@code null} or empty and throws an {@link IllegalArgumentException} if it is.
     *
     * @param name  the name of the parameter
     * @param value the value to check
     *
     * @return the parameter value
     *
     * @throws IllegalArgumentException if the object representing the parameter is {@code null}
     */
    public static String requiresNotNullOrNotEmptyParameter(final String name, final String value)
            throws IllegalArgumentException {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Parameter %s cannot be null or empty.".formatted(name));
        }
        return value;
    }

    /**
     * Checks if the parameter is {@code null} or empty and throws an {@link IllegalArgumentException} if it is.
     *
     * @param name  the name of the parameter
     * @param value the value to check
     *
     * @return the parameter value
     *
     * @throws IllegalArgumentException if the object representing the parameter is {@code null}
     */
    public static <E, T extends Collection<E>> T requiresNotNullOrNotEmptyParameter(final String name, final T value)
            throws IllegalArgumentException {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Parameter %s cannot be null or empty.".formatted(name));
        }
        return value;
    }
}
