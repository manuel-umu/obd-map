package obdmap.launcher.routing;

import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * Vía por la que circula el coche, con el último valor conocido retenido.
 */
public final class CurrentRoad {

    /**
     * Matrícula de carretera: A-7, AP-7, N-332, RM-410...
     */
    private static final Pattern ROAD_REF =
            Pattern.compile("^[A-Z]{1,3}-[A-Z]?\\d+[A-Za-z]?$");

    /** Tiempo que se mantiene la última referencia sin dato nuevo. */
    private static final long HOLD_MS = 20_000L;

    @Nullable private String ref;
    private long refTimeMs;

    /** Nombre crudo de la arista actual, ya filtrado por clase de vía. */
    @Nullable private String pendingName;

    /** Booleano si la arista actual es autovía, nacional o primaria */
    private boolean pendingMajor;

    /** Recoge el resultado del snap del fix actual. */
    public void setEdge(@Nullable String name, boolean major) {
        pendingName  = name;
        pendingMajor = major;
    }

    /**
     * Consolida el fix y devuelve la referencia a mostrar, o null si no hay.
     * En vía mayor sin nombre se conserva la última referencia: OSM deja sin
     * etiquetar ~40% de los tramos de autovía y el panel parpadearía.
     */
    @Nullable
    public String resolve(long nowMs) {
        if (!pendingMajor) {
            ref = null;
            return null;
        }
        String candidate = extractRef(pendingName);
        if (candidate != null) {
            ref = candidate;
            refTimeMs = nowMs;
        } else if (ref != null && nowMs - refTimeMs > HOLD_MS) {
            ref = null;
        }
        return ref;
    }

    /** Limpia el estado retenido (al perder el GPS o el grafo). */
    public void clear() {
        ref = null;
        pendingName = null;
        pendingMajor = false;
    }

    @Nullable
    private static String extractRef(@Nullable String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        int semi = name.indexOf(';');
        String first = (semi > 0) ? name.substring(0, semi) : name;
        first = first.trim();
        return ROAD_REF.matcher(first).matches() ? first : null;
    }
}
