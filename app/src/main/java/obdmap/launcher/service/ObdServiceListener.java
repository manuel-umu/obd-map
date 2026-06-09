package obdmap.launcher.service;

import androidx.annotation.NonNull;

import obdmap.launcher.obd.ObdState;

/**
 * Lo que la UI escucha del ObdService. A diferencia de los callbacks del
 * reader, estos llegan siempre en el main thread: se pueden tocar vistas
 * directamente.
 */
public interface ObdServiceListener {

    /** La conexión OBD cambió de estado (valores de ObdState). */
    void onObdStateChanged(@ObdState.State int state);

    /**
     * Llegó un dato nuevo de un PID.
     *
     * @param pid      qué PID, p. ej. "010C"
     * @param rawValue valor decodificado, en las unidades que documenta ObdPids
     */
    void onObdDataUpdated(@NonNull String pid, int rawValue);
}
