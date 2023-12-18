/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.plugin.tools;

import org.jboss.galleon.api.Provisioning;
import org.jboss.galleon.progresstracking.ProgressCallback;
import org.jboss.galleon.progresstracking.ProgressTracker;
import org.jboss.logging.Logger;

/**
 * A {@link ProgressCallback} which logs the status.
 *
 * @author jdenise@redhat.com
 */
@SuppressWarnings("unused")
public class PluginProgressTracker<T> implements ProgressCallback<T> {

    private static final String DELAYED_EXECUTION_MSG = "Delayed generation, waiting...";
    private final Logger log;
    private final String msgStart;
    private long lastTime;
    private final boolean delayed;

    private PluginProgressTracker(final Logger log, final String msgStart, final boolean delayed) {
        this.log = log;
        this.msgStart = msgStart;
        this.delayed = delayed;
    }

    @Override
    public void starting(final ProgressTracker<T> tracker) {
        log.info(msgStart);
        lastTime = System.currentTimeMillis();
    }

    @Override
    public void processing(final ProgressTracker<T> tracker) {
        // The case of config generated in forked process.
        if (delayed && tracker.getItem() == null) {
            log.info(DELAYED_EXECUTION_MSG);
            return;
        }
        // Print a message every 5 seconds
        if (System.currentTimeMillis() - lastTime > 5000) {
            if (tracker.getTotalVolume() > 0) {
                log.info(String.format("%s of %s (%s%%)",
                        tracker.getProcessedVolume(), tracker.getTotalVolume(),
                        ((double) Math.round(tracker.getProgress() * 10)) / 10));
            } else {
                log.info("In progress...");
            }
            lastTime = System.currentTimeMillis();
        }
    }

    @Override
    public void processed(final ProgressTracker<T> tracker) {
    }

    @Override
    public void pulse(final ProgressTracker<T> tracker) {
    }

    @Override
    public void complete(final ProgressTracker<T> tracker) {
    }

    /**
     * Creates a logging {@link ProgressTracker}.
     *
     * @param pm  the provisioning context
     * @param log the logger to write the progress to
     */
    public static void initTrackers(final Provisioning pm, final Logger log) {
        pm.setProgressCallback(org.jboss.galleon.Constants.TRACK_PACKAGES,
                new PluginProgressTracker<String>(log, "Installing packages", false));
        pm.setProgressCallback(org.jboss.galleon.Constants.TRACK_CONFIGS,
                new PluginProgressTracker<String>(log, "Generating configurations", true));
        pm.setProgressCallback(org.jboss.galleon.Constants.TRACK_LAYOUT_BUILD,
                new PluginProgressTracker<String>(log, "Resolving feature-packs", false));
        pm.setProgressCallback("JBMODULES",
                new PluginProgressTracker<String>(log, "Resolving artifacts", false));
        pm.setProgressCallback("JBEXTRACONFIGS",
                new PluginProgressTracker<String>(log, "Generating extra configurations", true));
    }
}
