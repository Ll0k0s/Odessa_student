package com.example.androidbuttons.core;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks TCP connection status flags with listeners for UI.
 */
public final class TcpStatusStore {

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile TcpState current = TcpState.DISCONNECTED;

    public TcpState get() {
        return current;
    }

    public void update(TcpState state) {
        current = state;
        for (Listener listener : listeners) {
            listener.onTcpStateChanged(state);
        }
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
        listener.onTcpStateChanged(current);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public interface Listener {
        void onTcpStateChanged(TcpState state);
    }
}
