package obdmap.launcher.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import obdmap.launcher.BuildConfig;
import obdmap.launcher.R;
import obdmap.launcher.obd.BluetoothObdReader;
import obdmap.launcher.obd.FuelCalculator;
import obdmap.launcher.obd.ObdListener;
import obdmap.launcher.obd.ObdState;
import obdmap.launcher.prefs.PrefsManager;

/**
 * Foreground Service que mantiene el ciclo de vida del {@link BluetoothObdReader}
 * durante el trayecto. Expone los datos a la UI mediante un Binder local para
 * no añadir la dependencia de LocalBroadcastManager (no está en el classpath).
 *
 * <p>El polling de PIDs corre en un {@link HandlerThread} propio del servicio
 * para no tocar el UI thread.</p>
 */
public final class ObdService extends Service implements ObdListener {

    private static final String TAG = "ObdService";

    // -------------------------------------------------------------------------
    // Notificación persistente
    // -------------------------------------------------------------------------
    static final String NOTIF_CHANNEL_ID   = "obd_service_channel";
    static final String NOTIF_CHANNEL_NAME = "OBD-Map activo";
    static final String NOTIF_CHANNEL_DESC = "Mantiene la lectura de datos OBD2 en segundo plano";
    private static final int NOTIF_ID = 1001;

    // -------------------------------------------------------------------------
    // PIDs rápidos (cada ciclo) y lentos (cada SLOW_CYCLE_COUNT ciclos)
    // -------------------------------------------------------------------------

    // --- PIDs rápidos: se encolan en cada ciclo de POLL_INTERVAL_MS ---
    private static final String PID_RPM        = "010C";
    private static final String PID_SPEED      = "010D";
    private static final String PID_MAF        = "0110";
    private static final String PID_FUEL_RATE  = "015E";
    private static final String PID_THROTTLE   = "0111";

    // --- PIDs lentos: se encolan cada SLOW_CYCLE_COUNT ciclos ---
    private static final String PID_LOAD       = "0104";
    private static final String PID_COOLANT    = "0105";
    private static final String PID_IAT        = "010F";
    private static final String PID_MAP        = "010B";

    /**
     * Periodo de polling en milisegundos: 200 ms = 5 Hz.
     * Dentro del rango 5-10 Hz definido en el CLAUDE.md.
     */
    private static final long POLL_INTERVAL_MS = 200L;

    /**
     * Número de ciclos rápidos entre cada ronda de PIDs lentos.
     * 25 ciclos × 200 ms = 5 s por muestra de los PIDs lentos.
     */
    private static final int SLOW_CYCLE_COUNT = 25;

    // -------------------------------------------------------------------------
    // Estado interno
    // -------------------------------------------------------------------------
    @Nullable private BluetoothObdReader reader;

    /** Estado actual del reader; leído desde cualquier hilo. */
    @ObdState.State
    private volatile int currentState = ObdState.DISCONNECTED;

    /** Últimos valores brutos recibidos para cada PID. -1 = sin dato aún. */
    private volatile int lastRpm      = -1;
    private volatile int lastSpeed    = -1;
    private volatile int lastLoad     = -1;
    private volatile int lastThrottle = -1;
    private volatile int lastCoolant  = Integer.MIN_VALUE; // puede ser negativo
    private volatile int lastIat      = Integer.MIN_VALUE; // puede ser negativo
    private volatile int lastMapKpa   = -1;
    // lastFuelRateRaw: -1 = sin dato; 0 = ECU respondió pero con valor cero.
    private volatile int lastFuelRateRaw = -1;

    /** Timestamp (System.currentTimeMillis) de la última lectura de cualquier PID. */
    private volatile long lastReadingTimestampMs = 0L;

    /** Calculadora de consumo; instanciada una vez, sin dependencias externas. */
    private final FuelCalculator fuelCalculator = new FuelCalculator();

    /** Contador de ciclos del polling para escalonar los PIDs lentos. */
    private int pollCycleCounter = 0;

    /**
     * Descripción del último error (p. ej. "dispositivo no emparejado").
     * Solo es relevante cuando {@link #currentState} es {@link ObdState#FAILED}.
     */
    @Nullable private volatile String lastErrorDescription;

    // HandlerThread dedicado para el polling; no usa el UI thread.
    @Nullable private HandlerThread pollingThread;
    @Nullable private Handler pollingHandler;

    /** true si el Runnable de polling está programado. Accedido desde hilos OBD y poll. */
    private volatile boolean pollingActive = false;

    // Handler sobre el main looper para repostear callbacks a la UI.
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Listener registrado por la Activity de debug. Puede ser null. */
    @Nullable private ObdServiceListener serviceListener;

    // -------------------------------------------------------------------------
    // Binder
    // -------------------------------------------------------------------------

    private final IBinder binder = new LocalBinder();

    /**
     * Binder que la Activity usa para obtener la referencia al servicio
     * y registrar/desregistrar su listener sin IntentFilter ni broadcasts.
     */
    public final class LocalBinder extends Binder {
        /** @return la instancia viva del servicio. */
        public ObdService getService() {
            return ObdService.this;
        }
    }

    // =========================================================================
    // Ciclo de vida del Service
    // =========================================================================

    @Override
    public void onCreate() {
        super.onCreate();

        PrefsManager prefs = new PrefsManager(this);
        String mac = prefs.getObdMac();

        if (mac == null || mac.isEmpty() || !android.bluetooth.BluetoothAdapter.checkBluetoothAddress(mac)) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "MAC inválida o no configurada — servicio detenido sin lanzar reader");
            }
            // Necesitamos startForeground antes de stopSelf en API 26+
            // para evitar ANR de "Context.startForegroundService() did not call startForeground()".
            createNotificationChannel();
            startForeground(NOTIF_ID, buildNotification(getString(R.string.obd_notif_no_mac)));
            stopSelf();
            return;
        }

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification(getString(R.string.obd_state_connecting)));

        pollingThread = new HandlerThread("obd-poll");
        pollingThread.start();
        pollingHandler = new Handler(pollingThread.getLooper());

        reader = new BluetoothObdReader(mac, this);
        reader.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // El servicio se arranca con startForegroundService; no necesitamos
        // reaccionar a intents extra en esta fase.
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopPolling();

        if (reader != null) {
            reader.stop();
            reader = null;
        }

        if (pollingThread != null) {
            pollingThread.quitSafely();
            pollingThread = null;
        }

        stopForeground(true);
        super.onDestroy();
    }

    // =========================================================================
    // API pública para la Activity de debug
    // =========================================================================

    /**
     * Registra un listener que recibirá actualizaciones de estado y datos en el
     * main thread. Solo puede haber un listener activo a la vez.
     *
     * @param listener listener a registrar, o {@code null} para desregistrar
     */
    public void setServiceListener(@Nullable ObdServiceListener listener) {
        // No se necesita sincronización: las Activities hacen bind/unbind en el
        // main thread, y las notificaciones se postean al main thread también.
        serviceListener = listener;
    }

    /** Devuelve el estado actual del reader. Seguro desde cualquier hilo. */
    @ObdState.State
    public int getObdState() {
        return currentState;
    }

    /** RPM real del motor (PID 010C, ya decodificado). -1 si sin dato. */
    public int getLastRpm() {
        return lastRpm;
    }

    /** Velocidad en km/h (PID 010D directo). -1 si sin dato. */
    public int getLastSpeed() {
        return lastSpeed;
    }

    /** Carga del motor en % (PID 0104). -1 si sin dato. */
    public int getLastLoad() {
        return lastLoad;
    }

    /** Posición del acelerador en % (PID 0111). -1 si sin dato. */
    public int getLastThrottle() {
        return lastThrottle;
    }

    /**
     * Temperatura del refrigerante en °C (PID 0105).
     * {@link Integer#MIN_VALUE} indica que aún no hay dato.
     * Puede devolver valores negativos (frío extremo).
     */
    public int getLastCoolant() {
        return lastCoolant;
    }

    /**
     * Temperatura de admisión en °C (PID 010F, IAT).
     * {@link Integer#MIN_VALUE} indica que aún no hay dato.
     * Puede devolver valores negativos.
     */
    public int getLastIat() {
        return lastIat;
    }

    /** Presión absoluta del colector en kPa (PID 010B). -1 si sin dato. */
    public int getLastMapKpa() {
        return lastMapKpa;
    }

    /**
     * Raw del PID 015E (Engine Fuel Rate). Dividir entre 20 para obtener L/h.
     * -1 = sin dato aún; 0 = ECU respondió con cero.
     */
    public int getLastFuelRateRaw() {
        return lastFuelRateRaw;
    }

    /**
     * Consumo instantáneo en L/h calculado por {@link FuelCalculator}.
     * Devuelve {@link Float#NaN} si no hay datos suficientes.
     */
    public float getInstantLh() {
        return fuelCalculator.getInstantLh();
    }

    /**
     * Consumo instantáneo en L/100km.
     * Devuelve {@link Float#NaN} si la velocidad es menor que
     * {@link FuelCalculator#MIN_SPEED_KMH} o si no hay datos.
     */
    public float getInstantL100km() {
        return fuelCalculator.getInstantL100km();
    }

    /**
     * Consumo medio en L/100km sobre la ventana de los últimos 5 minutos.
     * Devuelve {@link Float#NaN} si no hay muestras suficientes.
     */
    public float getAverageL100km() {
        return fuelCalculator.getAverageL100km(System.currentTimeMillis());
    }

    /**
     * Método de cálculo de consumo activo.
     * Uno de {@link FuelCalculator#METHOD_NONE}, {@link FuelCalculator#METHOD_FUEL_RATE},
     * {@link FuelCalculator#METHOD_MAF}, {@link FuelCalculator#METHOD_SPEED_DENSITY}.
     */
    public int getFuelMethod() {
        return fuelCalculator.getActiveMethod();
    }

    /** Resetea el consumo medio (ring buffer). */
    public void resetAverageFuel() {
        fuelCalculator.resetAverage();
    }

    /** Timestamp de la última lectura en ms (epoch), o 0 si no ha llegado ninguna. */
    public long getLastReadingTimestampMs() {
        return lastReadingTimestampMs;
    }

    /** Descripción del último error, relevante cuando el estado es FAILED. */
    @Nullable
    public String getLastErrorDescription() {
        return lastErrorDescription;
    }

    // =========================================================================
    // ObdListener — llegada desde el hilo OBD
    // =========================================================================

    @Override
    public void onStateChanged(@ObdState.State final int state) {
        currentState = state;

        if (state == ObdState.READY) {
            // Desde el polling handler, no desde el UI thread, para no bloquearlo.
            startPolling();
        } else {
            stopPolling();
        }

        updateNotification(state, null);

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (serviceListener != null) {
                    serviceListener.onObdStateChanged(state);
                }
            }
        });
    }

    @Override
    public void onObdData(@NonNull final String pid, final int rawValue) {
        storeValue(pid, rawValue);
        lastReadingTimestampMs = System.currentTimeMillis();

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (serviceListener != null) {
                    serviceListener.onObdDataUpdated(pid, rawValue);
                }
            }
        });
    }

    @Override
    public void onObdError(@NonNull String pid, @NonNull String description) {
        // Solo persistimos el mensaje si es un fallo definitivo, para mostrarlo en debug.
        if (currentState == ObdState.FAILED) {
            lastErrorDescription = description;
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ObdError pid=" + pid + " " + description);
        }
    }

    // =========================================================================
    // Almacenamiento de valores
    // =========================================================================

    private void storeValue(@NonNull String pid, int rawValue) {
        if (PID_RPM.equals(pid)) {
            lastRpm = rawValue;
        } else if (PID_SPEED.equals(pid)) {
            lastSpeed = rawValue;
            fuelCalculator.onSpeedUpdated(rawValue);
        } else if (PID_LOAD.equals(pid)) {
            lastLoad = rawValue;
        } else if (PID_MAF.equals(pid)) {
            fuelCalculator.onMafUpdated(rawValue);
        } else if (PID_FUEL_RATE.equals(pid)) {
            lastFuelRateRaw = rawValue;
            fuelCalculator.onFuelRateUpdated(rawValue);
        } else if (PID_THROTTLE.equals(pid)) {
            lastThrottle = rawValue;
            // Último PID rápido del ciclo: registrar UNA muestra por ciclo (~5 Hz).
            // Así el ring buffer de 1600 entradas cubre ~320 s ≈ la ventana de 5 min.
            fuelCalculator.addSample(System.currentTimeMillis());
        } else if (PID_COOLANT.equals(pid)) {
            lastCoolant = rawValue;
        } else if (PID_IAT.equals(pid)) {
            lastIat = rawValue;
        } else if (PID_MAP.equals(pid)) {
            lastMapKpa = rawValue;
        }

    }

    // =========================================================================
    // Polling de PIDs
    // =========================================================================

    /**
     * Programa la primera ronda de polling en el HandlerThread del servicio.
     * Idempotente: si ya está activo no hace nada.
     */
    private void startPolling() {
        if (pollingActive || pollingHandler == null) {
            return;
        }
        pollingActive = true;
        pollingHandler.post(pollRunnable);
    }

    /** Cancela el polling pendiente. Idempotente. */
    private void stopPolling() {
        pollingActive = false;
        pollCycleCounter = 0;
        if (pollingHandler != null) {
            pollingHandler.removeCallbacks(pollRunnable);
        }
    }

    /**
     * Runnable de polling escalonado.
     *
     * <p>El ELM327 no puede procesar ~9 PIDs a 5 Hz simultáneamente sin acumular
     * retardos. La solución es dividirlos en dos grupos:
     * <ul>
     *   <li><b>Rápidos</b> (cada ciclo, 5 Hz): RPM, velocidad, MAF, fuel rate, acelerador.</li>
     *   <li><b>Lentos</b> (cada {@link #SLOW_CYCLE_COUNT} ciclos, ≈0,2 Hz): carga, refrigerante, IAT, MAP.</li>
     * </ul>
     * El contador {@link #pollCycleCounter} es un campo de instancia de int primitivo
     * para no crear ningún objeto por ciclo.</p>
     *
     * <p>Corre en el HandlerThread del servicio, no en el UI thread.</p>
     */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pollingActive || reader == null) {
                return;
            }

            // --- PIDs rápidos: siempre ---
            reader.enqueuePid(PID_RPM);
            reader.enqueuePid(PID_SPEED);
            reader.enqueuePid(PID_MAF);
            reader.enqueuePid(PID_FUEL_RATE);
            reader.enqueuePid(PID_THROTTLE);

            // --- PIDs lentos: una vez cada SLOW_CYCLE_COUNT ciclos ---
            pollCycleCounter++;
            if (pollCycleCounter >= SLOW_CYCLE_COUNT) {
                pollCycleCounter = 0;
                reader.enqueuePid(PID_LOAD);
                reader.enqueuePid(PID_COOLANT);
                reader.enqueuePid(PID_IAT);
                reader.enqueuePid(PID_MAP);
            }

            if (pollingActive && pollingHandler != null) {
                pollingHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    // =========================================================================
    // Notificación
    // =========================================================================

    /**
     * Crea el canal de notificación requerido en API >= 26.
     * En API 24/25 esta llamada es un no-op seguro.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    NOTIF_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(NOTIF_CHANNEL_DESC);
            // Sin sonido ni vibración: es una notificación permanente de estado.
            channel.setSound(null, null);

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @NonNull
    private Notification buildNotification(@NonNull String contentText) {
        return new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_obd_notif)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /**
     * Actualiza el texto de la notificación persistente para reflejar el estado
     * actual del reader sin recrear el canal ni interrumpir al usuario.
     */
    private void updateNotification(@ObdState.State int state, @Nullable String extra) {
        String text = stateToNotifText(state);
        if (extra != null && !extra.isEmpty()) {
            text = text + ": " + extra;
        }
        Notification notif = buildNotification(text);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, notif);
        }
    }

    @NonNull
    private String stateToNotifText(@ObdState.State int state) {
        switch (state) {
            case ObdState.CONNECTING:    return getString(R.string.obd_state_connecting);
            case ObdState.INITIALIZING:  return getString(R.string.obd_state_initializing);
            case ObdState.READY:         return getString(R.string.obd_state_connected);
            case ObdState.RECONNECTING:  return getString(R.string.obd_state_reconnecting);
            case ObdState.FAILED:        return getString(R.string.obd_state_failed_short);
            default:                     return getString(R.string.obd_state_connecting);
        }
    }
}
