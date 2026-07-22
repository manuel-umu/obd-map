package obdmap.launcher.prefs;

import android.annotation.SuppressLint;
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

    // Preferencia de modo noche (true = noche, false = día).
    private static final String KEY_NIGHT_MODE = "night_mode";

    // Identificador de la región de datos offline activa (ver RegionData).
    private static final String KEY_ACTIVE_REGION = "active_region";

    // Prefijo de la versión de datos instalada, por región: se le concatena el id
    private static final String KEY_DATA_VERSION_PREFIX = "data_version_";

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

    // ---------------------------------------------------------------------
    // Destino de ruta
    // ---------------------------------------------------------------------
    private static final String DEST_LAT_KEY = "dest_lat";
    private static final String DEST_LON_KEY = "dest_lon";

    public float getDestLat() {
        return Float.intBitsToFloat(prefs.getInt(DEST_LAT_KEY, Float.floatToIntBits(Float.NaN)));
    }
    public float getDestLon() {
        return Float.intBitsToFloat(prefs.getInt(DEST_LON_KEY, Float.floatToIntBits(Float.NaN)));
    }

    /** Persiste las coordenadas del destino de ruta */
    public void setDestination(float lat, float lon) {
        prefs.edit()
                .putInt(DEST_LAT_KEY, Float.floatToIntBits(lat))
                .putInt(DEST_LON_KEY, Float.floatToIntBits(lon))
                .apply();
    }

    /** Elimina el destino de ruta guardado */
    public void clearDestination() {
        prefs.edit()
                .remove(DEST_LAT_KEY)
                .remove(DEST_LON_KEY)
                .apply();
    }

    // ---------------------------------------------------------------------
    // Región de datos offline
    // ---------------------------------------------------------------------

    /** Id de la región activa, o null para usar la de por defecto. */
    @Nullable
    public String getActiveRegionId() {
        return prefs.getString(KEY_ACTIVE_REGION, null);
    }

    public void setActiveRegionId(@Nullable String regionId) {
        prefs.edit().putString(KEY_ACTIVE_REGION, regionId).apply();
    }

    /**
     * Versión de datos instalada de una región, o null si no hay ninguna.
     */
    @Nullable
    public String getInstalledDataVersion(@NonNull String regionId) {
        return prefs.getString(KEY_DATA_VERSION_PREFIX + regionId, null);
    }

    /**
     * Marca la versión de datos instalada
     */
    @SuppressLint("ApplySharedPref")
    public void setInstalledDataVersion(@NonNull String regionId, @NonNull String version) {
        prefs.edit().putString(KEY_DATA_VERSION_PREFIX + regionId, version).commit();
    }

    /** Invalida la versión instalada de una región (fuerza reinstalación). */
    @SuppressLint("ApplySharedPref")
    public void clearInstalledDataVersion(@NonNull String regionId) {
        prefs.edit().remove(KEY_DATA_VERSION_PREFIX + regionId).commit();
    }

    // ---------------------------------------------------------------------
    // Modo día/noche
    // ---------------------------------------------------------------------
    public boolean isNightMode() {
        return prefs.getBoolean(KEY_NIGHT_MODE, false);
    }

    public void setNightMode(boolean night) {
        prefs.edit().putBoolean(KEY_NIGHT_MODE, night).apply();
    }
}
