/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.plugin.tools;

import java.io.IOException;

import org.jboss.as.controller.client.ModelControllerClient;
import org.wildfly.common.Assert;

/**
 * Information about the running container.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public interface ContainerDescription {

    /**
     * Returns the name of the product.
     *
     * @return the name of the product
     */
    String getProductName();

    /**
     * Returns the product version, if defined, or {@code null} if the product version was not defined.
     *
     * @return the product version or {@code null} if not defined
     */
    String getProductVersion();

    /**
     * Returns the release version, if defined, or {@code null} if the release version was not defined.
     * <p>
     * Note that in WildFly 9+ this is usually the version for WildFly Core. In WildFly 8 this is the full version.
     * </p>
     *
     * @return the release version or {@code null} if not defined
     */
    String getReleaseVersion();

    /**
     * Returns the root model version.
     *
     * @return the model version
     */
    default ModelVersion getModelVersion() {
        return ModelVersion.DEFAULT;
    }

    /**
     * Returns the type of the server that was launched.
     *
     * @return the type of the server that was launched or {@code null} if not defined
     */
    String getLaunchType();

    /**
     * Checks if the server is a managed domain server.
     *
     * @return {@code true} if this is a managed domain, otherwise {@code false}
     */
    boolean isDomain();


    /**
     * Queries the running container and attempts to lookup the information from the running container.
     *
     * @param client the client used to execute the management operation
     *
     * @return the container description
     *
     * @throws IOException                 if an error occurs communicating with the server
     * @throws OperationExecutionException if the operation used to query the container fails
     */
    static ContainerDescription lookup(final ModelControllerClient client)
            throws IOException, OperationExecutionException {
        return DefaultContainerDescription.lookup(Assert.checkNotNullParam("client", client));
    }

    /**
     * Describes the model version.
     */
    final class ModelVersion implements Comparable<ModelVersion> {
        static final ModelVersion DEFAULT = new ModelVersion(0, 0, 0);

        private final int major;
        private final int minor;
        private final int micro;

        ModelVersion(final int major, final int minor, final int micro) {
            this.major = major;
            this.minor = minor;
            this.micro = micro;
        }

        /**
         * The major version of the model.
         *
         * @return the major version
         */
        public int major() {
            return major;
        }

        /**
         * The minor version of the model.
         *
         * @return the minor version
         */
        public int minor() {
            return minor;
        }

        /**
         * THe micro version of the model.
         *
         * @return the micro version
         */
        public int micro() {
            return micro;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = major;
            result = prime * result + minor;
            result = prime * result + micro;
            return result;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ModelVersion)) {
                return false;
            }
            final ModelVersion other = (ModelVersion) obj;
            return major == other.major && minor == other.minor && micro == other.micro;
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + micro;
        }

        @Override
        public int compareTo(final ModelVersion o) {
            int result = Integer.compare(major, o.major);
            result = (result == 0) ? Integer.compare(minor, o.major) : result;
            result = (result == 0) ? Integer.compare(micro, o.micro) : result;
            return result;
        }
    }
}
