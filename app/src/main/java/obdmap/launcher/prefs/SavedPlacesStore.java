package obdmap.launcher.prefs;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Lista de sitios guardados
 */
public final class SavedPlacesStore {

    /** Tope de sitios guardados; al superarlo se descarta el más antiguo. */
    public static final int MAX_PLACES = 30;

    /** Radio en metros dentro del cual dos coordenadas son el mismo sitio. */
    private static final double MATCH_RADIUS_M = 25.0;

    // Metros por grado
    private static final double METERS_PER_DEG = 111320.0;

    private static final char FIELD_SEPARATOR = ';';
    private final PrefsManager prefsManager;

    public SavedPlacesStore(@NonNull PrefsManager prefsManager) {
        this.prefsManager = prefsManager;
    }

    @NonNull
    public List<SavedPlace> load() {
        List<SavedPlace> places = new ArrayList<>();
        String raw = prefsManager.getSavedPlacesRaw();
        if (raw == null || raw.isEmpty()) {
            return places;
        }
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            SavedPlace place = parseLine(lines[i]);
            if (place != null) {
                places.add(place);
            }
        }
        return places;
    }

    public void add(@NonNull SavedPlace place) {
        List<SavedPlace> places = load();
        places.add(place);
        while (places.size() > MAX_PLACES) {
            places.remove(0);
        }
        save(places);
    }

    public boolean isSaved(double lat, double lon) {
        List<SavedPlace> places = load();
        for (int i = 0; i < places.size(); i++) {
            SavedPlace place = places.get(i);
            if (metersBetween(lat, lon, place.lat, place.lon) <= MATCH_RADIUS_M) {
                return true;
            }
        }
        return false;
    }

    public void removeNear(double lat, double lon) {
        List<SavedPlace> places = load();
        boolean changed = false;
        for (int i = places.size() - 1; i >= 0; i--) {
            SavedPlace place = places.get(i);
            if (metersBetween(lat, lon, place.lat, place.lon) <= MATCH_RADIUS_M) {
                places.remove(i);
                changed = true;
            }
        }
        if (changed) {
            save(places);
        }
    }

    public void rename(int index, @NonNull String newName) {
        List<SavedPlace> places = load();
        if (index < 0 || index >= places.size()) {
            return;
        }
        SavedPlace old = places.get(index);
        places.set(index, new SavedPlace(old.lat, old.lon, newName));
        save(places);
    }

    public void removeAt(int index) {
        List<SavedPlace> places = load();
        if (index < 0 || index >= places.size()) {
            return;
        }
        places.remove(index);
        save(places);
    }

    private void save(@NonNull List<SavedPlace> places) {
        StringBuilder sb = new StringBuilder(places.size() * 48);
        for (int i = 0; i < places.size(); i++) {
            SavedPlace place = places.get(i);
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(place.lat)
                    .append(FIELD_SEPARATOR)
                    .append(place.lon)
                    .append(FIELD_SEPARATOR)
                    .append(sanitize(place.name));
        }
        prefsManager.setSavedPlacesRaw(sb.toString());
    }

    // Auxiliares

    // Devuelve null si la línea está vacía o mal formada.
    private static SavedPlace parseLine(@NonNull String line) {
        int first = line.indexOf(FIELD_SEPARATOR);
        if (first < 0) {
            return null;
        }
        int second = line.indexOf(FIELD_SEPARATOR, first + 1);
        if (second < 0) {
            return null;
        }
        try {
            double lat = Double.parseDouble(line.substring(0, first));
            double lon = Double.parseDouble(line.substring(first + 1, second));
            return new SavedPlace(lat, lon, line.substring(second + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Quita los caracteres que romperían el formato de una línea por registro
    @NonNull
    private static String sanitize(@NonNull String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != FIELD_SEPARATOR && c != '\n' && c != '\r') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static double metersBetween(double lat1, double lon1,
                                        double lat2, double lon2) {
        double dLat = (lat2 - lat1) * METERS_PER_DEG;
        double dLon = (lon2 - lon1) * METERS_PER_DEG * Math.cos(Math.toRadians(lat1));
        return Math.sqrt(dLat * dLat + dLon * dLon);
    }
}
