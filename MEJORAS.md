# Mejoras

Registro de mejoras detectadas durante la revisión del código, ordenadas por
importancia. Cada entrada indica el paso del [ROADMAP](ROADMAP.md) de origen, el
fichero afectado, la **Importancia** (ALTO / MEDIO / BAJO) y la **Dificultad**
(DIFÍCIL / MEDIO / FÁCIL) de aplicarla.

---

## Fase 2

### 🔴 ALTO

| # | Mejora | Paso | Fichero | Dificultad |
|---|--------|------|---------|------------|
| A1 | **Fallback de socket para ELM327 clónicos.** `createRfcommSocketToServiceRecord(SPP_UUID).connect()` falla con muchos adaptadores chinos baratos. Tener un fallback (canal RFCOMM 1 vía reflexión `createRfcommSocket(int)`) si el `connect()` da `IOException` sistemático. Choca con la norma de "no reflexión" de CLAUDE.md → valorar solo si la verificación 2.8 confirma el fallo. | 2.2 | `obd/BluetoothObdReader.java` (`connectSocket`) | MEDIO |
| A2 | **`in.available()` puede devolver siempre 0 en algunos stacks BT** → todos los comandos darían timeout. Sustituir el polling sobre `available()` + `sleep(5)` por un primer `read()` bloqueante (bloquea hasta ≥1 byte o cierre de socket). | 2.2 | `obd/BluetoothObdReader.java` (`readUntilPrompt`) | MEDIO |
| A3 | **Si el Bluetooth se apaga un instante, el reader muere para siempre.** `validatePreconditions()` devuelve `FAILED` + `return` si el BT está desactivado, y el hilo no se recupera al volver el BT. En coche el BT puede parpadear. Tratar "BT temporalmente desactivado" como caso de backoff (reintentar) en vez de fallo terminal; reservar `FAILED` solo para MAC inválida / adaptador inexistente. | 2.5 | `obd/BluetoothObdReader.java` (`runLoop`, `validatePreconditions`) | MEDIO |

### 🟡 MEDIO

| # | Mejora | Paso | Fichero | Dificultad |
|---|--------|------|---------|------------|
| M1 | **`READY` es optimista: no se verifica comunicación con la ECU.** Con `ATSP0` el protocolo no se negocia hasta la primera petición OBD; los primeros PIDs pueden dar `SEARCHING...` o `UNABLE TO CONNECT`. Enviar un `0100` al final del handshake para forzar la detección y confirmar que la ECU responde **antes** de pasar a `READY`. | 2.4 | `obd/BluetoothObdReader.java` (`performHandshake`) | MEDIO |
| M2 | **Falta pausa tras `ATZ`.** El reset del ELM327 tarda ~0.5–1 s; enviar `ATE0` demasiado pronto puede perderse y ralentizar/romper el primer connect. Añadir un pequeño delay tras `ATZ`. | 2.2 / 2.4 | `obd/BluetoothObdReader.java` (`performHandshake`) | FÁCIL |
| M3 | **`socket` y los streams no son `volatile` pero se tocan desde dos hilos.** `stop()` hace `closeQuietly(socket)` desde el hilo llamante (mecanismo para desbloquear la lectura). Sin `volatile` la visibilidad entre hilos no está garantizada. Marcar `socket`/`inputStream`/`outputStream` como `volatile`. | 2.2 / 2.3 | `obd/BluetoothObdReader.java` | FÁCIL |
| M4 | **Comentario engañoso + estado incoherente en el fallo de socket.** El comentario dice que `connectSocket` pone `RECONNECTING`, pero no toca el estado (se queda en `CONNECTING`), mientras que el fallo de handshake sí pone `RECONNECTING`. Decidir el criterio (p. ej. `CONNECTING` mientras nunca se ha conectado, `RECONNECTING` tras una caída) y alinear comentario + estado. | 2.5 | `obd/BluetoothObdReader.java` (`runLoop`) | FÁCIL |
| M5 | **Sin `WakeLock` en el servicio.** Con `keepScreenOn` en la Activity probablemente baste, pero si la radio apaga pantalla/CPU el hilo `obd-reader` se congelaría. Valorar un `PARTIAL_WAKE_LOCK` en el `ObdService`. | 2.6 | `service/ObdService.java` | FÁCIL |
| M6 | **Verificar que algo arranca el servicio y navega a la pantalla de debug.** `ObdDebugActivity` hace `start + bind`, pero hay que confirmar que `MainActivity`/`SettingsActivity` navegan hasta ella (si no, el servicio nunca arranca por esa vía). Idealmente añadir botón de acceso. | 2.6 / 2.7 | `ui/MainActivity.java`, `ui/SettingsActivity.java` | FÁCIL |
| M7 | **UX pobre cuando no hay MAC configurada.** Sin MAC, `onStart` igual hace `bind` con `BIND_AUTO_CREATE` → el servicio arranca, ve que no hay MAC y se autodetiene; la etiqueta se queda en "Conectando…" para siempre. Mostrar un texto explícito de "configura la MAC". | 2.7 | `ui/ObdDebugActivity.java` | FÁCIL |

### 🟢 BAJO

| # | Mejora | Paso | Fichero | Dificultad |
|---|--------|------|---------|------------|
| B1 | **Allocations por comando en el bucle caliente.** Cada `sendCommand` hace `command.getBytes()` (nuevo `byte[]`), `responseBuffer.toString().trim()` (nuevo String) y `extractHexBytes` hace `new int[7]` por respuesta. Precomputar los `byte[]` de los PIDs fijos para reducir GC. | 2.2 | `obd/BluetoothObdReader.java` | MEDIO |
| B2 | **Comentario obsoleto + `setState` redundante.** El comentario en `parsePidResponse` dice que `extractObdValue` acumula en `responseBuffer`, pero no lo usa. Además `runLoop` llama `setState(CONNECTING)` en cada vuelta → callbacks repetidos. Limpiar comentario y guardar con `if (newState != state)`. | 2.2 / 2.5 | `obd/BluetoothObdReader.java` | FÁCIL |
| B3 | **`SEARCHING...` se trata como respuesta no parseable** → `notifyError` en los primeros ciclos, ensucia el log. Filtrarlo como respuesta transitoria conocida (no error). | 2.4 | `obd/BluetoothObdReader.java` (`parsePidResponse`) | FÁCIL |
| B4 | **Un timeout de un comando AT = reconexión completa.** Un único reintento en `ATZ` (el más frágil) aceleraría el primer connect. | 2.4 | `obd/BluetoothObdReader.java` (`performHandshake`) | FÁCIL |
| B5 | **`onObdDataUpdated` no guarda `binding == null`** mientras `updateStateLabel` sí lo hace. No hay NPE real (todo en main thread, listener ya null entre `onStop` y `onDestroy`), pero es una inconsistencia frágil. Añadir el guard por simetría. | 2.7 | `ui/ObdDebugActivity.java` | FÁCIL |
| B6 | **MAF (`0110`) se sondea pero no se muestra** (reservado para Fase 3). De momento es un sondeo desperdiciado; decidir si dejarlo (prepara Fase 3) o quitarlo hasta entonces. | 2.6 / 2.7 | `service/ObdService.java`, `ui/ObdDebugActivity.java` | FÁCIL |
