/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.File;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jboss.as.controller.client.ModelControllerClient;
import org.wildfly.core.launcher.BootableJarCommandBuilder;
import org.wildfly.core.launcher.CommandBuilder;
import org.wildfly.core.launcher.DomainCommandBuilder;
import org.wildfly.core.launcher.Launcher;
import org.wildfly.core.launcher.StandaloneCommandBuilder;

/**
 * The configuration used when starting a server.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 * @since 1.2
 */
@SuppressWarnings({ "unused", "UnusedReturnValue" })
public abstract class Configuration<T extends Configuration<T>> {
    protected enum LaunchType {
        DOMAIN,
        STANDALONE
    }

    private final CommandBuilder commandBuilder;
    private final Map<String, String> env;
    private boolean redirectErrorStream;
    private Redirect outputDestination;
    private Redirect errorDestination;
    private File workingDirectory;
    private ModelControllerClient client;
    private String managementAddress;
    private int managementPort;
    private boolean shutdownOnClose;

    protected Configuration(final CommandBuilder commandBuilder) {
        this.commandBuilder = commandBuilder;
        this.env = new LinkedHashMap<>();
    }

    /**
     * Creates a standalone configuration to launch a standalone server.
     *
     * @param commandBuilder the standalone command builder used to launch the server
     *
     * @return a new standalone configuration
     */
    public static StandaloneConfiguration create(final StandaloneCommandBuilder commandBuilder) {
        return new StandaloneConfiguration(Objects.requireNonNull(commandBuilder, "The command builder cannot be null."));
    }

    /**
     * Creates a standalone configuration to launch a standalone server via the bootable JAR.
     *
     * @param commandBuilder the bootable JAR command builder used to launch the server
     *
     * @return a new standalone configuration
     */
    public static StandaloneConfiguration create(final BootableJarCommandBuilder commandBuilder) {
        return new StandaloneConfiguration(Objects.requireNonNull(commandBuilder, "The command builder cannot be null."));
    }

    /**
     * Creates a domain configuration to launch a domain server
     *
     * @param commandBuilder the domain command builder used to launch the server
     *
     * @return a new domain configuration
     */
    public static DomainConfiguration create(final DomainCommandBuilder commandBuilder) {
        return new DomainConfiguration(Objects.requireNonNull(commandBuilder, "The command builder cannot be null."));
    }

    /**
     * Sets the client to use for the server manager.
     * <p>
     * If the the server manager is {@linkplain ServerManager#close() closed}, the client will also be closed.
     * </p>
     *
     * @param client the client to use to communicate with the server
     *
     * @return this configuration
     */
    public T client(final ModelControllerClient client) {
        this.client = client;
        return self();
    }

    /**
     * The client set on the configuration or a new client.
     *
     * @return the client to use
     */
    protected ModelControllerClient client() {
        if (client == null) {
            try {
                return ModelControllerClient.Factory.create(managementAddress(), managementPort());
            } catch (UnknownHostException e) {
                throw new UncheckedIOException(e);
            }
        }
        return client;
    }

    /**
     * The management address to use for the client if the {@linkplain #client(ModelControllerClient) client} has
     * not been set.
     *
     * @param managementAddress the management address, default is {@code localhost}
     *
     * @return this configuration
     */
    public T managementAddress(final String managementAddress) {
        this.managementAddress = managementAddress;
        return self();
    }

    /**
     * The management address set or {@code 127.0.0.1} if not set.
     *
     * @return the management address
     */
    protected String managementAddress() {
        return managementAddress == null ? "127.0.0.1" : managementAddress;
    }

    /**
     * The management port to use for the client if the {@linkplain #client(ModelControllerClient) client} has
     * not been set.
     *
     * @param managementPort the management port, default is {@code 9990}
     *
     * @return this configuration
     */
    public T managementPort(final int managementPort) {
        this.managementPort = managementPort;
        return self();
    }

    /**
     * The management port or {@code 9990} if set to 0 or less.
     *
     * @return the management port
     */
    protected int managementPort() {
        return managementPort > 0 ? managementPort : 9990;
    }

    /**
     * When set to {@code true} the server will be {@linkplain ServerManager#shutdown() shutdown} when the server
     * manager is {@linkplain ServerManager#close() closed}.
     *
     * @param shutdownOnClose {@code true} to shutdown the server when the server manager is closed
     *
     * @return this configuration
     */
    public T shutdownOnClose(final boolean shutdownOnClose) {
        this.shutdownOnClose = shutdownOnClose;
        return self();
    }

    /**
     * Indicates if the server should be shutdown when the {@link ServerManager} is closed.
     *
     * @return {@code true} to shutdown the server when the {@linkplain ServerManager#close() is closed}
     */
    protected boolean shutdownOnClose() {
        return shutdownOnClose;
    }

    /**
     * Set to {@code true} if the error stream should be redirected to the output stream.
     *
     * @param redirectErrorStream {@code true} to merge the error stream into the output stream, otherwise {@code false}
     *                                to keep the streams separate
     *
     * @return the Configuration
     */
    public T redirectErrorStream(final boolean redirectErrorStream) {
        this.redirectErrorStream = redirectErrorStream;
        return self();
    }

    /**
     * Redirects the output of the process to a file.
     *
     * @param file the file to redirect the output to
     *
     * @return the Configuration
     *
     * @see Redirect#to(java.io.File)
     */
    public T redirectOutput(final File file) {
        outputDestination = Redirect.to(file);
        return self();
    }

    /**
     * Redirects the output of the process to a file.
     *
     * @param path the path to redirect the output to
     *
     * @return the Configuration
     *
     * @see Redirect#to(java.io.File)
     */
    public T redirectOutput(final Path path) {
        return redirectOutput(path.toFile());
    }

    /**
     * Redirects the output of the process to the destination provided.
     *
     * @param destination the output destination
     *
     * @return the Configuration
     *
     * @see java.lang.ProcessBuilder#redirectOutput(Redirect)
     */
    public T redirectOutput(final Redirect destination) {
        outputDestination = destination;
        return self();
    }

    /**
     * Checks if the output stream ({@code stdout}) needs to be consumed.
     *
     * @return {@code true} if the output stream should be consumed, otherwise {@code false}
     */
    protected boolean consumeStdout() {
        return outputDestination == Redirect.PIPE || outputDestination == null;
    }

    /**
     * Redirects the error stream of the process to a file.
     *
     * @param file the file to redirect the error stream to
     *
     * @return the Configuration
     *
     * @see Redirect#to(java.io.File)
     */
    public T redirectError(final File file) {
        errorDestination = Redirect.to(file);
        return self();
    }

    /**
     * Redirects the error stream of the process to the destination provided.
     *
     * @param destination the error stream destination
     *
     * @return the Configuration
     *
     * @see java.lang.ProcessBuilder#redirectError(Redirect)
     */
    public T redirectError(final Redirect destination) {
        errorDestination = destination;
        return self();
    }

    /**
     * Checks if the error stream ({@code stderr}) needs to be consumed.
     *
     * @return {@code true} if the error stream should be consumed, otherwise {@code false}
     */
    protected boolean consumeStderr() {
        return !redirectErrorStream && (errorDestination == Redirect.PIPE || errorDestination == null);
    }

    /**
     * Sets the working directory for the process created.
     *
     * @param path the path to the working directory
     *
     * @return the Configuration
     *
     * @see java.lang.ProcessBuilder#directory(java.io.File)
     */
    public T directory(final Path path) {
        workingDirectory = (path == null ? null : path.toAbsolutePath().normalize().toFile());
        return self();
    }

    /**
     * Sets the working directory for the process created.
     *
     * @param dir the working directory
     *
     * @return the Configuration
     *
     * @see java.lang.ProcessBuilder#directory(java.io.File)
     */
    public T directory(final File dir) {
        workingDirectory = dir;
        return self();
    }

    /**
     * Sets the working directory for the process created.
     *
     * @param dir the working directory
     *
     * @return the Configuration
     *
     * @see java.lang.ProcessBuilder#directory(java.io.File)
     */
    public T directory(final String dir) {
        if (dir == null)
            return self();
        Path path = Path.of(dir);
        if (Files.notExists(path)) {
            throw new IllegalArgumentException(String.format("Directory '%s' does not exist", dir));
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(String.format("Directory '%s' is not a directory.", dir));
        }
        return directory(path.toAbsolutePath().normalize());
    }

    /**
     * Adds an environment variable to the process being created. If the key or value is {@code null}, the environment
     * variable will not be added.
     *
     * @param key   they key for the variable
     * @param value the value for the variable
     *
     * @return the Configuration
     *
     * @see ProcessBuilder#environment()
     */
    public T addEnvironmentVariable(final String key, final String value) {
        if (key != null && value != null) {
            env.put(key, value);
        }
        return self();
    }

    /**
     * Adds the environment variables to the process being created. Note that {@code null} keys or values will not be
     * added.
     *
     * @param env the environment variables to add
     *
     * @return the Configuration
     *
     * @see ProcessBuilder#environment()
     */
    public T addEnvironmentVariables(final Map<String, String> env) {
        env.forEach((key, value) -> {
            if (key != null && value != null) {
                addEnvironmentVariable(key, value);
            }
        });
        return self();
    }

    /**
     * The command builder used to create the launcher.
     *
     * @return the command builder
     */
    protected CommandBuilder commandBuilder() {
        return commandBuilder;
    }

    /**
     * A configured launcher for create the server process.
     *
     * @return the configured launcher
     */
    protected Launcher launcher() {
        return Launcher.of(commandBuilder)
                .addEnvironmentVariables(env)
                .redirectError(errorDestination)
                .redirectOutput(outputDestination)
                .setDirectory(workingDirectory)
                .setRedirectErrorStream(redirectErrorStream);
    }

    /**
     * The type of the server to launch.
     *
     * @return the type of the server
     */
    protected abstract LaunchType launchType();

    /**
     * This instance.
     *
     * @return this instance
     */
    protected abstract T self();

    Map<String, String> env() {
        return env;
    }

    boolean redirectErrorStream() {
        return redirectErrorStream;
    }

    Redirect outputDestination() {
        return outputDestination;
    }

    Redirect errorDestination() {
        return errorDestination;
    }

    File workingDirectory() {
        return workingDirectory;
    }

    protected final Configuration<T> immutable() {
        return new ImmutableConfiguration<>(this);
    }
}
