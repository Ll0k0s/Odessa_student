package com.example.androidbuttons.core;

/**
 * Canonical TCP connection states shared across UI and services.
 */
public enum TcpState {
    DISCONNECTED(false, false, false),
    CONNECTING(false, true, false),
    CONNECTED(true, false, true),
    UNREACHABLE(false, false, false);

    public final boolean connected;
    public final boolean connecting;
    public final boolean reachable;

    TcpState(boolean connected, boolean connecting, boolean reachable) {
        this.connected = connected;
        this.connecting = connecting;
        this.reachable = reachable;
    }
}
