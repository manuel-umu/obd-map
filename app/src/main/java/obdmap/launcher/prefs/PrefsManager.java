package obdmap.launcher.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Acceso tipado a SharedPreferences. Toda la persistencia de la app pasa por
 * aquí — nada de bases de datos (Room/SQLite), que no caben en el presupuesto
 * de RAM. Se crea con new donde haga falta; sin singletons ni inyección.
 */
public final class PrefsManager {

    // Nombre del archivo de SharedPreferences. Privado al paquete.
    private static final String PREFS_FILE = "obd_map_prefs";

    // ---------------------------------------------------------------------
    // Claves de almacenamiento — privadas: el resto de la app accede siempre
    // a través de los getters/setters tipados, nunca por clave directa.
    // ---------------------------------------------------------------------
    // MAC del adaptador ELM327 emparejado, para reconectar sin escanear.
    private static final String KEY_OBD_MAC = "obd_mac";

    // Última posición conocida (lat/lon en float — precisión sobrada para coches
    // y la mitad de memoria que double).
    private static final String KEY_LAST_LAT = "last_lat";
    private static final String KEY_LAST_LON = "last_lon";

    // Ruta absoluta al archivo .map de Mapsforge seleccionado por el usuario.
    private static final String KEY_MAP_FILE_PATH = "map_file_path";

    // ---------------------------------------------------------------------
    // Estado interno
    // ---------------------------------------------------------------------
    private final SharedPreferences prefs;

    public PrefsManager(@NonNull Context context) {
        // applicationContext para no retener Activities y evitar fugas.
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------------
    // OBD MAC
    // ---------------------------------------------------------------------
    @Nullable
    public String getObdMac() {
        return prefs.getString(KEY_OBD_MAC, null);
    }

    public void setObdMac(@Nullable String mac) {
        // apply() es asíncrono — más rápido y suficiente para este caso de uso.
        prefs.edit().putString(KEY_OBD_MAC, mac).apply();
    }

    public void clearObdMac() {
        prefs.edit().remove(KEY_OBD_MAC).apply();
    }

    // ---------------------------------------------------------------------
    // Última posición GPS
    // ---------------------------------------------------------------------
    public float getLastLatitude() {
        return prefs.getFloat(KEY_LAST_LAT, 0f);
    }

    public float getLastLongitude() {
        return prefs.getFloat(KEY_LAST_LON, 0f);
    }

    /** Guarda lat y lon de una vez, en una sola escritura a disco. */
    public void setLastPosition(float latitude, float longitude) {
        prefs.edit()
                .putFloat(KEY_LAST_LAT, latitude)
                .putFloat(KEY_LAST_LON, longitude)
                .apply();
    }

    // ---------------------------------------------------------------------
    // Ruta del archivo .map
    // ---------------------------------------------------------------------
    @Nullable
    public String getMapFilePath() {
        return prefs.getString(KEY_MAP_FILE_PATH, null);
    }

    public void setMapFilePath(@Nullable String path) {
        prefs.edit().putString(KEY_MAP_FILE_PATH, path).apply();
    }
}
