package obdmap.launcher.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import obdmap.launcher.R;
import obdmap.launcher.obd.ObdState;

/**
 * La notificación persistente del ObdService: crear el canal, construirla y
 * actualizar su texto según el estado de la conexión. Está aquí fuera para
 * que el servicio se ocupe solo de su ciclo de vida y el polling.
 */
final class ObdNotifications {

    /** ID de la notificación del foreground service. */
    static final int NOTIFICATION_ID = 1001;

    private static final String CHANNEL_ID   = "obd_service_channel";
    private static final String CHANNEL_NAME = "OBD-Map activo";
    private static final String CHANNEL_DESC =
            "Mantiene la lectura de datos OBD2 en segundo plano";

    private final Service service;

    ObdNotifications(@NonNull Service service) {
        this.service = service;
    }

    /** Crea el canal de notificación (obligatorio en API 26+; antes no hace nada). */
    void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(CHANNEL_DESC);
            // Sin sonido ni vibración: es una notificación permanente de estado.
            channel.setSound(null, null);

            NotificationManager nm = service.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    /** Construye la notificación persistente con el texto dado. */
    @NonNull
    Notification build(@NonNull String contentText) {
        return new NotificationCompat.Builder(service, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_obd_notif)
                .setContentTitle(service.getString(R.string.app_name))
                .setContentText(contentText)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /** Cambia el texto de la notificación según el estado, sin sonido ni molestias. */
    void update(@ObdState.State int state) {
        NotificationManager nm =
                (NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, build(stateText(state)));
        }
    }

    @NonNull
    private String stateText(@ObdState.State int state) {
        switch (state) {
            case ObdState.INITIALIZING:  return service.getString(R.string.obd_state_initializing);
            case ObdState.READY:         return service.getString(R.string.obd_state_connected);
            case ObdState.RECONNECTING:  return service.getString(R.string.obd_state_reconnecting);
            case ObdState.FAILED:        return service.getString(R.string.obd_state_failed_short);
            case ObdState.CONNECTING:
            default:                     return service.getString(R.string.obd_state_connecting);
        }
    }
}
