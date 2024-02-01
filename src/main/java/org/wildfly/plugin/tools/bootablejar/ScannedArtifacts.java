/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.bootablejar;

import java.util.Set;

import org.jboss.galleon.universe.maven.MavenArtifact;

/**
 * Describes the artifacts required for the bootable JAR.
 *
 * @author jdenise
 */
public class ScannedArtifacts {

    private final MavenArtifact jbossModules;
    private final MavenArtifact boot;
    private final Set<MavenArtifact> cliArtifacts;

    /**
     * Creates a new scanned artifact description
     *
     * @param bootArtifact the boot artifact
     * @param jbossModules the JBoss Modules artifact
     * @param cliArtifacts the CLI artifact
     */
    public ScannedArtifacts(final MavenArtifact bootArtifact, final MavenArtifact jbossModules,
            final Set<MavenArtifact> cliArtifacts) {
        this.boot = bootArtifact;
        this.jbossModules = jbossModules;
        this.cliArtifacts = Set.copyOf(cliArtifacts);
    }

    /**
     * Returns the artifact used for booting.
     *
     * @return the boot artifact
     */
    public MavenArtifact getBoot() {
        return boot;
    }

    /**
     * Returns the JBoss Modules artifact.
     *
     * @return the JBoss Modules artifact
     */
    public MavenArtifact getJbossModules() {
        return jbossModules;
    }

    /**
     * Returns an immutable set of CLI artifacts. The artifacts themselves are mutable, however the set is not.
     *
     * @return the cliArtifacts
     */
    public Set<MavenArtifact> getCliArtifacts() {
        return cliArtifacts;
    }
}
