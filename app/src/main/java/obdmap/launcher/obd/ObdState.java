package obdmap.launcher.obd;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Estados del lector OBD2. Son constantes int en vez de un Enum para no
 * crear objetos (regla del proyecto en hardware con poca RAM).
 */
public final class ObdState {

    public static final int DISCONNECTED  = 0;
    public static final int CONNECTING    = 1;
    /** Conectados; enviando los comandos AT de inicialización. */
    public static final int INITIALIZING  = 2;
    public static final int READY         = 3;
    public static final int RECONNECTING  = 4;
    /** Fallo sin remedio: no hay adaptador Bluetooth o la MAC no vale. */
    public static final int FAILED        = 5;

    @IntDef({DISCONNECTED, CONNECTING, INITIALIZING, READY, RECONNECTING, FAILED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    private ObdState() {}
}
