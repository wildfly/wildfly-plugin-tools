/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.wildfly.plugin.tools.ConsoleConsumer;
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

    private final ReentrantLock lock = new ReentrantLock();

    private volatile ProcessHandle process;
    private final T client;
    private final Configuration<?> configuration;
    private final DeploymentManager deploymentManager;
    private final AtomicBoolean closed;
    private final AtomicBoolean shutdown;

    protected AbstractServerManager(final ProcessHandle process, final T client,
            final Configuration<?> configuration) {
        this.process = process;
        this.client = client;
        this.configuration = configuration.immutable();
        deploymentManager = DeploymentManager.create(client);
        this.closed = new AtomicBoolean(false);
        this.shutdown = new AtomicBoolean((process == null || !process.isAlive()));
    }

    @Override
    public T client() {
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
        final ProcessHandle process = this.process;
        if (process != null) {
            if (process.isAlive()) {
                return CompletableFuture.supplyAsync(() -> {
                    lock.lock();
                    try {
                        internalClose(false, false);
                        return process.destroyForcibly();
                    } finally {
                        lock.unlock();
                    }
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
            final ProcessHandle currentProcess = this.process;
            long before = System.currentTimeMillis();
            if (isRunning()) {
                break;
            }
            timeout -= (System.currentTimeMillis() - before);
            if (currentProcess != null && !currentProcess.isAlive()) {
                throw new ServerManagerException("The process %d is no longer active.", currentProcess.pid());
            }
            TimeUnit.MILLISECONDS.sleep(sleep);
            timeout -= sleep;
        }
        if (timeout <= 0) {
            final ProcessHandle currentProcess = this.process;
            if (currentProcess != null) {
                currentProcess.destroy();
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
    public ServerManager start(final long timeout, final TimeUnit unit) {
        lock.lock();
        try {
            if ((process != null && process.isAlive()) || !shutdown.compareAndSet(true, false)) {
                throw new ServerManagerException("A server is already running, you must shutdown the server first.");
            }
            final Process process = configuration.launcher().launch();
            if (configuration.consumeStderr()) {
                ConsoleConsumer.start(process.getErrorStream(), System.err);
            }
            if (configuration.consumeStdout()) {
                ConsoleConsumer.start(process.getInputStream(), System.out);
            }
            if (!process.isAlive()) {
                throw new ServerManagerException("Failed to start server. The process %d is no longer active.", process.pid());
            }
            this.process = process.toHandle();
            if (waitFor(timeout, unit)) {
                shutdown.set(false);
                closed.set(false);
                return this;
            }
            this.process = null;
            shutdown.set(true);
            throw new ServerManagerException("The server did not start within %s %s", timeout,
                    unit.name().toLowerCase(Locale.ROOT));
        } catch (InterruptedException e) {
            shutdown.set(true);
            Thread.currentThread().interrupt();
            throw new ServerManagerException(e, "Failed to start the server");
        } catch (IOException e) {
            shutdown.set(true);
            throw new ServerManagerException(e, "Failed to start the server.");
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CompletionStage<ServerManager> startAsync(final long timeout, final TimeUnit unit) {
        return CompletableFuture.supplyAsync(() -> start(timeout, unit));
    }

    @Override
    public void shutdown(final long timeout) throws IOException {
        checkState();
        lock.lock();
        try {
            // Check if the server is still running
            if ((process != null && process.isAlive()) || (process == null && ServerManager.isRunning(client()))) {
                internalShutdown(client(), timeout);
            }
            waitForShutdown(client(), timeout);
            process = null;
            shutdown.set(true);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CompletableFuture<ServerManager> shutdownAsync(final long timeout) {
        checkState();
        final ServerManager serverManager = this;
        final ProcessHandle process = this.process;
        final CompletableFuture<ServerManager> future;
        if (process == null) {
            future = CompletableFuture.supplyAsync(() -> {
                lock.lock();
                try {
                    // Check if the server is still running
                    if (ServerManager.isRunning(client())) {
                        internalShutdown(client(), timeout);
                    }
                    // Wait for the server to shut down
                    waitForRemoteShutdown(client, (timeout > 0 ? timeout : TIMEOUT));
                    this.process = null;
                    shutdown.set(true);
                } catch (IOException e) {
                    throw new CompletionException("Failed to shutdown server.", e);
                } finally {
                    lock.unlock();
                }
                return serverManager;
            });
        } else {
            future = CompletableFuture.supplyAsync(() -> {
                lock.lock();
                try {
                    // Check if the process is still alive
                    if (process.isAlive()) {
                        internalShutdown(client(), timeout);
                    }
                } catch (IOException e) {
                    throw new CompletionException("Failed to shutdown server.", e);
                } finally {
                    lock.unlock();
                }
                return serverManager;
            }).thenCombine(process.onExit(), (sm, processHandle) -> serverManager).handle((sm, error) -> {
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
                lock.lock();
                try {
                    this.process = null;
                    shutdown.set(true);
                } finally {
                    lock.unlock();
                }
                return serverManager;
            });
        }
        return future;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        internalClose(configuration.shutdownOnClose(), true);
    }

    Optional<ProcessHandle> process() {
        return Optional.ofNullable(process);
    }

    void checkState() {
        if (closed.get()) {
            throw new ServerManagerException("The server manager has been closed and cannot process requests");
        }
    }

    void internalClose(final boolean shutdownOnClose, final boolean waitForShutdown) {
        lock.lock();
        try {
            if (closed.compareAndSet(false, true)) {
                if (shutdownOnClose) {
                    try {
                        if (shutdown.compareAndSet(false, true)) {
                            internalShutdown(client, 0);
                        }
                        if (waitForShutdown) {
                            waitForShutdown(client, TIMEOUT);
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
        } finally {
            lock.unlock();
        }
    }

    abstract void internalShutdown(ModelControllerClient client, long timeout) throws IOException;

    /**
     * Waits for the server to shut down. Callers should do this within the {@link #lock}.
     *
     * @param client  the client used to check a remote server
     * @param timeout the number of seconds we should wait for shut down
     */
    private void waitForShutdown(final ModelControllerClient client, final long timeout) throws IOException {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("waitForShutdown must be called while holding the lock");
        }
        if (process != null) {
            try {
                if (timeout > 0) {
                    process.onExit()
                            .get(timeout, TimeUnit.SECONDS);
                } else {
                    process.onExit()
                            .get();
                }
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new ServerManagerException(e, "Error waiting for process %d to exit.", process.pid());
            } finally {
                process = null;
            }
        } else {
            waitForRemoteShutdown(client, timeout);
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
    @SuppressWarnings("UnusedReturnValue")
    static ModelNode executeOperation(final ModelControllerClient client, final ModelNode op)
            throws IOException, OperationExecutionException {
        final ModelNode result = client.execute(op);
        if (Operations.isSuccessfulOutcome(result)) {
            return Operations.readResult(result);
        }
        throw new OperationExecutionException(op, result);
    }

    private static void waitForRemoteShutdown(final ModelControllerClient client, final long timeout) {
        final long deadlineNanos = timeout > 0 ? (System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout)) : Long.MAX_VALUE;
        final long sleep = 100L;
        // Wait for the server manager to finish shutting down
        while (ServerManager.isRunning(client)) {
            Thread.onSpinWait();
            // Check if we're passed the deadline
            if (System.nanoTime() >= deadlineNanos) {
                throw new ServerManagerException("The server failed to shut down within %d seconds.", timeout);
            }
            try {
                TimeUnit.MILLISECONDS.sleep(sleep);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
                throw new ServerManagerException("The server failed to shut down within %d seconds.", timeout);
            }
        }
    }
}
