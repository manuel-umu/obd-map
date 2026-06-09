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
    // PIDs que se encolan en cada ronda
    // -------------------------------------------------------------------------
    private static final String PID_RPM    = "010C";
    private static final String PID_SPEED  = "010D";
    private static final String PID_LOAD   = "0104";
    private static final String PID_MAF    = "0110";

    /**
     * Periodo de polling en milisegundos: 200 ms = 5 Hz.
     * Dentro del rango 5-10 Hz definido en el CLAUDE.md.
     */
    private static final long POLL_INTERVAL_MS = 200L;

    // -------------------------------------------------------------------------
    // Estado interno
    // -------------------------------------------------------------------------
    @Nullable private BluetoothObdReader reader;

    /** Estado actual del reader; leído desde cualquier hilo. */
    @ObdState.State
    private volatile int currentState = ObdState.DISCONNECTED;

    /** Últimos valores brutos recibidos para cada PID. -1 = sin dato aún. */
    private volatile int lastRpm   = -1;
    private volatile int lastSpeed = -1;
    private volatile int lastLoad  = -1;

    /** Timestamp (System.currentTimeMillis) de la última lectura de cualquier PID. */
    private volatile long lastReadingTimestampMs = 0L;

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

    /** Valor bruto del PID 010D (km/h directo). -1 si sin dato. */
    public int getLastSpeed() {
        return lastSpeed;
    }

    /** Valor bruto del PID 0104 (% de carga). -1 si sin dato. */
    public int getLastLoad() {
        return lastLoad;
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
        } else if (PID_LOAD.equals(pid)) {
            lastLoad = rawValue;
        }
        // 0110 (MAF) se ignora aquí hasta la Fase 3.
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
        if (pollingHandler != null) {
            pollingHandler.removeCallbacks(pollRunnable);
        }
    }

    /**
     * Runnable que encola los cuatro PIDs en el reader y se reprograma a sí mismo.
     * Corre en el HandlerThread del servicio, no en el UI thread.
     */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pollingActive || reader == null) {
                return;
            }

            reader.enqueuePid(PID_RPM);
            reader.enqueuePid(PID_SPEED);
            reader.enqueuePid(PID_LOAD);
            reader.enqueuePid(PID_MAF);

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
