package obdmap.launcher.obd;

import androidx.annotation.NonNull;

/**
 * Calcula el consumo de combustible (instantáneo y medio) para motor diésel.
 *
 * Usa el mejor dato disponible, por este orden:
 *
 * 1. PID 015E (caudal directo de la ECU): el más fiable. Se activa solo en
 *    cuanto llega el primer valor válido.
 * 2. MAF: estimación a partir del aire que entra. En diésel sobreestima a
 *    carga parcial (la mezcla siempre lleva aire de sobra), así que es solo
 *    un plan B.
 * 3. Speed-density (MAP + IAT + RPM): pendiente de implementar.
 *
 * El consumo medio se calcula sobre los últimos 5 minutos con un ring buffer
 * de arrays primitivos: cero objetos nuevos por muestra.
 *
 * Cuando casi no hay velocidad, el L/100km se dispara a infinito y no dice
 * nada útil: en ese caso se devuelve NO_DATA y la UI debe enseñar L/h.
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

    /** Por debajo de esta velocidad el L/100km no es representativo. */
    public static final float MIN_SPEED_KMH = 3.0f;

    /** Valor que significa "sin dato". Comprobar con Float.isNaN(). */
    public static final float NO_DATA = Float.NaN;

    /** Ventana del consumo medio: 5 minutos. */
    private static final long WINDOW_DURATION_MS = 5L * 60L * 1000L;

    /**
     * Tamaño del ring buffer. A 5 Hz durante 5 min salen 1500 muestras;
     * 1600 da margen. Son 16 bytes por muestra → ~25 KB en total, nada.
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

    // -------------------------------------------------------------------------
    // Modelo de hilos: onXxxUpdated/addSample llegan desde el hilo OBD; los
    // getters se llaman desde el main thread (UI). Los escalares son volatile
    // para garantizar visibilidad; el ring buffer se protege con `lock`.
    // -------------------------------------------------------------------------

    /** Metodo activo actualmente. Escrito en hilo OBD, leído desde la UI. */
    private volatile int activeMethod = METHOD_NONE;

    /** true una vez que 015E respondió con un valor válido (>0). */
    private volatile boolean fuelRateSupported = false;

    // -------------------------------------------------------------------------
    // Últimos valores recibidos de los PIDs (todos en unidades reales)
    // -------------------------------------------------------------------------

    /** Flujo de masa de aire en g/s (raw del PID 0110 dividido entre 100). */
    private volatile float lastMafGs = NO_DATA;

    /** Tasa de combustible en L/h (raw del PID 015E dividido entre 20). */
    private volatile float lastFuelRateLh = NO_DATA;

    /** Velocidad del vehículo en km/h (PID 010D directo). */
    private volatile float lastSpeedKmh = NO_DATA;

    /** Protege el ring buffer (writeIndex, sampleCount y los tres arrays). */
    private final Object lock = new Object();

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
     * Nuevo valor de MAF (PID 0110).
     *
     * @param rawMaf punto fijo g/s×100, tal como lo entrega el reader
     */
    public void onMafUpdated(int rawMaf) {
        lastMafGs = rawMaf / MAF_DIVISOR;
        // Si 015E no está soportado, el MAF activa el metodo de fallback.
        if (!fuelRateSupported && activeMethod == METHOD_NONE) {
            activeMethod = METHOD_MAF;
        }
    }

    /**
     * Nuevo valor de caudal de combustible (PID 015E). El primer valor
     * positivo confirma que la ECU soporta el PID y activa este método
     * como fuente preferida.
     *
     * @param rawFuelRate punto fijo L/h×20, tal como lo entrega el reader
     */
    public void onFuelRateUpdated(int rawFuelRate) {
        if (!fuelRateSupported) {
            // Detección de soporte: solo un valor estrictamente positivo demuestra
            // que la ECU implementa el PID (un 0 aislado podría ser respuesta vacía).
            if (rawFuelRate <= 0) {
                return;
            }
            fuelRateSupported = true;
            activeMethod = METHOD_FUEL_RATE;
        } else if (rawFuelRate < 0) {
            // Respuesta inválida puntual: conservamos el último valor bueno.
            return;
        }
        // Con el soporte ya confirmado, el 0 ES un dato real: corte de inyección
        // al decelerar con marcha engranada (0 L/h). Ignorarlo inflaría el consumo.
        lastFuelRateLh = rawFuelRate / FUEL_RATE_DIVISOR;
    }

    /** Nueva velocidad (PID 010D), en km/h. */
    public void onSpeedUpdated(int speedKmh) {
        lastSpeedKmh = speedKmh;
    }

    // =========================================================================
    // API pública — cálculo de consumo instantáneo
    // =========================================================================

    /**
     * Consumo instantáneo en L/h con el mejor método disponible,
     * o NO_DATA si todavía no hay con qué calcularlo.
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
     * Consumo instantáneo en L/100km. Devuelve NO_DATA si vamos casi parados
     * (el valor no significaría nada) o si aún no hay dato de consumo.
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

    /** Qué método de cálculo está en uso (una de las constantes METHOD_*). */
    public int getActiveMethod() {
        return activeMethod;
    }

    // =========================================================================
    // API pública — consumo medio (ventana móvil)
    // =========================================================================

    /**
     * Guarda una muestra para el consumo medio. Llamar una vez por ciclo de
     * polling, cuando velocidad y consumo ya están frescos.
     *
     * @param nowMs System.currentTimeMillis() del momento de la muestra
     */
    public void addSample(long nowMs) {
        float instantLh = getInstantLh();
        if (Float.isNaN(instantLh)) {
            // Sin dato de consumo: no acumulamos nada para no contaminar el promedio.
            return;
        }
        synchronized (lock) {
            sampleTimestamps[writeIndex] = nowMs;
            sampleLh[writeIndex]         = instantLh;
            sampleSpeedKmh[writeIndex]   = lastSpeedKmh;

            writeIndex = (writeIndex + 1) % RING_SIZE;
            if (sampleCount < RING_SIZE) {
                sampleCount++;
            }
        }
    }

    /**
     * Consumo medio en L/100km de los últimos 5 minutos. Si en ese rato apenas
     * nos hemos movido (coche parado), devuelve NO_DATA: dividir por una
     * distancia ínfima daría un número absurdo.
     *
     * Recorre el buffer entero (como mucho 1600 vueltas), que a la frecuencia
     * a la que se llama es despreciable.
     *
     * @param nowMs System.currentTimeMillis() para situar el borde de la ventana
     */
    public float getAverageL100km(long nowMs) {
        long windowStart = nowMs - WINDOW_DURATION_MS;

        double totalDistanceKm  = 0.0;
        double totalFuelL       = 0.0;

        synchronized (lock) {
            if (sampleCount == 0) {
                return NO_DATA;
            }

            // El ring buffer puede estar parcialmente lleno. Iteramos las sampleCount
            // entradas más recientes navegando hacia atrás desde writeIndex.
            for (int i = 0; i < sampleCount; i++) {
                // Índice circular hacia atrás desde el último elemento escrito.
                int idx = (writeIndex - 1 - i + RING_SIZE) % RING_SIZE;

                long ts = sampleTimestamps[idx];
                if (ts < windowStart) {
                    // Las siguientes entradas son aún más viejas: salimos.
                    break;
                }

                // dt en horas para integrar con las unidades en L/h y km/h.
                // Para la primera muestra (i == 0) no hay muestra siguiente dentro del
                // buffer, así que usamos nowMs como referencia. Para las demás,
                // dt = diferencia con la muestra inmediatamente más reciente.
                float dtH;
                if (i == 0) {
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
        }

        if (totalDistanceKm < 0.001) {
            // Distancia insignificante: L/100km sería inútil.
            return NO_DATA;
        }

        return (float) (totalFuelL / totalDistanceKm * 100.0);
    }

    /** Borra el histórico del consumo medio y empieza de cero. */
    public void resetAverage() {
        synchronized (lock) {
            writeIndex  = 0;
            sampleCount = 0;
        }
    }

    // =========================================================================
    // Cálculo interno por metodo MAF
    // =========================================================================

    /**
     * Estima L/h a partir del flujo de aire (MAF): se divide el aire entre la
     * relación aire/combustible y se pasa de gramos a litros con la densidad.
     *
     * Cuidado: un diésel casi siempre trabaja con exceso de aire, así que esta
     * cuenta SOBREESTIMA el consumo a carga parcial. Es solo el plan B cuando
     * la ECU no da el caudal directo (015E).
     */
    private float calcLhFromMaf() {
        if (Float.isNaN(lastMafGs)) {
            return NO_DATA;
        }
        float fuelGs = lastMafGs / AFR_DIESEL;
        return fuelGs * 3600.0f / DENSIDAD_DIESEL;
    }
}
