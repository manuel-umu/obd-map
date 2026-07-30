package obdmap.launcher.prefs;

import androidx.annotation.NonNull;

/** Sitio guardado por el usuario */
public final class SavedPlace {
    public final double lat;
    public final double lon;
    public final String name;
    public SavedPlace(double lat, double lon, @NonNull String name) {
        this.lat = lat;
        this.lon = lon;
        this.name = name;
    }
}
