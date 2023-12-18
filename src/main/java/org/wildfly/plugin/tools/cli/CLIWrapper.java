/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.cli;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.logging.Logger;
import org.wildfly.plugin.tools.bootablejar.BootLoggingConfiguration;

/**
 * A CLI executor, resolving CLI classes from the provided Classloader. We can't
 * have cli/embedded/jboss modules in plugin classpath, it causes issue because
 * we are sharing the same jboss module classes between execution run inside the
 * same JVM.
 * <p>>
 * CLI dependencies are retrieved from provisioned server artifacts list and
 * resolved using maven. In addition jboss-modules.jar located in the
 * provisioned server is added.
 * </p>
 *
 * @author jdenise
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("unused")
public class CLIWrapper implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(CLIWrapper.class);

    private final Object ctx;
    private final Method handle;
    private final Method handleSafe;
    private final Method terminateSession;
    private final Method getModelControllerClient;
    private final Method bindClient;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final String origConfig;
    private final Path jbossHome;
    private final BootLoggingConfiguration bootLoggingConfiguration;

    /**
     * Creates a new CLIWrapper with a {@code null} {@link BootLoggingConfiguration}.
     * <p>
     * Note, when this constructor is used, the {@link #generateBootLoggingConfig()} cannot be invoked.
     * </p>
     *
     * @param jbossHome         the servers home directory
     * @param resolveExpression {@code true} if parameters in commands should be resolved before sending the command to
     *                          the server
     * @param loader            the class loader to use for loading the CLI context
     *
     * @throws RuntimeException if an error occurs creating the CLI context
     */
    public CLIWrapper(final Path jbossHome, final boolean resolveExpression, final ClassLoader loader) {
        this(jbossHome, resolveExpression, loader, null);
    }

    /**
     * Creates a new CLIWrapper with a {@code null} {@link BootLoggingConfiguration}.
     * <p>
     * Note, if the {@code bootLoggingConfiguration} is {@code null}, the {@link #generateBootLoggingConfig()} cannot be
     * invoked.
     * </p>
     *
     * @param jbossHome                the servers home directory
     * @param resolveExpression        {@code true} if parameters in commands should be resolved before sending the command to
     *                                 the server
     * @param loader                   the class loader to use for loading the CLI context
     * @param bootLoggingConfiguration the boot logging configuration generator, or {@code null} to not allow boot
     *                                 logging configuration
     *
     * @throws RuntimeException if an error occurs creating the CLI context
     */
    public CLIWrapper(final Path jbossHome, final boolean resolveExpression, final ClassLoader loader,
                      final BootLoggingConfiguration bootLoggingConfiguration) {
        if (jbossHome != null) {
            Path config = jbossHome.resolve("bin").resolve("jboss-cli.xml");
            origConfig = System.getProperty("jboss.cli.config");
            if (Files.exists(config)) {
                System.setProperty("jboss.cli.config", config.toString());
            }
        } else {
            origConfig = null;
        }
        this.jbossHome = jbossHome;
        try {
            // Find the default constructor
            final Constructor<?> constructor = loader.loadClass("org.jboss.as.cli.impl.CommandContextConfiguration$Builder")
                    .getConstructor();
            final Object builder = constructor.newInstance();
            final Method setEchoCommand = builder.getClass().getMethod("setEchoCommand", boolean.class);
            setEchoCommand.invoke(builder, true);
            final Method setResolve = builder.getClass().getMethod("setResolveParameterValues", boolean.class);
            setResolve.invoke(builder, resolveExpression);
            final Method setOutput = builder.getClass().getMethod("setConsoleOutput", OutputStream.class);
            setOutput.invoke(builder, out);

            final Object ctxConfig = builder.getClass().getMethod("build").invoke(builder);
            final Object factory = loader.loadClass("org.jboss.as.cli.CommandContextFactory")
                    .getMethod("getInstance")
                    .invoke(null);

            final Class<?> configClass = loader.loadClass("org.jboss.as.cli.impl.CommandContextConfiguration");
            ctx = factory.getClass().getMethod("newCommandContext", configClass).invoke(factory, ctxConfig);
            handle = ctx.getClass().getMethod("handle", String.class);
            handleSafe = ctx.getClass().getMethod("handleSafe", String.class);
            terminateSession = ctx.getClass().getMethod("terminateSession");
            getModelControllerClient = ctx.getClass().getMethod("getModelControllerClient");
            bindClient = ctx.getClass().getMethod("bindClient", ModelControllerClient.class);
        } catch (ClassNotFoundException | InvocationTargetException | NoSuchMethodException | InstantiationException |
                 IllegalAccessException e) {
            throw new RuntimeException("Failed to create the CLIWrapper.", e);
        }
        this.bootLoggingConfiguration = bootLoggingConfiguration;
    }

    /**
     * Handles invoking the command.
     *
     * @param command the command to invoke
     *
     * @throws Exception if an error occurs handling the command
     */
    public void handle(final String command) throws Exception {
        try {
            handle.invoke(ctx, command);
        } catch (IllegalAccessException | InvocationTargetException e) {
            // Attempt to unwrap
            final Throwable cause = e.getCause();
            if (cause != null) {
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw new RuntimeException("Failed to handle command.", cause);
            }
        }
    }

    /**
     * Safely handle invoking the command.
     *
     * @param command the command to invoke
     */
    public void handleSafe(final String command) {
        try {
            handleSafe.invoke(ctx, command);
        } catch (IllegalAccessException | InvocationTargetException e) {
            LOGGER.error("Failed to invoke command.", e);
        }
    }

    /**
     * Binds the client to the CLI context.
     *
     * @param client the client to bind
     *
     * @throws Exception if an error occurs binding the client to the context
     */
    public void bindClient(final ModelControllerClient client) throws Exception {
        try {
            bindClient.invoke(ctx, client);
        } catch (IllegalAccessException | InvocationTargetException e) {
            // Attempt to unwrap
            final Throwable cause = e.getCause();
            if (cause != null) {
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw new RuntimeException("Failed to bind the client to the CLI context.", cause);
            }
        }
    }

    /**
     * Returns the output from the CLI context.
     *
     * @return the CLI output
     */
    public String getOutput() {
        return out.toString();
    }

    @Override
    public void close() throws Exception {
        try {
            terminateSession.invoke(ctx);
        } finally {
            if (origConfig != null) {
                System.setProperty("jboss.cli.config", origConfig);
            } else {
                System.clearProperty("jboss.cli.config");
            }
        }
    }

    /**
     * Generate boot logging. The {@link #CLIWrapper(Path, boolean, ClassLoader, BootLoggingConfiguration)} constructor
     * must have been used with a non-null {@link BootLoggingConfiguration}.
     *
     * @throws RuntimeException if an error occurs generated the boot logging configuration
     */
    public void generateBootLoggingConfig() {
        Objects.requireNonNull(bootLoggingConfiguration);
        Exception toThrow = null;
        try {
            // Start the embedded server
            handle("embed-server --jboss-home=" + jbossHome + " --std-out=discard");
            // Get the client used to execute the management operations
            final ModelControllerClient client = getModelControllerClient();
            // Update the bootable logging config
            final Path configDir = jbossHome.resolve("standalone").resolve("configuration");
            bootLoggingConfiguration.generate(configDir, client);
        } catch (Exception e) {
            toThrow = e;
        } finally {
            try {
                // Always stop the embedded server
                handle("stop-embedded-server");
            } catch (Exception e) {
                if (toThrow != null) {
                    e.addSuppressed(toThrow);
                }
                toThrow = e;
            }
        }
        // Check if an error has been thrown and throw it.
        if (toThrow != null) {
            throw new RuntimeException("Failed to generate the boot logging configuration.", toThrow);
        }
    }

    private ModelControllerClient getModelControllerClient() throws Exception {
        return (ModelControllerClient) getModelControllerClient.invoke(ctx);
    }
}
