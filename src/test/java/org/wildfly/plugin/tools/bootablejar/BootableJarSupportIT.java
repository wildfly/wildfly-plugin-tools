/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools.bootablejar;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.repo.SimplisticMavenRepoManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wildfly.plugin.tools.Environment;

/**
 *
 * @author <a href="mailto:jperkins@ibm.com">James R. Perkins</a>
 */
class BootableJarSupportIT {

    private static MavenArtifact JBOSS_MODULES;
    private static MavenArtifact JBOSS_JAR;

    @BeforeAll
    static void createArtifacts() throws Exception {
        JBOSS_MODULES = MavenArtifact
                .fromString("org.jboss.modules:jboss-modules:%s".formatted(System.getProperty("version.org.jboss.modules")));
        JBOSS_JAR = MavenArtifact
                .fromString("org.wildfly.core:wildfly-jar-boot:%s".formatted(System.getProperty("version.org.wildfly.core")));
    }

    @Test
    void checkJarContent() throws Exception {
        final Path contentDir = Environment.createTempPath("test-jar");
        final Path jar = contentDir.resolve("test-bootable.jar");
        BootableJarSupport.buildJar(contentDir, jar,
                new ScannedArtifacts(JBOSS_JAR, JBOSS_MODULES, Set.of()),
                SimplisticMavenRepoManager.getInstance(Path.of(System.getProperty("user.home"), ".m2", "repository")));
        // Ensure the JAR is not empty and does not have specific files
        try (FileSystem zipFs = zipFs(jar)) {
            assertFileNonExistent(zipFs.getPath("/module-info.class"));
            assertFileNonExistent(zipFs.getPath("/META-INF/maven"));
            assertFileNonExistent(zipFs.getPath("/META-INF/INDEX.LIST"));
        }
    }

    private static void assertFileNonExistent(final Path file) throws Exception {
        Assertions.assertTrue(Files.notExists(file), () -> "File %s should not exist.".formatted(file));
    }

    private static FileSystem zipFs(final Path jarFile) throws Exception {
        final URI uri = URI.create("jar:" + jarFile.toUri());
        try {
            return FileSystems.getFileSystem(uri);
        } catch (FileSystemNotFoundException ignore) {
        }
        return FileSystems.newFileSystem(uri, Map.of());
    }
}
