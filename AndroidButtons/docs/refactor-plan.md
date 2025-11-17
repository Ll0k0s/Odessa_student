# Refactor Roadmap

This document captures the follow-up steps requested in the architecture audit. Each section lists the problem, outcome, and concrete tasks so changes can be delivered incrementally.

## 1. Retire `AppState` Globals
- **Status:** `AppState` (app/src/main/java/com/example/androidbuttons/AppState.java) теперь хранит только TCP-параметры по умолчанию; все прежние глобалы удалены. `TcpConfigRepository` остаётся единственным источником правды для выбранного локомотива, отдельный `SelectedLocoStore` не требуется. Лишний broadcast `ACTION_OVERLAY_UPDATED` убран — изменения позиций/масштаба распространяются через `OverlaySettingsRepository` и директивные Intent’ы `ACTION_APPLY_*`.
- **Next Outcome:** Все жизненные данные живут в репозиториях/сторах, UI подписывается на них и не имеет ссылок на `AppState`.
- **Remaining Tasks:**
  1. Пройтись по документации/диаграммам и явно описать роль `AppState` как "shared prefs metadata only".
  2. Подготовить чек-лист для случаев добавления новых глобалов (чтобы команда сначала искала решение через репозитории/сто́ры).

## 2. Clarify TcpManager/TcpService Boundaries
- **Pain:** `TcpManager` currently handles UI-facing concerns (status strings, log formatting) and low-level socket logic, while `TcpService` exposes only a Binder and log store.
- **Outcome:** `TcpService` becomes the single orchestrator that owns the manager and exposes high-level commands/states; `TcpManager` focuses solely on connection lifecycle + bytes.
- **Tasks:**
  1. **Gateway interface:**
    - Добавить `core/TcpGateway.java` с методами `sendControl(int loco, int state)`, `connect()`, `disconnect()`, `TcpState getState()`, подписка на события Rx/Listener.
    - Обновить `TcpService.LocalBinder` так, чтобы наружу отдавался `TcpGateway`; удалить прямой доступ к самому сервису из активити.
    - В потребителях (`SettingsActivity`, потенциально `FloatingOverlayService`) заменить вызовы через `TcpService` на работу с интерфейсом.
  2. **Событийная модель:**
    - Создать `TcpEvent` (sealed class/enum) под типы `Connected`, `Disconnected`, `Data`, `Error`.
    - В `TcpManager` оставить только низкоуровневые callback’и (`onConnected`, `onDisconnected`, `onFrame(byte[])`, `onError(Throwable)`), а форматирование строк и запись в `ConsoleLogRepository` перенести в `TcpService`.
    - Добавить буферизацию `TcpEvent` внутри `TcpService`, чтобы UI мог подписываться и получать историю последних N сообщений.
  3. **Auto-connect policy:**
    - Вынести логику `enableAutoConnect/disableAutoConnect` из `TcpManager` в отдельный помощник (`TcpAutoConnector`) внутри `TcpService`, чтобы менеджер оперировал только прямыми командами `connect/ disconnect`.
    - Обеспечить, чтобы `TcpService` отвечал за нормализацию хоста/порта (уже реализовано частично) и управлял повторными попытками через `Handler`/`ScheduledExecutor`.
    - Добавить метрики/логи вокруг состояния авто-подключения в `ConsoleLogRepository`.
  4. **Тесты:**
    - Переместить frame-парсинг в отдельный класс (`TcpFrameParser`) и покрыть тестами (успешный/битый CRC, мусор).
    - Написать JVM-тесты для новой авто-политики (подменяемый `Clock` + fake `TcpManager`).
    - Добавить тест на `TcpGateway`/`TcpService`, проверяющий, что подписчики получают корректную последовательность `TcpState` при имитации callback’ов.

## 3. Overlay + Service Harmonization
- **Pain:** `FloatingOverlayService` and `MainActivity` both manipulate overlay state; edit mode is special-cased inside the service.
- **Outcome:** Overlay geometry/edit state lives exclusively in `OverlaySettingsRepository` + `OverlayStateStore`; services subscribe but never derive their own truths.
- **Tasks:**
  1. Move edit-mode gating logic into `OverlayStateStore` (e.g., ignore non-zero states when `editModeEnabled`), so `FloatingOverlayService` does not need custom checks.
  2. Ensure overlay attach/detach events publish through a dedicated `OverlayLifecycleStore`; `MainActivity` can react (launch settings when overlay fails) without poking the service directly.
  3. Replace broadcast-based scale/position updates with repository writes. When Settings saves new values, call `overlaySettingsRepository.update()`, and let the service react through its listener.

## 4. Settings Repository Cleanup
- **Pain:** `SettingsActivity` directly manipulates `SharedPreferences` through `TcpConfigRepository`/`OverlaySettingsRepository`, but still keeps pending text fields and manual validation.
- **Outcome:** A dedicated `SettingsRepository` exposes immutable snapshots + validation helpers, so the Activity becomes a thin view/controller.
- **Tasks:**
  1. Extract the pending-value logic into `SettingsViewModel` (can be a simple class under `core/` since we are not on Jetpack ViewModel). It tracks staged values, validation, and dirty flags.
  2. Provide `SettingsRepository.saveTcpConfig(host, port)`/`saveOverlaySettings(...)` methods that clamp/normalize values before persisting; call them from the view model.
  3. Cover repository constraints with JVM tests (e.g., host normalization, port bounds, overlay scale clamping).

## 5. Protocol & Transport Tests
- **Pain:** No automated verification for frame builder/parser or retry logic; regressions surface only on hardware.
- **Outcome:** Deterministic tests guard the TCP framing contract and ensure control frames stay backward compatible.
- **Tasks:**
  1. Extract payload parsing from `TcpManager` into `TcpFrameParser` (plain Java class). Write tests for garbage filtering, CRC handling, and loco/state extraction in `app/src/test/java`.
  2. Add tests for `TcpManager.buildControlFrame` to confirm CRC, length field, and 0x7E framing.
  3. Simulate exponential backoff scheduling via a testable `RetryScheduler` abstraction (inject a fake clock to assert next-attempt timings).

## 6. Logging & Diagnostics
- **Pain:** Logs are appended with ad-hoc tags (e.g., `[#TCP_TX#]`) scattered across services.
- **Outcome:** A structured logging helper formats entries consistently and routes them to `ConsoleLogRepository` + Logcat.
- **Tasks:**
  1. Introduce `ConsoleLogger` with fixed event types (TCP_TX, TCP_RX, ERROR, STATUS, UART_TX/RX) that returns pre-colored prefixes for UI.
  2. Replace raw `consoleLogRepository.append` calls in `TcpService`, `FloatingOverlayService`, and UART components with the helper.
  3. Add basic integration tests that drain the repository (using `ConsoleLogRepositoryTest`) to ensure color tags remain stable for the UI coloring logic (see `SettingsActivity.appendColored`).

---
Tackle sections sequentially—each can merge independently and reduces surface area for later steps like removing `AppState` entirely or rewriting the overlay input flow.
