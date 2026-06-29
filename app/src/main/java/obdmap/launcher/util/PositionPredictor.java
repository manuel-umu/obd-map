package obdmap.launcher.util;

import androidx.annotation.NonNull;

/**
 * Proyecta una posición GPS hacia delante en la dirección del rumbo para
 * compensar la latencia del animador del mapa (~750 ms) y el ciclo GPS (~1 s).
 *
 * <p>No tiene estado: todos los métodos son estáticos. No crea objetos en el
 * hot path; escribe el resultado en el array {@code out} pasado como parametro
 */
public final class PositionPredictor {

    /**
     * Milisegundos de adelanto a proyectar. Debe ser >= a la duración de la
     * animación del viewport (MapManager.ANIM_DURATION_MS = 750 ms) más la
     * latencia típica del GPS (250 ms). Subir si la flecha sigue por detrás;
     * bajar si se adelanta demasiado en frenadas o curvas cerradas.
     */
    public static final long LOOKAHEAD_MS = 600L;

    /**
     * Distancia máxima de adelanto en metros, por si el GPS envia algo raro
     */
    public static final double MAX_LEAD_METERS = 60.0;

    /**
     * Velocidad mínima en m/s para activar la predicción
     */
    public static final float MIN_PREDICT_SPEED_MS = 1.0f;

    /**
     * Metros por grado de latitud
     */
    private static final double METERS_PER_DEG = 111320.0;

    private PositionPredictor() {}

    /**
     * Calcula la posición predicha proyectando (lat, lon) una distancia
     * {@code speedMs * (lookaheadMs / 1000.0)} metros en la dirección de
     * {@code bearingDeg}, acotada a {@code maxLeadMeters}.
     *
     * <p>Escribe el resultado en {@code out[0]=lat, out[1]=lon} y devuelve
     * {@code true} solo si la predicción procede. Devuelve {@code false} (sin
     * tocar {@code out}) cuando el coche está parado o el bearing no es fiable.
     *
     * @param lat           latitud del punto de partida
     * @param lon           longitud del punto de partida
     * @param bearingDeg    azimut geográfico en grados [0, 360): 0=N, 90=E
     * @param hasBearing    {@code true} si hay bearing válido
     * @param speedMs       velocidad en m/s
     * @param lookaheadMs   milisegundos de adelanto a proyectar
     * @param maxLeadMeters distancia máxima de adelanto en metros
     * @param out           array para guardar la latitud/longitud predicha
     * @return {@code true} si se escribió una posición predicha en {@code out}
     */
    public static boolean predict(double lat, double lon,
                                  float bearingDeg, boolean hasBearing,
                                  float speedMs,
                                  long lookaheadMs, double maxLeadMeters,
                                  @NonNull double[] out) {
        // Sin bearing fiable o velocidad demasiado baja: no predecir
        if (!hasBearing || speedMs < MIN_PREDICT_SPEED_MS) {
            return false;
        }

        // Distancia de adelanto natural, acotada al tope de seguridad
        double d = speedMs * (lookaheadMs / 1000.0);
        if (d > maxLeadMeters) {
            d = maxLeadMeters;
        }

        // Proyección esférica simplificada
        double bearingRad = Math.toRadians(bearingDeg);
        double latRad = Math.toRadians(lat);

        double dLat = (d * Math.cos(bearingRad)) / METERS_PER_DEG;
        // cos(lat) escala los grados de longitud a metros en la latitud actual
        double cosLat = Math.cos(latRad);
        double dLon = (cosLat > 1e-9) ? (d * Math.sin(bearingRad)) / (METERS_PER_DEG * cosLat) : 0.0;
        out[0] = lat + dLat;
        out[1] = lon + dLon;
        return true;
    }
}
