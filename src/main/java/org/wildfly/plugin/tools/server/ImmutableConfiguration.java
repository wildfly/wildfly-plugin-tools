/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.server;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jboss.as.controller.client.ModelControllerClient;
import org.wildfly.core.launcher.CommandBuilder;
import org.wildfly.core.launcher.Launcher;

/**
 * An immutable configuration.
 *
 * @author <a href="mailto:jperkins@ibm.com">James R. Perkins</a>
 */
class ImmutableConfiguration<T extends Configuration<T>> extends Configuration<T> {
    private final Map<String, String> env;
    private final boolean redirectErrorStream;
    private final ProcessBuilder.Redirect outputDestination;
    private final ProcessBuilder.Redirect errorDestination;
    private final File workingDirectory;
    private final ModelControllerClient client;
    private final String managementAddress;
    private final int managementPort;
    private final boolean shutdownOnClose;
    private final LaunchType launchType;

    protected ImmutableConfiguration(final Configuration<T> configuration) {
        super(new ImmutableCommandBuilder(configuration.commandBuilder()));
        this.env = Map.copyOf(configuration.env());
        this.redirectErrorStream = configuration.redirectErrorStream();
        this.outputDestination = configuration.outputDestination();
        this.errorDestination = configuration.errorDestination();
        this.workingDirectory = configuration.workingDirectory();
        this.client = configuration.client();
        this.managementAddress = configuration.managementAddress();
        this.managementPort = configuration.managementPort();
        this.shutdownOnClose = configuration.shutdownOnClose();
        this.launchType = configuration.launchType();
    }

    @Override
    public T client(final ModelControllerClient client) {
        return self();
    }

    @Override
    protected ModelControllerClient client() {
        return client;
    }

    @Override
    public T managementAddress(final String managementAddress) {
        return self();
    }

    @Override
    protected String managementAddress() {
        return managementAddress;
    }

    @Override
    public T managementPort(final int managementPort) {
        return self();
    }

    @Override
    protected int managementPort() {
        return managementPort;
    }

    @Override
    public T shutdownOnClose(final boolean shutdownOnClose) {
        return self();
    }

    @Override
    protected boolean shutdownOnClose() {
        return shutdownOnClose;
    }

    @Override
    public T redirectErrorStream(final boolean redirectErrorStream) {
        return self();
    }

    @Override
    public T redirectOutput(final File file) {
        return self();
    }

    @Override
    public T redirectOutput(final Path path) {
        return self();
    }

    @Override
    public T redirectOutput(final ProcessBuilder.Redirect destination) {
        return self();
    }

    @Override
    protected boolean consumeStdout() {
        return outputDestination == ProcessBuilder.Redirect.PIPE || outputDestination == null;
    }

    @Override
    public T redirectError(final File file) {
        return self();
    }

    @Override
    public T redirectError(final ProcessBuilder.Redirect destination) {
        return self();
    }

    @Override
    protected boolean consumeStderr() {
        return !redirectErrorStream && (errorDestination == ProcessBuilder.Redirect.PIPE || errorDestination == null);
    }

    @Override
    public T directory(final Path path) {
        return self();
    }

    @Override
    public T directory(final File dir) {
        return self();
    }

    @Override
    public T directory(final String dir) {
        return self();
    }

    @Override
    public T addEnvironmentVariable(final String key, final String value) {
        return self();
    }

    @Override
    public T addEnvironmentVariables(final Map<String, String> env) {
        return self();
    }

    @Override
    protected Launcher launcher() {
        return Launcher.of(commandBuilder())
                .addEnvironmentVariables(env)
                .redirectError(errorDestination)
                .redirectOutput(outputDestination)
                .setDirectory(workingDirectory)
                .setRedirectErrorStream(redirectErrorStream);
    }

    @Override
    protected LaunchType launchType() {
        return launchType;
    }

    @Override
    protected T self() {
        throw new UnsupportedOperationException("Immutable configuration, cannot set any data");
    }

    private static class ImmutableCommandBuilder implements CommandBuilder {
        private final List<String> buildArguments;
        private final List<String> build;

        ImmutableCommandBuilder(final CommandBuilder commandBuilder) {
            if (commandBuilder == null) {
                buildArguments = List.of();
                build = List.of();
            } else {
                buildArguments = List.copyOf(commandBuilder.buildArguments());
                build = List.copyOf(commandBuilder.build());
            }
        }

        @Override
        public List<String> build() {
            return build;
        }

        @Override
        public List<String> buildArguments() {
            return buildArguments;
        }
    }
}
