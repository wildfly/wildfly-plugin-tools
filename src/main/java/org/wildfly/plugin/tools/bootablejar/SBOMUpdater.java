/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.bootablejar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.cyclonedx.exception.GeneratorException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Dependency;
import org.cyclonedx.model.Evidence;
import org.cyclonedx.model.Hash;
import org.cyclonedx.model.component.evidence.Occurrence;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenArtifact;

import dev.cyberstamp.maven.assembly.sbom.BomEditor;
import dev.cyberstamp.maven.assembly.sbom.BomMerger;
import dev.cyberstamp.maven.assembly.sbom.BomReader;

/**
 *
 * @author jdenise
 */
class SBOMUpdater {

    private static final String SBOM_DEFAULT_FILE_NAME_RADICAL = "sbom.cdx";
    private static final String SBOM_DEFAULT_XML_PATH = SBOM_DEFAULT_FILE_NAME_RADICAL + ".xml";
    private static final String SBOM_DEFAULT_JSON_PATH = SBOM_DEFAULT_FILE_NAME_RADICAL + ".json";
    private static final String SBOM_PATH_OPTION = "jboss-cyclonedx-output";
    private static final String SBOM_COMPRESS_OPTION = "jboss-cyclonedx-compress";
    private static final String SBOM_COMPRESS_EXTENSION = ".gz";
    private static final String HASH_ALGORITHM = "SHA-256";

    /**
     * If an sbom file has been generated, it must copied over to the jar's META-INF directory.
     *
     * @param serverHome     Server home.
     * @param rootContentDir Jar root content dir.
     * @param galleonOptions Galleon provisioning options.
     */
    static void packageSBOM(Path serverHome, Path rootContentDir, Map<String, String> galleonOptions,
            ScannedArtifacts boot, Map<String, SBOMArtifact> deployments, SBOMArtifact bootableJar,
            SBOMArtifact toolArtifact, Path outputFileDirectory, boolean aggregateSBOM)
            throws IOException, ProvisioningException {
        String filePath = galleonOptions == null ? null : galleonOptions.get(SBOM_PATH_OPTION);
        boolean compressed = true;
        if (galleonOptions != null && galleonOptions.containsKey(SBOM_COMPRESS_OPTION)) {
            compressed = Boolean.parseBoolean(galleonOptions.get(SBOM_COMPRESS_OPTION));
        }
        String extension = compressed ? SBOM_COMPRESS_EXTENSION : "";
        Path sbomFile = null;
        if (filePath == null) {
            Path defaultPath = serverHome.resolve(SBOM_DEFAULT_JSON_PATH + extension);
            if (!Files.exists(defaultPath)) {
                defaultPath = serverHome.resolve(SBOM_DEFAULT_XML_PATH + extension);
            }
            if (Files.exists(defaultPath)) {
                sbomFile = defaultPath;
            }
        } else {
            filePath += extension;
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
            if (compressed) {
                String fileName = sbomFile.getFileName().toString().substring(0,
                        sbomFile.getFileName().toString().length() - SBOM_COMPRESS_EXTENSION.length());
                Path unzippedFile = rootContentDir.resolve(fileName);
                unzip(sbomFile, unzippedFile);
                sbomFile = unzippedFile;
            }
            Path targetFile = metaDir.resolve(sbomFile.getFileName());
            if (aggregateSBOM) {
                adjustSBOM(sbomFile, targetFile, boot, bootableJar, deployments, toolArtifact);
            } else {
                adjustSBOM(sbomFile, targetFile, boot, null, Map.of(), null);
            }
            if (outputFileDirectory != null) {
                Files.copy(targetFile, outputFileDirectory.resolve(targetFile.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            if (compressed) {
                final Path gzPath = targetFile
                        .resolveSibling(targetFile.getFileName().toString() + SBOM_COMPRESS_EXTENSION);
                try {
                    try (InputStream in = Files.newInputStream(targetFile);
                            OutputStream out = new GZIPOutputStream(Files.newOutputStream(gzPath))) {
                        in.transferTo(out);
                    }
                } finally {
                    Files.deleteIfExists(targetFile);
                    Files.deleteIfExists(sbomFile);
                }
            }
        }
    }

    static void adjustSBOM(Path original, Path target, ScannedArtifacts boot, SBOMArtifact bootableJar,
            Map<String, SBOMArtifact> deployments, SBOMArtifact toolArtifact)
            throws ProvisioningException, IOException {
        try {
            BomEditor.load(original)
                    .mutate(bom -> updateBom(bom, boot.getBoot(), bootableJar, deployments, toolArtifact))
                    .writeTo(target);
        } catch (GeneratorException ex) {
            throw new ProvisioningException(ex);
        }
    }

    private static void updateBom(Bom bom, MavenArtifact artifact, SBOMArtifact bootableJar,
            Map<String, SBOMArtifact> deployments, SBOMArtifact toolArtifact) {
        String productRef = bom.getMetadata().getComponent().getBomRef();
        Dependency productDep = new Dependency(productRef);

        if (bootableJar != null) {
            Component toolComponent = buildComponent(toolArtifact, Component.Type.APPLICATION);
            if (bom.getMetadata().getToolChoice() == null) {
                bom.getMetadata().setToolChoice(new org.cyclonedx.model.metadata.ToolInformation());
            }
            if (bom.getMetadata().getToolChoice().getComponents() == null) {
                bom.getMetadata().getToolChoice().setComponents(new java.util.ArrayList<>());
            }
            bom.getMetadata().getToolChoice().getComponents().add(toolComponent);
            Component serverComponent = bom.getMetadata().getComponent();
            serverComponent.setType(Component.Type.FRAMEWORK);
            serverComponent.setScope(Component.Scope.EXCLUDED);
            bom.addComponent(serverComponent);

            Component jarComponent = buildComponent(bootableJar, Component.Type.APPLICATION);
            bom.getMetadata().setComponent(jarComponent);
            Dependency bootableJarDependency = new Dependency(jarComponent.getBomRef());
            bootableJarDependency.addDependency(productDep);
            bom.addDependency(bootableJarDependency);

            if (deployments != null) {
                for (SBOMArtifact d : deployments.values()) {
                    Component dComponent = buildComponent(d, Component.Type.APPLICATION);
                    bom.addComponent(dComponent);
                    bootableJarDependency.addDependency(new Dependency(dComponent.getBomRef()));
                    Bom embedded = scanForEmbeddedSbom(d.getPath());
                    if (embedded == null) {
                        continue;
                    }
                    BomMerger.mergeUnder(bom, dComponent.getBomRef(), embedded);
                }
            }
        }

        Component component = buildComponent(artifact);
        bom.addComponent(component);
        for (Dependency dep : bom.getDependencies()) {
            if (productDep.equals(dep)) {
                dep.addDependency(new Dependency(component.getBomRef()));
            }
        }

        // Prefix all occurrences with wildfly.zip!
        prefixOccurrences(bom.getComponents());
    }

    private static void prefixOccurrences(List<Component> components) {
        if (components == null) {
            return;
        }
        for (Component c : components) {
            Evidence evidence = c.getEvidence();
            if (evidence != null && evidence.getOccurrences() != null) {
                for (Occurrence occ : evidence.getOccurrences()) {
                    if (occ.getLocation() != null && !occ.getLocation().startsWith("wildfly.zip!")) {
                        occ.setLocation("wildfly.zip!" + occ.getLocation());
                    }
                }
            }
            prefixOccurrences(c.getComponents());
        }
    }

    private static String computeHash(Path file) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            return null;
        }
    }

    private static Component buildComponent(MavenArtifact artifact) {
        return buildComponent(artifact, Component.Type.LIBRARY, null);
    }

    private static Component buildComponent(MavenArtifact artifact, Component.Type type, String extension) {
        return buildComponent(SBOMArtifact.fromMaven(artifact, extension), type);
    }

    private static Component buildComponent(SBOMArtifact artifact, Component.Type type) {
        Component comp = new Component();
        comp.setType(type);
        comp.setBomRef(artifact.getPurl());
        comp.setGroup(artifact.getGroup());
        comp.setName(artifact.getName());
        comp.setVersion(artifact.getVersion());
        comp.setPurl(artifact.getPurl());
        if (artifact.getPath() != null) {
            String hash = computeHash(artifact.getPath());
            if (hash != null) {
                comp.setHashes(List.of(new Hash(HASH_ALGORITHM, hash)));
            }
        }
        return comp;
    }

    private static Bom scanForEmbeddedSbom(Path jarPath) {
        if (jarPath == null || !Files.exists(jarPath)) {
            return null;
        }
        try (FileSystem fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
            Path root = fs.getPath("/META-INF/sbom");
            if (!Files.exists(root)) {
                return null;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                return walk
                        .filter(p -> {
                            String name = p.getFileName() == null ? "" : p.getFileName().toString();
                            return name.endsWith(".cdx.json") || name.endsWith(".cdx.xml");
                        })
                        .findFirst()
                        .map(p -> {
                            try (InputStream is = Files.newInputStream(p)) {
                                return BomReader.readBom(is);
                            } catch (Exception e) {
                                return null;
                            }
                        })
                        .orElse(null);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static void unzip(Path gzippedFile, Path outputFile) throws IOException {
        try (InputStream fis = Files.newInputStream(gzippedFile);
                GZIPInputStream gzis = new GZIPInputStream(fis);
                OutputStream fos = Files.newOutputStream(outputFile)) {
            gzis.transferTo(fos);
        }
    }
}
