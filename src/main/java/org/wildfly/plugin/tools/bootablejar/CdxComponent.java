/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools.bootablejar;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Minimal CycloneDX component POJO used when injecting the bootable-jar boot
 * component into the provisioned SBOM.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
class CdxComponent {

    private String type;
    @JsonProperty("bom-ref")
    private String bomRef;
    private String group;
    private String name;
    private String version;
    private String purl;
    private Evidence evidence;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getBomRef() { return bomRef; }
    public void setBomRef(String bomRef) { this.bomRef = bomRef; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getPurl() { return purl; }
    public void setPurl(String purl) { this.purl = purl; }

    public Evidence getEvidence() { return evidence; }
    public void setEvidence(Evidence evidence) { this.evidence = evidence; }

    // -------------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class Evidence {
        private List<Identity> identity;

        public List<Identity> getIdentity() { return identity; }
        public void setIdentity(List<Identity> identity) { this.identity = identity; }
    }

    // -------------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class Identity {
        private String field;
        private String confidence;
        private List<Method> methods;

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }

        public String getConfidence() { return confidence; }
        public void setConfidence(String confidence) { this.confidence = confidence; }

        public List<Method> getMethods() { return methods; }
        public void setMethods(List<Method> methods) { this.methods = methods; }
    }

    // -------------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class Method {
        private String technique;
        private String value;

        public String getTechnique() { return technique; }
        public void setTechnique(String technique) { this.technique = technique; }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
}
