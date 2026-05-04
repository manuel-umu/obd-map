package com.obdmap.launcher.obd;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Constantes de estado del lector OBD2. Sustituye a un Enum para evitar
 * el overhead de boxing y la creación de objetos estáticos en arranque.
 */
public final class ObdState {

    public static final int DISCONNECTED  = 0;
    public static final int CONNECTING    = 1;
    /** Handshake AT en curso (ATZ → ATE0 → ATL0 → ATSP0). */
    public static final int INITIALIZING  = 2;
    public static final int READY         = 3;
    public static final int RECONNECTING  = 4;
    /** Fallo definitivo: falta MAC, BT desactivado o handshake rechazado. */
    public static final int FAILED        = 5;

    @IntDef({DISCONNECTED, CONNECTING, INITIALIZING, READY, RECONNECTING, FAILED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    private ObdState() {}
}
