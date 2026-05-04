package com.obdmap.launcher.obd;

import androidx.annotation.NonNull;

/**
 * Interfaz de callbacks del lector OBD2. Los métodos se invocan desde el
 * hilo OBD, no desde el UI thread. El implementador debe hacer {@code post}
 * al hilo principal si necesita actualizar Views.
 */
public interface ObdListener {

    /**
     * Notifica un cambio de estado de la conexión.
     *
     * @param state uno de los valores de {@link ObdState}
     */
    void onStateChanged(@ObdState.State int state);

    /**
     * Notifica una respuesta OBD2 parseada. Los parámetros son primitivos para
     * no crear objetos por llamada en el bucle caliente.
     *
     * @param pid      código de 4 caracteres del modo/PID (p. ej. {@code "010C"})
     * @param rawValue valor entero calculado a partir de los bytes de la respuesta;
     *                 la interpretación (RPM = rawValue / 4, km/h = rawValue, etc.)
     *                 corre a cargo del Bloque C
     */
    void onObdData(@NonNull String pid, int rawValue);

    /**
     * Informa de un error no fatal (respuesta malformada, timeout de un PID).
     * La conexión continúa activa.
     *
     * @param pid  PID que causó el error, o cadena vacía si el error es genérico
     * @param description descripción breve legible
     */
    void onObdError(@NonNull String pid, @NonNull String description);
}
