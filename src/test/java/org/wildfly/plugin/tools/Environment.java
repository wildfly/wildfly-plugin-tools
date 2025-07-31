/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assertions;
import org.wildfly.core.launcher.DomainCommandBuilder;
import org.wildfly.core.launcher.StandaloneCommandBuilder;
import org.wildfly.plugin.tools.server.Configuration;
import org.wildfly.plugin.tools.server.DomainManager;
import org.wildfly.plugin.tools.server.ServerManager;
import org.wildfly.plugin.tools.server.StandaloneManager;
import org.wildfly.plugin.tools.util.Utils;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings({ "WeakerAccess", "Duplicates" })
public class Environment {

    /**
     * The default WildFly home directory specified by the {@code jboss.home} system property.
     */
    public static final Path WILDFLY_HOME;
    /**
     * The host name specified by the {@code wildfly.management.hostname} system property or {@code localhost} by
     * default.
     */
    public static final String HOSTNAME = System.getProperty("wildfly.management.hostname", "localhost");
    /**
     * The port specified by the {@code wildfly.management.port} system property or {@code 9990} by default.
     */
    public static final int PORT;

    /**
     * The default server startup timeout specified by {@code wildfly.timeout}, default is 60 seconds.
     */
    public static final long TIMEOUT;

    private static final String TMP_DIR = System.getProperty("java.io.tmpdir", "target");
    private static final int LOG_SERVER_PORT = getProperty("ts.log.server.port", 10514);
    private static final Collection<String> JVM_ARGS;

    static {
        final Logger logger = Logger.getLogger(Environment.class);

        // Get the WildFly home directory and copy to the temp directory
        final String wildflyDist = System.getProperty("jboss.home");
        assert wildflyDist != null : "WildFly home property, jboss.home, was not set";
        Path wildflyHome = Paths.get(wildflyDist);
        validateWildFlyHome(wildflyHome);
        WILDFLY_HOME = wildflyHome;

        final String port = System.getProperty("wildfly.management.port", "9990");
        try {
            PORT = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            logger.debugf(e, "Invalid port: %s", port);
            throw new RuntimeException("Invalid port: " + port, e);
        }

        final String timeout = System.getProperty("wildfly.timeout", "60");
        try {
            TIMEOUT = Long.parseLong(timeout);
        } catch (NumberFormatException e) {
            logger.debugf(e, "Invalid timeout: %s", timeout);
            throw new RuntimeException("Invalid timeout: " + timeout, e);
        }
        final String jvmArgs = System.getProperty("test.jvm.args");
        if (jvmArgs == null) {
            JVM_ARGS = Collections.emptyList();
        } else {
            JVM_ARGS = Utils.splitArguments(jvmArgs);
        }
    }

    public static ModelControllerClient createClient() throws UnknownHostException {
        return ModelControllerClient.Factory.create(HOSTNAME, PORT);
    }

    /**
     * Creates a {@link ServerManager.Builder} for testing with a default management address and port.
     *
     * @param process the process to bind to the server manager
     *
     * @return a server manager builder
     */
    public static ServerManager.Builder createServerManagerBuilder(final Process process) {
        return ServerManager.builder()
                .managementAddress(HOSTNAME)
                .managementPort(PORT)
                .process(process);
    }

    private static void validateWildFlyHome(final Path wildflyHome) {
        if (!ServerManager.isValidHomeDirectory(wildflyHome)) {
            throw new RuntimeException("Invalid WildFly home directory: " + wildflyHome);
        }
    }

    /**
     * Creates a temporary path based on the {@code java.io.tmpdir} system
     * property.
     *
     * @param paths the additional portions of the path
     *
     * @return the path
     */
    public static Path createTempPath(final String... paths) {
        return Paths.get(TMP_DIR, paths);
    }

    /**
     * Gets the log server port
     * <p>
     * The default is 10514 and can be overridden via the
     * {@code ts.log.server.port} system property.
     * </p>
     *
     * @return the log server port
     */
    public static int getLogServerPort() {
        return LOG_SERVER_PORT;
    }

    /**
     * Returns a collection of the JVM arguments to set for any server started during the test process.
     *
     * @return the JVM arguments
     */
    public static Collection<String> getJvmArgs() {
        return JVM_ARGS;
    }

    public static StandaloneManager launchStandalone() {
        return launchStandalone(true);
    }

    public static StandaloneManager launchStandalone(final boolean shutdownOnClose) {
        final StandaloneManager serverManager = ServerManager
                .start(Configuration.create(StandaloneCommandBuilder.of(Environment.WILDFLY_HOME))
                        .shutdownOnClose(shutdownOnClose)
                        .managementAddress(HOSTNAME)
                        .managementPort(PORT));
        try {
            if (!serverManager.waitFor(TIMEOUT)) {
                serverManager.kill();
                Assertions.fail(String.format("Failed to start standalone server withing %d seconds", TIMEOUT));
            }
        } catch (Throwable e) {
            failServerStart(serverManager, e);
        }
        return serverManager;
    }

    public static DomainManager launchDomain() {
        return launchDomain(true);
    }

    public static DomainManager launchDomain(final boolean shutdownOnClose) {
        final DomainManager serverManager = ServerManager
                .start(Configuration.create(DomainCommandBuilder.of(Environment.WILDFLY_HOME))
                        .shutdownOnClose(shutdownOnClose)
                        .managementAddress(HOSTNAME)
                        .managementPort(PORT));
        try {
            if (!serverManager.waitFor(TIMEOUT)) {
                serverManager.kill();
                Assertions.fail(String.format("Failed to start standalone server withing %d seconds", TIMEOUT));
            }
        } catch (Throwable e) {
            failServerStart(serverManager, e);
        }
        return serverManager;
    }

    private static int getProperty(final String name, final int dft) {
        final String value = System.getProperty(name);
        return value == null ? dft : Integer.parseInt(value);
    }

    private static void failServerStart(final ServerManager serverManager, final Throwable cause) {
        serverManager.kill();
        try (StringWriter msg = new StringWriter()) {
            msg.write("Failed to start server.");
            msg.write(System.lineSeparator());
            cause.printStackTrace(new PrintWriter(msg));
            Assertions.fail(msg.toString());
        } catch (IOException e) {
            // Should never happen
            throw new UncheckedIOException(e);
        }
    }
}
