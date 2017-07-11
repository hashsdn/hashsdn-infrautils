/*
 * Copyright (c) 2017 Red Hat, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.infrautils.testutils.tests;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.opendaylight.infrautils.testutils.LogCaptureRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unit test, and example, of how to use the {@link LogCaptureRule}.
 *
 * @author Michael Vorburger.ch
 */
public class LogCaptureRuleTest {

    private static final Logger LOG = LoggerFactory.getLogger(LogCaptureRuleTest.class);

    public @Rule LogCaptureRule logRule = new LogCaptureRule();

    @Test
    public void logError() {
        logRule.expectError("boum");
        LOG.error("boum");
    }

    @Test
    public void logErrorWithException() {
        Exception ko = new IllegalArgumentException("KO");
        logRule.expectError("boum");
        LOG.error("boum", ko);
        assertThat(logRule.getLastErrorThrowable()).isEqualTo(ko);
    }

    @Test
    public void logErrorWithOneMessageFormatArgument() {
        logRule.expectError("bada boum");
        LOG.error("{} boum", "bada");
    }

    @Test
    public void logErrorWithTwoMessageFormatArguments() {
        logRule.expectError("bada boum kadum");
        LOG.error("{} boum {}", "bada", "kadum");
    }

    @Test
    public void logErrorWithThreeMessageFormatArguments() {
        logRule.expectError("bada boum kadum tabam");
        LOG.error("{} boum {} {}", "bada", "kadum", "tabam");
    }

    // TODO logErrorInBackgroundThread

    @Test
    public void logInfo() {
        LOG.info("FYI");
    }

    @Test
    public void noLog() {
        // all good, LogCaptureRule should do nothing in this case
    }

}