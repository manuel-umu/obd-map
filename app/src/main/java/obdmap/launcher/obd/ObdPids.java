package obdmap.launcher.obd;

import androidx.annotation.NonNull;

/**
 * Todos los PIDs OBD2 que usa la app, en un solo sitio: el código de cada
 * comando y la fórmula para decodificar su respuesta (estándar SAE J1979).
 * Así el reader, el servicio y las pantallas hablan el mismo idioma.
 *
 * Unidades que devuelve decode():
 * - RPM: revoluciones reales (entero)
 * - SPEED: km/h
 * - LOAD y THROTTLE: porcentaje
 * - COOLANT e IAT: °C (puede salir negativo)
 * - MAP: kPa
 * - MAF: punto fijo g/s×100 (el consumidor divide entre 100)
 * - FUEL_RATE: punto fijo L/h×20 (el consumidor divide entre 20)
 */
public final class ObdPids {

    // -------------------------------------------------------------------------
    // PIDs rápidos (telemetría en vivo, cada ciclo de polling)
    // -------------------------------------------------------------------------
    public static final String RPM       = "010C";
    public static final String SPEED     = "010D";
    public static final String MAF       = "0110";
    public static final String FUEL_RATE = "015E";
    public static final String THROTTLE  = "0111";

    // -------------------------------------------------------------------------
    // PIDs lentos (cambian despacio: temperaturas, presión, carga)
    // -------------------------------------------------------------------------
    public static final String LOAD    = "0104";
    public static final String COOLANT = "0105";
    public static final String IAT     = "010F";
    public static final String MAP     = "010B";

    private ObdPids() {
        // Utilidad estática, no se instancia.
    }

    /**
     * Convierte los bytes de datos de una respuesta en el valor final, en las
     * unidades de la cabecera. Solo aritmética de primitivos: apto para el
     * bucle caliente del reader.
     *
     * @param pid comando enviado, p. ej. RPM
     * @param a   primer byte de datos
     * @param b   segundo byte de datos, o 0 si la respuesta solo trae uno
     * @return el valor decodificado; para PIDs desconocidos, los dos bytes juntos
     */
    public static int decode(@NonNull String pid, int a, int b) {
        if (RPM.equals(pid)) {
            // RPM real = (A*256 + B) / 4. Resolución 0,25 rpm redondeada a entero.
            return ((a << 8) | b) / 4;
        } else if (SPEED.equals(pid)) {
            // Velocidad en km/h, directa.
            return a;
        } else if (LOAD.equals(pid)) {
            // Carga del motor en porcentaje: (A * 100) / 255.
            return (a * 100) / 255;
        } else if (MAF.equals(pid)) {
            // MAF en punto fijo g/s×100: valor = (A*256 + B).
            // NO se divide aquí: en ralentí (~2-4 g/s) la división entera
            // entre 100 perdería casi toda la precisión.
            return (a << 8) | b;
        } else if (THROTTLE.equals(pid)) {
            // Posición del acelerador: (A * 100) / 255 → porcentaje entero.
            return (a * 100) / 255;
        } else if (COOLANT.equals(pid)) {
            // Temperatura del refrigerante: A - 40 → °C. Rango real -40..+215 °C.
            return a - 40;
        } else if (IAT.equals(pid)) {
            // Temperatura de admisión (IAT): A - 40 → °C. Mismo encoding que 0105.
            return a - 40;
        } else if (MAP.equals(pid)) {
            // Presión absoluta del colector (MAP): A → kPa directo.
            return a;
        } else if (FUEL_RATE.equals(pid)) {
            // Tasa de combustible (Engine Fuel Rate): L/h = (A*256 + B) / 20.0.
            // Se devuelve el raw sin dividir para no perder precisión;
            // el consumidor (FuelCalculator) divide entre 20.
            return (a << 8) | b;
        } else {
            // PID genérico: devolvemos los primeros dos bytes de datos combinados
            // para no perder información que el consumidor pueda interpretar.
            return (a << 8) | b;
        }
    }
}
