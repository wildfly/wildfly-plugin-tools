/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.bootablejar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link BootableJarSupport#adjustSBOM}.
 */
class AdjustSBOMTest {

    private static final String GROUP_ID = "org.wildfly.core";
    private static final String ARTIFACT_ID = "wildfly-jar-boot";
    private static final String VERSION = "1.0.0.Final";
    private static final String EXPECTED_PURL =
            "pkg:maven/" + GROUP_ID + "/" + ARTIFACT_ID + "@" + VERSION;

    // Minimal CycloneDX JSON SBOM with one component that has evidence/occurrences
    private static final String JSON_SBOM =
            "{\n" +
            "  \"bomFormat\": \"CycloneDX\",\n" +
            "  \"specVersion\": \"1.6\",\n" +
            "  \"components\": [\n" +
            "    {\n" +
            "      \"type\": \"library\",\n" +
            "      \"name\": \"some-lib\",\n" +
            "      \"version\": \"1.0\",\n" +
            "      \"purl\": \"pkg:maven/com.example/some-lib@1.0\",\n" +
            "      \"evidence\": {\n" +
            "        \"occurrences\": [\n" +
            "          { \"location\": \"modules/com/example/main/some-lib-1.0.jar\" }\n" +
            "        ],\n" +
            "        \"identity\": [\n" +
            "          { \"field\": \"purl\", \"confidence\": \"1.0\" }\n" +
            "        ]\n" +
            "      }\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    // Minimal CycloneDX XML SBOM with one component that has evidence/occurrences
    private static final String XML_SBOM =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<bom xmlns=\"http://cyclonedx.org/schema/bom/1.6\" version=\"1\">\n" +
            "  <components>\n" +
            "    <component type=\"library\">\n" +
            "      <name>some-lib</name>\n" +
            "      <version>1.0</version>\n" +
            "      <purl>pkg:maven/com.example/some-lib@1.0</purl>\n" +
            "      <evidence>\n" +
            "        <occurrences>\n" +
            "          <occurrence>\n" +
            "            <location>modules/com/example/main/some-lib-1.0.jar</location>\n" +
            "          </occurrence>\n" +
            "        </occurrences>\n" +
            "        <identity>\n" +
            "          <field>purl</field>\n" +
            "          <confidence>1.0</confidence>\n" +
            "        </identity>\n" +
            "      </evidence>\n" +
            "    </component>\n" +
            "  </components>\n" +
            "</bom>";

    private ScannedArtifacts bootArtifacts() {
        final MavenArtifact boot = new MavenArtifact();
        boot.setGroupId(GROUP_ID);
        boot.setArtifactId(ARTIFACT_ID);
        boot.setVersion(VERSION);
        boot.setExtension("jar");
        final MavenArtifact jbossModules = new MavenArtifact();
        jbossModules.setGroupId("org.jboss.modules");
        jbossModules.setArtifactId("jboss-modules");
        jbossModules.setVersion("2.0.0.Final");
        jbossModules.setExtension("jar");
        return new ScannedArtifacts(boot, jbossModules, Set.of());
    }

    @Test
    void adjustSBOMJson(@TempDir Path tmpDir) throws Exception {
        final Path source = tmpDir.resolve("sbom.cdx.json");
        final Path target = tmpDir.resolve("sbom-adjusted.cdx.json");
        Files.writeString(source, JSON_SBOM);

        BootableJarSupport.adjustSBOM(source, target, bootArtifacts());

        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode root = mapper.readTree(target.toFile());

        // original top-level fields preserved
        assertEquals("CycloneDX", root.get("bomFormat").asText());

        final JsonNode components = root.get("components");
        assertNotNull(components);

        // one original + one boot component added
        assertEquals(2, components.size());

        // occurrences stripped from original component
        final JsonNode original = components.get(0);
        final JsonNode evidence = original.get("evidence");
        assertNotNull(evidence);
        assertFalse(evidence.has("occurrences"), "occurrences should have been removed");
        // identity preserved
        assertNotNull(evidence.get("identity"));

        // boot component added with correct fields
        final JsonNode boot = components.get(1);
        assertEquals("library", boot.get("type").asText());
        assertEquals(GROUP_ID, boot.get("group").asText());
        assertEquals(ARTIFACT_ID, boot.get("name").asText());
        assertEquals(VERSION, boot.get("version").asText());
        assertEquals(EXPECTED_PURL, boot.get("purl").asText());
        assertEquals(EXPECTED_PURL, boot.get("bom-ref").asText());

        // boot component has evidence/identity with method
        final JsonNode bootEvidence = boot.get("evidence");
        assertNotNull(bootEvidence);
        final JsonNode bootIdentity = bootEvidence.get("identity").get(0);
        assertEquals("purl", bootIdentity.get("field").asText());
        assertEquals("manifest-analysis", bootIdentity.get("methods").get(0).get("technique").asText());
    }

    @Test
    void adjustSBOMXml(@TempDir Path tmpDir) throws Exception {
        final Path source = tmpDir.resolve("sbom.cdx.xml");
        final Path target = tmpDir.resolve("sbom-adjusted.cdx.xml");
        Files.writeString(source, XML_SBOM);

        BootableJarSupport.adjustSBOM(source, target, bootArtifacts());

        final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        final Document doc = dbf.newDocumentBuilder().parse(target.toFile());
        final String ns = "http://cyclonedx.org/schema/bom/1.6";

        // root element preserved
        assertEquals("bom", doc.getDocumentElement().getLocalName());
        assertEquals(ns, doc.getDocumentElement().getNamespaceURI());

        final NodeList componentsList = doc.getElementsByTagNameNS(ns, "component");

        // one original + one boot component
        assertEquals(2, componentsList.getLength());

        // occurrences stripped from original component
        final Element original = (Element) componentsList.item(0);
        final NodeList evidenceList = original.getElementsByTagNameNS(ns, "evidence");
        assertEquals(1, evidenceList.getLength());
        final Element evidence = (Element) evidenceList.item(0);
        assertEquals(0, evidence.getElementsByTagNameNS(ns, "occurrences").getLength(),
                "occurrences should have been removed");
        // identity preserved
        assertEquals(1, evidence.getElementsByTagNameNS(ns, "identity").getLength());

        // boot component added with correct fields
        final Element boot = (Element) componentsList.item(1);
        assertEquals("library", boot.getAttribute("type"));
        assertEquals(EXPECTED_PURL, boot.getAttribute("bom-ref"));
        assertEquals(GROUP_ID, boot.getElementsByTagNameNS(ns, "group").item(0).getTextContent());
        assertEquals(ARTIFACT_ID, boot.getElementsByTagNameNS(ns, "name").item(0).getTextContent());
        assertEquals(VERSION, boot.getElementsByTagNameNS(ns, "version").item(0).getTextContent());
        assertEquals(EXPECTED_PURL, boot.getElementsByTagNameNS(ns, "purl").item(0).getTextContent());

        // boot component has evidence/identity/methods
        final NodeList bootEvidence = boot.getElementsByTagNameNS(ns, "evidence");
        assertEquals(1, bootEvidence.getLength());
        final Element bootIdentityEl = (Element) ((Element) bootEvidence.item(0))
                .getElementsByTagNameNS(ns, "identity").item(0);
        assertNotNull(bootIdentityEl);
        assertEquals("purl", bootIdentityEl.getElementsByTagNameNS(ns, "field").item(0).getTextContent());
        assertEquals("manifest-analysis",
                ((Element) bootIdentityEl.getElementsByTagNameNS(ns, "method").item(0))
                        .getElementsByTagNameNS(ns, "technique").item(0).getTextContent());
    }
}
