package com.obdmap.launcher.map;

import android.os.Environment;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileFilter;
import java.util.Locale;

/**
 * Utilidad de búsqueda del archivo .map de Mapsforge en almacenamiento externo.
 * En Fase 1 sólo se inspecciona la carpeta {@code Download/} de la SD; en
 * fases posteriores se podrá configurar la ruta desde una pantalla de Ajustes.
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
     * Devuelve el primer archivo .map encontrado en /sdcard/Download/, o null
     * si la carpeta no existe o no contiene ninguno.
     */
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
