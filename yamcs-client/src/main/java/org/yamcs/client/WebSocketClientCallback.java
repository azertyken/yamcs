package org.yamcs.client;

import org.yamcs.protobuf.WebSocketServerMessage.WebSocketSubscriptionData;

public interface WebSocketClientCallback {

    /**
     * When a connection attempt is underway
     */
    default void connecting() {
    }

    /**
     * When the connection was successfully established
     */
    default void connected() {
    }

    /**
     * When the initial connection attempt failed
     */
    default void connectionFailed(Throwable t) {
    }

    /**
     * When a previously successful connection was disconnected
     */
    default void disconnected() {
    }

    void onMessage(WebSocketSubscriptionData data);
}
