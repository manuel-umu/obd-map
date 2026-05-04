package com.obdmap.launcher.obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.obdmap.launcher.BuildConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Lector OBD2 sobre Bluetooth SPP (ELM327). Gestiona por sí solo la conexión,
 * el handshake AT y el bucle de petición/respuesta de PIDs. El llamador
 * (Bloque C — ObdService) solo necesita:
 * <ol>
 *   <li>Instanciar con {@code new BluetoothObdReader(mac, listener)}</li>
 *   <li>Llamar a {@link #start()}</li>
 *   <li>Encolar PIDs con {@link #enqueuePid(String)}</li>
 *   <li>Llamar a {@link #stop()} al detener el servicio</li>
 * </ol>
 *
 * <p>Todos los callbacks de {@link ObdListener} se invocan desde el hilo OBD
 * interno; el Bloque C debe hacer {@code Handler.post} si actualiza la UI.</p>
 */
public final class BluetoothObdReader {

    private static final String TAG = "BluetoothObdReader";

    /** UUID estándar del perfil Serial Port Profile (SPP). */
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /** Timeout de lectura de una respuesta AT o PID (milisegundos). */
    private static final int READ_TIMEOUT_MS = 3000;

    /** Prompts que delimitan fin de respuesta del ELM327. */
    private static final char PROMPT_CHAR = '>';

    /**
     * Tamaño de la cola de comandos pendientes. Se descartan los más antiguos
     * cuando se llena para no bloquear nunca al llamador (poll sin espera).
     */
    private static final int COMMAND_QUEUE_CAPACITY = 16;

    // Backoff exponencial: 1s, 2s, 4s, 8s, 16s, tope en 30s.
    private static final long BACKOFF_INITIAL_MS = 1_000L;
    private static final long BACKOFF_MAX_MS = 30_000L;

    /**
     * Tiempo mínimo en estado READY antes de resetear el backoff.
     * Evita que una conexión frágil (cae a los 2s) resetee el contador.
     */
    private static final long BACKOFF_RESET_STABLE_MS = 10_000L;

    // -------------------------------------------------------------------------
    // Buffers reutilizables — NUNCA crear nuevos dentro del bucle de lectura.
    // -------------------------------------------------------------------------
    private final byte[] readBuffer = new byte[256];
    private final StringBuilder responseBuffer  = new StringBuilder(128);

    // -------------------------------------------------------------------------
    // Estado
    // -------------------------------------------------------------------------
    private final String mac;
    private final ObdListener listener;

    /** Cola de PIDs a enviar; se llena desde cualquier hilo, se consume en obdThread. */
    private final ArrayBlockingQueue<String> commandQueue =
            new ArrayBlockingQueue<>(COMMAND_QUEUE_CAPACITY);

    @ObdState.State
    private volatile int state = ObdState.DISCONNECTED;

    private volatile boolean running = false;
    @Nullable private Thread obdThread;

    // Recursos de conexión — solo se acceden desde obdThread.
    @Nullable private BluetoothSocket socket;
    @Nullable private InputStream inputStream;
    @Nullable private OutputStream outputStream;

    // Marca de tiempo en que se alcanzó READY por última vez (para backoff reset).
    private long readySinceMs = 0L;

    // Nivel actual del backoff.
    private long currentBackoffMs = BACKOFF_INITIAL_MS;

    // -------------------------------------------------------------------------

    /**
     * @param mac      dirección MAC del ELM327, ya validada por PrefsManager
     * @param listener callback de eventos; no puede ser null
     */
    public BluetoothObdReader(@NonNull String mac, @NonNull ObdListener listener) {
        this.mac      = mac;
        this.listener = listener;
    }

    // =========================================================================
    // API pública
    // =========================================================================

    /**
     * Arranca el hilo OBD si no está en marcha. Idempotente.
     * La conexión real es asíncrona; el estado se notifica vía
     * {@link ObdListener#onStateChanged(int)}.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        obdThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop();
            }
        }, "obd-reader");
        obdThread.setDaemon(true);
        obdThread.start();
    }

    /**
     * Detiene el hilo OBD y cierra el socket. Idempotente.
     * Bloqueante hasta que el hilo termina (máx. ~500 ms por el join con timeout).
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        // Interrumpimos el hilo para despertar cualquier sleep/wait activo.
        if (obdThread != null) {
            obdThread.interrupt();
        }
        // Cerramos el socket para que la lectura bloqueante en InputStream se desbloquee.
        closeQuietly(socket);
        socket = null;

        try {
            if (obdThread != null) {
                obdThread.join(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        obdThread = null;
        setState(ObdState.DISCONNECTED);
    }

    /**
     * Encola un PID OBD2 para ser enviado en el siguiente ciclo del bucle.
     * Si la cola está llena, descarta el comando más antiguo para hacer hueco:
     * la telemetría en tiempo real prefiere el dato nuevo sobre el dato viejo.
     *
     * @param pidCommand comando de 4 chars (p. ej. {@code "010C"} para RPM)
     */
    public void enqueuePid(@NonNull String pidCommand) {
        // Si la cola está llena, sacrificamos el comando más antiguo.
        if (!commandQueue.offer(pidCommand)) {
            commandQueue.poll();
            commandQueue.offer(pidCommand);
        }
    }

    /** Devuelve el estado actual (puede leerse desde cualquier hilo). */
    @ObdState.State
    public int getState() {
        return state;
    }

    // =========================================================================
    // Bucle principal del hilo OBD
    // =========================================================================

    private void runLoop() {
        while (running) {
            setState(ObdState.CONNECTING);

            if (!validatePreconditions()) {
                // BT no disponible o MAC inválida: fallo definitivo, no reintentamos.
                setState(ObdState.FAILED);
                return;
            }

            if (!connectSocket()) {
                // connectSocket ya puso estado RECONNECTING; hacemos backoff y reintentamos.
                if (!sleepBackoff()) {
                    return; // stop() llamado durante el sleep
                }
                continue;
            }

            if (!performHandshake()) {
                closeConnection();
                setState(ObdState.RECONNECTING);
                if (!sleepBackoff()) {
                    return;
                }
                continue;
            }

            // Conexión establecida y lista: reset del backoff cuando sea estable.
            setState(ObdState.READY);
            readySinceMs = System.currentTimeMillis();

            // Bucle de lectura de PIDs.
            pumpPidLoop();

            // Si salimos del bucle y running es false, fue un stop() normal.
            if (!running) {
                break;
            }

            // La conexión cayó sola: reconectar con backoff.
            closeConnection();

            // Si llevábamos suficiente tiempo en READY, reseteamos el backoff.
            if (System.currentTimeMillis() - readySinceMs >= BACKOFF_RESET_STABLE_MS) {
                currentBackoffMs = BACKOFF_INITIAL_MS;
            }

            setState(ObdState.RECONNECTING);
            if (!sleepBackoff()) {
                return;
            }
        }

        closeConnection();
        setState(ObdState.DISCONNECTED);
    }

    // =========================================================================
    // Validación previa
    // =========================================================================

    private boolean validatePreconditions() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            notifyError("", "Bluetooth no disponible en este dispositivo");
            return false;
        }
        if (!adapter.isEnabled()) {
            notifyError("", "Bluetooth desactivado");
            return false;
        }
        if (mac == null || mac.isEmpty()) {
            notifyError("", "MAC del ELM327 no configurada");
            return false;
        }
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            notifyError("", "MAC inválida: " + mac);
            return false;
        }
        return true;
    }

    // =========================================================================
    // Conexión del socket
    // =========================================================================

    private boolean connectSocket() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(mac);

        // Cancelar discovery si está activo: puede enlentecer o bloquear la conexión.
        if (adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }

        BluetoothSocket newSocket = null;
        try {
            newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            newSocket.connect();

            socket       = newSocket;
            inputStream  = newSocket.getInputStream();
            outputStream = newSocket.getOutputStream();

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Socket conectado a " + mac);
            }
            return true;

        } catch (IOException e) {
            closeQuietly(newSocket);
            socket      = null;
            inputStream = null;
            outputStream = null;
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Fallo al conectar socket: " + e.getMessage());
            }
            return false;
        }
    }

    // =========================================================================
    // Handshake AT
    // =========================================================================

    /**
     * Secuencia de inicialización del ELM327. Cada comando se envía y se espera
     * una respuesta antes de continuar. Si alguno falla, devuelve {@code false}.
     */
    private boolean performHandshake() {
        setState(ObdState.INITIALIZING);

        // ATZ: reset del ELM327. La respuesta incluye el banner "ELM327 v...".
        if (!sendAndExpect("ATZ\r", "ELM327")) {
            notifyError("", "ATZ: el dispositivo no respondió con 'ELM327'");
            return false;
        }

        // ATE0: desactivar echo (el ELM327 deja de repetir los comandos enviados).
        if (!sendAndExpect("ATE0\r", "OK")) {
            notifyError("", "ATE0: sin respuesta OK");
            return false;
        }

        // ATL0: desactivar saltos de línea adicionales en las respuestas.
        if (!sendAndExpect("ATL0\r", "OK")) {
            notifyError("", "ATL0: sin respuesta OK");
            return false;
        }

        // ATSP0: selección automática de protocolo OBD2.
        if (!sendAndExpect("ATSP0\r", "OK")) {
            notifyError("", "ATSP0: sin respuesta OK");
            return false;
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Handshake AT completado");
        }
        return true;
    }

    /**
     * Envía un comando AT y comprueba que la respuesta contenga {@code expectedToken}.
     * La comparación es case-insensitive para tolerar variantes de firmware ELM327.
     */
    private boolean sendAndExpect(@NonNull String command, @NonNull String expectedToken) {
        String response = sendCommand(command);
        if (response == null) {
            return false;
        }
        // toUpperCase asignaría un objeto nuevo; comparamos con indexOf insensible manual.
        return containsIgnoreCase(response, expectedToken);
    }

    // =========================================================================
    // Bucle de PIDs
    // =========================================================================

    /**
     * Extrae comandos de la cola y los envía al ELM327 hasta que {@link #running}
     * sea false o se produzca un error de I/O. Cuando retorna, el llamador
     * comprueba {@link #running} para distinguir stop() voluntario de caída.
     */
    private void pumpPidLoop() {
        while (running) {
            String pid = commandQueue.poll(); // no bloquea
            if (pid == null) {
                // Cola vacía: pequeña pausa para no quemar CPU sin trabajo real.
                // 20 ms = 50 Hz máx de polling, más que suficiente.
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            // Enviamos el PID y leemos la respuesta.
            String response = sendCommand(pid + "\r");
            if (response == null) {
                // I/O error: conexión perdida; salimos para que runLoop reconecte.
                return;
            }

            // Parseamos la respuesta y notificamos al listener.
            parsePidResponse(pid, response);
        }
    }

    // =========================================================================
    // I/O de bajo nivel
    // =========================================================================

    /**
     * Envía {@code command} al ELM327 y lee la respuesta completa (hasta el
     * carácter {@code >}). Usa los buffers reutilizables de instancia.
     *
     * @return la respuesta como String, o {@code null} si hubo timeout o error de I/O
     */
    @Nullable
    private String sendCommand(@NonNull String command) {
        OutputStream out = outputStream;
        InputStream  in  = inputStream;
        if (out == null || in == null) {
            return null;
        }

        try {
            // Enviar comando.
            byte[] cmdBytes = command.getBytes(StandardCharsets.US_ASCII);
            out.write(cmdBytes);
            out.flush();

            // Leer respuesta byte a byte hasta el '>' del ELM327.
            return readUntilPrompt(in);

        } catch (IOException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Error de I/O al enviar '" + command.trim() + "': " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Lee bytes del stream hasta encontrar el prompt {@code >}, con timeout.
     * Reutiliza {@link #readBuffer} y {@link #responseBuffer}; no asigna objetos
     * por llamada salvo el String final devuelto.
     *
     * @return respuesta acumulada (sin el {@code >}), o {@code null} si timeout/error
     */
    @Nullable
    private String readUntilPrompt(@NonNull InputStream in) throws IOException {
        responseBuffer.setLength(0);

        long deadline = System.currentTimeMillis() + READ_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            // available() evita bloquearse indefinidamente cuando no hay datos aún.
            int available = in.available();
            if (available <= 0) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }

            int toRead = Math.min(available, readBuffer.length);
            int bytesRead = in.read(readBuffer, 0, toRead);
            if (bytesRead < 0) {
                // Stream cerrado por el dispositivo remoto.
                throw new IOException("Stream cerrado por ELM327");
            }

            for (int i = 0; i < bytesRead; i++) {
                char c = (char) (readBuffer[i] & 0xFF);
                if (c == PROMPT_CHAR) {
                    // Respuesta completa.
                    return responseBuffer.toString().trim();
                }
                // Filtramos CR/LF y nulos que el ELM327 puede intercalar.
                if (c != '\r' && c != '\n' && c != '\0') {
                    responseBuffer.append(c);
                }
            }
        }

        // Timeout.
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Timeout leyendo respuesta ELM327");
        }
        return null;
    }

    // =========================================================================
    // Parser de respuestas OBD2
    // =========================================================================

    /**
     * Parsea la respuesta hexadecimal del ELM327 para un PID dado y notifica
     * al listener con el valor entero calculado.
     *
     * <p>El formato estándar de respuesta OBD2 modo 01 es:
     * {@code 41 XX AA BB ...} donde XX es el PID y AA/BB son bytes de datos.
     * No usamos {@code String.split} para evitar allocations: extraemos los
     * tokens hex directamente con índices sobre el {@link #responseBuffer}.</p>
     *
     * @param pid      PID enviado (p. ej. {@code "010C"})
     * @param response respuesta cruda del ELM327 (ya trimmeada, sin prompt)
     */
    private void parsePidResponse(@NonNull String pid, @NonNull String response) {
        // Respuestas de error estándar del ELM327: no son fallos de conexión.
        if (response.equals("NO DATA") || response.equals("?")
                || response.equals("ERROR") || response.equals("UNABLE TO CONNECT")) {
            notifyError(pid, "ELM327 respondió: " + response);
            return;
        }

        // Extraemos los bytes hex de la respuesta (ignoramos espacios y prefijos "41 XX").
        // Acumulamos los tokens en responseBuffer (está disponible porque este método
        // no se llama desde sendCommand simultáneamente).
        int rawValue = extractObdValue(response, pid);
        if (rawValue == Integer.MIN_VALUE) {
            notifyError(pid, "Respuesta no parseable: " + response);
            return;
        }

        listener.onObdData(pid, rawValue);
    }

    /**
     * Extrae el valor entero de la respuesta OBD2 para el PID dado.
     *
     * <p>Fórmulas según SAE J1979 / ISO 15765:
     * <ul>
     *   <li>{@code 010C} RPM: (A*256 + B) / 4</li>
     *   <li>{@code 010D} velocidad: A (km/h directo)</li>
     *   <li>{@code 0104} carga: (A * 100) / 255</li>
     *   <li>{@code 0110} MAF: (A*256 + B) / 100  (g/s * 100)</li>
     * </ul>
     * Para PIDs desconocidos devolvemos los primeros dos bytes combinados
     * para no perder datos que el Bloque C pueda interpretar.</p>
     *
     * @return valor calculado, o {@link Integer#MIN_VALUE} si la respuesta es inválida
     */
    private int extractObdValue(@NonNull String response, @NonNull String pid) {
        // La respuesta puede incluir espacios: "41 0C 1A F8" o sin espacios "410C1AF8".
        // Tokenizamos manualmente para evitar String.split.
        int[] bytes = extractHexBytes(response);
        if (bytes == null || bytes.length < 3) {
            // Necesitamos al menos: byte de modo (41), byte de PID, y un byte de dato.
            return Integer.MIN_VALUE;
        }

        // bytes[0] = 0x41 (modo 01 respuesta), bytes[1] = PID, bytes[2..] = datos.
        int a = bytes[2];
        int b = bytes.length > 3 ? bytes[3] : 0;

        // Comparamos el PID en mayúsculas (ya viene así del caller).
        if ("010C".equals(pid)) {
            // RPM = (A*256 + B) / 4. Devolvemos A*256+B; el Bloque C divide entre 4.
            return (a << 8) | b;
        } else if ("010D".equals(pid)) {
            // Velocidad en km/h, directa.
            return a;
        } else if ("0104".equals(pid)) {
            // Carga del motor en porcentaje: (A * 100) / 255.
            return (a * 100) / 255;
        } else if ("0110".equals(pid)) {
            // MAF en g/s * 100: (A*256 + B). Bloque C divide entre 100.
            return (a << 8) | b;
        } else {
            // PID genérico: devolvemos los primeros dos bytes de datos combinados.
            return (a << 8) | b;
        }
    }

    /**
     * Extrae todos los tokens hexadecimales de una cadena de respuesta OBD2,
     * sin crear arrays intermedios de tokens de String.
     *
     * @return array de valores enteros (0-255) por byte, o {@code null} si no hay
     *         ningún token hex válido
     */
    @Nullable
    private int[] extractHexBytes(@NonNull String response) {
        // Máximo 7 bytes por respuesta modo 01 (1 modo + 1 PID + 5 datos).
        int[] result = new int[7];
        int count = 0;

        int len = response.length();
        int i = 0;
        while (i < len && count < result.length) {
            char c = response.charAt(i);
            if (c == ' ') {
                i++;
                continue;
            }
            // Intentamos leer dos caracteres hex consecutivos.
            if (i + 1 < len) {
                int hi = hexDigit(response.charAt(i));
                int lo = hexDigit(response.charAt(i + 1));
                if (hi >= 0 && lo >= 0) {
                    result[count++] = (hi << 4) | lo;
                    i += 2;
                    continue;
                }
            }
            i++;
        }

        if (count == 0) {
            return null;
        }
        // Devolvemos un array ajustado solo si es más corto que el preasignado.
        if (count == result.length) {
            return result;
        }
        int[] trimmed = new int[count];
        System.arraycopy(result, 0, trimmed, 0, count);
        return trimmed;
    }

    /**
     * Convierte un carácter hex ('0'-'9', 'A'-'F', 'a'-'f') a su valor entero.
     *
     * @return valor 0-15, o -1 si el carácter no es hex válido
     */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }

    // =========================================================================
    // Backoff exponencial
    // =========================================================================

    /**
     * Duerme el hilo OBD el tiempo de backoff actual y duplica para la próxima vez.
     *
     * @return {@code true} si el sleep completó; {@code false} si fue interrumpido
     *         por {@link #stop()} y el bucle debe terminar
     */
    private boolean sleepBackoff() {
        long delay = currentBackoffMs;

        // Duplicamos para la próxima vez, con tope en BACKOFF_MAX_MS.
        currentBackoffMs = Math.min(currentBackoffMs * 2, BACKOFF_MAX_MS);

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Backoff: esperando " + delay + " ms antes de reconectar");
        }

        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setState(@ObdState.State int newState) {
        state = newState;
        listener.onStateChanged(newState);
    }

    private void notifyError(@NonNull String pid, @NonNull String description) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ObdError pid='" + pid + "' desc=" + description);
        }
        listener.onObdError(pid, description);
    }

    /**
     * Cierra los streams y el socket de la conexión activa de forma silenciosa.
     * No toca los campos de instancia; los pone a null el llamador.
     */
    private void closeConnection() {
        closeQuietly(inputStream);
        closeQuietly(outputStream);
        closeQuietly(socket);
        inputStream  = null;
        outputStream = null;
        socket       = null;
    }

    /**
     * Cierra un {@link java.io.Closeable} ignorando cualquier excepción.
     * Centralizado aquí para no repetir try/catch vacíos por todo el código.
     */
    private static void closeQuietly(@Nullable java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Silencioso por diseño: ya estábamos en el camino de error.
        }
    }

    /**
     * Comprueba si {@code source} contiene {@code token} ignorando mayúsculas/minúsculas,
     * sin llamar a {@code toUpperCase()} (que crea un String nuevo).
     */
    private static boolean containsIgnoreCase(@NonNull String source, @NonNull String token) {
        int sourceLen = source.length();
        int tokenLen  = token.length();
        if (tokenLen == 0) return true;
        if (tokenLen > sourceLen) return false;

        outer:
        for (int i = 0; i <= sourceLen - tokenLen; i++) {
            for (int j = 0; j < tokenLen; j++) {
                char cs = source.charAt(i + j);
                char ct = token.charAt(j);
                // Comparación ASCII case-insensitive (suficiente para "ELM327", "OK").
                if (cs != ct && Character.toLowerCase(cs) != Character.toLowerCase(ct)) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
