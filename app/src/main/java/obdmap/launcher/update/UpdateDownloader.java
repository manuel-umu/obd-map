package obdmap.launcher.update;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import obdmap.launcher.BuildConfig;
import obdmap.launcher.util.IoUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Descarga el APK de una {@link UpdateInfo} al almacenamiento externo propio de
 * la app ({@code getExternalFilesDir("apk")}), la ruta expuesta por el
 * {@code FileProvider}. La descarga corre en un hilo propio y los callbacks se
 * entregan siempre en el hilo principal.
 */
public final class UpdateDownloader {

    private static final String TAG = "UpdateDownloader";

    /** Subdirectorio dentro de getExternalFilesDir; debe coincidir con file_paths.xml. */
    private static final String APK_SUBDIR = "apk";
    private static final String APK_FILENAME = "update.apk";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int BUFFER_SIZE = 8192;

    /** Callbacks de la descarga. Todos se invocan en el hilo principal. */
    public interface DownloadListener {
        /** Progreso 0-100; como mucho una llamada por punto porcentual. */
        void onProgress(int percent);

        /** Descarga completa; {@code apk} queda listo para instalar. */
        void onComplete(@NonNull File apk);

        /** Fallo de red o de escritura. */
        void onError(@NonNull String message);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void download(@NonNull Context context,
                         @NonNull final UpdateInfo info,
                         @NonNull final DownloadListener listener) {
        // applicationContext para no retener la Activity durante la descarga.
        final Context appContext = context.getApplicationContext();
        Thread thread = new Thread(() -> runDownload(appContext, info, listener),
                "update-downloader");
        thread.setDaemon(true);
        thread.start();
    }

    private void runDownload(@NonNull Context context,
                             @NonNull UpdateInfo info,
                             @NonNull DownloadListener listener) {
        File dir = context.getExternalFilesDir(APK_SUBDIR);
        if (dir == null) {
            postError(listener, "Almacenamiento externo no disponible");
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            postError(listener, "No se pudo crear el directorio de descarga");
            return;
        }

        File apkFile = new File(dir, APK_FILENAME);
        // Se elimina cualquier descarga previa para no instalar un APK a medias.
        if (apkFile.exists() && !apkFile.delete() && BuildConfig.DEBUG) {
            Log.d(TAG, "No se pudo borrar el APK previo; se sobrescribirá");
        }

        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            URL url = new URL(info.downloadUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // El asset de GitHub redirige a un CDN; se sigue el 302 automáticamente.
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "OBD-Map-Updater");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                postError(listener, "HTTP " + code);
                return;
            }

            // Content-Length si el servidor lo envía; si no, el tamaño de la API.
            long total = conn.getContentLength();
            if (total <= 0) {
                total = info.sizeBytes;
            }

            in = conn.getInputStream();
            out = new FileOutputStream(apkFile);

            byte[] buffer = new byte[BUFFER_SIZE];
            long downloaded = 0;
            int lastPercent = -1;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;
                if (total > 0) {
                    int percent = (int) (downloaded * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        final int p = percent;
                        mainHandler.post(() -> listener.onProgress(p));
                    }
                }
            }
            out.flush();

            final File result = apkFile;
            mainHandler.post(() -> listener.onComplete(result));
        } catch (Exception e) {
            postError(listener, e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            IoUtils.closeQuietly(out);
            IoUtils.closeQuietly(in);
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void postError(@NonNull DownloadListener listener, @NonNull String message) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Descarga fallida: " + message);
        }
        mainHandler.post(() -> listener.onError(message));
    }
}
