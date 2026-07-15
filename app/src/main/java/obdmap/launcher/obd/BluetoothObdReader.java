package obdmap.launcher.obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import obdmap.launcher.util.IoUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Lector OBD2 por Bluetooth (ELM327). Se encarga solo de todo: conectar,
 * inicializar el adaptador con los comandos AT y pedir PIDs en bucle.
 *
 * Uso: crear con la MAC y un listener, llamar a start(), encolar PIDs con
 * enqueuePid() y llamar a stop() al terminar.
 *
 * Ojo: los callbacks del listener llegan desde el hilo OBD interno, no desde
 * el hilo de UI. Quien actualice vistas debe postear al main thread.
 */
public final class BluetoothObdReader {

    /** UUID estándar del perfil serie Bluetooth (SPP). */
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /** Cuánto esperamos la respuesta de un comando antes de darla por perdida (ms). */
    private static final int READ_TIMEOUT_MS = 3000;

    /** El ELM327 termina cada respuesta con este carácter. */
    private static final char PROMPT_CHAR = '>';

    /** Tamaño de la cola de comandos. Si se llena, se descarta el más antiguo. */
    private static final int COMMAND_QUEUE_CAPACITY = 16;

    // Backoff exponencial: 1s, 2s, 4s, 8s, 16s, tope en 30s.
    private static final long BACKOFF_INITIAL_MS = 1_000L;
    private static final long BACKOFF_MAX_MS = 30_000L;

    /**
     * Tiempo mínimo conectados antes de resetear el backoff a 1s.
     * Así una conexión frágil que se cae cada pocos segundos no
     * machaca al adaptador con reintentos inmediatos.
     */
    private static final long BACKOFF_RESET_STABLE_MS = 10_000L;

    // Buffers reutilizables
    private final byte[] readBuffer = new byte[256];
    private final StringBuilder responseBuffer  = new StringBuilder(128);

    // Estado
    private final String mac;
    private final ObdListener listener;

    // Cola de PIDs a enviar
    private final ArrayBlockingQueue<String> commandQueue =
            new ArrayBlockingQueue<>(COMMAND_QUEUE_CAPACITY);

    @ObdState.State
    private volatile int state = ObdState.DISCONNECTED;

    private volatile boolean running = false;
    @Nullable private Thread obdThread;

    // Recursos de conexión. Los usa el hilo OBD, pero stop() cierra el socket
    // desde el hilo llamante para desbloquear la lectura: volatile garantiza
    // que ambos hilos vean siempre la referencia viva (no una caché obsoleta).
    @Nullable private volatile BluetoothSocket socket;
    @Nullable private volatile InputStream inputStream;
    @Nullable private volatile OutputStream outputStream;

    // Marca de tiempo en que se alcanzó READY por última vez (para backoff reset)
    private long readySinceMs = 0L;

    // Nivel actual del backoff
    private long currentBackoffMs = BACKOFF_INITIAL_MS;

    // -------------------------------------------------------------------------

    /**
     * @param mac      dirección MAC del OBD
     * @param listener callback de eventos
     */
    public BluetoothObdReader(@NonNull String mac, @NonNull ObdListener listener) {
        this.mac      = mac;
        this.listener = listener;
    }

    /**
     * Arranca el hilo OBD si no está en marcha. Idempotente.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        obdThread = new Thread(this::runLoop, "obd-reader");
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
        // Cerramos el socket para que la lectura se desbloquee
        IoUtils.closeQuietly(socket);
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
     * Encola un PID para enviarlo en el siguiente ciclo. Si la cola está llena
     * se descarta el más antiguo: en telemetría en vivo el dato nuevo siempre
     * vale más que el viejo.
     *
     * @param pidCommand comando de 4 caracteres, p. ej. "010C" para RPM
     */
    public void enqueuePid(@NonNull String pidCommand) {
        // Si la cola está llena, sacrificamos el comando más antiguo.
        if (!commandQueue.offer(pidCommand)) {
            commandQueue.poll();
            commandQueue.offer(pidCommand);
        }
    }

    /** Getter del estado. */
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

            int precondition = checkPreconditions();
            if (precondition == PRECONDITION_FATAL) {
                // Sin adaptador o MAC inválida: irrecuperable, el hilo termina.
                setState(ObdState.FAILED);
                return;
            }
            if (precondition == PRECONDITION_RETRY) {
                // Bluetooth desactivado: en el coche puede parpadear (arranque,
                // cortes de alimentación). Reintentamos con backoff en vez de morir.
                setState(ObdState.RECONNECTING);
                if (!sleepBackoff()) {
                    return;
                }
                continue;
            }

            if (!connectSocket()) {
                setState(ObdState.RECONNECTING);
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
    /** Precondiciones cumplidas: se puede intentar la conexión. */
    private static final int PRECONDITION_OK    = 0;
    /** Fallo transitorio (Bluetooth desactivado): reintentar con backoff. */
    private static final int PRECONDITION_RETRY = 1;
    /** Fallo permanente (sin adaptador, MAC inválida): estado FAILED y fin. */
    private static final int PRECONDITION_FATAL = 2;

    /**
     * Comprueba las precondiciones distinguiendo fallos permanentes (no tiene
     * sentido reintentar) de transitorios (el BT puede volver a encenderse).
     */
    private int checkPreconditions() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            notifyError("", "Bluetooth no disponible en este dispositivo");
            return PRECONDITION_FATAL;
        }
        if (mac == null || mac.isEmpty()) {
            notifyError("", "MAC del ELM327 no configurada");
            return PRECONDITION_FATAL;
        }
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            notifyError("", "MAC inválida: " + mac);
            return PRECONDITION_FATAL;
        }
        if (!adapter.isEnabled()) {
            notifyError("", "Bluetooth desactivado");
            return PRECONDITION_RETRY;
        }
        return PRECONDITION_OK;
    }

    // =========================================================================
    // Conexión del socket
    // =========================================================================
    private boolean connectSocket() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(mac);

        // Cancelar discovery si está activo porque se puede bloquear
        if (adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }

        BluetoothSocket newSocket = null;
        try {
            newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            newSocket.connect();
            socket = newSocket;
            inputStream = newSocket.getInputStream();
            outputStream = newSocket.getOutputStream();
            return true;

        } catch (IOException e) {
            IoUtils.closeQuietly(newSocket);
            socket = null;
            inputStream = null;
            outputStream = null;
            return false;
        }
    }

    /**
     * Inicializa el ELM327 con la secuencia AT clásica: reset, sin echo,
     * sin saltos de línea extra y protocolo automático. Cada comando espera
     * su respuesta antes del siguiente. Si alguno falla, devuelve false.
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
        return true;
    }

    /**
     * Envía un comando y comprueba que la respuesta contiene el texto esperado.
     * Ignora mayúsculas/minúsculas porque cada firmware ELM327 responde a su manera.
     */
    private boolean sendAndExpect(@NonNull String command, @NonNull String expectedToken) {
        String response = sendCommand(command);
        if (response == null) {
            return false;
        }
        return containsIgnoreCase(response, expectedToken);
    }

    /**
     * Bucle principal de trabajo: va sacando PIDs de la cola y enviándolos
     * hasta que paren el reader o se caiga la conexión. Al volver, runLoop
     * mira `running` para distinguir una parada voluntaria de una caída.
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
                // Conexión perdida
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
     * Envía un comando al ELM327 y espera la respuesta completa (hasta el '>').
     *
     * @return la respuesta, o null si hubo timeout o se perdió la conexión
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
            return null;
        }
    }

    /**
     * Lee del stream hasta encontrar el '>' que cierra cada respuesta, con
     * timeout. Reutiliza los buffers de instancia: lo único que se crea por
     * llamada es el String final.
     *
     * @return la respuesta sin el '>', o null si hubo timeout o error
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
        return null;
    }

    /**
     * Interpreta la respuesta del ELM327 para un PID y avisa al listener con
     * el valor ya decodificado. Una respuesta normal tiene la forma
     * "41 XX AA BB": 41 = modo 01, XX = PID, y AA/BB son los datos.
     *
     * @param pid      PID que se envió, p. ej. "010C"
     * @param response respuesta cruda, ya sin espacios sobrantes ni el '>'
     */
    private void parsePidResponse(@NonNull String pid, @NonNull String response) {
        // "SEARCHING..." es transitorio: con ATSP0 el ELM327 negocia el protocolo
        // en las primeras peticiones. No es un error; simplemente aún no hay dato.
        if (response.startsWith("SEARCHING")) {
            return;
        }

        // Respuestas de error estándar del ELM327
        if (response.equals("NO DATA") || response.equals("?")
                || response.equals("ERROR") || response.equals("UNABLE TO CONNECT")) {
            notifyError(pid, "ELM327 respondió: " + response);
            return;
        }

        // Extraemos los bytes hex de la respuesta (ignoramos espacios y prefijos "41 XX").
        int rawValue = extractObdValue(response, pid);
        if (rawValue == Integer.MIN_VALUE) {
            notifyError(pid, "Respuesta no parseable: " + response);
            return;
        }

        listener.onObdData(pid, rawValue);
    }

    /**
     * Saca los bytes de datos de la respuesta y deja la decodificación en manos
     * de ObdPids.decode(), que es donde viven todas las fórmulas y unidades.
     *
     * No basta con coger bytes[2]/bytes[3] a ciegas: hay que validar la cabecera
     * "41 <pid>" y localizar los datos RESPECTO a ella. De lo contrario se
     * decodifican como valores reales respuestas que no lo son —negativas
     * (0x7F...), tramas de otro PID que llegan desfasadas, restos de "SEARCHING"
     * o ruido de línea— y salen números irreales (RPM fantasma con el coche
     * parado, cientos de L/h de consumo, saltos bruscos).
     *
     * @return valor decodificado, o Integer.MIN_VALUE si la respuesta no vale
     */
    private int extractObdValue(@NonNull String response, @NonNull String pid) {
        // La respuesta puede incluir espacios: "41 0C 1A F8" o sin espacios "410C1AF8".
        int[] bytes = extractHexBytes(response);
        if (bytes == null) {
            return Integer.MIN_VALUE;
        }

        // Cabecera esperada de una respuesta positiva: (0x40 | modo) seguido del byte de PID
        int expectedMode = 0x40 | ((hexDigit(pid.charAt(0)) << 4) | hexDigit(pid.charAt(1)));
        int expectedPid  = (hexDigit(pid.charAt(2)) << 4) | hexDigit(pid.charAt(3));

        // Buscamos la cabecera dentro
        for (int j = 0; j + 2 < bytes.length; j++) {
            if (bytes[j] == expectedMode && bytes[j + 1] == expectedPid) {
                int a = bytes[j + 2];
                int b = (j + 3 < bytes.length) ? bytes[j + 3] : 0;
                return ObdPids.decode(pid, a, b);
            }
        }

        // Sin cabecera válida para este PID: respuesta no valida.
        return Integer.MIN_VALUE;
    }

    /**
     * Convierte la respuesta de texto ("41 0C 1A F8") en bytes numéricos,
     * sin trocear el String (nada de split, que crea arrays a lo loco).
     *
     * @return un byte por posición (0-255), o null si no había nada hex válido
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

    /** Valor numérico de un carácter hex (0-15), o -1 si no es hex. */
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
     * Espera el tiempo de backoff actual y lo duplica para la próxima vez.
     *
     * @return true si la espera terminó normal; false si nos interrumpió stop()
     *         y hay que salir del bucle
     */
    private boolean sleepBackoff() {
        long delay = currentBackoffMs;

        // Duplicamos para la próxima vez, con tope en BACKOFF_MAX_MS.
        currentBackoffMs = Math.min(currentBackoffMs * 2, BACKOFF_MAX_MS);

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
        if (newState == state) {
            return; // sin cambio real: no renotificamos al listener
        }
        state = newState;
        listener.onStateChanged(newState);
    }

    private void notifyError(@NonNull String pid, @NonNull String description) {
        listener.onObdError(pid, description);
    }

    /** Cierra streams y socket sin protestar, y deja los campos a null. */
    private void closeConnection() {
        IoUtils.closeQuietly(inputStream);
        IoUtils.closeQuietly(outputStream);
        IoUtils.closeQuietly(socket);
        inputStream  = null;
        outputStream = null;
        socket       = null;
    }

    /**
     * Como String.contains() pero sin distinguir mayúsculas de minúsculas,
     * y sin crear Strings nuevos por el camino (nada de toUpperCase).
     */
    private static boolean containsIgnoreCase(@NonNull String source, @NonNull String token) {
        int max = source.length() - token.length();
        for (int i = 0; i <= max; i++) {
            if (source.regionMatches(true, i, token, 0, token.length())) {
                return true;
            }
        }
        return false;
    }
}
