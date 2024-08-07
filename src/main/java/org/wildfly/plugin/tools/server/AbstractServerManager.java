/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
abstract class AbstractServerManager<T extends ModelControllerClient> implements ServerManager {
    private static final Logger LOGGER = Logger.getLogger(AbstractServerManager.class);

    protected final ProcessHandle process;
    final T client;
    private final boolean shutdownOnClose;
    private final DeploymentManager deploymentManager;
    private final AtomicBoolean closed;
    private final AtomicBoolean shutdown;

    protected AbstractServerManager(final ProcessHandle process, final T client,
            final boolean shutdownOnClose) {
        this.process = process;
        this.client = client;
        this.shutdownOnClose = shutdownOnClose;
        deploymentManager = DeploymentManager.create(client);
        this.closed = new AtomicBoolean(false);
        this.shutdown = new AtomicBoolean(false);
    }

    @Override
    public ModelControllerClient client() {
        checkState();
        return client;
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
    public CompletableFuture<ServerManager> kill() {
        if (process != null) {
            if (process.isAlive()) {
                return CompletableFuture.supplyAsync(() -> {
                    internalClose(false, false);
                    return process.destroyForcibly();
                }).thenCompose((successfulRequest) -> {
                    if (successfulRequest) {
                        return process.onExit().thenApply((processHandle) -> this);
                    }
                    return CompletableFuture.completedFuture(this);
                });
            }
        }
        return CompletableFuture.completedFuture(this);
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
                throw new ServerManagerException("The process %d is no longer active.", process.pid());
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
     *
     * @throws OperationExecutionException if the reload operation fails
     */
    @Override
    public void executeReload(final ModelNode reloadOp) throws IOException, OperationExecutionException {
        try {
            executeOperation(reloadOp);
        } catch (IOException e) {
            final Throwable cause = e.getCause();
            if (!(cause instanceof ExecutionException) && !(cause instanceof CancellationException)) {
                throw e;
            } // else ignore, this might happen if the channel gets closed before we got the response
        }
    }

    @Override
    public void shutdown(final long timeout) throws IOException {
        checkState();
        if (shutdown.compareAndSet(false, true)) {
            internalShutdown(client(), timeout);
        }
        waitForShutdown(client());
    }

    @Override
    public CompletableFuture<ServerManager> shutdownAsync(final long timeout) {
        checkState();
        final ServerManager serverManager = this;
        if (process != null) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (shutdown.compareAndSet(false, true)) {
                        internalShutdown(client, timeout);
                    }
                } catch (IOException e) {
                    throw new CompletionException("Failed to shutdown server.", e);
                }
                return null;
            })
                    .thenCombine(process.onExit(), (outcome, processHandle) -> null)
                    .handle((ignore, error) -> {
                        if (error != null && process.isAlive()) {
                            if (process.destroyForcibly()) {
                                LOGGER.warnf(error,
                                        "Failed to shutdown the server. An attempt to destroy the process %d has been made, but it may still temporarily run in the background.",
                                        process.pid());
                            } else {
                                LOGGER.warnf(error,
                                        "Failed to shutdown server and destroy the process %d. The server may still be running in a process.",
                                        process.pid());
                            }
                        }
                        return serverManager;
                    });
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (shutdown.compareAndSet(false, true)) {
                    internalShutdown(client, timeout);
                }
            } catch (IOException e) {
                throw new CompletionException("Failed to shutdown server.", e);
            }
            return null;
        }).thenApply(outcome -> {
            // Wait for the server manager to finish shutting down
            while (ServerManager.isRunning(client)) {
                Thread.onSpinWait();
            }
            return serverManager;
        });
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        internalClose(shutdownOnClose, true);
    }

    void checkState() {
        if (closed.get()) {
            throw new ServerManagerException("The server manager has been closed and cannot process requests");
        }
    }

    void internalClose(final boolean shutdownOnClose, final boolean waitForShutdown) {
        if (closed.compareAndSet(false, true)) {
            if (shutdownOnClose) {
                try {
                    if (shutdown.compareAndSet(false, true)) {
                        internalShutdown(client, 0);
                    }
                    if (waitForShutdown) {
                        waitForShutdown(client);
                    }
                } catch (IOException e) {
                    LOGGER.error("Failed to shutdown the server while closing the server manager.", e);
                }
            }
            try {
                client.close();
            } catch (IOException e) {
                LOGGER.error("Failed to close the client.", e);
            }
        }
    }

    abstract void internalShutdown(ModelControllerClient client, long timeout) throws IOException;

    private void waitForShutdown(final ModelControllerClient client) {
        if (process != null) {
            try {
                process.onExit()
                        .get();
            } catch (InterruptedException | ExecutionException e) {
                throw new ServerManagerException(e, "Error waiting for process %d to exit.", process.pid());
            }
        } else {
            // Wait for the server manager to finish shutting down
            while (ServerManager.isRunning(client)) {
                Thread.onSpinWait();
            }
        }
    }

    /**
     * Executes the operation with the {@code client} returning the result or throwing an {@link OperationExecutionException}
     * if the operation failed.
     *
     * @param client the client used to execute the operation
     * @param op     the operation to execute
     *
     * @return the result of the operation
     *
     * @throws IOException                 if an error occurs communicating with the server
     * @throws OperationExecutionException if the operation failed
     */
    static ModelNode executeOperation(final ModelControllerClient client, final ModelNode op)
            throws IOException, OperationExecutionException {
        final ModelNode result = client.execute(op);
        if (Operations.isSuccessfulOutcome(result)) {
            return Operations.readResult(result);
        }
        throw new OperationExecutionException(op, result);
    }
}
