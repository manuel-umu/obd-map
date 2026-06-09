package obdmap.launcher.map;

import android.os.Environment;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileFilter;
import java.util.Locale;

/**
 * Busca el archivo .map del mapa en el almacenamiento. De momento solo mira
 * en Download/; más adelante la ruta será configurable desde Ajustes.
 */
public final class MapFileLocator {

    private static final String MAP_EXTENSION = ".map";

    // Filtro reutilizable: evita instanciarlo en cada llamada a listFiles().
    private static final FileFilter MAP_FILTER = new FileFilter() {
        @Override
        public boolean accept(File file) {
            if (!file.isFile()) {
                return false;
            }
            // Locale.ROOT para que el toLowerCase no se vea afectado por idiomas
            // exóticos (turco) que cambian el comportamiento de la 'I'.
            return file.getName().toLowerCase(Locale.ROOT).endsWith(MAP_EXTENSION);
        }
    };

    private MapFileLocator() {
        // Utilidad estática, no se instancia.
    }

    /** El primer .map que haya en Download/, o null si no hay ninguno. */
    @Nullable
    public static File findFirstMapFile() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null || !downloadDir.isDirectory()) {
            return null;
        }
        File[] candidates = downloadDir.listFiles(MAP_FILTER);
        if (candidates == null || candidates.length == 0) {
            return null;
        }
        return candidates[0];
    }
}
