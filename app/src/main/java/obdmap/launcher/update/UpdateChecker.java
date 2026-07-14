package obdmap.launcher.update;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import obdmap.launcher.BuildConfig;
import obdmap.launcher.util.IoUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Comprueba en GitHub Releases si hay una versión más nueva que la instalada.
 * Hace una única petición HTTP a la API pública del repositorio y compara el
 * versionCode del tag con {@link BuildConfig#VERSION_CODE}.
 *
 * <p>Sin dependencias externas: {@link HttpURLConnection} + {@code org.json},
 * ambos del framework. La comprobación corre en un hilo propio y los callbacks
 * se entregan siempre en el hilo principal.</p>
 *
 * <p><b>Convención del tag:</b> el {@code tag_name} de la release es el
 * versionCode, con prefijo {@code v} opcional (p. ej. {@code "v3"} o {@code "3"}).</p>
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    /** Endpoint de la última release publicada del repositorio. */
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/manuel-umu/obd-map/releases/latest";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    /** Resultado de la comprobación. Todos los métodos se invocan en el hilo principal. */
    public interface CheckListener {
        /** Hay una versión más nueva disponible. */
        void onUpdateAvailable(@NonNull UpdateInfo info);

        /** La app ya está en la última versión. */
        void onNoUpdate();

        /** No se pudo comprobar (sin red, error HTTP, respuesta malformada…). */
        void onError(@NonNull String message);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Lanza la comprobación en un hilo de fondo de un solo uso. */
    public void check(@NonNull final CheckListener listener) {
        Thread thread = new Thread(() -> runCheck(listener), "update-checker");
        thread.setDaemon(true);
        thread.start();
    }

    private void runCheck(@NonNull final CheckListener listener) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(LATEST_RELEASE_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            // GitHub exige User-Agent en todas las peticiones a su API.
            conn.setRequestProperty("User-Agent", "OBD-Map-Updater");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                postError(listener, "HTTP " + code);
                return;
            }

            String body = readBody(conn.getInputStream());
            final UpdateInfo info = parse(body);
            if (info == null) {
                postError(listener, "Respuesta sin APK o tag inválido");
                return;
            }

            if (info.versionCode > BuildConfig.VERSION_CODE) {
                mainHandler.post(() -> listener.onUpdateAvailable(info));
            } else {
                mainHandler.post(listener::onNoUpdate);
            }
        } catch (Exception e) {
            postError(listener, e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @NonNull
    private static String readBody(@NonNull InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            char[] buffer = new char[2048];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, n);
            }
        } finally {
            IoUtils.closeQuietly(reader);
        }
        return sb.toString();
    }

    /**
     * Parsea la respuesta de la API y localiza el primer asset {@code .apk}.
     *
     * @return la info de la release, o {@code null} si no hay tag válido ni APK
     */
    @Nullable
    private UpdateInfo parse(@NonNull String json) {
        try {
            JSONObject root = new JSONObject(json);

            int versionCode = parseVersionCode(root.optString("tag_name", ""));
            if (versionCode < 0) {
                return null;
            }
            String versionName = root.optString("name", "");
            if (versionName.isEmpty()) {
                versionName = root.optString("tag_name", String.valueOf(versionCode));
            }

            JSONArray assets = root.optJSONArray("assets");
            if (assets == null) {
                return null;
            }
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");
                if (name.toLowerCase().endsWith(".apk")) {
                    String downloadUrl = asset.optString("browser_download_url", "");
                    if (!downloadUrl.isEmpty()) {
                        long size = asset.optLong("size", 0L);
                        return new UpdateInfo(versionCode, versionName, downloadUrl, size);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Error parseando release JSON: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Extrae el versionCode del tag: prefijo {@code v}/{@code V} opcional
     * seguido de los dígitos iniciales (p. ej. {@code "v12"} → 12).
     *
     * @return el versionCode, o -1 si el tag no empieza por un número
     */
    private static int parseVersionCode(@NonNull String tag) {
        String s = tag;
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int end = 0;
        while (end < s.length() && s.charAt(end) >= '0' && s.charAt(end) <= '9') {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(s.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void postError(@NonNull CheckListener listener, @NonNull String message) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Comprobación fallida: " + message);
        }
        mainHandler.post(() -> listener.onError(message));
    }
}
