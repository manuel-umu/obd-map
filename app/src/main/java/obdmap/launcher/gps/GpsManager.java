package obdmap.launcher.gps;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;

import androidx.annotation.NonNull;

/**
 * Recibe la posición del GPS interno de la radio a 1 Hz, usando el
 * LocationManager de Android a pelo. Nada de Google Play Services: las
 * radios chinas no suelen traerlos y tampoco los necesitamos.
 *
 * Quien llame a start() debe haber comprobado antes el permiso de ubicación.
 */
public final class GpsManager {

    /** Frecuencia mínima de actualización solicitada al proveedor (ms). */
    private static final long MIN_INTERVAL_MS = 1000L;

    /** Distancia mínima entre actualizaciones (m). 0 = no filtrar por distancia. */
    private static final float MIN_DISTANCE_M = 0f;

    /**
     * Callback de posición. Llega en el hilo principal, así que se pueden
     * tocar vistas directamente.
     */
    public interface PositionListener {
        /**
         * @param hasBearing si es false, el fix no trae rumbo fiable:
         *                   bearingDegrees vale 0 y no sirve para rotar la flecha
         */
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

        // Listener interno único: se reutiliza durante toda la vida del manager.
        // No es un hot path (1 Hz), así que la clase anónima no impacta.
        this.internalListener = new android.location.LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                float bearing = location.hasBearing() ? location.getBearing() : 0f;
                float speed = location.hasSpeed() ? location.getSpeed() : 0f;
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
