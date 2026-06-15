package obdmap.launcher.map;

import androidx.annotation.NonNull;

import java.io.File;

/**
 * Callback de la descarga del mapa.
 *
 * Se usa desde `MapDownloader` para avisar del progreso, del éxito o de un error.
 * Los callbacks llegan en el hilo principal.
 */
public interface MapDownloadListener {

    /** Porcentaje de descarga completado, de 0 a 100. */
    void onProgress(int percent);

    /** La descarga terminó bien y el fichero ya está guardado en disco. */
    void onComplete(@NonNull File file);

    /** Ocurrió un error durante la descarga. */
    void onError(@NonNull String message);
}
