/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jboss.galleon.Constants;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.api.ConfigurationId;
import org.jboss.galleon.api.GalleonBuilder;
import org.jboss.galleon.api.GalleonFeaturePack;
import org.jboss.galleon.api.Provisioning;
import org.jboss.galleon.api.config.GalleonConfigurationWithLayersBuilder;
import org.jboss.galleon.api.config.GalleonFeaturePackConfig;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.jboss.galleon.util.IoUtils;

/**
 * Utilities for provisioning a server with Galleon.
 *
 * @author jdenise
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@SuppressWarnings("unused")
public class GalleonUtils {

    private static final String WILDFLY_DEFAULT_FEATURE_PACK_LOCATION = "wildfly@maven(org.jboss.universe:community-universe)";
    private static final String STANDALONE = "standalone";
    private static final String SERVER_CONFIG_PROPERTY = "--server-config";

    /**
     * Galleon provisioning of a default server.
     *
     * @param jbossHome           server installation directory
     * @param featurePackLocation the location of the feature pack
     * @param version             server version, if null latest is used
     * @param artifactResolver    artifact resolver used by Galleon
     *
     * @throws ProvisioningException if there is an error provisioning the server
     */
    public static void provision(final Path jbossHome, final String featurePackLocation, final String version,
            final MavenRepoManager artifactResolver) throws ProvisioningException {
        final GalleonProvisioningConfig config = buildDefaultConfig(featurePackLocation, version);
        try (
                Provisioning pm = new GalleonBuilder().addArtifactResolver(artifactResolver)
                        .newProvisioningBuilder(config)
                        .setInstallationHome(jbossHome)
                        .build()) {
            pm.provision(config);
        }
    }

    /**
     * Build a default WildFly provisioning config.
     *
     * @return the default WildFly configuration
     *
     * @throws ProvisioningException if an error occurs creating the configuration
     */
    public static GalleonProvisioningConfig buildDefaultConfig() throws ProvisioningException {
        return buildDefaultConfig(WILDFLY_DEFAULT_FEATURE_PACK_LOCATION, null);
    }

    /**
     * Build a default server provisioning config.
     *
     * @param featurePackLocation the feature pack location
     * @param version             server version, if null latest is used.
     *
     * @return the default configuration for the feature pack location
     *
     * @throws ProvisioningException if an error occurs creating the configuration
     */
    public static GalleonProvisioningConfig buildDefaultConfig(final String featurePackLocation, final String version)
            throws ProvisioningException {
        final String location = getFeaturePackLocation(featurePackLocation, version);
        final GalleonProvisioningConfig.Builder state = GalleonProvisioningConfig.builder();
        final GalleonFeaturePackConfig.Builder fp = GalleonFeaturePackConfig.builder(FeaturePackLocation.fromString(location));
        fp.setInheritConfigs(true);
        fp.setInheritPackages(true);
        state.addFeaturePackDep(fp.build());
        state.addOptions(Map.of("jboss-fork-embedded", "true"));
        return state.build();
    }

    /**
     * Build a Galleon provisioning configuration.
     *
     * @param pm                   The Galleon provisioning runtime
     * @param featurePacks         The list of feature-packs
     * @param layers               Layers to include
     * @param excludedLayers       Layers to exclude
     * @param pluginOptions        Galleon plugin options
     * @param layersConfigFileName The name of the configuration generated from layers
     *
     * @return the provisioning config
     *
     * @throws ProvisioningException if an error occurs creating the configuration
     */
    public static GalleonProvisioningConfig buildConfig(final GalleonBuilder pm,
            final List<GalleonFeaturePack> featurePacks,
            final List<String> layers,
            final List<String> excludedLayers,
            final Map<String, String> pluginOptions,
            final String layersConfigFileName) throws ProvisioningException, IllegalArgumentException {
        return buildConfig(pm, featurePacks, layers, excludedLayers, pluginOptions, layersConfigFileName, layersConfigFileName);
    }

    /**
     * Build a Galleon provisioning configuration.
     *
     * @param pm                        The Galleon provisioning runtime
     * @param featurePacks              The list of feature-packs
     * @param layers                    Layers to include
     * @param excludedLayers            Layers to exclude
     * @param pluginOptions             Galleon plugin options
     * @param layersConfigFileName      The name of the configuration generated from layers
     * @param provisionedConfigFileName The name of the provisioned configuration file name
     *
     * @return the provisioning config
     *
     * @throws ProvisioningException if an error occurs creating the configuration
     */
    public static GalleonProvisioningConfig buildConfig(final GalleonBuilder pm,
            final List<GalleonFeaturePack> featurePacks,
            final List<String> layers,
            final List<String> excludedLayers,
            final Map<String, String> pluginOptions,
            final String layersConfigFileName,
            final String provisionedConfigFileName)
            throws ProvisioningException, IllegalArgumentException {
        final GalleonProvisioningConfig.Builder state = GalleonProvisioningConfig.builder();
        final boolean hasLayers = !layers.isEmpty();
        boolean fpWithDefaults = true;
        if (!hasLayers) {
            // Check if we have all feature-packs with default values only.
            for (GalleonFeaturePack fp : featurePacks) {
                if (fp.isInheritConfigs() != null ||
                        fp.isInheritPackages() != null ||
                        !fp.getIncludedConfigs().isEmpty() ||
                        !fp.getExcludedConfigs().isEmpty() ||
                        fp.isTransitive() ||
                        !fp.getExcludedPackages().isEmpty() ||
                        !fp.getIncludedPackages().isEmpty()) {
                    fpWithDefaults = false;
                    break;
                }
            }
        }

        for (GalleonFeaturePack fp : featurePacks) {
            if (fp.getLocation() == null && (fp.getGroupId() == null || fp.getArtifactId() == null)
                    && fp.getNormalizedPath() == null) {
                throw new IllegalArgumentException("Feature-pack location, Maven GAV or feature pack path is missing");
            }

            final FeaturePackLocation fpl;
            if (fp.getNormalizedPath() != null) {
                fpl = pm.addLocal(fp.getNormalizedPath(), false);
            } else if (fp.getGroupId() != null && fp.getArtifactId() != null) {
                final String coords = getMavenCoords(fp);
                fpl = FeaturePackLocation.fromString(coords);
            } else {
                // Special case for G:A that conflicts with producer:channel that we can't have in the plugin.
                String location = fp.getLocation();
                if (!FeaturePackLocation.fromString(location).hasUniverse()) {
                    long numSeparators = location.chars().filter(ch -> ch == ':').count();
                    if (numSeparators <= 1) {
                        location += ":";
                    }
                }
                fpl = FeaturePackLocation.fromString(location);
            }

            final GalleonFeaturePackConfig.Builder fpConfig = fp.isTransitive()
                    ? GalleonFeaturePackConfig.transitiveBuilder(fpl)
                    : GalleonFeaturePackConfig.builder(fpl);
            if (fp.isInheritConfigs() == null) {
                if (hasLayers) {
                    fpConfig.setInheritConfigs(false);
                } else {
                    if (fpWithDefaults) {
                        fpConfig.setInheritConfigs(true);
                    }
                }
            } else {
                fpConfig.setInheritConfigs(fp.isInheritConfigs());
            }

            if (fp.isInheritPackages() == null) {
                if (hasLayers) {
                    fpConfig.setInheritPackages(false);
                } else {
                    if (fpWithDefaults) {
                        fpConfig.setInheritConfigs(true);
                    }
                }
            } else {
                fpConfig.setInheritPackages(fp.isInheritPackages());
            }

            if (!fp.getExcludedConfigs().isEmpty()) {
                for (ConfigurationId configId : fp.getExcludedConfigs()) {
                    if (configId.isModelOnly()) {
                        fpConfig.excludeConfigModel(configId.getId().getModel());
                    } else {
                        fpConfig.excludeDefaultConfig(configId.getId());
                    }
                }
            }
            if (!fp.getIncludedConfigs().isEmpty()) {
                for (ConfigurationId configId : fp.getIncludedConfigs()) {
                    if (configId.isModelOnly()) {
                        fpConfig.includeConfigModel(configId.getId().getModel());
                    } else {
                        fpConfig.includeDefaultConfig(configId.getId());
                    }
                }
            }

            if (!fp.getIncludedPackages().isEmpty()) {
                for (String includedPackage : fp.getIncludedPackages()) {
                    fpConfig.includePackage(includedPackage);
                }
            }
            if (!fp.getExcludedPackages().isEmpty()) {
                for (String excludedPackage : fp.getExcludedPackages()) {
                    fpConfig.excludePackage(excludedPackage);
                }
            }

            state.addFeaturePackDep(fpConfig.build());
        }
        final Map<String, String> copiedOptions = new LinkedHashMap<>(pluginOptions);

        if (!layers.isEmpty()) {
            GalleonConfigurationWithLayersBuilder config = GalleonConfigurationWithLayersBuilder.builder(STANDALONE,
                    layersConfigFileName);
            for (String l : layers) {
                config.includeLayer(l);
            }
            for (String l : excludedLayers) {
                config.excludeLayer(l);
            }
            if (!layersConfigFileName.equals(provisionedConfigFileName)) {
                config.setProperty(SERVER_CONFIG_PROPERTY, provisionedConfigFileName);
            }
            state.addConfig(config.build());
            if (pluginOptions.isEmpty()) {
                copiedOptions.put(Constants.OPTIONAL_PACKAGES, Constants.PASSIVE_PLUS);
            } else if (!copiedOptions.containsKey(Constants.OPTIONAL_PACKAGES)) {
                copiedOptions.put(Constants.OPTIONAL_PACKAGES, Constants.PASSIVE_PLUS);
            }
        }

        state.addOptions(copiedOptions);

        return state.build();
    }

    private static String getMavenCoords(GalleonFeaturePack fp) {
        final StringBuilder builder = new StringBuilder();
        builder.append(fp.getGroupId()).append(":").append(fp.getArtifactId());
        final String type = fp.getExtension() == null ? fp.getType() : fp.getExtension();
        if (fp.getClassifier() != null || type != null) {
            builder.append(":").append(fp.getClassifier() == null ? "" : fp.getClassifier()).append(":")
                    .append(type == null ? "" : type);
        }
        if (fp.getVersion() != null) {
            builder.append(":").append(fp.getVersion());
        }
        return builder.toString();
    }

    private static String getFeaturePackLocation(String featurePackLocation, String version) {
        final StringBuilder fplBuilder = new StringBuilder();
        fplBuilder.append(Objects.requireNonNull(featurePackLocation, "The feature pack location is required."));
        if (version != null) {
            fplBuilder.append("#").append(version);
        }
        return fplBuilder.toString();
    }

    /**
     * Cleanup the temporary content of a server installation.
     *
     * @param jbossHome The server installation
     * @throws IOException
     */
    public static void cleanupServer(final Path jbossHome) throws IOException {
        Path history = jbossHome.resolve("standalone").resolve("configuration").resolve("standalone_xml_history");
        IoUtils.recursiveDelete(history);
        Path tmp = jbossHome.resolve("standalone").resolve("tmp");
        IoUtils.recursiveDelete(tmp);
        Path log = jbossHome.resolve("standalone").resolve("log");
        Path domainTmp = jbossHome.resolve("domain").resolve("tmp");
        IoUtils.recursiveDelete(domainTmp);
        Path domainLog = jbossHome.resolve("domain").resolve("log");
        IoUtils.recursiveDelete(domainLog);
    }
}
