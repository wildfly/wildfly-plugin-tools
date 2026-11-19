/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.bootablejar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Dependency;
import org.cyclonedx.model.Evidence;
import org.cyclonedx.model.Hash;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.component.evidence.Occurrence;
import org.cyclonedx.model.metadata.ToolInformation;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.cyberstamp.maven.assembly.sbom.BomReader;
import dev.cyberstamp.maven.assembly.sbom.BomWriter;

/**
 * Unit tests for {@link BootableJarSupport} and {@link SBOMUpdater}.
 */
class AdjustSBOMTest {

    private static final String GROUP_ID = "org.wildfly.core";
    private static final String ARTIFACT_ID = "wildfly-jar-boot";
    private static final String VERSION = "1.0.0.Final";
    private static final String EXPECTED_BOOT_PURL = "pkg:maven/" + GROUP_ID + "/" + ARTIFACT_ID + "@" + VERSION;
    private static final SBOMArtifact TOOL = SBOMArtifact.generic("wildfly-plugin-tools", "2.1.2.Final");

    private ScannedArtifacts bootArtifacts(Path tmpDir) throws IOException {
        final MavenArtifact boot = new MavenArtifact();
        boot.setGroupId(GROUP_ID);
        boot.setArtifactId(ARTIFACT_ID);
        boot.setVersion(VERSION);
        boot.setExtension("jar");
        Path p = tmpDir.resolve("fake_artifact.jar");
        Files.write(p, "A very fake jar".getBytes());
        boot.setPath(p);
        final MavenArtifact jbossModules = new MavenArtifact();
        jbossModules.setGroupId("org.jboss.modules");
        jbossModules.setArtifactId("jboss-modules");
        jbossModules.setVersion("2.0.0.Final");
        jbossModules.setExtension("jar");
        return new ScannedArtifacts(boot, jbossModules, Set.of());
    }

    private static Bom createInitialBom() {
        Bom bom = new Bom();
        Metadata metadata = new Metadata();
        Component metadataComponent = new Component();
        metadataComponent.setType(Component.Type.APPLICATION);
        metadataComponent.setName("WildFly");
        metadataComponent.setVersion("43.0.0.Final");
        metadataComponent.setBomRef("pkg:generic/wildfly@43.0.0.final");
        metadataComponent.setPurl("pkg:generic/wildfly@43.0.0.final");
        metadata.setComponent(metadataComponent);

        Component toolComp = new Component();
        toolComp.setType(Component.Type.APPLICATION);
        toolComp.setGroup("org.wildfly.galleon-plugins");
        toolComp.setName("wildfly-galleon-plugins");
        toolComp.setVersion("8.2.3.Final");
        toolComp.setPurl("pkg:maven/org.wildfly.galleon-plugins/wildfly-galleon-plugins@8.2.3.Final");
        ToolInformation toolChoice = new ToolInformation();
        toolChoice.setComponents(new java.util.ArrayList<>(List.of(toolComp)));
        metadata.setToolChoice(toolChoice);
        bom.setMetadata(metadata);

        Component lib = new Component();
        lib.setType(Component.Type.LIBRARY);
        String purl = "pkg:maven/io.undertow/undertow-core@2.3.18.Final";
        lib.setBomRef(purl);
        lib.setGroup("io.undertow");
        lib.setName("undertow-core");
        lib.setVersion("2.3.18.Final");
        lib.setPurl(purl);
        Evidence evidence = new Evidence();
        Occurrence occurrence = new Occurrence();
        occurrence.setLocation("modules/system/layers/base/io/undertow/core/main/undertow-core-2.3.18.Final.jar");
        evidence.addOccurrence(occurrence);
        lib.setEvidence(evidence);
        bom.addComponent(lib);

        Dependency productDep = new Dependency(metadataComponent.getBomRef());
        productDep.addDependency(new Dependency(lib.getBomRef()));
        bom.addDependency(productDep);
        return bom;
    }

    private static Path createDeploymentArchive(Path dir, String fileName, Bom embeddedBom, boolean isJson)
            throws Exception {
        Path archivePath = dir.resolve(fileName);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(archivePath))) {
            if (embeddedBom != null) {
                String entryName = isJson ? "META-INF/sbom/sbom.cdx.json" : "META-INF/sbom/sbom.cdx.xml";
                zos.putNextEntry(new ZipEntry(entryName));
                Path tempBom = Files.createTempFile("embedded-sbom", isJson ? ".json" : ".xml");
                try {
                    if (isJson) {
                        BomWriter.writeJson(embeddedBom, tempBom, false);
                    } else {
                        BomWriter.writeXml(embeddedBom, tempBom, org.cyclonedx.Version.VERSION_16);
                    }
                    Files.copy(tempBom, zos);
                } finally {
                    Files.deleteIfExists(tempBom);
                }
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry("WEB-INF/web.xml"));
            zos.write("<web-app/>".getBytes());
            zos.closeEntry();
        }
        return archivePath;
    }

    private static Bom createEmbeddedAppBom() {
        Bom bom = new Bom();
        Metadata metadata = new Metadata();
        Component appRoot = new Component();
        appRoot.setType(Component.Type.APPLICATION);
        appRoot.setName("my-app");
        appRoot.setVersion("1.0");
        appRoot.setBomRef("pkg:maven/com.acme/my-app@1.0?type=war");
        metadata.setComponent(appRoot);
        bom.setMetadata(metadata);

        Component opencsv = new Component();
        opencsv.setType(Component.Type.LIBRARY);
        opencsv.setGroup("com.opencsv");
        opencsv.setName("opencsv");
        opencsv.setVersion("5.9");
        opencsv.setPurl("pkg:maven/com.opencsv/opencsv@5.9");
        opencsv.setBomRef("pkg:maven/com.opencsv/opencsv@5.9");
        bom.addComponent(opencsv);

        Dependency appDep = new Dependency(appRoot.getBomRef());
        appDep.addDependency(new Dependency(opencsv.getBomRef()));
        bom.addDependency(appDep);
        return bom;
    }

    private static void gzipFile(Path source, Path target) throws IOException {
        try (InputStream in = Files.newInputStream(source);
                OutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
            in.transferTo(out);
        }
    }

    private static void gunzipFile(Path source, Path target) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(source));
                OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }
    }

    @Test
    void adjustSBOM_defaultWithoutBootableJar(@TempDir Path tmpDir) throws Exception {
        final Path source = tmpDir.resolve("sbom.cdx.json");
        final Path target = tmpDir.resolve("sbom-adjusted.cdx.json");
        Bom bom = createInitialBom();
        BomWriter.writeJson(bom, source, true);
        SBOMUpdater.adjustSBOM(source, target, bootArtifacts(tmpDir), null, Collections.emptyMap(), null);

        Bom updated = BomReader.readBom(target);

        // Metadata component remains the server product
        Component metaComp = updated.getMetadata().getComponent();
        assertNotNull(metaComp);
        assertEquals(Component.Type.APPLICATION, metaComp.getType());
        assertEquals("WildFly", metaComp.getName());

        List<Component> components = updated.getComponents();
        assertNotNull(components);
        // Original library (undertow-core) + Boot component (wildfly-jar-boot)
        assertEquals(2, components.size());

        // Occurrences prefixed with wildfly.zip! on original component
        final Component originalLib = components.stream()
                .filter(c -> "undertow-core".equals(c.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(originalLib);
        final Evidence evidence = originalLib.getEvidence();
        assertNotNull(evidence);
        assertNotNull(evidence.getOccurrences());
        assertEquals(1, evidence.getOccurrences().size());
        assertEquals("wildfly.zip!modules/system/layers/base/io/undertow/core/main/undertow-core-2.3.18.Final.jar",
                evidence.getOccurrences().get(0).getLocation());

        // Boot component added with correct fields
        final Component boot = components.stream()
                .filter(c -> ARTIFACT_ID.equals(c.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(boot);
        assertEquals(Component.Type.LIBRARY, boot.getType());
        assertEquals(GROUP_ID, boot.getGroup());
        assertEquals(ARTIFACT_ID, boot.getName());
        assertEquals(VERSION, boot.getVersion());
        assertEquals(EXPECTED_BOOT_PURL, boot.getPurl());
        assertEquals(EXPECTED_BOOT_PURL, boot.getBomRef());
        assertNotNull(boot.getHashes());
        assertEquals(1, boot.getHashes().size());
        Hash hash = boot.getHashes().get(0);
        assertNotNull(hash.getValue());
        assertNotNull(hash.getAlgorithm());

        // Dependencies: server product dependency now has undertow-core and wildfly-jar-boot
        assertEquals(1, updated.getDependencies().size());
        Dependency productDep = updated.getDependencies().get(0);
        assertEquals("pkg:generic/wildfly@43.0.0.final", productDep.getRef());
        assertEquals(2, productDep.getDependencies().size());
        assertTrue(productDep.getDependencies().stream().anyMatch(d -> EXPECTED_BOOT_PURL.equals(d.getRef())));
        assertTrue(productDep.getDependencies().stream().anyMatch(d -> originalLib.getBomRef().equals(d.getRef())));
    }

    @Test
    void adjustSBOM_genericBootableJarAndTool(@TempDir Path tmpDir) throws Exception {
        final Path source = tmpDir.resolve("sbom.cdx.json");
        final Path target = tmpDir.resolve("sbom-adjusted.cdx.json");
        BomWriter.writeJson(createInitialBom(), source, true);

        SBOMUpdater.adjustSBOM(source, target, bootArtifacts(tmpDir),
                SBOMArtifact.generic("kitchensink-bootable", "1.0"), Collections.emptyMap(),
                SBOMArtifact.generic("wildfly-glow", "2.2.1.Final"));

        Bom updated = BomReader.readBom(target);
        Component metaComp = updated.getMetadata().getComponent();
        assertEquals("kitchensink-bootable", metaComp.getName());
        assertEquals("1.0", metaComp.getVersion());
        assertEquals("pkg:generic/kitchensink-bootable@1.0", metaComp.getPurl());
        assertEquals("pkg:generic/kitchensink-bootable@1.0", metaComp.getBomRef());
        assertEquals(Component.Type.APPLICATION, metaComp.getType());
        assertTrue(updated.getMetadata().getToolChoice().getComponents().stream()
                .anyMatch(c -> "pkg:generic/wildfly-glow@2.2.1.Final".equals(c.getPurl())));
        Dependency jarDep = updated.getDependencies().stream()
                .filter(d -> "pkg:generic/kitchensink-bootable@1.0".equals(d.getRef()))
                .findFirst().orElse(null);
        assertNotNull(jarDep);
    }

    @Test
    void adjustSBOM_fullBootableJarWithDeploymentsAndTool(@TempDir Path tmpDir) throws Exception {
        final Path source = tmpDir.resolve("sbom.cdx.json");
        final Path target = tmpDir.resolve("sbom-adjusted.cdx.json");
        Bom bom = createInitialBom();
        BomWriter.writeJson(bom, source, true);

        // Bootable JAR artifact
        final MavenArtifact bootableJar = new MavenArtifact();
        bootableJar.setGroupId("com.acme");
        bootableJar.setArtifactId("server-bootable");
        bootableJar.setVersion("1.0");
        bootableJar.setExtension("jar");

        // Tool artifact (wildfly-maven-plugin)
        final MavenArtifact toolArtifact = new MavenArtifact();
        toolArtifact.setGroupId("org.wildfly.plugins");
        toolArtifact.setArtifactId("wildfly-maven-plugin");
        toolArtifact.setVersion("6.2.0.Final");
        toolArtifact.setExtension("jar");

        // Deployment WAR with embedded CycloneDX JSON SBOM
        Path warFile = createDeploymentArchive(tmpDir, "my-app.war", createEmbeddedAppBom(), true);
        final MavenArtifact deploymentArtifact = new MavenArtifact();
        deploymentArtifact.setGroupId("com.acme");
        deploymentArtifact.setArtifactId("my-app");
        deploymentArtifact.setVersion("1.0");
        deploymentArtifact.setExtension("war");
        deploymentArtifact.setPath(warFile);

        Map<String, SBOMArtifact> deployments = Map.of("my-app.war", SBOMArtifact.fromMaven(deploymentArtifact, "war"));

        SBOMUpdater.adjustSBOM(source, target, bootArtifacts(tmpDir), SBOMArtifact.fromMaven(bootableJar), deployments,
                SBOMArtifact.fromMaven(toolArtifact));

        Bom updated = BomReader.readBom(target);

        // 1. Metadata component is the Bootable JAR
        Component metaComp = updated.getMetadata().getComponent();
        assertNotNull(metaComp);
        assertEquals(Component.Type.APPLICATION, metaComp.getType());
        assertEquals("server-bootable", metaComp.getName());
        assertEquals("com.acme", metaComp.getGroup());
        assertEquals("1.0", metaComp.getVersion());
        assertEquals("pkg:maven/com.acme/server-bootable@1.0", metaComp.getPurl());
        assertEquals("pkg:maven/com.acme/server-bootable@1.0", metaComp.getBomRef());

        // 2. Metadata tools contains galleon-plugins and wildfly-maven-plugin
        List<Component> toolComponents = updated.getMetadata().getToolChoice().getComponents();
        assertNotNull(toolComponents);
        assertEquals(2, toolComponents.size());
        assertTrue(toolComponents.stream().anyMatch(c -> "wildfly-maven-plugin".equals(c.getName())));
        assertTrue(toolComponents.stream().anyMatch(c -> "wildfly-galleon-plugins".equals(c.getName())));

        // 3. Components list
        List<Component> components = updated.getComponents();
        assertNotNull(components);

        // Server product component (Type: FRAMEWORK, Scope: EXCLUDED)
        Component serverComp = components.stream()
                .filter(c -> "WildFly".equals(c.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(serverComp);
        assertEquals(Component.Type.FRAMEWORK, serverComp.getType());
        assertEquals(Component.Scope.EXCLUDED, serverComp.getScope());

        // Deployment component (Type: APPLICATION, purl includes ?type=war)
        Component depComp = components.stream()
                .filter(c -> "my-app".equals(c.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(depComp);
        assertEquals(Component.Type.APPLICATION, depComp.getType());
        assertEquals("pkg:maven/com.acme/my-app@1.0?type=war", depComp.getPurl());
        assertEquals("pkg:maven/com.acme/my-app@1.0?type=war", depComp.getBomRef());
        assertNotNull(depComp.getHashes());
        assertFalse(depComp.getHashes().isEmpty());

        // Nested sub-components from embedded SBOM
        assertNotNull(depComp.getComponents());
        assertTrue(depComp.getComponents().stream().anyMatch(c -> "opencsv".equals(c.getName())));

        // Boot runtime component
        Component bootComp = components.stream()
                .filter(c -> ARTIFACT_ID.equals(c.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(bootComp);
        assertEquals(Component.Type.LIBRARY, bootComp.getType());

        // Evidence occurrences prefixed with wildfly.zip!
        final Component undertowLib = components.stream()
                .filter(c -> "undertow-core".equals(c.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(undertowLib);
        assertNotNull(undertowLib.getEvidence());
        assertNotNull(undertowLib.getEvidence().getOccurrences());
        assertEquals(1, undertowLib.getEvidence().getOccurrences().size());
        assertEquals("wildfly.zip!modules/system/layers/base/io/undertow/core/main/undertow-core-2.3.18.Final.jar",
                undertowLib.getEvidence().getOccurrences().get(0).getLocation());

        // 4. Dependencies
        List<Dependency> dependencies = updated.getDependencies();
        assertNotNull(dependencies);

        // Bootable JAR dependency depends on Server and Deployment
        Dependency bootableJarDep = dependencies.stream()
                .filter(d -> "pkg:maven/com.acme/server-bootable@1.0".equals(d.getRef()))
                .findFirst()
                .orElse(null);
        assertNotNull(bootableJarDep);
        assertEquals(2, bootableJarDep.getDependencies().size());
        assertTrue(bootableJarDep.getDependencies().stream()
                .anyMatch(d -> "pkg:generic/wildfly@43.0.0.final".equals(d.getRef())));
        assertTrue(bootableJarDep.getDependencies().stream()
                .anyMatch(d -> "pkg:maven/com.acme/my-app@1.0?type=war".equals(d.getRef())));

        // Server dependency depends on undertow-core and wildfly-jar-boot
        Dependency serverDep = dependencies.stream()
                .filter(d -> "pkg:generic/wildfly@43.0.0.final".equals(d.getRef()))
                .findFirst()
                .orElse(null);
        assertNotNull(serverDep);
        assertTrue(serverDep.getDependencies().stream()
                .anyMatch(d -> EXPECTED_BOOT_PURL.equals(d.getRef())));
        assertTrue(serverDep.getDependencies().stream()
                .anyMatch(d -> "pkg:maven/io.undertow/undertow-core@2.3.18.Final".equals(d.getRef())));

        // Embedded dependency graph for the application
        Dependency embeddedAppDep = dependencies.stream()
                .filter(d -> "pkg:maven/com.acme/my-app@1.0?type=war".equals(d.getRef()))
                .findFirst()
                .orElse(null);
        assertNotNull(embeddedAppDep);
        assertTrue(embeddedAppDep.getDependencies().stream()
                .anyMatch(d -> "pkg:maven/com.opencsv/opencsv@5.9".equals(d.getRef())));
    }

    @Test
    void adjustSBOM_deploymentWithEmbeddedXmlSbom(@TempDir Path tmpDir) throws Exception {
        final Path source = tmpDir.resolve("sbom.cdx.json");
        final Path target = tmpDir.resolve("sbom-adjusted.cdx.json");
        Bom bom = createInitialBom();
        BomWriter.writeJson(bom, source, true);

        final MavenArtifact bootableJar = new MavenArtifact();
        bootableJar.setGroupId("com.acme");
        bootableJar.setArtifactId("server-bootable");
        bootableJar.setVersion("1.0");

        Path warFile = createDeploymentArchive(tmpDir, "my-xml-app.war", createEmbeddedAppBom(), false);
        final MavenArtifact deploymentArtifact = new MavenArtifact();
        deploymentArtifact.setGroupId("com.acme");
        deploymentArtifact.setArtifactId("my-xml-app");
        deploymentArtifact.setVersion("1.0");
        deploymentArtifact.setExtension("war");
        deploymentArtifact.setPath(warFile);

        Map<String, SBOMArtifact> deployments = Map.of("my-xml-app.war", SBOMArtifact.fromMaven(deploymentArtifact, "war"));

        SBOMUpdater.adjustSBOM(source, target, bootArtifacts(tmpDir), SBOMArtifact.fromMaven(bootableJar), deployments, TOOL);

        Bom updated = BomReader.readBom(target);
        Component depComp = updated.getComponents().stream()
                .filter(c -> "my-xml-app".equals(c.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(depComp);
        assertNotNull(depComp.getComponents());
        assertTrue(depComp.getComponents().stream().anyMatch(c -> "opencsv".equals(c.getName())));
    }

    @Test
    void adjustSBOM_genericDeploymentWithEmbeddedSbom(@TempDir Path tmpDir) throws Exception {
        final Path source = tmpDir.resolve("sbom.cdx.json");
        final Path target = tmpDir.resolve("sbom-adjusted.cdx.json");
        BomWriter.writeJson(createInitialBom(), source, true);
        Path warFile = createDeploymentArchive(tmpDir, "my-app.war", createEmbeddedAppBom(), true);

        Map<String, SBOMArtifact> deployments = Map.of("my-app.war",
                SBOMArtifact.generic("my-app.war", null, warFile));

        SBOMUpdater.adjustSBOM(source, target, bootArtifacts(tmpDir),
                SBOMArtifact.generic("my-app.jar", "1.0"), deployments, TOOL);

        Bom updated = BomReader.readBom(target);
        Component depComp = updated.getComponents().stream()
                .filter(c -> "pkg:generic/my-app.war".equals(c.getPurl()))
                .findFirst()
                .orElse(null);
        assertNotNull(depComp);
        assertEquals("my-app.war", depComp.getName());
        assertNull(depComp.getVersion());
        assertNotNull(depComp.getHashes());
        assertTrue(depComp.getComponents().stream().anyMatch(c -> "opencsv".equals(c.getName())));
        Dependency jarDep = updated.getDependencies().stream()
                .filter(d -> "pkg:generic/my-app.jar@1.0".equals(d.getRef()))
                .findFirst().orElse(null);
        assertNotNull(jarDep);
        assertTrue(jarDep.getDependencies().stream().anyMatch(d -> "pkg:generic/my-app.war".equals(d.getRef())));
    }

    @Test
    void adjustSBOM_multipleDeployments(@TempDir Path tmpDir) throws Exception {
        final Path source = tmpDir.resolve("sbom.cdx.json");
        final Path target = tmpDir.resolve("sbom-adjusted.cdx.json");
        Bom bom = createInitialBom();
        BomWriter.writeJson(bom, source, true);

        final MavenArtifact bootableJar = new MavenArtifact();
        bootableJar.setGroupId("com.acme");
        bootableJar.setArtifactId("multi-bootable");
        bootableJar.setVersion("1.0");

        Path warFile1 = createDeploymentArchive(tmpDir, "app1.war", createEmbeddedAppBom(), true);
        final MavenArtifact dep1 = new MavenArtifact();
        dep1.setGroupId("com.acme");
        dep1.setArtifactId("app1");
        dep1.setVersion("1.0");
        dep1.setExtension("war");
        dep1.setPath(warFile1);

        Path jarFile2 = createDeploymentArchive(tmpDir, "app2.jar", null, true);
        final MavenArtifact dep2 = new MavenArtifact();
        dep2.setGroupId("com.acme");
        dep2.setArtifactId("app2");
        dep2.setVersion("2.0");
        dep2.setExtension("jar");
        dep2.setPath(jarFile2);

        Map<String, SBOMArtifact> deployments = new HashMap<>();
        deployments.put("app1.war", SBOMArtifact.fromMaven(dep1, "war"));
        deployments.put("app2.jar", SBOMArtifact.fromMaven(dep2, "jar"));

        SBOMUpdater.adjustSBOM(source, target, bootArtifacts(tmpDir), SBOMArtifact.fromMaven(bootableJar), deployments, TOOL);

        Bom updated = BomReader.readBom(target);

        assertTrue(updated.getComponents().stream().anyMatch(c -> "app1".equals(c.getName())));
        assertTrue(updated.getComponents().stream().anyMatch(c -> "app2".equals(c.getName())));

        Dependency bootableJarDep = updated.getDependencies().stream()
                .filter(d -> "pkg:maven/com.acme/multi-bootable@1.0".equals(d.getRef()))
                .findFirst()
                .orElse(null);
        assertNotNull(bootableJarDep);
        assertEquals(3, bootableJarDep.getDependencies().size());
        assertTrue(bootableJarDep.getDependencies().stream()
                .anyMatch(d -> "pkg:maven/com.acme/app1@1.0?type=war".equals(d.getRef())));
        assertTrue(bootableJarDep.getDependencies().stream()
                .anyMatch(d -> "pkg:maven/com.acme/app2@2.0?type=jar".equals(d.getRef())));
        assertTrue(bootableJarDep.getDependencies().stream()
                .anyMatch(d -> "pkg:generic/wildfly@43.0.0.final".equals(d.getRef())));
    }

    @Test
    void packageSBOM_noAggregation(@TempDir Path tmpDir) throws Exception {
        Path serverHome = tmpDir.resolve("server");
        Files.createDirectories(serverHome);
        Path rootContentDir = tmpDir.resolve("content");
        Files.createDirectories(rootContentDir);
        Path outputDir = tmpDir.resolve("target-output");
        Files.createDirectories(outputDir);

        Path originalJson = tmpDir.resolve("original-sbom.cdx.json");
        Bom bom = createInitialBom();
        BomWriter.writeJson(bom, originalJson, false);
        Path serverSbomGz = serverHome.resolve("sbom.cdx.json.gz");
        gzipFile(originalJson, serverSbomGz);

        SBOMUpdater.packageSBOM(serverHome, rootContentDir, null, bootArtifacts(tmpDir),
                Collections.emptyMap(), null, null, outputDir, false);

        Path packagedSbom = rootContentDir.resolve("META-INF").resolve("sbom").resolve("sbom.cdx.json.gz");
        assertTrue(Files.exists(packagedSbom));

        // The SBOM is also copied, uncompressed, to the output directory
        Path standaloneSbom = outputDir.resolve("sbom.cdx.json");
        assertTrue(Files.exists(standaloneSbom));
        assertEquals("WildFly", BomReader.readBom(standaloneSbom).getMetadata().getComponent().getName());

        Path unzipped = tmpDir.resolve("unzipped-no-agg.json");
        gunzipFile(packagedSbom, unzipped);
        Bom read = BomReader.readBom(unzipped);
        assertEquals("WildFly", read.getMetadata().getComponent().getName());
        assertEquals(2, read.getComponents().size());
        assertTrue(read.getComponents().stream().anyMatch(c -> "undertow-core".equals(c.getName())));
        assertTrue(read.getComponents().stream().anyMatch(c -> ARTIFACT_ID.equals(c.getName())));
        Dependency productDep = read.getDependencies().get(0);
        assertTrue(productDep.getDependencies().stream().anyMatch(d -> EXPECTED_BOOT_PURL.equals(d.getRef())));
    }

    @Test
    void packageSBOM_aggregated_compressed(@TempDir Path tmpDir) throws Exception {
        Path serverHome = tmpDir.resolve("server");
        Files.createDirectories(serverHome);
        Path rootContentDir = tmpDir.resolve("content");
        Files.createDirectories(rootContentDir);
        Path outputDir = tmpDir.resolve("target-output");
        Files.createDirectories(outputDir);

        Path originalJson = tmpDir.resolve("original-sbom.cdx.json");
        Bom bom = createInitialBom();
        BomWriter.writeJson(bom, originalJson, false);
        Path serverSbomGz = serverHome.resolve("sbom.cdx.json.gz");
        gzipFile(originalJson, serverSbomGz);

        final MavenArtifact bootableJar = new MavenArtifact();
        bootableJar.setGroupId("com.acme");
        bootableJar.setArtifactId("app-bootable");
        bootableJar.setVersion("1.0");

        SBOMUpdater.packageSBOM(serverHome, rootContentDir, Collections.emptyMap(), bootArtifacts(tmpDir),
                Collections.emptyMap(), SBOMArtifact.fromMaven(bootableJar), TOOL, outputDir, true);

        // In JAR content: META-INF/sbom/sbom.cdx.json.gz
        Path packagedSbomGz = rootContentDir.resolve("META-INF").resolve("sbom").resolve("sbom.cdx.json.gz");
        assertTrue(Files.exists(packagedSbomGz));

        // In target output: standalone uncompressed sbom.cdx.json
        Path standaloneSbom = outputDir.resolve("sbom.cdx.json");
        assertTrue(Files.exists(standaloneSbom));

        // Verify content in both locations
        Bom fromOutput = BomReader.readBom(standaloneSbom);
        assertEquals("app-bootable", fromOutput.getMetadata().getComponent().getName());

        Path unzippedFromMeta = tmpDir.resolve("unzipped-meta.json");
        gunzipFile(packagedSbomGz, unzippedFromMeta);
        Bom fromMeta = BomReader.readBom(unzippedFromMeta);
        assertEquals("app-bootable", fromMeta.getMetadata().getComponent().getName());

        // Verify intermediate files are cleaned up
        assertFalse(Files.exists(rootContentDir.resolve("sbom.cdx.json")));
        assertFalse(Files.exists(rootContentDir.resolve("META-INF").resolve("sbom").resolve("sbom.cdx.json")));
    }

    @Test
    void packageSBOM_aggregated_uncompressed(@TempDir Path tmpDir) throws Exception {
        Path serverHome = tmpDir.resolve("server");
        Files.createDirectories(serverHome);
        Path rootContentDir = tmpDir.resolve("content");
        Files.createDirectories(rootContentDir);
        Path outputDir = tmpDir.resolve("target-output");
        Files.createDirectories(outputDir);

        Path serverSbom = serverHome.resolve("sbom.cdx.json");
        Bom bom = createInitialBom();
        BomWriter.writeJson(bom, serverSbom, false);

        Map<String, String> options = Map.of("jboss-cyclonedx-compress", "false");

        final MavenArtifact bootableJar = new MavenArtifact();
        bootableJar.setGroupId("com.acme");
        bootableJar.setArtifactId("app-uncompressed");
        bootableJar.setVersion("1.0");

        SBOMUpdater.packageSBOM(serverHome, rootContentDir, options, bootArtifacts(tmpDir),
                Collections.emptyMap(), SBOMArtifact.fromMaven(bootableJar), TOOL, outputDir, true);

        Path packagedSbom = rootContentDir.resolve("META-INF").resolve("sbom").resolve("sbom.cdx.json");
        assertTrue(Files.exists(packagedSbom));

        Path standaloneSbom = outputDir.resolve("sbom.cdx.json");
        assertTrue(Files.exists(standaloneSbom));

        Bom fromMeta = BomReader.readBom(packagedSbom);
        assertEquals("app-uncompressed", fromMeta.getMetadata().getComponent().getName());
    }

    @Test
    void packageSBOM_customPath_successAndFailure(@TempDir Path tmpDir) throws Exception {
        Path serverHome = tmpDir.resolve("server");
        Files.createDirectories(serverHome.resolve("custom/nested"));
        Path rootContentDir = tmpDir.resolve("content");
        Files.createDirectories(rootContentDir);
        Path outputDir = tmpDir.resolve("target-output");
        Files.createDirectories(outputDir);

        Path originalJson = tmpDir.resolve("original-custom.json");
        Bom bom = createInitialBom();
        BomWriter.writeJson(bom, originalJson, false);
        Path customSbomGz = serverHome.resolve("custom/nested/my-custom-sbom.cdx.json.gz");
        gzipFile(originalJson, customSbomGz);

        Map<String, String> options = Map.of("jboss-cyclonedx-output", "custom/nested/my-custom-sbom.cdx.json");

        final MavenArtifact bootableJar = new MavenArtifact();
        bootableJar.setGroupId("com.acme");
        bootableJar.setArtifactId("custom-path-app");
        bootableJar.setVersion("1.0");

        SBOMUpdater.packageSBOM(serverHome, rootContentDir, options, bootArtifacts(tmpDir),
                Collections.emptyMap(), SBOMArtifact.fromMaven(bootableJar), TOOL, outputDir, true);

        Path packagedSbomGz = rootContentDir.resolve("META-INF").resolve("sbom").resolve("my-custom-sbom.cdx.json.gz");
        assertTrue(Files.exists(packagedSbomGz));

        // Test non-existent custom path throws ProvisioningException
        Map<String, String> invalidOptions = Map.of("jboss-cyclonedx-output", "does/not/exist.json");
        ProvisioningException ex = assertThrows(ProvisioningException.class,
                () -> SBOMUpdater.packageSBOM(serverHome, rootContentDir, invalidOptions, bootArtifacts(tmpDir),
                        Collections.emptyMap(), SBOMArtifact.fromMaven(bootableJar), TOOL, outputDir, true));
        assertTrue(ex.getMessage().contains("Custom SBOM file location"));
    }

    @Test
    void packageSBOM_xmlFallback(@TempDir Path tmpDir) throws Exception {
        Path serverHome = tmpDir.resolve("server");
        Files.createDirectories(serverHome);
        Path rootContentDir = tmpDir.resolve("content");
        Files.createDirectories(rootContentDir);
        Path outputDir = tmpDir.resolve("target-output");
        Files.createDirectories(outputDir);

        Path originalXml = tmpDir.resolve("original-sbom.cdx.xml");
        Bom bom = createInitialBom();
        BomWriter.writeXml(bom, originalXml, org.cyclonedx.Version.VERSION_16);
        Path serverSbomXmlGz = serverHome.resolve("sbom.cdx.xml.gz");
        gzipFile(originalXml, serverSbomXmlGz);

        final MavenArtifact bootableJar = new MavenArtifact();
        bootableJar.setGroupId("com.acme");
        bootableJar.setArtifactId("xml-fallback-app");
        bootableJar.setVersion("1.0");

        SBOMUpdater.packageSBOM(serverHome, rootContentDir, Collections.emptyMap(), bootArtifacts(tmpDir),
                Collections.emptyMap(), SBOMArtifact.fromMaven(bootableJar), TOOL, outputDir, true);

        Path packagedSbomGz = rootContentDir.resolve("META-INF").resolve("sbom").resolve("sbom.cdx.xml.gz");
        assertTrue(Files.exists(packagedSbomGz));
        Path standaloneXml = outputDir.resolve("sbom.cdx.xml");
        assertTrue(Files.exists(standaloneXml));
    }

    @Test
    void packageSBOM_noSbom_doesNothing(@TempDir Path tmpDir) throws Exception {
        Path serverHome = tmpDir.resolve("server");
        Files.createDirectories(serverHome);
        Path rootContentDir = tmpDir.resolve("content");
        Files.createDirectories(rootContentDir);

        SBOMUpdater.packageSBOM(serverHome, rootContentDir, Collections.emptyMap(), bootArtifacts(tmpDir),
                Collections.emptyMap(), null, null, null, true);

        assertFalse(Files.exists(rootContentDir.resolve("META-INF").resolve("sbom")));
    }
}
