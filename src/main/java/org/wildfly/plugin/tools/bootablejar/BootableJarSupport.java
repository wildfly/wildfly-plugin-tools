/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.bootablejar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.xml.sax.SAXException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.api.GalleonBuilder;
import org.jboss.galleon.api.GalleonFeaturePackRuntime;
import org.jboss.galleon.api.GalleonPackageRuntime;
import org.jboss.galleon.api.GalleonProvisioningRuntime;
import org.jboss.galleon.api.Provisioning;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.ZipUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
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
    private static final String PACKAGE_ID_WILDFLY_CLI_SHADED_JAR = "org.wildfly.core.wildfly-cli.shaded";

    private static final String SBOM_DEFAULT_FILE_NAME_RADICAL = "sbom.cdx";
    private static final String SBOM_DEFAULT_XML_PATH = SBOM_DEFAULT_FILE_NAME_RADICAL + ".xml";
    private static final String SBOM_DEFAULT_JSON_PATH = SBOM_DEFAULT_FILE_NAME_RADICAL + ".json";
    private static final String SBOM_PATH_OPTION = "jboss-cyclonedx-output";

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
                            .build()) {
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
            packageSBOM(serverHome, contentRootDir, config.getOptions(), bootable);
            zipServer(serverHome, contentRootDir);
            buildJar(contentRootDir, targetJarFile, bootable, resolver);
        } finally {
            IoUtils.recursiveDelete(contentRootDir);
        }
    }

    /**
     * If an sbom file has been generated, it must copied over to the jar's META-INF directory.
     *
     * @param serverHome     Server home.
     * @param rootContentDir Jar root content dir.
     * @param galleonOptions Galleon provisioning options.
     */
    private static void packageSBOM(Path serverHome, Path rootContentDir, Map<String, String> galleonOptions, ScannedArtifacts boot)
            throws IOException, ProvisioningException {
        String filePath = galleonOptions == null ? null : galleonOptions.get(SBOM_PATH_OPTION);
        Path sbomFile = null;
        if (filePath == null) {
            Path defaultPath = serverHome.resolve(SBOM_DEFAULT_JSON_PATH);
            if (!Files.exists(defaultPath)) {
                defaultPath = serverHome.resolve(SBOM_DEFAULT_XML_PATH);
            }
            if (Files.exists(defaultPath)) {
                sbomFile = defaultPath;
            }
        } else {
            Path customPath = serverHome.resolve(filePath).normalize();
            if (Files.exists(customPath)) {
                sbomFile = customPath;
            } else {
                throw new ProvisioningException("Bootable JAR creation error. Custom SBOM file location " + filePath
                        + " set with " + SBOM_PATH_OPTION + " option is not found in the server installation.");
            }
        }
        if (sbomFile != null) {
            Path metaDir = rootContentDir.resolve("META-INF").resolve("sbom");
            Files.createDirectories(metaDir);
            adjustSBOM(sbomFile, metaDir.resolve(sbomFile.getFileName()), boot);
        }
    }

    public static void adjustSBOM(Path sbomFile, Path targetFile, ScannedArtifacts boot) throws IOException {
        final String purl = bootPurl(boot);
        final CdxComponent bootComponent = buildBootComponent(boot, purl);
        if (sbomFile.getFileName().toString().endsWith(".xml")) {
            adjustSBOMXml(sbomFile, targetFile, bootComponent, purl);
        } else {
            adjustSBOMJson(sbomFile, targetFile, bootComponent);
        }
    }

    /** Builds the format-agnostic boot component model. */
    private static CdxComponent buildBootComponent(ScannedArtifacts boot, String purl) {
        final CdxComponent.Method method = new CdxComponent.Method();
        method.setTechnique("manifest-analysis");
        method.setValue("maven-pom-analysis");

        final CdxComponent.Identity identity = new CdxComponent.Identity();
        identity.setField("purl");
        identity.setConfidence("1.0");
        identity.setMethods(List.of(method));

        final CdxComponent.Evidence evidence = new CdxComponent.Evidence();
        evidence.setIdentity(List.of(identity));

        final CdxComponent bootComponent = new CdxComponent();
        bootComponent.setType("library");
        bootComponent.setBomRef(purl);
        bootComponent.setGroup(boot.getBoot().getGroupId());
        bootComponent.setName(boot.getBoot().getArtifactId());
        bootComponent.setVersion(boot.getBoot().getVersion());
        bootComponent.setPurl(purl);
        bootComponent.setEvidence(evidence);
        return bootComponent;
    }

    private static String bootPurl(ScannedArtifacts boot) {
        return "pkg:maven/" + boot.getBoot().getGroupId()
                + "/" + boot.getBoot().getArtifactId()
                + "@" + boot.getBoot().getVersion();
    }

    private static void adjustSBOMJson(Path sbomFile, Path targetFile, CdxComponent bootComponent) throws IOException {
        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode root = mapper.readTree(sbomFile.toFile());
        final JsonNode components = root.get("components");
        if (components != null && components.isArray()) {
            ((ArrayNode) components).add(mapper.valueToTree(bootComponent));
            stripOccurrencesJson((ArrayNode) components);
        }
        mapper.writer().writeValue(targetFile.toFile(), root);
    }

    private static void adjustSBOMXml(Path sbomFile, Path targetFile, CdxComponent bootComponent, String purl)
            throws IOException {
        try {
            final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            final Document doc = dbf.newDocumentBuilder().parse(sbomFile.toFile());
            final String ns = doc.getDocumentElement().getNamespaceURI();

            // Find <components> as a direct child of the root <bom>
            Element componentsEl = null;
            final NodeList children = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                final org.w3c.dom.Node n = children.item(i);
                if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                        && "components".equals(n.getLocalName())) {
                    componentsEl = (Element) n;
                    break;
                }
            }

            if (componentsEl != null) {
                componentsEl.appendChild(buildBootComponentXml(doc, ns, bootComponent, purl));
                stripOccurrencesXml(componentsEl, ns);
            }

            final Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.transform(new DOMSource(doc), new StreamResult(targetFile.toFile()));
        } catch (ParserConfigurationException | SAXException | TransformerException e) {
            throw new IOException("Failed to process XML SBOM: " + sbomFile, e);
        }
    }

    /** Serialises a {@link CdxComponent} to a DOM element using the BOM namespace. */
    private static Element buildBootComponentXml(Document doc, String ns, CdxComponent c, String purl) {
        final Element component = createElement(doc, ns, "component");
        component.setAttribute("type", c.getType());
        component.setAttribute("bom-ref", purl);
        component.appendChild(createTextElement(doc, ns, "group", c.getGroup()));
        component.appendChild(createTextElement(doc, ns, "name", c.getName()));
        component.appendChild(createTextElement(doc, ns, "version", c.getVersion()));
        component.appendChild(createTextElement(doc, ns, "purl", c.getPurl()));

        final CdxComponent.Identity identity = c.getEvidence().getIdentity().get(0);
        final CdxComponent.Method method = identity.getMethods().get(0);

        final Element methodEl = createElement(doc, ns, "method");
        methodEl.appendChild(createTextElement(doc, ns, "technique", method.getTechnique()));
        methodEl.appendChild(createTextElement(doc, ns, "value", method.getValue()));

        final Element methodsEl = createElement(doc, ns, "methods");
        methodsEl.appendChild(methodEl);

        final Element identityEl = createElement(doc, ns, "identity");
        identityEl.appendChild(createTextElement(doc, ns, "field", identity.getField()));
        identityEl.appendChild(createTextElement(doc, ns, "confidence", identity.getConfidence()));
        identityEl.appendChild(methodsEl);

        final Element evidenceEl = createElement(doc, ns, "evidence");
        evidenceEl.appendChild(identityEl);
        component.appendChild(evidenceEl);
        return component;
    }

    private static void stripOccurrencesXml(Element componentsEl, String ns) {
        final NodeList components = componentsEl.getChildNodes();
        for (int i = 0; i < components.getLength(); i++) {
            final org.w3c.dom.Node n = components.item(i);
            if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
                continue;
            }
            final NodeList evidenceList = ((Element) n).getElementsByTagNameNS(ns, "evidence");
            for (int j = 0; j < evidenceList.getLength(); j++) {
                final Element evidence = (Element) evidenceList.item(j);
                final NodeList occurrencesList = evidence.getElementsByTagNameNS(ns, "occurrences");
                for (int k = occurrencesList.getLength() - 1; k >= 0; k--) {
                    evidence.removeChild(occurrencesList.item(k));
                }
            }
        }
    }

    private static Element createElement(Document doc, String ns, String localName) {
        return ns != null ? doc.createElementNS(ns, localName) : doc.createElement(localName);
    }

    private static Element createTextElement(Document doc, String ns, String localName, String text) {
        final Element el = createElement(doc, ns, localName);
        el.setTextContent(text);
        return el;
    }

    private static void stripOccurrencesJson(ArrayNode components) {
        for (JsonNode component : components) {
            if (!component.isObject()) {
                continue;
            }
            final ObjectNode comp = (ObjectNode) component;
            final JsonNode evidence = comp.get("evidence");
            if (evidence == null || !evidence.isObject()) {
                continue;
            }
            ((ObjectNode) evidence).remove("occurrences");
            final JsonNode nested = comp.get("components");
            if (nested != null && nested.isArray()) {
                stripOccurrencesJson((ArrayNode) nested);
            }
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
    public static ScannedArtifacts scanArtifacts(final Provisioning pm, final GalleonProvisioningConfig config,
            final MessageWriter writer) throws ProvisioningException {
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
                GalleonPackageRuntime shadedModelpackage = fprt.getGalleonPackage(PACKAGE_ID_WILDFLY_CLI_SHADED_JAR);
                if (shadedModelpackage != null) {
                    Path shadedModelFile = shadedModelpackage.getResource("pm", "wildfly", "shaded", "shaded-model.xml");
                    cliArtifacts.addAll(getArtifacts(shadedModelFile, propsMap));
                }
                for (Map.Entry<String, String> entry : propsMap.entrySet()) {
                    String value = entry.getValue();
                    MavenArtifact a = parseArtifact(value);
                    if (cliArtifacts.isEmpty()) {
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
    public static void buildJar(final Path contentDir, final Path jarFile, final ScannedArtifacts bootable,
            final MavenRepoManager resolver)
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

    private static List<MavenArtifact> getArtifacts(Path shadedModel, Map<String, String> propsMap)
            throws ProvisioningException {
        Element rootElement;
        try (InputStream srcInput = Files.newInputStream(shadedModel)) {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(srcInput);
            rootElement = document.getDocumentElement();
        } catch (Exception ex) {
            throw new ProvisioningException(ex);
        }
        List<MavenArtifact> artifacts = new ArrayList<>();
        NodeList shadedDependencies = rootElement.getElementsByTagName("dependency");
        for (int i = 0; i < shadedDependencies.getLength(); i++) {
            Node n = shadedDependencies.item(i);
            if (n instanceof Element) {
                Element e = (Element) n;
                MavenArtifact ma = parseArtifact(e.getTextContent());
                StringBuilder keyBuilder = new StringBuilder();
                // groupId
                keyBuilder.append(ma.getGroupId()).append(":");
                // artifactId
                keyBuilder.append(ma.getArtifactId());
                // classifier
                if (ma.getClassifier() != null && !ma.getClassifier().isEmpty()) {
                    keyBuilder.append("::").append(ma.getClassifier());
                }
                String withVersion = propsMap.get(keyBuilder.toString());
                artifacts.add(parseArtifact(withVersion));
            }
        }
        return artifacts;
    }
}
