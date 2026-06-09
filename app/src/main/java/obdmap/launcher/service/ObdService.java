package obdmap.launcher.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import obdmap.launcher.BuildConfig;
import obdmap.launcher.R;
import obdmap.launcher.obd.BluetoothObdReader;
import obdmap.launcher.obd.FuelCalculator;
import obdmap.launcher.obd.ObdListener;
import obdmap.launcher.obd.ObdPids;
import obdmap.launcher.obd.ObdState;
import obdmap.launcher.prefs.PrefsManager;

/**
 * Foreground Service que mantiene vivo el lector OBD durante todo el trayecto,
 * aunque la pantalla cambie de Activity. Guarda los últimos valores de cada
 * PID y se los sirve a la UI a través de un Binder local (sin broadcasts).
 *
 * El polling corre en su propio HandlerThread; el hilo de UI no se toca.
 */
public final class ObdService extends Service implements ObdListener {

    private static final String TAG = "ObdService";

    // Los PIDs y sus fórmulas viven en ObdPids (fuente única de verdad).
    // Aquí solo se decide CUÁNDO se sondea cada uno (rápidos vs lentos).

    /** Cada cuánto se piden los PIDs rápidos: 200 ms = 5 Hz. */
    private static final long POLL_INTERVAL_MS = 200L;

    /** Los PIDs lentos van una vez cada 25 ciclos (25 × 200 ms = 5 s). */
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

    /** Gestiona la notificación persistente del foreground service. */
    @Nullable private ObdNotifications notifications;

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
     * Binder local: la Activity hace bind y con getService() obtiene la
     * instancia viva del servicio. Sin broadcasts ni IntentFilters.
     */
    public final class LocalBinder extends Binder {
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

        notifications = new ObdNotifications(this);
        notifications.createChannel();

        if (mac == null || mac.isEmpty() || !android.bluetooth.BluetoothAdapter.checkBluetoothAddress(mac)) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "MAC inválida o no configurada — servicio detenido sin lanzar reader");
            }
            // Necesitamos startForeground antes de stopSelf en API 26+
            // para evitar ANR de "Context.startForegroundService() did not call startForeground()".
            startForeground(ObdNotifications.NOTIFICATION_ID,
                    notifications.build(getString(R.string.obd_notif_no_mac)));
            stopSelf();
            return;
        }

        startForeground(ObdNotifications.NOTIFICATION_ID,
                notifications.build(getString(R.string.obd_state_connecting)));

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
     * Registra quién recibe las actualizaciones (en el main thread).
     * Solo puede haber uno a la vez; pasar null para desregistrar.
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
     * Temperatura del refrigerante en °C (PID 0105). Puede ser negativa,
     * así que el "sin dato" es Integer.MIN_VALUE, no -1.
     */
    public int getLastCoolant() {
        return lastCoolant;
    }

    /**
     * Temperatura del aire de admisión en °C (PID 010F). Igual que el
     * refrigerante: sin dato = Integer.MIN_VALUE.
     */
    public int getLastIat() {
        return lastIat;
    }

    /** Presión absoluta del colector en kPa (PID 010B). -1 si sin dato. */
    public int getLastMapKpa() {
        return lastMapKpa;
    }

    /**
     * Caudal de combustible en bruto (PID 015E): dividir entre 20 para L/h.
     * -1 = sin dato aún; 0 = la ECU respondió con cero.
     */
    public int getLastFuelRateRaw() {
        return lastFuelRateRaw;
    }

    /** Consumo instantáneo en L/h, o NaN si aún no se puede calcular. */
    public float getInstantLh() {
        return fuelCalculator.getInstantLh();
    }

    /** Consumo instantáneo en L/100km, o NaN si vamos casi parados o sin dato. */
    public float getInstantL100km() {
        return fuelCalculator.getInstantL100km();
    }

    /** Consumo medio de los últimos 5 minutos en L/100km, o NaN si no hay muestras. */
    public float getAverageL100km() {
        return fuelCalculator.getAverageL100km(System.currentTimeMillis());
    }

    /** Qué método de consumo está activo (constantes METHOD_* de FuelCalculator). */
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

        if (notifications != null) {
            notifications.update(state);
        }

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
        if (ObdPids.RPM.equals(pid)) {
            lastRpm = rawValue;
        } else if (ObdPids.SPEED.equals(pid)) {
            lastSpeed = rawValue;
            fuelCalculator.onSpeedUpdated(rawValue);
        } else if (ObdPids.LOAD.equals(pid)) {
            lastLoad = rawValue;
        } else if (ObdPids.MAF.equals(pid)) {
            fuelCalculator.onMafUpdated(rawValue);
        } else if (ObdPids.FUEL_RATE.equals(pid)) {
            lastFuelRateRaw = rawValue;
            fuelCalculator.onFuelRateUpdated(rawValue);
        } else if (ObdPids.THROTTLE.equals(pid)) {
            lastThrottle = rawValue;
            // Último PID rápido del ciclo: registrar UNA muestra por ciclo (~5 Hz).
            // Así el ring buffer de 1600 entradas cubre ~320 s ≈ la ventana de 5 min.
            fuelCalculator.addSample(System.currentTimeMillis());
        } else if (ObdPids.COOLANT.equals(pid)) {
            lastCoolant = rawValue;
        } else if (ObdPids.IAT.equals(pid)) {
            lastIat = rawValue;
        } else if (ObdPids.MAP.equals(pid)) {
            lastMapKpa = rawValue;
        }
    }

    // =========================================================================
    // Polling de PIDs
    // =========================================================================

    /** Arranca el polling si no estaba ya en marcha. */
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
     * Polling escalonado. El ELM327 no da abasto con 9 PIDs a 5 Hz, así que
     * se reparten: los rápidos (RPM, velocidad, MAF, caudal, acelerador) van
     * en cada ciclo; los lentos (carga, temperaturas, MAP) solo una vez cada
     * 5 segundos, que para una temperatura sobra.
     *
     * Corre en el HandlerThread del servicio, nunca en el de UI.
     */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pollingActive || reader == null) {
                return;
            }

            // --- PIDs rápidos: siempre ---
            reader.enqueuePid(ObdPids.RPM);
            reader.enqueuePid(ObdPids.SPEED);
            reader.enqueuePid(ObdPids.MAF);
            reader.enqueuePid(ObdPids.FUEL_RATE);
            reader.enqueuePid(ObdPids.THROTTLE);

            // --- PIDs lentos: una vez cada SLOW_CYCLE_COUNT ciclos ---
            pollCycleCounter++;
            if (pollCycleCounter >= SLOW_CYCLE_COUNT) {
                pollCycleCounter = 0;
                reader.enqueuePid(ObdPids.LOAD);
                reader.enqueuePid(ObdPids.COOLANT);
                reader.enqueuePid(ObdPids.IAT);
                reader.enqueuePid(ObdPids.MAP);
            }

            if (pollingActive && pollingHandler != null) {
                pollingHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

}
