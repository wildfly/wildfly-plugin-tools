/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.bootablejar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Dependency;
import org.cyclonedx.model.Evidence;
import org.cyclonedx.model.Hash;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.component.evidence.Occurrence;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.cyberstamp.maven.assembly.sbom.BomReader;
import dev.cyberstamp.maven.assembly.sbom.BomWriter;

/**
 * Unit tests for {@link BootableJarSupport#adjustSBOM}.
 */
class AdjustSBOMTest {

    private static final String GROUP_ID = "org.wildfly.core";
    private static final String ARTIFACT_ID = "wildfly-jar-boot";
    private static final String VERSION = "1.0.0.Final";
    private static final String EXPECTED_PURL = "pkg:maven/" + GROUP_ID + "/" + ARTIFACT_ID + "@" + VERSION;

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
        metadataComponent.setName("test-app");
        metadataComponent.setVersion("1.0.0");
        metadataComponent.setBomRef("product-ref");
        metadata.setComponent(metadataComponent);
        bom.setMetadata(metadata);

        Component comp = new Component();
        comp.setType(Component.Type.LIBRARY);
        String purl = "pkg:maven/com.example/some-lib@1.0";
        comp.setBomRef(purl);
        comp.setGroup("com.example");
        comp.setName("some-lib");
        comp.setVersion("1.0");
        comp.setPurl(purl);
        Evidence evidence = new Evidence();
        Occurrence occurrence = new Occurrence();
        occurrence.setLocation("modules/com/example/main/some-lib-1.0.jar");
        evidence.addOccurrence(occurrence);
        comp.setEvidence(evidence);
        bom.addComponent(comp);
        Dependency productDep = new Dependency(metadataComponent.getBomRef());
        productDep.addDependency(new Dependency(comp.getBomRef()));
        bom.addDependency(productDep);
        return bom;
    }

    @Test
    void adjustSBOM(@TempDir Path tmpDir) throws Exception {
        final Path source = tmpDir.resolve("sbom.cdx.json");
        final Path target = tmpDir.resolve("sbom-adjusted.cdx.json");
        Bom bom = createInitialBom();
        BomWriter.writeJson(bom, source, true);
        BootableJarSupport.adjustSBOM(source, target, bootArtifacts(tmpDir));

        Bom updated = BomReader.readBom(target);
        List<Component> components = updated.getComponents();
        assertNotNull(components);
        // one original + one boot component added
        assertEquals(2, components.size());

        // occurrences stripped from original component
        final Component original = components.get(0);
        final Evidence evidence = original.getEvidence();
        assertNotNull(evidence);
        assertNull(evidence.getOccurrences());

        // boot component added with correct fields
        final Component boot = components.get(1);
        assertEquals(Component.Type.LIBRARY, boot.getType());
        assertEquals(GROUP_ID, boot.getGroup());
        assertEquals(ARTIFACT_ID, boot.getName());
        assertEquals(VERSION, boot.getVersion());
        assertEquals(EXPECTED_PURL, boot.getPurl());
        assertEquals(EXPECTED_PURL, boot.getBomRef());
        assertNotNull(boot.getHashes());
        assertEquals(1, boot.getHashes().size());
        Hash hash = boot.getHashes().get(0);
        assertNotNull(hash.getValue());
        assertNotNull(hash.getAlgorithm());

        // Dependencies
        assertEquals(1, updated.getDependencies().size());
        Dependency productDep = updated.getDependencies().get(0);
        assertEquals(2, productDep.getDependencies().size());
        assertTrue(productDep.getDependencies().get(1).equals(new Dependency(boot.getBomRef())));
    }
}
