/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.bootablejar;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.api.GalleonBuilder;
import org.jboss.galleon.api.GalleonFeaturePackRuntime;
import org.jboss.galleon.api.GalleonProvisioningRuntime;
import org.jboss.galleon.api.Provisioning;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.ZipUtils;
import org.wildfly.common.Assert;
import org.wildfly.plugin.tools.cli.CLIForkedBootConfigGenerator;
import org.wildfly.plugin.tools.cli.ForkedCLIUtil;
import org.wildfly.plugin.tools.util.Assertions;

/**
 * Various utilities for packing a bootable JAR.
 *
 * @author jdenise
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("unused")
public class BootableJarSupport {

    public static final String BOOTABLE_SUFFIX = "bootable";

    public static final String JBOSS_MODULES_GROUP_ID = "org.jboss.modules";
    public static final String JBOSS_MODULES_ARTIFACT_ID = "jboss-modules";

    private static final String MODULE_ID_JAR_RUNTIME = "org.wildfly.bootable-jar";

    private static final String BOOT_ARTIFACT_ID = "wildfly-jar-boot";
    public static final String WILDFLY_ARTIFACT_VERSIONS_RESOURCE_PATH = "wildfly/artifact-versions.properties";

    /**
     * Package a server as a bootable JAR.
     *
     * @param targetJarFile the path to the JAR file to create
     * @param workDir       the working directory used to generate and store content
     * @param config        the Galleon provisioning configuration
     * @param serverHome    the server directory
     * @param resolver      the Maven resolver used to resolve artifacts
     * @param writer        the message writer where messages will be written to
     *
     * @throws IOException           if an error occurs packaging the bootable JAR
     * @throws ProvisioningException if an error occurs packaging the bootable JAR
     */
    public static void packageBootableJar(final Path targetJarFile, final Path workDir,
                                          final GalleonProvisioningConfig config, final Path serverHome, final MavenRepoManager resolver,
                                          final MessageWriter writer) throws IOException, ProvisioningException {
        final Path contentRootDir = workDir.resolve("bootable-jar-build-artifacts");
        if (Files.exists(contentRootDir)) {
            IoUtils.recursiveDelete(contentRootDir);
        }
        Files.createDirectories(contentRootDir);
        try {
            final ScannedArtifacts bootable;
            final Path emptyHome = contentRootDir.resolve("tmp-home");
            Files.createDirectories(emptyHome);
            try (
                    Provisioning pm = new GalleonBuilder().addArtifactResolver(resolver).newProvisioningBuilder(config)
                            .setInstallationHome(emptyHome)
                            .setMessageWriter(writer)
                            .build()
            ) {
                bootable = scanArtifacts(pm, config, writer);
                pm.storeProvisioningConfig(config, contentRootDir.resolve("provisioning.xml"));
            }
            final Collection<String> paths = new ArrayList<>();
            for (MavenArtifact a : bootable.getCliArtifacts()) {
                resolver.resolve(a);
                paths.add(a.getPath().toAbsolutePath().toString());
            }
            final Path output = File.createTempFile("cli-script-output", null).toPath();
            Files.deleteIfExists(output);
            IoUtils.recursiveDelete(emptyHome);
            try {
                ForkedCLIUtil.fork(paths, CLIForkedBootConfigGenerator.class, serverHome, output);
            } finally {
                Files.deleteIfExists(output);
            }
            zipServer(serverHome, contentRootDir);
            buildJar(contentRootDir, targetJarFile, bootable, resolver);
        } finally {
            IoUtils.recursiveDelete(contentRootDir);
        }
    }

    /**
     * Resolves the cloud extension for the version provided. It then unpacks the extension into the content directory.
     *
     * @param contentDir the directory the cloud extension should be extracted to
     * @param version    the version of the cloud extension to use
     * @param resolver   the Maven resolver used to resolve the cloud extension
     *
     * @throws MavenUniverseException if an error occurs while resolving the artifact
     * @throws IOException            if en error occurs extracting the extension
     */
    public static void unzipCloudExtension(final Path contentDir, final String version, final MavenRepoManager resolver)
            throws MavenUniverseException, IOException {
        final MavenArtifact ma = new MavenArtifact();
        ma.setGroupId("org.wildfly.plugins");
        ma.setArtifactId("wildfly-jar-cloud-extension");
        ma.setExtension("jar");
        ma.setVersion(Assertions.requiresNotNullOrNotEmptyParameter("version", version));
        resolver.resolve(ma);
        ZipUtils.unzip(ma.getPath(), Assert.checkNotNullParam("contentDir", contentDir));
    }

    /**
     * Creates a ZIP archive for the server. The archive name will be {@code wildfly.zip}.
     *
     * @param source    path for the content to archive
     * @param targetDir the target directory for the archive to be created in
     *
     * @throws IOException if an error occurs creating the archive
     */
    public static void zipServer(final Path source, final Path targetDir) throws IOException {
        zipServer(source, targetDir, "wildfly.zip");
    }

    /**
     * Creates a ZIP archive for the server.
     *
     * @param source      path for the content to archive
     * @param targetDir   the target directory for the archive to be created in
     * @param zipFileName the of the archive to create
     *
     * @throws IOException if an error occurs creating the archive
     */
    public static void zipServer(final Path source, final Path targetDir, final String zipFileName) throws IOException {
        cleanupServer(Assert.checkNotNullParam("source", source));
        final Path target = Assert.checkNotNullParam("targetDir", targetDir).resolve(
                Assertions.requiresNotNullOrNotEmptyParameter("zipFileName", zipFileName));
        ZipUtils.zip(source, target);
    }

    /**
     * Scans the provisioning session for required artifacts. These include {@code jboss-modules}, the {@code wildfly-jar-boot}
     * artifact and CLI artifacts.
     *
     * @param pm     the provisioning session
     * @param config the provisioning configuration
     * @param writer the message writer
     *
     * @return the scanned artifacts
     *
     * @throws ProvisioningException if an error occurs scanning
     */
    public static ScannedArtifacts scanArtifacts(final Provisioning pm, final GalleonProvisioningConfig config, final MessageWriter writer) throws ProvisioningException {
        final Set<MavenArtifact> cliArtifacts = new HashSet<>();
        MavenArtifact jbossModules = null;
        MavenArtifact bootArtifact = null;
        try (GalleonProvisioningRuntime rt = pm.getProvisioningRuntime(config)) {
            for (GalleonFeaturePackRuntime fprt : rt.getGalleonFeaturePacks()) {
                if (fprt.getGalleonPackage(MODULE_ID_JAR_RUNTIME) != null) {
                    // We need to discover GAV of the associated boot.
                    final Path artifactProps = fprt.getResource(WILDFLY_ARTIFACT_VERSIONS_RESOURCE_PATH);
                    final Map<String, String> propsMap = new HashMap<>();
                    try {
                        readProperties(artifactProps, propsMap);
                    } catch (Exception ex) {
                        throw new RuntimeException("Error reading artifact versions", ex);
                    }
                    for (Map.Entry<String, String> entry : propsMap.entrySet()) {
                        String value = entry.getValue();
                        MavenArtifact a = parseArtifact(value);
                        if (BOOT_ARTIFACT_ID.equals(a.getArtifactId())) {
                            // We got it.
                            if (writer.isVerboseEnabled()) {
                                writer.verbose("Found %s in %s", a, fprt.getFPID());
                            }
                            bootArtifact = a;
                            break;
                        }
                    }
                }
                // Lookup artifacts to retrieve the required dependencies for isolated CLI execution
                final Path artifactProps = fprt.getResource(WILDFLY_ARTIFACT_VERSIONS_RESOURCE_PATH);
                final Map<String, String> propsMap = new HashMap<>();
                try {
                    readProperties(artifactProps, propsMap);
                } catch (Exception ex) {
                    throw new RuntimeException("Error reading artifact versions", ex);
                }
                for (Map.Entry<String, String> entry : propsMap.entrySet()) {
                    String value = entry.getValue();
                    MavenArtifact a = parseArtifact(value);
                    if ("wildfly-cli".equals(a.getArtifactId())
                            && "org.wildfly.core".equals(a.getGroupId())) {
                        // We got it.
                        a.setClassifier("client");
                        // We got it.
                        if (writer.isVerboseEnabled()) {
                            writer.verbose("Found %s in %s", a, fprt.getFPID());
                        }
                        cliArtifacts.add(a);
                        continue;
                    }
                    if (JBOSS_MODULES_ARTIFACT_ID.equals(a.getArtifactId())
                            && JBOSS_MODULES_GROUP_ID.equals(a.getGroupId())) {
                        jbossModules = a;
                    }
                }
            }
        }
        if (bootArtifact == null) {
            throw new ProvisioningException("Server doesn't support bootable jar packaging");
        }
        if (jbossModules == null) {
            throw new ProvisioningException("JBoss Modules not found in dependency, can't create a Bootable JAR");
        }
        return new ScannedArtifacts(bootArtifact, jbossModules, cliArtifacts);
    }

    /**
     * Builds a JAR file for the bootable JAR.
     *
     * @param contentDir the directory which stores the bootable JAR's content
     * @param jarFile    the target JAR file
     * @param bootable   the scanned artifacts
     * @param resolver   the Maven resolver used to resolve artifacts
     *
     * @throws IOException            if an error occurs building the JAR
     * @throws MavenUniverseException if an error occurs resolving artifacts
     */
    public static void buildJar(final Path contentDir, final Path jarFile, final ScannedArtifacts bootable, final MavenRepoManager resolver)
            throws IOException, MavenUniverseException {
        resolver.resolve(bootable.getBoot());
        final Path rtJarFile = bootable.getBoot().getPath();
        resolver.resolve(bootable.getJbossModules());
        final Path jbossModulesFile = bootable.getJbossModules().getPath();
        ZipUtils.unzip(jbossModulesFile, contentDir);
        ZipUtils.unzip(rtJarFile, contentDir);
        ZipUtils.zip(contentDir, jarFile);
    }

    private static void cleanupServer(final Path jbossHome) throws IOException {
        Path history = jbossHome.resolve("standalone").resolve("configuration").resolve("standalone_xml_history");
        IoUtils.recursiveDelete(history);
        Files.deleteIfExists(jbossHome.resolve("README.txt"));
    }

    private static void readProperties(final Path propsFile, final Map<String, String> propsMap) {
        try (BufferedReader reader = Files.newBufferedReader(propsFile)) {
            String line = reader.readLine();
            while (line != null) {
                line = line.trim();
                if (!line.isEmpty() && line.charAt(0) != '#') {
                    final int i = line.indexOf('=');
                    if (i < 0) {
                        throw new RuntimeException("Failed to parse property " + line + " from " + propsFile);
                    }
                    propsMap.put(line.substring(0, i), line.substring(i + 1));
                }
                line = reader.readLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MavenArtifact parseArtifact(final String artifact) {
        final String[] parts = artifact.split(":");
        if (parts.length < 5) {
            throw new IllegalArgumentException("Failed to parse artifact " + artifact);
        }
        final String groupId = parts[0];
        final String artifactId = parts[1];
        final String version = parts[2];
        final String classifier = parts[3];
        final String extension = parts[4];

        final MavenArtifact ma = new MavenArtifact();
        ma.setGroupId(groupId);
        ma.setArtifactId(artifactId);
        ma.setVersion(version);
        ma.setClassifier(classifier);
        ma.setExtension(extension);
        return ma;
    }
}
