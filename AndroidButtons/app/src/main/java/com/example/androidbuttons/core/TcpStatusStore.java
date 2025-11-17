package com.example.androidbuttons.core;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks TCP connection status flags with listeners for UI.
 */
public final class TcpStatusStore {

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile TcpStatus current = TcpStatus.disconnected();

    public TcpStatus get() {
        return current;
    }

    public void update(TcpStatus status) {
        current = status;
        for (Listener listener : listeners) {
            listener.onTcpStatusChanged(status);
        }
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
        listener.onTcpStatusChanged(current);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public interface Listener {
        void onTcpStatusChanged(TcpStatus status);
    }

    public static final class TcpStatus {
        public final boolean connected;
        public final boolean connecting;
        public final boolean reachable;

        private TcpStatus(boolean connected, boolean connecting, boolean reachable) {
            this.connected = connected;
            this.connecting = connecting;
            this.reachable = reachable;
        }

        public static TcpStatus connecting() {
            return new TcpStatus(false, true, false);
        }

        public static TcpStatus connected() {
            return new TcpStatus(true, false, true);
        }

        public static TcpStatus disconnected() {
            return new TcpStatus(false, false, false);
        }

        public TcpStatus withReachable(boolean reachable) {
            return new TcpStatus(connected, connecting, reachable);
        }
    }
}
