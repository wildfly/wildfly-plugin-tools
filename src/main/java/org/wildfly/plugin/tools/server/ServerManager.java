/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.Operation;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.wildfly.core.launcher.DomainCommandBuilder;
import org.wildfly.core.launcher.StandaloneCommandBuilder;
import org.wildfly.plugin.tools.ConsoleConsumer;
import org.wildfly.plugin.tools.ContainerDescription;
import org.wildfly.plugin.tools.DeploymentManager;
import org.wildfly.plugin.tools.OperationExecutionException;

/**
 * A simple manager for various interactions with a potentially running server.
 * <p>
 * When the server manager is closed, the underlying client is also closed.
 * </p>
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("unused")
public interface ServerManager extends AutoCloseable {
    int UNKNOWN_EXIT_STATUS = -1;

    /**
     * A 60-second default timeout which can be overridden with the {@code org.wildfly.plugin.tools.server.timeout}
     * system property.
     */
    long TIMEOUT = Long.parseLong(System.getProperty("org.wildfly.plugin.tools.server.timeout", "60"));

    /**
     * A builder used to build a {@link ServerManager}.
     */
    class Builder {
        private final Configuration<?> configuration;
        private Process process;
        private ProcessHandle processHandle;

        public Builder() {
            this(new StandaloneConfiguration(null));
        }

        protected Builder(final Configuration<?> configuration) {
            this.configuration = configuration;
        }

        /**
         * Sets the client to use for the server manager.
         * <p>
         * If the this server manager is {@linkplain #close() closed}, the client will also be closed.
         * </p>
         *
         * @param client the client to use to communicate with the server
         *
         * @return this builder
         */
        public Builder client(final ModelControllerClient client) {
            configuration.client(client);
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
            this.processHandle = process;
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
            this.process = process;
            this.processHandle = process == null ? null : process.toHandle();
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
            configuration.managementAddress(managementAddress);
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
            configuration.managementPort(managementPort);
            return this;
        }

        /**
         * When set to {@code true} the server will be {@linkplain ServerManager#shutdown() shutdown} when the server
         * manager is {@linkplain ServerManager#close() closed}.
         *
         * @param shutdownOnClose {@code true} to shutdown the server when the server manager is closed
         *
         * @return this builder
         *
         * @since 1.2
         */
        public Builder shutdownOnClose(final boolean shutdownOnClose) {
            configuration.shutdownOnClose(shutdownOnClose);
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
            return new StandaloneManager(process, processHandle, configuration.client(), configuration.shutdownOnClose());
        }

        /**
         * Creates a {@link DomainManager} based on the builders settings. If the
         * {@link #client(ModelControllerClient) client} was not set, the {@link #managementAddress(String) managementAddress}
         * and the {@link #managementPort(int) managementPort} will be used to create the client.
         *
         * @return a new {@link DomainManager}
         */
        public DomainManager domain() {
            return new DomainManager(process, processHandle, getOrCreateDomainClient(), configuration.shutdownOnClose());
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
            @SuppressWarnings("resource")
            final ModelControllerClient client = configuration.client();
            final Process process = this.process;
            final ProcessHandle processHandle = this.processHandle;
            return CompletableFuture.supplyAsync(() -> {
                // Wait until the server is running, then determine what type we need to return
                while (!isRunning(client)) {
                    Thread.onSpinWait();
                    if (process != null && !process.isAlive()) {
                        throw new ServerManagerException(
                                "The server process has died. See previous output from the process. Process id "
                                        + process.pid());
                    }
                }
                final String launchType = launchType(client).orElseThrow(() -> new ServerManagerException(
                        "Could not determine the type of the server. Verify the server is running."));
                if ("STANDALONE".equals(launchType)) {
                    return new StandaloneManager(process, processHandle, client, configuration.shutdownOnClose());
                } else if ("DOMAIN".equals(launchType)) {
                    return new DomainManager(process, processHandle, getOrCreateDomainClient(),
                            configuration.shutdownOnClose());
                }
                throw new ServerManagerException("Only standalone and domain servers are support. %s is not supported.",
                        launchType);
            });
        }

        private DomainClient getOrCreateDomainClient() {
            final ModelControllerClient client = configuration.client();
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
     * Starts a standalone server based on the {@link StandaloneCommandBuilder command builder}.
     *
     * <pre>
     * final ServerManager serverManager = ServerManager.start(StandaloneCommandBuilder.of(jbossHome));
     * if (!serverManager.waitFor(10L, TimeUnit.SECONDS)) {
     *     serverManager.kill();
     *     throw new RuntimeException(&quot;Failed to start server&quot;);
     * }
     * </pre>
     *
     * @param configuration the configuration used for starting and managing the server
     *
     * @return the server manager
     *
     * @throws ServerManagerException if an error occurs starting the server
     *
     * @since 1.2
     */
    static StandaloneManager start(final StandaloneConfiguration configuration) throws ServerManagerException {
        Process process = null;
        try {
            process = configuration.launcher().launch();
            if (configuration.consumeStderr()) {
                ConsoleConsumer.start(process.getErrorStream(), System.err);
            }
            if (configuration.consumeStdout()) {
                ConsoleConsumer.start(process.getInputStream(), System.out);
            }
            return new Builder(configuration).process(process).standalone();
        } catch (Throwable t) {
            if (process != null) {
                process.destroyForcibly();
            }
            throw ServerManagerException.startException(configuration, t);
        }
    }

    /**
     * Starts a domain server based on the {@link DomainCommandBuilder command builder} and waits until the server is
     * started.
     *
     * <pre>
     * final ServerManager serverManager = ServerManager.start(DomainCommandBuilder.of(jbossHome));
     * if (!serverManager.waitFor(10L, TimeUnit.SECONDS)) {
     *     serverManager.kill();
     *     throw new RuntimeException(&quot;Failed to start server&quot;);
     * }
     * </pre>
     *
     * @param configuration the configuration used for starting and managing the server
     *
     * @return the server manager
     *
     * @throws ServerManagerException if an error occurs starting the server
     * @since 1.2
     */
    static DomainManager start(final DomainConfiguration configuration)
            throws ServerManagerException {
        Process process = null;
        try {
            process = configuration.launcher().launch();
            if (configuration.consumeStderr()) {
                ConsoleConsumer.start(process.getErrorStream(), System.err);
            }
            if (configuration.consumeStdout()) {
                ConsoleConsumer.start(process.getInputStream(), System.out);
            }
            return new Builder(configuration).process(process).domain();
        } catch (Throwable t) {
            if (process != null) {
                process.destroyForcibly();
            }
            throw ServerManagerException.startException(configuration, t);
        }
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
     * If a process is available and {@linkplain ProcessHandle#isAlive() alive}, the process is attempted to be
     * {@linkplain ProcessHandle#destroyForcibly() killed}. Note in cases where the process is not associated with this
     * server manager, this method does nothing.
     * <p>
     * The returned {@link ServerManager} is the same instance of this server manager. You can use the
     * {@link CompletableFuture#get()} to wait until the process, if available, to exit.
     * </p>
     *
     * @return a completable future that on a {@link CompletableFuture#get()} will wait for the process, if available, exits
     *
     * @since 1.2
     */
    default CompletableFuture<ServerManager> kill() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

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
     * Shuts down the server without a graceful shutdown timeout and wait for the server to be shutdown. This is a
     * shortcut for @link #shutdown(long) shutdown(0)}.
     *
     * @throws IOException if an error occurs communicating with the server
     * @see #shutdown(long)
     */
    default void shutdown() throws IOException {
        shutdown(0);
    }

    /**
     * Shuts down the server and wait for the servers to be shutdown.
     *
     * @param timeout the graceful shutdown timeout, a value of {@code -1} will wait indefinitely and a value of
     *                    {@code 0} will not attempt a graceful shutdown
     *
     * @throws IOException if an error occurs communicating with the server
     */
    void shutdown(long timeout) throws IOException;

    /**
     * Shuts down the server without a graceful shutdown timeout. This is a shortcut for
     * {@link #shutdownAsync(long) shutdown(0)}.
     * <p>
     * The returned {@link ServerManager} is the same instance of this server manager. You can use the
     * {@link CompletableFuture#get()} to wait until the process, if available, to exit.
     * </p>
     *
     * @return a completable future that on a {@link CompletableFuture#get()} will wait for the process, if available, exits
     *
     * @see #shutdownAsync(long)
     *
     * @since 1.2
     */
    default CompletableFuture<ServerManager> shutdownAsync() {
        return shutdownAsync(0);
    }

    /**
     * Shuts down the server.
     * <p>
     * The returned {@link ServerManager} is the same instance of this server manager. You can use the
     * {@link CompletableFuture#get()} to wait until the process, if available, to exit.
     * </p>
     * <p>
     * <em>Note for implementations. The default method should likely not be used. Care must be taken to ensure a
     * {@link java.util.concurrent.TimeoutException} on a {@link CompletableFuture#get()} stops the shutdown from
     * continuing to run in the background.
     * </em>
     * </p>
     *
     * @param timeout the graceful shutdown timeout, a value of {@code -1} will wait indefinitely and a value of
     *                    {@code 0} will not attempt a graceful shutdown
     *
     * @return a completable future that on a {@link CompletableFuture#get()} will wait for the process, if available, exits
     *
     * @since 1.2
     */
    default CompletableFuture<ServerManager> shutdownAsync(long timeout) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                shutdown(timeout);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
            return ServerManager.this;
        });
    }

    /**
     * Waits for the server to be shutdown and the process, if defined, to terminate. This returns the exit status of
     * the process if it was defined. Otherwise, {@link #UNKNOWN_EXIT_STATUS -1} will be returned.
     * <p>
     * Note this is a blocking action and will block the current thread until the server has been exited or this server
     * manager has been {@linkplain #close() closed}. If this server manager has been closed, the thread waiting for
     * terminate will be interrupted.
     * </p>
     *
     * @return the exit status of the process, if defined. If not defined a value of {@code -1} will be returned
     *
     * @throws InterruptedException if the current thread is interrupted while waiting for the server to exit
     * @since 1.2
     */
    default int waitForTermination() throws InterruptedException {
        while (!isClosed() && isRunning()) {
            Thread.onSpinWait();
        }
        return UNKNOWN_EXIT_STATUS;
    }

    /**
     * Returns the exit status of the process if it's defined. If the {@link Builder#process(Process)} was used and the
     * process has not yet terminated, an {@link IllegalStateException} will be thrown.
     * <p>
     * If no process was set or the process is defined as a {@link ProcessHandle}, then {@link #UNKNOWN_EXIT_STATUS -1}
     * will be returned.
     * </p>
     *
     * @return the exit status of the process, if defined. If not defined a value of {@code -1} will be returned
     *
     * @throws IllegalStateException if the process has not yet terminated
     * @since 1.2
     */
    default int exitValue() throws IllegalStateException {
        return UNKNOWN_EXIT_STATUS;
    }

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
     * completion with a 60-second timeout. The timeout can be globally changed with the
     * {@code org.wildfly.plugin.tools.server.timeout} system property.
     *
     * @throws IOException if an error occurs communicating with the server
     * @see #reloadIfRequired(long, TimeUnit)
     */
    default void reloadIfRequired() throws IOException {
        reloadIfRequired(TIMEOUT, TimeUnit.SECONDS);
    }

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
     *
     * @since 1.2
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
     *
     * @since 1.2
     */
    default ModelNode executeOperation(final Operation op) throws IOException, OperationExecutionException {
        @SuppressWarnings("resource")
        final ModelNode result = client().execute(op);
        if (Operations.isSuccessfulOutcome(result)) {
            return Operations.readResult(result);
        }
        throw new OperationExecutionException(op, result);
    }

    /**
     * Checks if this server manager has been closed. The server manager may be closed if the underlying client was
     * closed.
     *
     * @return {@code true} if the server manager was closed, otherwise {@code false}
     *
     * @since 1.2
     */
    default boolean isClosed() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.2
     */
    @Override
    default void close() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Returns an instance of this server manager which does not allow the shutting down the server. A {@code shutdown}
     * operation is not allowed and throws an {@link UnsupportedOperationException}. The {@link #shutdown()},
     * {@link #shutdown(long)} and {@link #kill()} operations also throw an {@link UnsupportedOperationException}.
     *
     * <p>
     * The use-case for this is for cases when you do not want to allow a caller to be able to shutdown a server that
     * has been started.
     * </p>
     *
     * @return a managed server manager
     *
     * @since 1.2
     */
    default ServerManager asManaged() {
        return new ManagedServerManager(this);
    }
}
