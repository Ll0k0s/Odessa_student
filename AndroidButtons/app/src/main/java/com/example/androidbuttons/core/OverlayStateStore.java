package com.example.androidbuttons.core;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replacement for the old StateBus: keeps overlay state and notifies listeners.
 */
public final class OverlayStateStore {

    private final AtomicInteger currentState = new AtomicInteger(0);
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SelectionListener> selectionListeners = new CopyOnWriteArrayList<>();

    public int getCurrentState() {
        return currentState.get();
    }

    public void publish(int state) {
        if (state <= 0) {
            currentState.set(0);
            return;
        }
        int normalized = ProtocolConstraints.clampState(state);
        currentState.set(normalized);
        for (Listener listener : listeners) {
            listener.onOverlayStateChanged(normalized);
        }
    }

    public void publishSelection(int state) {
        if (!ProtocolConstraints.isValidState(state)) {
            return;
        }
        for (SelectionListener listener : selectionListeners) {
            listener.onOverlayStateSelected(state);
        }
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
        int state = currentState.get();
        if (state > 0) {
            listener.onOverlayStateChanged(state);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void addSelectionListener(SelectionListener listener) {
        selectionListeners.addIfAbsent(listener);
    }

    public void removeSelectionListener(SelectionListener listener) {
        selectionListeners.remove(listener);
    }

    public interface Listener {
        void onOverlayStateChanged(int state);
    }

    public interface SelectionListener {
        void onOverlayStateSelected(int state);
    }
}
