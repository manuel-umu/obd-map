package obdmap.launcher.obd;

import androidx.annotation.NonNull;

/**
 * Callbacks del lector OBD2. Llegan desde el hilo OBD, no desde el de UI:
 * quien actualice vistas debe postear al main thread.
 */
public interface ObdListener {

    /** La conexión cambió de estado (uno de los valores de ObdState). */
    void onStateChanged(@ObdState.State int state);

    /**
     * Llegó un dato nuevo. Parámetros primitivos a propósito: esto se llama
     * muchas veces por segundo y no queremos crear objetos.
     *
     * @param pid      qué PID respondió, p. ej. "010C"
     * @param rawValue valor ya en unidades reales (rpm, km/h, %, °C, kPa).
     *                 Excepciones en punto fijo: MAF es g/s×100 y FUEL_RATE
     *                 es L/h×20 — ver ObdPids.
     */
    void onObdData(@NonNull String pid, int rawValue);

    /**
     * Algo fue mal con un PID concreto (respuesta rara, timeout), pero la
     * conexión sigue viva.
     *
     * @param pid         PID que falló, o cadena vacía si el error es general
     * @param description explicación corta legible
     */
    void onObdError(@NonNull String pid, @NonNull String description);
}
