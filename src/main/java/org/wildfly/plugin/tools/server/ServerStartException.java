/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import org.wildfly.core.launcher.CommandBuilder;

/**
 * Represents a failure when attempting to start a server.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class ServerStartException extends RuntimeException {
    private final CommandBuilder commandBuilder;

    ServerStartException(final CommandBuilder commandBuilder, final Throwable cause) {
        super("Failed to start server with command: " + commandBuilder.buildArguments(), cause);
        this.commandBuilder = commandBuilder;
    }

    /**
     * The command builder used which caused a boot failure.
     *
     * @return the command builder which failed to boot
     */
    public CommandBuilder getCommandBuilder() {
        return commandBuilder;
    }
}
