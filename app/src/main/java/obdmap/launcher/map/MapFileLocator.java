package obdmap.launcher.map;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.NonNull;
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

    /**
     * Punto de entrada principal. Busca el mapa de una región en este orden:
     *   1. El .map instalado en la carpeta de la región (descargado o copiado ahí).
     *   2. El primer .map que el usuario haya copiado a mano en Download/.
     * Devuelve null si no hay ningún .map disponible.
     */
    @Nullable
    public static File findMapFile(@NonNull Context ctx, @NonNull RegionData region) {
        // Primero el mapa propio de la región.
        File regionMap = region.mapFile(ctx);
        if (regionMap.isFile()) {
            return regionMap;
        }
        // Fallback: .map copiado manualmente por el usuario.
        return findFirstMapFile();
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
