package obdmap.launcher.update;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import obdmap.launcher.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Comprueba en GitHub Releases si hay una versión más nueva que la instalada
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    /** Endpoint de la última release */
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/manuel-umu/obd-map/releases/latest";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    /** Resultado de la comprobación */
    public interface CheckListener {
        void onUpdateAvailable(@NonNull UpdateInfo info);
        void onNoUpdate();
        void onError(@NonNull String message);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Lanza la comprobación en un hilo
     */
    public void check(@NonNull final CheckListener listener) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runCheck(listener);
            }
        }, "update-checker");
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
            // GitHub exige un User-Agent en todas las peticiones a su API
            conn.setRequestProperty("User-Agent", "OBD-Map-Updater");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                postError(listener, "HTTP " + code);
                return;
            }

            String body = readBody(conn.getInputStream());
            UpdateInfo info = parse(body);
            if (info == null) {
                postError(listener, "Respuesta sin APK o tag inválido");
                return;
            }

            if (info.versionCode > BuildConfig.VERSION_CODE) {
                postAvailable(listener, info);
            } else {
                postNoUpdate(listener);
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
            reader.close();
        }
        return sb.toString();
    }

    /**
     * Parsea la respuesta de la API y localiza el primer .apk
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
     * Extrae el versionCode del tag. Acepta un prefijo {@code v}/{@code V} opcional
     * y toma los dígitos iniciales (p. ej. {@code "v12"} → 12).
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

    // Auxiliares

    private void postAvailable(@NonNull final CheckListener listener, @NonNull final UpdateInfo info) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onUpdateAvailable(info);
            }
        });
    }

    private void postNoUpdate(@NonNull final CheckListener listener) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onNoUpdate();
            }
        });
    }

    private void postError(@NonNull final CheckListener listener, @NonNull final String message) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Comprobación fallida: " + message);
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onError(message);
            }
        });
    }
}
