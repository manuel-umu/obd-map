package obdmap.launcher.util;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Constantes para el modo día/noche de la UI y el mapa. */
public final class DayNightMode {
    public static final int DAY   = 0;
    public static final int NIGHT = 1;

    @IntDef({DAY, NIGHT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mode {}

    /** Preferencia del usuario: automático por sol, o forzado a día/noche. */
    public static final int PREF_AUTO  = 0;
    public static final int PREF_DAY   = 1;
    public static final int PREF_NIGHT = 2;

    @IntDef({PREF_AUTO, PREF_DAY, PREF_NIGHT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Pref {}

    private DayNightMode() {}
}
