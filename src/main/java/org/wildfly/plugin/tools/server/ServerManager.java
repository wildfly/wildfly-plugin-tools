/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.Operation;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.wildfly.plugin.tools.ContainerDescription;
import org.wildfly.plugin.tools.DeploymentManager;
import org.wildfly.plugin.tools.OperationExecutionException;

/**
 * A simple manager for various interactions with a potentially running server.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("unused")
public interface ServerManager {

    /**
     * A builder used to build a {@link ServerManager}.
     */
    class Builder {
        private ModelControllerClient client;
        private String managementAddress;
        private int managementPort;
        private ProcessHandle process;

        /**
         * Sets the client to use for the server manager.
         *
         * @param client the client to use to communicate with the server
         *
         * @return this builder
         */
        public Builder client(final ModelControllerClient client) {
            this.client = client;
            return this;
        }

        /**
         * The process handle to associate with the server manager.
         *
         * @param process the process handle to associate with the server manager
         *
         * @return this builder
         */
        public Builder process(final ProcessHandle process) {
            this.process = process;
            return this;
        }

        /**
         * The process to associate with the server manager. If the {@code process} argument is not {@code null}, this
         * simply invokes {@link #process(ProcessHandle) process(process.toHandle())}.
         *
         * @param process the process to associate with the server manager
         *
         * @return this builder
         *
         * @see #process(ProcessHandle)
         */
        public Builder process(final Process process) {
            this.process = process == null ? null : process.toHandle();
            return this;
        }

        /**
         * The management address to use for the client if the {@linkplain #client(ModelControllerClient) client} has
         * not been set.
         *
         * @param managementAddress the management address, default is {@code localhost}
         *
         * @return this builder
         */
        public Builder managementAddress(final String managementAddress) {
            this.managementAddress = managementAddress;
            return this;
        }

        /**
         * The management port to use for the client if the {@linkplain #client(ModelControllerClient) client} has
         * not been set.
         *
         * @param managementPort the management port, default is {@code 9990}
         *
         * @return this builder
         */
        public Builder managementPort(final int managementPort) {
            this.managementPort = managementPort;
            return this;
        }

        /**
         * Creates a {@link StandaloneManager} based on the builders settings. If the
         * {@link #client(ModelControllerClient) client} was not set, the {@link #managementAddress(String) managementAddress}
         * and the {@link #managementPort(int) managementPort} will be used to create the client.
         *
         * @return a new {@link StandaloneManager}
         */
        public StandaloneManager standalone() {
            return new StandaloneManager(process, getOrCreateClient());
        }

        /**
         * Creates a {@link DomainManager} based on the builders settings. If the
         * {@link #client(ModelControllerClient) client} was not set, the {@link #managementAddress(String) managementAddress}
         * and the {@link #managementPort(int) managementPort} will be used to create the client.
         *
         * @return a new {@link DomainManager}
         */
        public DomainManager domain() {
            return new DomainManager(process, getOrCreateDomainClient());
        }

        /**
         * Creates either a {@link DomainManager} or {@link StandaloneManager} based on the
         * {@link ServerManager#launchType(ModelControllerClient)}. If the {@link #client(ModelControllerClient) client}
         * was not set, the {@link #managementAddress(String) managementAddress} and the
         * {@link #managementPort(int) managementPort} will be used to create the client.
         * <p>
         * Note that if the {@linkplain #process(ProcessHandle) process} was not set, the future may never complete
         * if a server is never started. It's best practice to either use a known {@link #standalone()} or {@link #domain()}
         * server manager type, or use the {@link CompletableFuture#get(long, TimeUnit)} method to timeout if a server
         * was never started.
         * </p>
         *
         * @return a completable future that will eventually produce a {@link DomainManager} or {@link StandaloneManager}
         *             assuming a server is running
         */
        public CompletableFuture<ServerManager> build() {
            final ModelControllerClient client = getOrCreateClient();
            final ProcessHandle process = this.process;
            return CompletableFuture.supplyAsync(() -> {
                // Wait until the server is running, then determine what type we need to return
                while (!isRunning(client)) {
                    Thread.onSpinWait();
                }
                final String launchType = launchType(client).orElseThrow(() -> new IllegalStateException(
                        "Could not determine the type of the server. Verify the server is running."));
                if ("STANDALONE".equals(launchType)) {
                    return new StandaloneManager(process, client);
                } else if ("DOMAIN".equals(launchType)) {
                    return new DomainManager(process, getOrCreateDomainClient());
                }
                throw new IllegalStateException(
                        String.format("Only standalone and domain servers are support. %s is not supported.", launchType));
            });
        }

        private ModelControllerClient getOrCreateClient() {
            if (client == null) {
                final String address = managementAddress == null ? "localhost" : managementAddress;
                final int port = managementPort <= 0 ? 9990 : managementPort;
                try {
                    return ModelControllerClient.Factory.create(address, port);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return client;
        }

        private DomainClient getOrCreateDomainClient() {
            if (client == null) {
                return DomainClient.Factory.create(getOrCreateClient());
            }
            return (client instanceof DomainClient) ? (DomainClient) client : DomainClient.Factory.create(client);
        }
    }

    /**
     * Creates a builder to build a server manager.
     *
     * @return a new builder
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Attempts to find the controlling process. For a domain server this, returns the process handle for the
     * process controller. For a standalone server, this returns the standalone process handle.
     * <p>
     * Please note this method does not work on Windows. The {@link ProcessHandle.Info#arguments()} is limited by the
     * operating systems privileges.
     * </p>
     *
     * @return the process handle if one was found running
     */
    static Optional<ProcessHandle> findProcess() {
        // Attempt to find a running server process, this may be a process controller or standalone instance
        return ProcessHandle.allProcesses()
                .filter(p -> {
                    final ProcessHandle.Info info = p.info();
                    boolean found = false;
                    if (info.arguments().isPresent()) {
                        // Look for the "-jar jboss-modules.jar" argument
                        final String[] arguments = info.arguments().get();
                        for (int i = 0; i < arguments.length; i++) {
                            final String arg = arguments[i];
                            if (!found && arg.trim().equalsIgnoreCase("-jar")) {
                                found = arguments[++i].contains("jboss-modules.jar");
                                continue;
                            }
                            if (found && ("org.jboss.as.process-controller").equals(arg)
                                    || "org.jboss.as.standalone".equals(arg)) {
                                return true;
                            }
                        }
                    }
                    return false;
                }).findFirst();
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
    static boolean isValidHomeDirectory(final Path path) {
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
    static boolean isValidHomeDirectory(final String path) {
        return path != null && isValidHomeDirectory(Path.of(path));
    }

    /**
     * Checks if a server is running regardless if it is a standalone or domain server.
     *
     * @param client the client used to check the server state
     *
     * @return {@code true} if a server is running, otherwise {@code false}
     */
    static boolean isRunning(final ModelControllerClient client) {
        return launchType(client).map(launchType -> {
            try {
                if ("STANDALONE".equals(launchType)) {
                    return CommonOperations.isStandaloneRunning(client);
                } else if ("DOMAIN".equals(launchType)) {
                    return CommonOperations.isDomainRunning(client, false);
                }
            } catch (RuntimeException e) {
                Logger.getLogger(ServerManager.class).trace("Interrupted determining if server is running", e);
            }
            return false;
        }).orElse(false);
    }

    /**
     * Returns the "launch-type" attribute of a server.
     *
     * @param client the client used to check the launch-type attribute
     *
     * @return the servers launch-type
     */
    static Optional<String> launchType(final ModelControllerClient client) {
        try {
            final ModelNode response = client
                    .execute(Operations.createReadAttributeOperation(new ModelNode().setEmptyList(), "launch-type"));
            return Operations.isSuccessfulOutcome(response) ? Optional.of(Operations.readResult(response).asString())
                    : Optional.empty();
        } catch (RuntimeException | IOException ignore) {
        }
        return Optional.empty();
    }

    /**
     * Returns the client associated with this server manager.
     *
     * @return a client to communicate with the server
     */
    ModelControllerClient client();

    /**
     * Gets the "server-state" for standalone servers or the "host-state" for domain servers.
     *
     * @return the server-state or "failed" if an error occurred
     */
    String serverState();

    /**
     * Determines the servers "launch-type".
     *
     * @return the servers launch-type or "unknown" if it could not be determined
     */
    String launchType();

    /**
     * Takes a snapshot of the current servers configuration and returns the relative file name of the snapshot.
     * <p>
     * This simply executes the {@code take-snapshot} operation with no arguments.
     * </p>
     *
     * @return the file name of the snapshot configuration file
     *
     * @throws IOException if an error occurs executing the operation
     */
    String takeSnapshot() throws IOException;

    /**
     * Returns the container description for the running server.
     *
     * @return the container description for the running server
     *
     * @throws IOException if an error occurs communicating with the server
     */
    ContainerDescription containerDescription() throws IOException;

    /**
     * Returns the deployment manager for the server.
     *
     * @return the deployment manager
     */
    DeploymentManager deploymentManager();

    /**
     * Checks to see if a server is running.
     *
     * @return {@code true} if the server is running, otherwise {@code false}
     */
    boolean isRunning();

    /**
     * Waits the given amount of time in seconds for a server to start.
     * <p>
     * If the {@code process} is not {@code null} and a timeout occurs the process will be
     * {@linkplain Process#destroy() destroyed}.
     * </p>
     *
     * @param startupTimeout the time, in seconds, to wait for the server start
     *
     * @return {@code true} if the server is up and running, {@code false} if a timeout occurred waiting for the
     *             server to be in a running state
     *
     * @throws InterruptedException if interrupted while waiting for the server to start
     */
    default boolean waitFor(final long startupTimeout) throws InterruptedException {
        return waitFor(startupTimeout, TimeUnit.SECONDS);
    }

    /**
     * Waits the given amount of time for a server to start.
     * <p>
     * If the {@code process} is not {@code null} and a timeout occurs the process will be
     * {@linkplain Process#destroy() destroyed}.
     * </p>
     *
     * @param startupTimeout the time, to wait for the server start
     * @param unit           the time unit for the {@code startupTimeout} argument
     *
     * @return {@code true} if the server is up and running, {@code false} if a timeout occurred waiting for the
     *             server to be in a running state
     *
     * @throws InterruptedException if interrupted while waiting for the server to start
     */
    boolean waitFor(long startupTimeout, TimeUnit unit) throws InterruptedException;

    /**
     * Shuts down the server.
     *
     * @throws IOException if an error occurs communicating with the server
     */
    void shutdown() throws IOException;

    /**
     * Shuts down the server.
     *
     * @param timeout the graceful shutdown timeout, a value of {@code -1} will wait indefinitely and a value of
     *                    {@code 0} will not attempt a graceful shutdown
     *
     * @throws IOException if an error occurs communicating with the server
     */
    void shutdown(long timeout) throws IOException;

    /**
     * Reloads the server and returns immediately.
     *
     * @throws IOException if an error occurs communicating with the server
     */
    void executeReload() throws IOException;

    /**
     * Reloads the server and returns immediately.
     *
     * @param reloadOp the reload operation to execute
     *
     * @throws IOException if an error occurs communicating with the server
     */
    void executeReload(final ModelNode reloadOp) throws IOException;

    /**
     * Checks if the container status is "reload-required" and if it's the case, executes reload and waits for
     * completion with a 10 second timeout.
     *
     * @throws IOException if an error occurs communicating with the server
     * @see #reloadIfRequired(long, TimeUnit)
     */
    void reloadIfRequired() throws IOException;

    /**
     * Checks if the container status is "reload-required" and if it's the case, executes reload and waits for completion.
     *
     * @param timeout the time to wait for the server to reload
     * @param unit    the time unit for the {@code timeout} argument
     *
     * @throws IOException if an error occurs communicating with the server
     */
    void reloadIfRequired(final long timeout, final TimeUnit unit) throws IOException;

    /**
     * Executes the operation with the {@link #client()} returning the result or throwing an {@link OperationExecutionException}
     * if the operation failed.
     *
     * @param op the operation to execute
     *
     * @return the result of the operation
     *
     * @throws IOException                 if an error occurs communicating with the server
     * @throws OperationExecutionException if the operation failed
     */
    default ModelNode executeOperation(final ModelNode op) throws IOException, OperationExecutionException {
        return executeOperation(Operation.Factory.create(op));
    }

    /**
     * Executes the operation with the {@link #client()} returning the result or throwing an {@link OperationExecutionException}
     * if the operation failed.
     *
     * @param op the operation to execute
     *
     * @return the result of the operation
     *
     * @throws IOException                 if an error occurs communicating with the server
     * @throws OperationExecutionException if the operation failed
     */
    default ModelNode executeOperation(final Operation op) throws IOException, OperationExecutionException {
        @SuppressWarnings("resource")
        final ModelNode result = client().execute(op);
        if (Operations.isSuccessfulOutcome(result)) {
            return Operations.readResult(result);
        }
        throw new OperationExecutionException(op, result);
    }
}
