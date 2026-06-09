package obdmap.launcher.obd;

import androidx.annotation.NonNull;

/**
 * Calcula el consumo instantáneo y medio de combustible para motores DIÉSEL.
 *
 * <h3>Cadena de prioridad de métodos:</h3>
 * <ol>
 *   <li><b>015E (Engine Fuel Rate)</b>: si el ECU lo soporta, es el dato más directo.
 *       Se activa automáticamente la primera vez que llega un valor válido.</li>
 *   <li><b>MAF (Mass Air Flow)</b>: cálculo estequiométrico a partir del flujo de aire.
 *       Nota: en diésel la mezcla es pobre a carga parcial (exceso de aire), por lo que
 *       este metodo SOBREESTIMA el consumo real. Solo es un fallback.</li>
 *   <li><b>Speed-Density (MAP + IAT + RPM)</b>: TODO — pendiente de implementar en
 *       iteración futura. Por ahora devuelve {@link #NO_DATA} si los dos anteriores fallan.</li>
 * </ol>
 *
 * <h3>Consumo medio — ring buffer:</h3>
 * <p>Se mantiene una ventana temporal de {@link #WINDOW_DURATION_MS} milisegundos.
 * Se usan arrays paralelos de primitivos para almacenar cada muestra sin crear objetos.
 * Las muestras anteriores a la ventana se descartan en cada inserción.</p>
 *
 * <h3>Sentinel para L/100km:</h3>
 * <p>Cuando la velocidad es menor que {@link #MIN_SPEED_KMH}, L/100km no tiene sentido
 * físico (divisor cerca de cero). En ese caso {@link #getInstantL100km()} devuelve
 * {@link #NO_DATA} y la UI debe mostrar el valor en L/h en su lugar.</p>
 */
public final class FuelCalculator {

    // -------------------------------------------------------------------------
    // Constantes de combustible diésel
    // -------------------------------------------------------------------------

    /** Relación aire-combustible estequiométrica del diésel (λ = 1). */
    private static final float AFR_DIESEL = 14.5f;

    /** Densidad del gasóleo tipo B (media estándar) en g/L. */
    private static final float DENSIDAD_DIESEL = 832.0f;

    /** Divisor para convertir el raw de 015E a L/h. */
    private static final float FUEL_RATE_DIVISOR = 20.0f;

    /** Divisor para convertir el raw de MAF (g/s × 100) a g/s. */
    private static final float MAF_DIVISOR = 100.0f;

    // -------------------------------------------------------------------------
    // Constantes de comportamiento
    // -------------------------------------------------------------------------

    /**
     * Velocidad mínima por debajo de la cual L/100km no es representativo.
     * Por debajo de este umbral se devuelve {@link #NO_DATA} para L/100km.
     */
    public static final float MIN_SPEED_KMH = 3.0f;

    /** Sentinel que indica "sin dato disponible". */
    public static final float NO_DATA = Float.NaN;

    /** Duración de la ventana del consumo medio en milisegundos (5 minutos) */
    private static final long WINDOW_DURATION_MS = 5L * 60L * 1000L;

    /**
     * Tamaño del ring buffer. A 5 Hz durante 5 min = 1500 muestras.
     * 1600 para tener margen sin desperdiciar RAM.
     * Cada muestra ocupa: 8B (long) + 4B (float) + 4B (float) = 16 bytes.
     * Total: 1600 × 16 = 25 600 bytes ≈ 25 KB.
     */
    private static final int RING_SIZE = 1600;

    // -------------------------------------------------------------------------
    // Detección de soporte de 015E
    // -------------------------------------------------------------------------

    /** @IntDef para el metodo de cálculo activo. */
    public static final int METHOD_NONE         = 0;
    public static final int METHOD_FUEL_RATE    = 1; // 015E
    public static final int METHOD_MAF          = 2;
    public static final int METHOD_SPEED_DENSITY = 3; // no implementado aún

    /** Metodo activo actualmente. Accedido solo desde el hilo de polling. */
    private int activeMethod = METHOD_NONE;

    /** true una vez que 015E respondió con un valor != -1. */
    private boolean fuelRateSupported = false;

    // -------------------------------------------------------------------------
    // Últimos valores recibidos de los PIDs (todos en unidades reales)
    // -------------------------------------------------------------------------

    /** Flujo de masa de aire en g/s (raw del PID 0110 dividido entre 100). */
    private float lastMafGs = NO_DATA;

    /** Tasa de combustible en L/h (raw del PID 015E dividido entre 20). */
    private float lastFuelRateLh = NO_DATA;

    /** Velocidad del vehículo en km/h (PID 010D directo). */
    private float lastSpeedKmh = NO_DATA;

    // -------------------------------------------------------------------------
    // Ring buffer para el consumo medio
    // -------------------------------------------------------------------------

    /** Timestamps de cada muestra (System.currentTimeMillis). */
    private final long[]  sampleTimestamps = new long[RING_SIZE];

    /** Consumo instantáneo en L/h de cada muestra. */
    private final float[] sampleLh         = new float[RING_SIZE];

    /** Velocidad en km/h de cada muestra. */
    private final float[] sampleSpeedKmh   = new float[RING_SIZE];

    /** Índice de escritura en el ring buffer (avanza circularmente). */
    private int writeIndex = 0;

    /** Número de muestras válidas actualmente en el buffer (0..RING_SIZE). */
    private int sampleCount = 0;

    // =========================================================================
    // Alimentación de datos
    // =========================================================================

    /**
     * Notifica un nuevo valor del PID 0110 (MAF).
     * Se llama desde el hilo de polling; no crea objetos.
     *
     * @param rawMaf valor punto fijo g/s×100 entregado por {@code extractObdValue}
     */
    public void onMafUpdated(int rawMaf) {
        lastMafGs = rawMaf / MAF_DIVISOR;
        // Si 015E no está soportado, el MAF activa el metodo de fallback.
        if (!fuelRateSupported && activeMethod == METHOD_NONE) {
            activeMethod = METHOD_MAF;
        }
    }

    /**
     * Notifica un nuevo valor del PID 015E (Engine Fuel Rate).
     * La primera vez que llega un valor válido activa este metodo prioritario.
     * Se llama desde el hilo de polling; no crea objetos.
     *
     * @param rawFuelRate valor punto fijo entregado por {@code extractObdValue};
     *                    -1 o {@link Integer#MIN_VALUE} indica que el ECU no soporta el PID
     */
    public void onFuelRateUpdated(int rawFuelRate) {
        if (rawFuelRate <= 0) {
            // El ECU responde NO DATA o valor cero: no activamos 015E.
            return;
        }
        lastFuelRateLh = rawFuelRate / FUEL_RATE_DIVISOR;
        if (!fuelRateSupported) {
            fuelRateSupported = true;
            activeMethod = METHOD_FUEL_RATE;
        }
    }

    /**
     * Notifica un nuevo valor del PID 010D (velocidad).
     * Se llama desde el hilo de polling; no crea objetos.
     *
     * @param speedKmh velocidad en km/h (valor directo del PID)
     */
    public void onSpeedUpdated(int speedKmh) {
        lastSpeedKmh = speedKmh;
    }

    // =========================================================================
    // API pública — cálculo de consumo instantáneo
    // =========================================================================

    /**
     * Consumo instantáneo en L/h según el mejor metodo disponible.
     * Devuelve {@link #NO_DATA} si no hay datos suficientes.
     */
    public float getInstantLh() {
        switch (activeMethod) {
            case METHOD_FUEL_RATE:
                return lastFuelRateLh;
            case METHOD_MAF:
                return calcLhFromMaf();
            default:
                // TODO: speed-density (MAP + IAT + RPM) no implementado aún.
                return NO_DATA;
        }
    }

    /**
     * Consumo instantáneo en L/100km.
     * Devuelve {@link #NO_DATA} si la velocidad es menor que {@link #MIN_SPEED_KMH}
     * o si no hay datos de consumo disponibles.
     */
    public float getInstantL100km() {
        if (lastSpeedKmh < MIN_SPEED_KMH) {
            // A velocidades muy bajas o en parado el resultado sería infinito o inútil.
            return NO_DATA;
        }
        float lh = getInstantLh();
        if (Float.isNaN(lh)) {
            return NO_DATA;
        }
        // L/100km = (L/h) / (km/h) * 100
        return (lh / lastSpeedKmh) * 100.0f;
    }

    /**
     * Metodo de cálculo que se está usando actualmente.
     * Uno de {@link #METHOD_NONE}, {@link #METHOD_FUEL_RATE},
     * {@link #METHOD_MAF}, {@link #METHOD_SPEED_DENSITY}.
     */
    public int getActiveMethod() {
        return activeMethod;
    }

    // =========================================================================
    // API pública — consumo medio (ventana móvil)
    // =========================================================================

    /**
     * Registra una nueva muestra de telemetría en el ring buffer.
     * Debe llamarse cada vez que llegan datos frescos (al mismo ritmo que el polling rápido).
     * No crea objetos.
     *
     * @param nowMs timestamp actual (System.currentTimeMillis)
     */
    public void addSample(long nowMs) {
        float instantLh = getInstantLh();
        if (Float.isNaN(instantLh)) {
            // Sin dato de consumo: no acumulamos nada para no contaminar el promedio.
            return;
        }
        sampleTimestamps[writeIndex] = nowMs;
        sampleLh[writeIndex]         = instantLh;
        sampleSpeedKmh[writeIndex]   = lastSpeedKmh;

        writeIndex = (writeIndex + 1) % RING_SIZE;
        if (sampleCount < RING_SIZE) {
            sampleCount++;
        }
    }

    /**
     * Consumo medio en L/100km calculado sobre la ventana de los últimos 5 minutos.
     * Devuelve {@link #NO_DATA} si la distancia acumulada en la ventana es ínfima
     * (vehículo parado casi todo el tiempo).
     *
     * <p>Recorre el ring buffer sin crear objetos. La complejidad es O(n) con n = muestras
     * en ventana, que en el peor caso es {@link #RING_SIZE} = 1600 iteraciones,
     * negligible frente a la frecuencia de llamada.</p>
     *
     * @param nowMs timestamp actual para determinar el borde de la ventana
     */
    public float getAverageL100km(long nowMs) {
        if (sampleCount == 0) {
            return NO_DATA;
        }

        long windowStart = nowMs - WINDOW_DURATION_MS;

        double totalDistanceKm  = 0.0;
        double totalFuelL       = 0.0;

        // El ring buffer puede estar parcialmente lleno. Iteramos las sampleCount entradas
        // más recientes navegando hacia atrás desde writeIndex.
        for (int i = 0; i < sampleCount; i++) {
            // Índice circular hacia atrás desde el último elemento escrito.
            int idx = (writeIndex - 1 - i + RING_SIZE) % RING_SIZE;

            long ts = sampleTimestamps[idx];
            if (ts < windowStart) {
                // Las siguientes entradas son aún más viejas: salimos.
                break;
            }

            // dt en horas para integrar con las unidades en L/h y km/h.
            // Para la primera muestra (i == 0) no hay muestra siguiente dentro del buffer,
            // así que estimamos dt basándonos en el intervalo de polling típico (200 ms).
            // Para las demás, dt = diferencia entre esta muestra y la anterior (más reciente).
            float dtH;
            if (i == 0) {
                // Muestra más reciente: usamos nowMs como referencia.
                dtH = (nowMs - ts) / 3_600_000.0f;
                if (dtH <= 0.0f) {
                    dtH = 200.0f / 3_600_000.0f; // fallback: 200 ms
                }
            } else {
                int prevIdx = (writeIndex - i + RING_SIZE) % RING_SIZE;
                long prevTs = sampleTimestamps[prevIdx];
                dtH = (prevTs - ts) / 3_600_000.0f;
                if (dtH <= 0.0f) {
                    dtH = 200.0f / 3_600_000.0f;
                }
            }

            totalDistanceKm += sampleSpeedKmh[idx] * dtH;
            totalFuelL      += sampleLh[idx] * dtH;
        }

        if (totalDistanceKm < 0.001) {
            // Distancia insignificante: L/100km sería inútil.
            return NO_DATA;
        }

        return (float) (totalFuelL / totalDistanceKm * 100.0);
    }

    /**
     * Resetea el consumo medio borrando todas las muestras del ring buffer.
     * No libera memoria (los arrays ya estaban preallocados).
     */
    public void resetAverage() {
        writeIndex  = 0;
        sampleCount = 0;
    }

    // =========================================================================
    // Cálculo interno por metodo MAF
    // =========================================================================

    /**
     * Calcula L/h a partir del MAF usando estequiometría diésel.
     *
     * <p>Fórmula: combustible_g/s = MAF_g/s / AFR_diesel;
     * L/h = combustible_g/s * 3600 / densidad_diesel_g/L.</p>
     *
     * <p>ADVERTENCIA: en diésel la mezcla es siempre pobre (λ > 1) salvo bajo carga
     * máxima. Usar AFR estequiométrico SOBREESTIMA el consumo real. Solo es un
     * fallback cuando 015E no está disponible.</p>
     */
    private float calcLhFromMaf() {
        if (Float.isNaN(lastMafGs)) {
            return NO_DATA;
        }
        float fuelGs = lastMafGs / AFR_DIESEL;
        return fuelGs * 3600.0f / DENSIDAD_DIESEL;
    }
}
