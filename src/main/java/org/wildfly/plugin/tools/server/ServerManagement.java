/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;

/**
 * A utility for validating a server installations and executing common operations.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("unused")
public class ServerManagement {
    static final ModelNode EMPTY_ADDRESS = new ModelNode().setEmptyList();
    private static final Logger LOGGER = Logger.getLogger(ServerManagement.class);

    static {
        EMPTY_ADDRESS.protect();
    }

    /**
     * Checks whether the directory is a valid home directory for a server.
     * <p>
     * This validates the path is not {@code null}, exists, is a directory and contains a {@code jboss-modules.jar}.
     * </p>
     *
     * @param path the path to validate
     *
     * @return {@code true} if the path is valid otherwise {@code false}
     */
    public static boolean isValidHomeDirectory(final Path path) {
        return path != null
                && Files.exists(path)
                && Files.isDirectory(path)
                && Files.exists(path.resolve("jboss-modules.jar"));
    }

    /**
     * Checks whether the directory is a valid home directory for a server.
     * <p>
     * This validates the path is not {@code null}, exists, is a directory and contains a {@code jboss-modules.jar}.
     * </p>
     *
     * @param path the path to validate
     *
     * @return {@code true} if the path is valid otherwise {@code false}
     */
    public static boolean isValidHomeDirectory(final String path) {
        return path != null && isValidHomeDirectory(Path.of(path));
    }

    /**
     * Determines the servers "launch-type".
     *
     * @param client the client used to communicate with the server
     *
     * @return the servers launch-type or "unknown" if it could not be determined
     */
    public static String launchType(final ModelControllerClient client) {
        try {
            final ModelNode response = client.execute(Operations.createReadAttributeOperation(EMPTY_ADDRESS, "launch-type"));
            if (Operations.isSuccessfulOutcome(response)) {
                return Operations.readResult(response).asString();
            }
        } catch (RuntimeException | IOException e) {
            LOGGER.trace("Interrupted determining the launch type", e);
        }
        return "unknown";
    }

    /**
     * Takes a snapshot of the current servers configuration and returns the relative file name of the snapshot.
     * <p>
     * This simply executes the {@code take-snapshot} operation with no arguments.
     * </p>
     *
     * @param client the client used to take the snapshot
     *
     * @return the file name of the snapshot configuration file
     *
     * @throws IOException if an error occurs executing the operation
     */
    public static String takeSnapshot(final ModelControllerClient client) throws IOException {
        final ModelNode op = Operations.createOperation("take-snapshot");
        final ModelNode result = client.execute(op);
        if (!Operations.isSuccessfulOutcome(result)) {
            throw new RuntimeException(
                    "The take-snapshot operation did not complete successfully: " + Operations.getFailureDescription(result)
                            .asString());
        }
        final String snapshot = Operations.readResult(result)
                .asString();
        return snapshot.contains(File.separator)
                ? snapshot.substring(snapshot.lastIndexOf(File.separator) + 1)
                : snapshot;
    }
}
