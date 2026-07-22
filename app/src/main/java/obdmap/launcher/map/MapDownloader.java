package obdmap.launcher.map;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import obdmap.launcher.util.IoUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Descarga el mapa desde el servidor de Mapsforge en un hilo daemon propio.
 * Es de un solo uso: cuando termina o se cancela, se descarta.
 */
public final class MapDownloader {

    // Tamaño del buffer de lectura: 8 KB, reutilizado durante toda la descarga.
    private static final int BUFFER_SIZE = 8 * 1024;

    // Timeouts de conexión y lectura en milisegundos.
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS    = 15_000;

    // volatile: se escribe desde MainActivity (main thread) y se lee desde el
    // hilo de descarga; sin volatile el compilador podría cachear el valor.
    private volatile boolean cancelled = false;
    private volatile boolean running   = false;

    // Handler al main thread para despachar los callbacks del Listener.
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Devuelve el fichero de destino del mapa de una región
     */
    @NonNull
    public static File getMapFile(@NonNull Context ctx, @NonNull RegionData region) {
        return region.mapFile(ctx);
    }

    /** true mientras la descarga está en marcha. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Cancela la descarga en curso. Si ya terminó o no había empezado, no hace nada.
     * No se llamará onComplete tras la cancelación; el fichero temporal se borra.
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Lanza la descarga en un hilo daemon. Si ya hay una en curso, lanza
     * IllegalStateException para que el llamador lo detecte en tiempo de desarrollo.
     *
     * @param ctx      Contexto para obtener el directorio de destino.
     * @param region   Región cuyo .map se descarga (aporta URL y nombre).
     * @param listener Receptor de callbacks (main thread).
     */
    public void start(@NonNull final Context ctx,
                      @NonNull final RegionData region,
                      @NonNull final MapDownloadListener listener) {
        if (running) {
            throw new IllegalStateException("Ya hay una descarga en curso");
        }
        running   = true;
        cancelled = false;

        Thread thread = new Thread(() -> download(ctx, region, listener), "map-download");
        // Daemon: si la app muere inesperadamente, el hilo no bloquea la JVM.
        thread.setDaemon(true);
        thread.start();
    }

    // -------------------------------------------------------------------------
    // Lógica interna del hilo de descarga
    // -------------------------------------------------------------------------

    private void download(@NonNull Context ctx,
                          @NonNull RegionData region,
                          @NonNull MapDownloadListener listener) {
        File destFile  = getMapFile(ctx, region);
        File tmpFile   = new File(destFile.getParent(), region.mapFileName + ".tmp");

        // La carpeta de la región puede no existir todavía en una instalación nueva.
        File parentDir = destFile.getParentFile();
        if (parentDir != null && !parentDir.isDirectory() && !parentDir.mkdirs()) {
            postError(listener, "No se pudo crear el directorio de la región");
            running = false;
            return;
        }

        // Limpieza de un intento previo incompleto.
        if (tmpFile.exists()) {
            tmpFile.delete();
        }

        HttpURLConnection connection = null;
        InputStream inputStream      = null;
        FileOutputStream outputStream = null;

        try {
            URL url = new URL(region.mapUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            // Permitir redireccionamientos 301/302 que pueda hacer el CDN.
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                postError(listener, "Error HTTP " + responseCode);
                return;
            }

            // Content-Length puede ser -1 si el servidor no lo envía.
            long totalBytes = connection.getContentLength();

            inputStream  = connection.getInputStream();
            outputStream = new FileOutputStream(tmpFile);

            byte[] buffer      = new byte[BUFFER_SIZE];
            long   bytesRead   = 0;
            int    lastPercent = -1;
            int    read;

            while ((read = inputStream.read(buffer)) != -1) {
                if (cancelled) {
                    // Cortamos el bucle; el finally limpiará streams y el tmp.
                    return;
                }
                outputStream.write(buffer, 0, read);
                bytesRead += read;

                // Actualizamos el porcentaje solo si cambió el entero, para no
                // saturar el main thread con mensajes en cada chunk de 8 KB.
                if (totalBytes > 0) {
                    int percent = (int) (bytesRead * 100L / totalBytes);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        postProgress(listener, percent);
                    }
                }
            }

            if (cancelled) {
                return;
            }

            // Aseguramos que todo llegó a disco antes de renombrar.
            outputStream.flush();
            IoUtils.closeQuietly(outputStream);
            outputStream = null;

            // Renombrado atómico: el .map queda disponible de golpe o no existe.
            if (!tmpFile.renameTo(destFile)) {
                postError(listener, "No se pudo renombrar el fichero temporal");
                return;
            }

            postComplete(listener, destFile);

        } catch (IOException ex) {
            postError(listener, ex.getMessage() != null ? ex.getMessage() : "Error de red");
        } finally {
            IoUtils.closeQuietly(inputStream);
            IoUtils.closeQuietly(outputStream);
            if (connection != null) {
                connection.disconnect();
            }
            // Si se canceló o hubo error, el .tmp no debe quedar a medias.
            if ((cancelled || !destFile.exists()) && tmpFile.exists()) {
                tmpFile.delete();
            }
            running = false;
        }
    }

    // -------------------------------------------------------------------------
    // Despacho de callbacks al main thread
    // -------------------------------------------------------------------------

    private void postProgress(final MapDownloadListener listener, final int percent) {
        mainHandler.post(() -> listener.onProgress(percent));
    }

    private void postComplete(final MapDownloadListener listener, final File file) {
        mainHandler.post(() -> listener.onComplete(file));
    }

    private void postError(final MapDownloadListener listener, final String message) {
        mainHandler.post(() -> listener.onError(message));
    }
}
