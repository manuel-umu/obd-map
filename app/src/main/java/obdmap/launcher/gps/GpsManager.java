package obdmap.launcher.gps;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;

import androidx.annotation.NonNull;

/**
 * Recibe la posición del GPS interno de la radio a 1 Hz
 */
public final class GpsManager {

    /** Frecuencia mínima de actualización solicitada al proveedor (ms). */
    private static final long MIN_INTERVAL_MS = 200L;

    /** Distancia mínima entre actualizaciones (m). 0 = no filtrar por distancia. */
    private static final float MIN_DISTANCE_M = 0f;

    /**
     * Umbral de precisión horizontal (metros).
     */
    private static final float MAX_ACCURACY_FOR_SPEED_M = 25.0f;

    /**
     * Velocidad minima considerada real (m/s).
     */
    private static final float MIN_SPEED_MS = 1.0f;

    public interface PositionListener {
        void onPositionUpdate(double latitude, double longitude,
                              float bearingDegrees, boolean hasBearing, float speedMs);
        void onProviderDisabled();
    }

    private final LocationManager locationManager;
    private final PositionListener listener;
    private final android.location.LocationListener internalListener;
    private boolean active;

    public GpsManager(@NonNull Context context, @NonNull PositionListener listener) {
        this.locationManager = (LocationManager) context.getApplicationContext()
                .getSystemService(Context.LOCATION_SERVICE);
        this.listener = listener;

        this.internalListener = new android.location.LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                float bearing = location.hasBearing() ? location.getBearing() : 0f;

                // Filtrado segun precision
                float speed = 0f;
                if (location.hasSpeed()) {
                    boolean accuracyOk = !location.hasAccuracy()
                            || location.getAccuracy() <= MAX_ACCURACY_FOR_SPEED_M;
                    float raw = location.getSpeed();
                    if (accuracyOk && raw >= MIN_SPEED_MS) {
                        speed = raw;
                    }
                }
                GpsManager.this.listener.onPositionUpdate(
                        location.getLatitude(),
                        location.getLongitude(),
                        bearing,
                        location.hasBearing(),
                        speed);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
                // Sin uso por ahora; se rellenará cuando interese mostrar nº de satélites.
            }

            @Override
            public void onProviderEnabled(String provider) {
                // Sin uso por ahora.
            }

            @Override
            public void onProviderDisabled(String provider) {
                GpsManager.this.listener.onProviderDisabled();
            }
        };
    }

    /**
     * Empieza a escuchar el GPS. Lanza SecurityException si no tenemos
     * el permiso de ubicación.
     */
    @SuppressLint("MissingPermission")
    public void start() throws SecurityException {
        if (active || locationManager == null) {
            return;
        }
        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                internalListener);
        active = true;
    }

    /** Deja de escuchar el GPS. Se puede llamar aunque ya esté parado. */
    public void stop() {
        if (!active || locationManager == null) {
            return;
        }
        locationManager.removeUpdates(internalListener);
        active = false;
    }
}
