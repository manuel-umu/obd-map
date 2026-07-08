package obdmap.launcher.update;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import obdmap.launcher.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Descarga el APK de una {@link UpdateInfo} al almacenamiento
 */
public final class UpdateDownloader {
    private static final String TAG = "UpdateDownloader";
    private static final String APK_SUBDIR = "apk";
    private static final String APK_FILENAME = "update.apk";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int BUFFER_SIZE = 8192;

    /** Callbacks de la descarga */
    public interface DownloadListener {
        void onProgress(int percent);
        void onComplete(@NonNull File apk);
        void onError(@NonNull String message);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void download(@NonNull final Context context,
                         @NonNull final UpdateInfo info,
                         @NonNull final DownloadListener listener) {
        final Context appContext = context.getApplicationContext();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runDownload(appContext, info, listener);
            }
        }, "update-downloader");
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
        // Borramos cualquier descarga previa para no instalar un APK a medias
        if (apkFile.exists() && !apkFile.delete()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "No se pudo borrar el APK previo; se sobrescribirá");
            }
        }

        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            URL url = new URL(info.downloadUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "OBD-Map-Updater");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                postError(listener, "HTTP " + code);
                return;
            }
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
                        postProgress(listener, percent);
                    }
                }
            }
            out.flush();

            postComplete(listener, apkFile);
        } catch (Exception e) {
            postError(listener, e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void closeQuietly(@androidx.annotation.Nullable java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    // Auxiliares

    private void postProgress(@NonNull final DownloadListener listener, final int percent) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onProgress(percent);
            }
        });
    }

    private void postComplete(@NonNull final DownloadListener listener, @NonNull final File apk) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onComplete(apk);
            }
        });
    }

    private void postError(@NonNull final DownloadListener listener, @NonNull final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onError(message);
            }
        });
    }
}
