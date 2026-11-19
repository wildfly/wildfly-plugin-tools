/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.bootablejar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.jboss.galleon.universe.maven.MavenArtifact;
import org.junit.jupiter.api.Test;

class SBOMArtifactTestCase {

    @Test
    void maven() {
        MavenArtifact ma = new MavenArtifact();
        ma.setGroupId("com.acme");
        ma.setArtifactId("app");
        ma.setVersion("1.0");
        SBOMArtifact a = SBOMArtifact.fromMaven(ma);
        assertEquals("pkg:maven/com.acme/app@1.0", a.getPurl());
        assertEquals("com.acme", a.getGroup());
        assertEquals("app", a.getName());
        assertEquals("1.0", a.getVersion());
        assertEquals("pkg:maven/com.acme/app@1.0?type=war", SBOMArtifact.fromMaven(ma, "war").getPurl());
    }

    @Test
    void mavenNullVersion() {
        MavenArtifact ma = new MavenArtifact();
        ma.setGroupId("com.acme");
        ma.setArtifactId("app");
        SBOMArtifact a = SBOMArtifact.fromMaven(ma);
        assertEquals("pkg:maven/com.acme/app", a.getPurl());
        assertNull(a.getVersion());
    }

    @Test
    void generic() {
        SBOMArtifact a = SBOMArtifact.generic("wildfly-glow", "2.2.1.Final");
        assertEquals("pkg:generic/wildfly-glow@2.2.1.Final", a.getPurl());
        assertNull(a.getGroup());
        assertEquals("wildfly-glow", a.getName());
        assertEquals("2.2.1.Final", a.getVersion());
    }

    @Test
    void genericEncoding() {
        SBOMArtifact a = SBOMArtifact.generic("my app*.war", "1.0+b1");
        assertEquals("pkg:generic/my%20app%2A.war@1.0%2Bb1", a.getPurl());
        assertEquals("my app*.war", a.getName());
        assertEquals("1.0+b1", a.getVersion());
        assertNull(SBOMArtifact.generic("app.war", null).getVersion());
        assertEquals("pkg:generic/app.war", SBOMArtifact.generic("app.war", null).getPurl());
    }
}
