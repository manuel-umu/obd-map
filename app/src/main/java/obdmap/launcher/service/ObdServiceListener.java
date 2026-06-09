package obdmap.launcher.service;

import androidx.annotation.NonNull;

import obdmap.launcher.obd.ObdState;

/**
 * Contrato para que la UI reciba actualizaciones del {@link ObdService} sin
 * usar broadcasts. Los callbacks llegan siempre en el main thread.
 */
public interface ObdServiceListener {

    /**
     * El estado del reader cambió.
     *
     * @param state nuevo estado; uno de los valores de {@link ObdState}
     */
    void onObdStateChanged(@ObdState.State int state);

    /**
     * Llegó un nuevo valor bruto para un PID.
     *
     * @param pid      código de 4 caracteres (p. ej. {@code "010C"})
     * @param rawValue valor entero crudo tal como lo interpreta el reader
     */
    void onObdDataUpdated(@NonNull String pid, int rawValue);
}
