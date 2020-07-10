/********************************************************************************
 * Copyright (c) 2019 Calypso Networks Association https://www.calypsonet-asso.org/
 *
 * See the NOTICE file(s) distributed with this work for additional information regarding copyright
 * ownership.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.keyple.calypso.transaction;

import org.eclipse.keyple.calypso.command.sam.AbstractSamCommandBuilder;
import org.eclipse.keyple.calypso.command.sam.AbstractSamResponseParser;
import org.eclipse.keyple.calypso.command.sam.builder.security.UnlockCmdBuild;
import org.eclipse.keyple.core.selection.AbstractSeSelectionRequest;
import org.eclipse.keyple.core.seproxy.message.SeResponse;

/**
 * Specialized selection request to manage the specific characteristics of Calypso SAMs
 */
public class SamSelectionRequest extends
        AbstractSeSelectionRequest<AbstractSamCommandBuilder<? extends AbstractSamResponseParser>> {
    /**
     * Create a {@link SamSelectionRequest}
     * 
     * @param samSelector the SAM selector
     */
    public SamSelectionRequest(SamSelector samSelector) {
        super(samSelector);
        byte[] unlockData = samSelector.getUnlockData();
        if (unlockData != null) {
            // a unlock data value has been set, let's add the unlock command to be executed
            // following the selection
            addCommandBuilder(new UnlockCmdBuild(samSelector.getTargetSamRevision(),
                    samSelector.getUnlockData()));
        }
    }

    /**
     * Create a CalypsoSam object containing the selection data received from the plugin
     *
     * @param seResponse the SE response received
     * @return a {@link CalypsoSam}
     */
    @Override
    protected CalypsoSam parse(SeResponse seResponse) {
        return new CalypsoSam(seResponse, seSelector.getSeProtocol().getTransmissionMode());
    }
}
