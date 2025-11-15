package com.example.androidbuttons;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

// Шина состояний между активити и overlay-сервисом
public final class StateBus {

    private StateBus() {}

    // Слушатель изменения состояния полосы
    public interface StripStateListener {
        // Пришло новое состояние (1..5)
        void onStripStateChanged(int state);
    }

    // Слушатель выбора состояния из оверлея
    public interface OverlaySelectionListener {
        // Пользователь выбрал состояние в оверлее
        void onOverlayStateSelected(int state);
    }

    // Подписчики на текущее состояние полосы
    private static final CopyOnWriteArrayList<StripStateListener> stateListeners =
            new CopyOnWriteArrayList<>();

    // Подписчики на выбор состояния из оверлея
    private static final CopyOnWriteArrayList<OverlaySelectionListener> selectionListeners =
            new CopyOnWriteArrayList<>();

    // Текущее состояние полосы (0 — ещё не установлено)
    private static final AtomicInteger currentState = new AtomicInteger(0);

    // Получить запомненное состояние
    public static int getCurrentState() {
        return currentState.get();
    }

    // Добавить слушателя состояния
    public static void registerStateListener(StripStateListener listener) {
        if (listener == null) return;
        stateListeners.addIfAbsent(listener);
        int state = currentState.get();
        if (state > 0) {
            // При регистрации сразу отправляем последнее состояние
            listener.onStripStateChanged(state);
        }
    }

    // Удалить слушателя состояния
    public static void unregisterStateListener(StripStateListener listener) {
        if (listener == null) return;
        stateListeners.remove(listener);
    }

    // Добавить слушателя выбора из оверлея
    public static void registerSelectionListener(OverlaySelectionListener listener) {
        if (listener == null) return;
        selectionListeners.addIfAbsent(listener);
    }

    // Удалить слушателя выбора из оверлея
    public static void unregisterSelectionListener(OverlaySelectionListener listener) {
        if (listener == null) return;
        selectionListeners.remove(listener);
    }

    // Установить новое состояние полосы и разослать его всем слушателям
    public static void publishStripState(int state) {
        if (state <= 0) {
            currentState.set(0);
            return;
        }
        currentState.set(state);
        for (StripStateListener listener : stateListeners) {
            listener.onStripStateChanged(state);
        }
    }

    // Сообщить, что пользователь выбрал состояние в оверлее
    public static void publishOverlaySelection(int state) {
        if (state <= 0) return;
        for (OverlaySelectionListener listener : selectionListeners) {
            listener.onOverlayStateSelected(state);
        }
    }
}
