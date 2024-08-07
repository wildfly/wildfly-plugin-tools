/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

/**
 * An exception that represents some sort of failure within the {@linkplain ServerManager server manager}.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 * @since 1.2
 */
public class ServerManagerException extends RuntimeException {

    /**
     * Creates a new server manager exception with the specified detail message.
     *
     * @param msg the detail message
     */
    ServerManagerException(final String msg) {
        super(msg);
    }

    /**
     * Creates a new server manager exception with the specified detail message. The message and arguments are formatted
     * with {@link String#format(String, Object...)}.
     *
     * @param format the message format
     * @param args   the arguments for the format
     */
    ServerManagerException(final String format, final Object... args) {
        super(String.format(format, args));
    }

    /**
     * Creates a new server manager exception with the specified detail message and cause.
     *
     * @param cause the cause of the exception
     * @param msg   the detail message
     */
    ServerManagerException(final Throwable cause, final String msg) {
        super(msg, cause);
    }

    /**
     * Creates a new server manager exception with the specified detail message and cause. The message and arguments are
     * formatted with {@link String#format(String, Object...)}.
     *
     * @param cause  the cause of the exception
     * @param format the message format
     * @param args   the arguments for the format
     */
    ServerManagerException(final Throwable cause, final String format, final Object... args) {
        super(String.format(format, args), cause);
    }

    /**
     * Creates a server manager exception indicating there was a failure in starting the server. The
     * {@linkplain Configuration configuration} parameter is used to add the start command, if applicable, to the detail
     * message.
     *
     * @param configuration the configuration which caused the start exception
     * @param cause         the cause of the exception
     *
     * @return a new server manager exception
     */
    static ServerManagerException startException(final Configuration<?> configuration, final Throwable cause) {
        return new ServerManagerException(cause, createStartFailureMessage(configuration));
    }

    private static String createStartFailureMessage(final Configuration<?> configuration) {
        if (configuration.commandBuilder() != null) {
            return "Failed to start server with command: " + configuration.commandBuilder().build();
        }
        return String.format("Failed to start %s server.", configuration.launchType().name().toLowerCase());
    }
}
