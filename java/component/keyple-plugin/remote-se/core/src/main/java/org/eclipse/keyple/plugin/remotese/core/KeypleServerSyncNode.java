/********************************************************************************
 * Copyright (c) 2020 Calypso Networks Association https://www.calypsonet-asso.org/
 *
 * See the NOTICE file(s) distributed with this work for additional information regarding copyright
 * ownership.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.keyple.plugin.remotese.core;

import java.util.List;

/**
 * <p>
 * <b>Keyple Server Sync Node</b> API.
 * </p>
 * <p>
 * This kind of node must be bind on the server's side if you want to use a Client-Server
 * communication protocol, such as standard HTTP for example.
 * </p>
 * <p>
 * Keyple provides its own implementations of this interface and manages their lifecycle.<br>
 * This kind of node can be bind to a all <b>server</b> Remote SE plugins and services :
 * <ul>
 * <li>{@code RemoteSeServerPlugin}</li>
 * <li>{@code NativeSeServerService}</li>
 * <li>{@code NativeSePoolServerService}</li>
 * </ul>
 * To create it, you must only bind a <b>sync</b> node during the initialization process.<br>
 * Then, you can access it everywhere on the server's side using one of the following utility
 * methods, depending on your use case :
 * <ul>
 * <li>{@code RemoteSeServerUtils.getSyncNode()}</li>
 * <li>{@code NativeSeServerUtils.getSyncNode()}</li>
 * <li>{@code NativeSePoolServerUtils.getSyncNode()}</li>
 * </ul>
 *
 * @since 1.0
 */
public interface KeypleServerSyncNode {

    /**
     * This method must be called by the server controller endpoint following the reception and
     * deserialization of a {@link KeypleMessageDto} from the client. Following the receive of a
     * request, the controller must :
     * <ul>
     * <li>Retrieve the node {@link KeypleServerSyncNode} using one of the following utility
     * methods, depending on your use case :
     * <ul>
     * <li>{@code RemoteSeServerUtils.getSyncNode()}</li>
     * <li>{@code NativeSeServerUtils.getSyncNode()}</li>
     * <li>{@code NativeSePoolServerUtils.getSyncNode()}</li>
     * </ul>
     * <li>Call this method on the node.</li>
     * <li>Serialize the result en return it to the client.</li>
     * </ul>
     *
     * @param msg The message to process.
     * @return not null but empty list if there is no result.
     * @since 1.0
     */
    List<KeypleMessageDto> onRequest(KeypleMessageDto msg);
}
