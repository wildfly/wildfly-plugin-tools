/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

/**
 * Represents a failure when attempting to start a server.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 * @since 1.2
 */
public class ServerStartException extends RuntimeException {

    ServerStartException(final Configuration configuration, final Throwable cause) {
        super(createMessage(configuration), cause);
    }

    private static String createMessage(final Configuration configuration) {
        if (configuration.commandBuilder() != null) {
            return "Failed to start server with command: " + configuration.commandBuilder().build();
        }
        return String.format("Failed to start %s server.", configuration.launchType().name().toLowerCase());
    }
}
