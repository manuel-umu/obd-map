package obdmap.launcher.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import obdmap.launcher.obd.FuelCalculator;
import obdmap.launcher.util.DayNightMode;
import obdmap.launcher.util.SunPosition;

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
    // Clave nueva a propósito: "night_mode" quedó guardada como boolean en
    // instalaciones previas y leerla como int lanzaría ClassCastException.
    private static final String KEY_DAY_NIGHT_PREF = "day_night_pref";

    // Posición de referencia para el cálculo solar sin fix GPS previo.
    private static final double FALLBACK_LAT = 40.416775;
    private static final double FALLBACK_LON = -3.703790;

    // Calibración del consumo por carga: mg de gasoil por carrera a plena carga.
    private static final String KEY_FULL_LOAD_MG = "full_load_mg_per_stroke";

    // Overlay de diagnóstico de snap sobre el mapa (true = visible).
    private static final String KEY_DEBUG_OVERLAY = "debug_overlay";

    // Overlay de diagnóstico de rendimiento sobre el mapa (true = visible).
    private static final String KEY_PERF_OVERLAY = "perf_overlay";
    private static final String KEY_PERF_FULL = "perf_full";

    // Identificador de la región de datos offline activa (ver RegionData).
    private static final String KEY_ACTIVE_REGION = "active_region";

    // Prefijo de la versión de datos instalada, por región: se le concatena el id
    private static final String KEY_DATA_VERSION_PREFIX = "data_version_";

    // Sitios guardados serializados: un registro por línea, campos lat;lon;nombre.
    private static final String KEY_SAVED_PLACES = "saved_places";

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
    // Sitios guardados (favoritos)
    // ---------------------------------------------------------------------
    @Nullable
    public String getSavedPlacesRaw() {
        return prefs.getString(KEY_SAVED_PLACES, null);
    }

    public void setSavedPlacesRaw(@Nullable String raw) {
        prefs.edit().putString(KEY_SAVED_PLACES, raw).apply();
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
    // Overlay de diagnóstico de snap
    // ---------------------------------------------------------------------
    public boolean isDebugOverlayEnabled() {
        return prefs.getBoolean(KEY_DEBUG_OVERLAY, false);
    }

    public void setDebugOverlayEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DEBUG_OVERLAY, enabled).apply();
    }

    // ---------------------------------------------------------------------
    // Overlay de diagnóstico de rendimiento
    // ---------------------------------------------------------------------
    public boolean isPerfOverlayEnabled() {
        return prefs.getBoolean(KEY_PERF_OVERLAY, false);
    }

    public void setPerfOverlayEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PERF_OVERLAY, enabled).apply();
    }

    public boolean isPerfFullEnabled() {
        return prefs.getBoolean(KEY_PERF_FULL, false);
    }
    public void setPerfFullEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PERF_FULL, enabled).apply();
    }

    // ---------------------------------------------------------------------
    // Modo día/noche
    // ---------------------------------------------------------------------
    /** Preferencia elegida: DayNightMode.PREF_AUTO, PREF_DAY o PREF_NIGHT. */
    @DayNightMode.Pref
    public int getDayNightPref() {
        return prefs.getInt(KEY_DAY_NIGHT_PREF, DayNightMode.PREF_AUTO);
    }

    public void setDayNightPref(@DayNightMode.Pref int pref) {
        prefs.edit().putInt(KEY_DAY_NIGHT_PREF, pref).apply();
    }

    /**
     * Modo efectivo: en PREF_AUTO lo decide la altura del sol sobre la última
     * posición conocida.
     */
    public boolean isNightMode() {
        int pref = getDayNightPref();
        if (pref == DayNightMode.PREF_DAY) {
            return false;
        }
        if (pref == DayNightMode.PREF_NIGHT) {
            return true;
        }
        double lat = getLastLatitude();
        double lon = getLastLongitude();
        // Sin fix previo, centro de España: sirve para acertar el ciclo día/noche.
        if (lat == 0.0 && lon == 0.0) {
            lat = FALLBACK_LAT;
            lon = FALLBACK_LON;
        }
        return SunPosition.isNight(lat, lon, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------------
    // Calibración del consumo por carga
    // ---------------------------------------------------------------------
    public float getFullLoadMgPerStroke() {
        return prefs.getFloat(KEY_FULL_LOAD_MG, FuelCalculator.DEFAULT_FULL_LOAD_MG_PER_STROKE);
    }

    public void setFullLoadMgPerStroke(float mgPerStroke) {
        prefs.edit().putFloat(KEY_FULL_LOAD_MG, mgPerStroke).apply();
    }
}
