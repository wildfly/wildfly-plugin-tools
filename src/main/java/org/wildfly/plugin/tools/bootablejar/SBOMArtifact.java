/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.bootablejar;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

import org.jboss.galleon.universe.maven.MavenArtifact;

/**
 * Describes an artifact (e.g.: the Bootable JAR, the tool that builds it) as a component of the generated SBOM.
 * <p>
 * An artifact is identified by a <a href="https://github.com/package-url/purl-spec">package URL</a>. Artifacts that
 * have Maven coordinates are created with {@link #fromMaven(MavenArtifact)}, the others with
 * {@link #generic(String, String)}.
 * </p>
 */
public final class SBOMArtifact {

    private final String purl;
    private final String group;
    private final String name;
    private final String version;
    private final Path path;

    private SBOMArtifact(String purl, String group, String name, String version, Path path) {
        this.purl = Objects.requireNonNull(purl, "purl");
        this.group = group;
        this.name = Objects.requireNonNull(name, "name");
        this.version = version;
        this.path = path;
    }

    /**
     * Creates an artifact from Maven coordinates. The purl is {@code pkg:maven/<groupId>/<artifactId>@<version>}, the
     * version is omitted when it is {@code null}.
     *
     * @param artifact the Maven artifact, its path is used to compute the component hash when set
     *
     * @return the artifact
     */
    public static SBOMArtifact fromMaven(MavenArtifact artifact) {
        return fromMaven(artifact, null);
    }

    /**
     * Creates an artifact from Maven coordinates.
     *
     * @param artifact the Maven artifact, its path is used to compute the component hash when set
     * @param type     the optional {@code type} qualifier of the purl, can be {@code null}
     *
     * @return the artifact
     */
    public static SBOMArtifact fromMaven(MavenArtifact artifact, String type) {
        String purl = "pkg:maven/" + artifact.getGroupId()
                + "/" + artifact.getArtifactId();
        if (artifact.getVersion() != null) {
            purl += "@" + artifact.getVersion();
        }
        if (type != null) {
            purl += "?type=" + type;
        }
        return new SBOMArtifact(purl, artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
                artifact.getPath());
    }

    /**
     * Creates an artifact that has no Maven coordinates. The purl is {@code pkg:generic/<name>@<version>}.
     *
     * @param name    the name of the artifact
     * @param version the version of the artifact, can be {@code null}
     *
     * @return the artifact
     */
    public static SBOMArtifact generic(String name, String version) {
        return generic(name, version, null);
    }

    /**
     * Creates an artifact that has no Maven coordinates. The purl is {@code pkg:generic/<name>@<version>}, name and
     * version are percent-encoded.
     *
     * @param name    the name of the artifact
     * @param version the version of the artifact, can be {@code null}
     * @param path    the file of the artifact, used to compute the component hash, can be {@code null}
     *
     * @return the artifact
     */
    public static SBOMArtifact generic(String name, String version, Path path) {
        Objects.requireNonNull(name, "name");
        String purl = "pkg:generic/" + encode(name);
        if (version != null) {
            purl += "@" + encode(version);
        }
        return new SBOMArtifact(purl, null, name, version, path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A");
    }

    /**
     * @return the package URL that identifies the artifact
     */
    public String getPurl() {
        return purl;
    }

    /**
     * @return the group (Maven group id or purl namespace), can be {@code null}
     */
    public String getGroup() {
        return group;
    }

    /**
     * @return the name (Maven artifact id or purl name)
     */
    public String getName() {
        return name;
    }

    /**
     * @return the version, can be {@code null}
     */
    public String getVersion() {
        return version;
    }

    /**
     * @return the file of the artifact, can be {@code null}
     */
    public Path getPath() {
        return path;
    }

    @Override
    public String toString() {
        return purl;
    }
}
