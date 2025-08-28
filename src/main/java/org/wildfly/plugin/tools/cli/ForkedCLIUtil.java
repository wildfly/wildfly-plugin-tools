/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.cli;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jboss.logging.Logger;

/**
 * A utility for forking a CLI process.
 *
 * @author jdenise
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class ForkedCLIUtil {

    private static final Logger LOGGER = Logger.getLogger(ForkedCLIUtil.class);
    private static final Path JAVA_HOME;
    private static final String JAVA_CMD;

    static {
        final String javaHome = System.getProperty("java.home");
        JAVA_HOME = Path.of(javaHome);
        final Path cmd = JAVA_HOME.resolve("bin").resolve("java");
        if (Files.notExists(cmd)) {
            JAVA_CMD = "java";
        } else {
            JAVA_CMD = cmd.toAbsolutePath().toString();
        }
    }

    /**
     * Forks a CLI process.
     *
     * @param artifacts the artifacts to add to the class path
     * @param clazz     the class to invoke
     * @param home      the home directory, this is always the first argument
     * @param output    the path to the output file for the process
     * @param args      any additional arguments to send add to the call
     *
     * @throws IOException if an error occurs create the process
     */
    public static void fork(final String[] artifacts, final Class<?> clazz, final Path home,
            final Path output, final String... args) throws IOException {
        fork(List.of(artifacts), clazz, home, output, args);
    }

    /**
     * Forks a CLI process.
     *
     * @param artifacts the artifacts to add to the class path
     * @param clazz     the class to invoke
     * @param home      the home directory, this is always the first argument
     * @param output    the path to the output file for the process
     * @param args      any additional arguments to send add to the call
     *
     * @throws IOException if an error occurs create the process
     */
    public static void fork(final Collection<String> artifacts, final Class<?> clazz, final Path home,
            final Path output, final String... args) throws IOException {
        fork(artifacts, clazz, home, null, output, args);
    }

    /**
     * Forks a CLI process.
     *
     * @param artifacts      the artifacts to add to the class path
     * @param clazz          the class to invoke
     * @param home           the home directory, this is always the first argument
     * @param stabilityLevel the stability level used when starting the embedded server.
     * @param output         the path to the output file for the process
     * @param args           any additional arguments to send add to the call
     *
     * @throws IOException if an error occurs create the process
     */
    public static void fork(final Collection<String> artifacts, final Class<?> clazz, final Path home,
            final String stabilityLevel, final Path output, final String... args) throws IOException {
        // prepare the classpath
        final StringBuilder cp = new StringBuilder();
        for (String loc : artifacts) {
            cp.append(loc).append(File.pathSeparator);
        }
        final StringBuilder contextCP = new StringBuilder();
        collectCpUrls(Thread.currentThread().getContextClassLoader(), contextCP);
        // This happens when running tests, use the process classpath to retrieve the CLIForkedExecutor main class
        if (contextCP.length() == 0) {
            LOGGER.debug("Re-using process classpath to retrieve Maven plugin classes to fork CLI process.");
            cp.append(System.getProperty("java.class.path"));
        } else {
            cp.append(contextCP);
        }
        final Path properties = storeSystemProps();

        // Create temp file with classpath.
        final Path cpFile = Files.createTempFile("classpath-", ".txt");
        Files.writeString(cpFile, cp.toString(), StandardCharsets.UTF_8);
        LOGGER.debugf("Classpath '%s' written to argument file %s (%d chars)", cp.toString(), cpFile, cp.length());

        // Create the command
        final List<String> argsList = new ArrayList<>();
        argsList.add(JAVA_CMD);
        argsList.add("-server");
        argsList.add("-cp");
        // Use a Java argument file (@file notation) to avoid Windows command line length limits.
        argsList.add("@" + cpFile);
        argsList.add(clazz.getName());
        argsList.add(home.toString());
        argsList.add(output.toString());
        argsList.add(properties.toString());
        if (stabilityLevel != null) {
            argsList.add(stabilityLevel);
        }
        argsList.addAll(List.of(args));
        LOGGER.debugf("CLI process command line %s", argsList);
        try {
            final Process p = new ProcessBuilder(argsList).redirectErrorStream(true).start();
            final StringBuilder traces = new StringBuilder();
            try (
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null) {
                    traces.append(line).append(System.lineSeparator());
                    line = reader.readLine();
                }
                if (p.isAlive()) {
                    try {
                        p.waitFor();
                    } catch (InterruptedException e) {
                        LOGGER.errorf(e, "Interrupted while waiting for forked process %d to terminate.", p.pid());
                    }
                }
            }
            int exitCode = p.exitValue();
            if (exitCode != 0) {
                LOGGER.errorf("Error executing CLI: %s", traces);
                throw new RuntimeException("CLI execution failed:" + traces);
            }
        } finally {
            Files.deleteIfExists(properties);
            Files.deleteIfExists(cpFile);
        }
    }

    private static Path storeSystemProps() throws IOException {
        final Path props;
        props = Files.createTempFile("wfbootablejar", "sysprops");
        try (BufferedWriter writer = Files.newBufferedWriter(props)) {
            System.getProperties().store(writer, "");
        }
        return props;
    }

    private static void collectCpUrls(final ClassLoader cl, final StringBuilder buf) {
        final ClassLoader parentCl = cl.getParent();
        if (parentCl != null) {
            collectCpUrls(cl.getParent(), buf);
        }
        if (cl instanceof URLClassLoader) {
            for (URL url : ((URLClassLoader) cl).getURLs()) {
                final String file;
                try {
                    file = new File(url.toURI()).getAbsolutePath();
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
                if (file.startsWith(JAVA_HOME.toString())) {
                    continue;
                }
                if (buf.length() > 0) {
                    buf.append(File.pathSeparatorChar);
                }
                buf.append(file);
            }
        }
    }
}
