package obdmap.launcher.routing;

import androidx.annotation.NonNull;

/**
 * Resultado inmutable de un cálculo de ruta.
 *
 * Los puntos se guardan como arrays primitivos paralelos.
 * Las instrucciones de maniobra también son arrays paralelos
 * alineados entre sí (mismo índice = misma instrucción).
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

    // ------------------------------------------------------------------
    // Arrays de instrucciones de maniobra
    // ------------------------------------------------------------------
    /** Constante de maniobra de GraphHopper (Instruction.TURN_LEFT, etc.). */
    public final int[] instrSign;

    /** Nombre de la calle en la que ocurre la maniobra. */
    public final String[] instrName;

    /** Longitud del tramo que precede a la maniobra, en metros. */
    public final double[] instrDistanceM;

    /** Tiempo estimado del tramo que precede a la maniobra, en ms. */
    public final long[] instrTimeMs;

    /** Latitud del punto de inicio de la maniobra. */
    public final double[] instrLat;

    /** Longitud del punto de inicio de la maniobra. */
    public final double[] instrLon;

    /**
     * Constructor  con polilínea e instrucciones de maniobra.
     */
    public Route(@NonNull double[] lats, @NonNull double[] lons,
                 double distanceMeters, long timeMs,
                 @NonNull int[] instrSign,
                 @NonNull String[] instrName,
                 @NonNull double[] instrDistanceM,
                 @NonNull long[] instrTimeMs,
                 @NonNull double[] instrLat,
                 @NonNull double[] instrLon) {
        this.lats = lats;
        this.lons = lons;
        this.distanceMeters = distanceMeters;
        this.timeMs = timeMs;
        this.instrSign = instrSign;
        this.instrName = instrName;
        this.instrDistanceM = instrDistanceM;
        this.instrTimeMs = instrTimeMs;
        this.instrLat = instrLat;
        this.instrLon = instrLon;
    }

    /**
     * Constructor de compatibilidad sin instrucciones.
     */
    public Route(@NonNull double[] lats, @NonNull double[] lons,
                 double distanceMeters, long timeMs) {
        this(lats, lons, distanceMeters, timeMs,
                new int[0], new String[0], new double[0], new long[0],
                new double[0], new double[0]);
    }

    /** Número de puntos en la polilínea. */
    public int pointCount() {
        return lats.length;
    }

    /** Número de instrucciones de maniobra. */
    public int instructionCount() {
        return instrSign.length;
    }
}
