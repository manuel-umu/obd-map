package obdmap.launcher.util;

import androidx.annotation.Nullable;

import java.io.Closeable;

/**
 * Utilidades de E/S compartidas por los componentes que manejan streams
 * (Bluetooth, descargas HTTP, ficheros).
 */
public final class IoUtils {

    private IoUtils() {
    }

    /**
     * Cierra un {@link Closeable} ignorando cualquier excepción. Pensado para
     * rutas de limpieza donde un fallo al cerrar no aporta nada accionable.
     */
    public static void closeQuietly(@Nullable Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Silencioso por diseño: ya estamos liberando recursos.
        }
    }
}
