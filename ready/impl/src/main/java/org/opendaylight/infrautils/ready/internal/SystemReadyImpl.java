/*
 * Copyright (c) 2017 Red Hat, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.infrautils.ready.internal;

import static org.opendaylight.infrautils.ready.SystemState.ACTIVE;
import static org.opendaylight.infrautils.ready.SystemState.BOOTING;
import static org.opendaylight.infrautils.ready.SystemState.FAILURE;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.opendaylight.infrautils.ready.SystemReadyListener;
import org.opendaylight.infrautils.ready.SystemReadyMonitor;
import org.opendaylight.infrautils.ready.SystemState;
import org.opendaylight.infrautils.utils.concurrent.ThreadFactoryProvider;
import org.opendaylight.odlparent.bundles4test.SystemStateFailureException;
import org.opendaylight.odlparent.bundles4test.TestBundleDiag;
import org.ops4j.pax.cdi.api.OsgiServiceProvider;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of "system ready" services.
 *
 * @author Michael Vorburger.ch
 */
@Singleton
@OsgiServiceProvider(classes = SystemReadyMonitor.class)
public class SystemReadyImpl implements SystemReadyMonitor, Runnable /* TODO c/56750 , DiagUpdatesListener */ {

    private static final Logger LOG = LoggerFactory.getLogger(SystemReadyImpl.class);

    private final Queue<SystemReadyListener> listeners = new ConcurrentLinkedQueue<>();
    private final AtomicReference<SystemState> currentSystemState = new AtomicReference<>(BOOTING);

    private final BundleContext bundleContext;
    private final ThreadFactory threadFactory = ThreadFactoryProvider.builder()
                                                    .namePrefix("SystemReadyService")
                                                    .logger(LOG)
                                                    .build().get();

    @Inject
    public SystemReadyImpl(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        LOG.info("Now starting to provide full system readiness status updates (see TestBundleDiag's logs)...");
    }

    @PostConstruct
    public void init() {
        threadFactory.newThread(this).start();
    }

    @Override
    @SuppressWarnings("checkstyle:IllegalCatch")
    public void run() {
        try {
            // 5 minutes really ought to be enough for the whole circus to completely boot up?!
            TestBundleDiag.checkBundleDiagInfos(bundleContext, 5, TimeUnit.MINUTES /* TODO c/56750 , this */);
            currentSystemState.set(ACTIVE);
            LOG.info("System ready; AKA: Aye captain, all warp coils are now operating at peak efficiency! [M.]");

            if (!listeners.isEmpty()) {
                LOG.info("Now notifying all its registered SystemReadyListeners...");
            }
            SystemReadyListener listener;
            while ((listener = listeners.poll()) != null) {
                listener.onSystemBootReady();
            }

        } catch (SystemStateFailureException e) {
            LOG.error("Failed, some bundles did not start (SystemReadyListeners are not called)", e);
            currentSystemState.set(FAILURE);
            // We do not re-throw this

        } catch (Throwable throwable) {
            // It's exceptionally OK to catch Throwable here, because we do want to set the currentFullSystemStatus
            LOG.error("Failed unexpectedly (SystemReadyListeners are not called)", throwable);
            currentSystemState.set(FAILURE);
            throw throwable; // we do re-throw this
        }
    }

/* TODO uncomment when https://git.opendaylight.org/gerrit/#/c/56750/ is merged in odlparent..

    @Override
    public void onUpdate(String diagInfo) {
        currentFullSystemStatus.set(diagInfo);
    }
*/
    @Override
    public SystemState getSystemState() {
        return currentSystemState.get();
    }

    @Override
    public void registerListener(SystemReadyListener listener) {
        listeners.add(listener);
    }

}
