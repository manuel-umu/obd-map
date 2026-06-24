package obdmap.launcher.routing;

import androidx.annotation.NonNull;

/**
 * Resultado inmutable de un cálculo de ruta.
 *
 * Los puntos se guardan como arrays primitivos paralelos
 */
public final class Route {

    /** Latitudes de cada punto de la polilínea, en grados decimales. */
    public final double[] lats;

    /** Longitudes de cada punto de la polilínea, en grados decimales. */
    public final double[] lons;

    /** Distancia total de la ruta en metros. */
    public final double distanceMeters;

    /** Tiempo estimado de viaje en milisegundos. */
    public final long timeMs;

    /**
     * Construye un Route con los arrays ya rellenados.
     * Los arrays deben tener la misma longitud.
     */
    public Route(@NonNull double[] lats, @NonNull double[] lons,
                 double distanceMeters, long timeMs) {
        this.lats = lats;
        this.lons = lons;
        this.distanceMeters = distanceMeters;
        this.timeMs = timeMs;
    }

    /** Número de puntos en la polilínea. */
    public int pointCount() {
        return lats.length;
    }
}
