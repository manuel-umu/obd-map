package com.obdmap.launcher.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Wrapper tipado sobre {@link SharedPreferences}. Sin singletons automáticos,
 * sin inyección de dependencias: el llamador instancia con {@code new PrefsManager(context)}
 * cuando lo necesita.
 *
 * <p>Toda la persistencia del proyecto pasa por aquí. No se usan bases de datos
 * pesadas (Room/SQLite) por presupuesto de RAM.</p>
 */
public final class PrefsManager {

    // Nombre del archivo de SharedPreferences. Privado al paquete.
    private static final String PREFS_FILE = "obd_map_prefs";

    // ---------------------------------------------------------------------
    // Claves de almacenamiento
    // ---------------------------------------------------------------------
    // MAC del adaptador ELM327 emparejado, para reconectar sin escanear.
    public static final String KEY_OBD_MAC = "obd_mac";

    // Última posición conocida (lat/lon en float — precisión sobrada para coches
    // y la mitad de memoria que double).
    public static final String KEY_LAST_LAT = "last_lat";
    public static final String KEY_LAST_LON = "last_lon";

    // Ruta absoluta al archivo .map de Mapsforge seleccionado por el usuario.
    public static final String KEY_MAP_FILE_PATH = "map_file_path";

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

    /**
     * Guarda la última posición conocida en una sola transacción para minimizar I/O.
     */
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
