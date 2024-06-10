/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.wildfly.plugin.tools.ContainerDescription;
import org.wildfly.plugin.tools.DeploymentManager;
import org.wildfly.plugin.tools.OperationExecutionException;

/**
 * A utility for validating a server installations and executing common operations.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("unused")
abstract class AbstractServerManager implements ServerManager {
    private static final Logger LOGGER = Logger.getLogger(AbstractServerManager.class);

    protected final ProcessHandle process;
    private final DeploymentManager deploymentManager;

    protected AbstractServerManager(final ProcessHandle process, final ModelControllerClient client) {
        this.process = process;
        deploymentManager = DeploymentManager.Factory.create(client);
    }

    @Override
    public ContainerDescription containerDescription() throws IOException {
        return ContainerDescription.lookup(client());
    }

    @Override
    public DeploymentManager deploymentManager() {
        return deploymentManager;
    }

    /**
     * Determines the servers "launch-type".
     *
     * @return the servers launch-type or "unknown" if it could not be determined
     */
    @Override
    public String launchType() {
        return ServerManager.launchType(client()).orElse("unknown");
    }

    @Override
    public boolean waitFor(final long startupTimeout, final TimeUnit unit) throws InterruptedException {
        long timeout = unit.toMillis(startupTimeout);
        final long sleep = 100;
        while (timeout > 0) {
            long before = System.currentTimeMillis();
            if (isRunning()) {
                break;
            }
            timeout -= (System.currentTimeMillis() - before);
            if (process != null && !process.isAlive()) {
                throw new RuntimeException(
                        String.format("The process %d is no longer active.", process.pid()));
            }
            TimeUnit.MILLISECONDS.sleep(sleep);
            timeout -= sleep;
        }
        if (timeout <= 0) {
            if (process != null) {
                process.destroy();
            }
            return false;
        }
        return true;
    }

    /**
     * Takes a snapshot of the current servers configuration and returns the relative file name of the snapshot.
     * <p>
     * This simply executes the {@code take-snapshot} operation with no arguments.
     * </p>
     *
     * @return the file name of the snapshot configuration file
     *
     * @throws IOException                 if an error occurs executing the operation
     * @throws OperationExecutionException if the take-snapshot operation fails
     */
    @Override
    public String takeSnapshot() throws IOException, OperationExecutionException {
        final ModelNode op = Operations.createOperation("take-snapshot");
        final String snapshot = executeOperation(op).asString();
        return snapshot.contains(File.separator)
                ? snapshot.substring(snapshot.lastIndexOf(File.separator) + 1)
                : snapshot;
    }

    /**
     * Reloads the server and returns immediately.
     *
     * @param reloadOp the reload operation to execute
     * @throws OperationExecutionException if the reload operation fails
     */
    @Override
    public void executeReload(final ModelNode reloadOp) throws OperationExecutionException {
        try {
            executeOperation(reloadOp);
        } catch (IOException e) {
            final Throwable cause = e.getCause();
            if (!(cause instanceof ExecutionException) && !(cause instanceof CancellationException)) {
                throw new RuntimeException(e);
            } // else ignore, this might happen if the channel gets closed before we got the response
        }
    }
}
