package obdmap.launcher.routing;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.IntDef;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.util.EncodingManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Singleton que gestiona el ciclo de vida del grafo de GraphHopper offline.
 *
 * El grafo se empaqueta como asset ZIP (murcia-gh.zip) en el APK. La primera
 * vez que se llama a startLoading(), se extrae al directorio de datos interno
 * de la app y luego se carga con MMAP (setMemoryMapped()), reduciendo el
 * footprint en heap al mínimo posible en la radio de 1 GB de RAM.
 *
 * El estado se consulta con getState() y los resultados se notifican vía
 * RoutingListener siempre en el hilo principal.
 *
 * No hay Dagger, no hay Kotlin, no hay RxJava: el hilo de carga es un Thread
 * simple que envía mensajes al Handler del main looper.
 */
public final class RoutingManager {

    // ------------------------------------------------------------------
    // Constantes de estado (sustituyen a un Enum: regla del proyecto)
    // ------------------------------------------------------------------
    public static final int STATE_IDLE = 0;
    public static final int STATE_LOADING = 1;
    public static final int STATE_READY = 2;
    public static final int STATE_ERROR = 3;

    @IntDef({STATE_IDLE, STATE_LOADING, STATE_READY, STATE_ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    // Nombre del asset ZIP que contiene el grafo precompilado.
    private static final String ASSET_ZIP_NAME = "murcia-gh.zip";
    // Subdirectorio dentro de getFilesDir() donde se extrae el grafo.
    private static final String GRAPH_DIR_NAME = "murcia-gh";

    // ------------------------------------------------------------------
    // Singleton — instanciación manual, sin inyección de dependencias
    // ------------------------------------------------------------------
    @Nullable
    private static volatile RoutingManager instance;

    /** Devuelve la instancia única de RoutingManager. */
    @NonNull
    public static RoutingManager getInstance() {
        if (instance == null) {
            synchronized (RoutingManager.class) {
                if (instance == null) {
                    instance = new RoutingManager();
                }
            }
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // Estado interno
    // ------------------------------------------------------------------
    @State
    private volatile int state = STATE_IDLE;

    @Nullable
    private GraphHopper hopper;

    @Nullable
    private String lastError;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RoutingManager() {}

    // ------------------------------------------------------------------
    // Interfaz de escucha (callbacks en el hilo principal)
    // ------------------------------------------------------------------
    public interface RoutingListener {
        /** El grafo está listo para calcular rutas. */
        void onRoutingReady();
        /** Se produjo un error durante la extracción o carga del grafo. */
        void onRoutingError(@NonNull String message);
        /** Progreso informativo durante la extracción y carga. */
        void onRoutingProgress(@NonNull String status);
    }

    // ------------------------------------------------------------------
    // API pública
    // ------------------------------------------------------------------

    /** Devuelve el estado actual del grafo. Seguro desde cualquier hilo. */
    @State
    public int getState() {
        return state;
    }

    /**
     * Devuelve la instancia de GraphHopper lista para calcular rutas, o null
     * si el grafo no se ha cargado todavía (state != STATE_READY).
     */
    @Nullable
    public GraphHopper getHopper() {
        return hopper;
    }

    /** Devuelve el último mensaje de error, o null si no hubo error. */
    @Nullable
    public String getLastError() {
        return lastError;
    }

    /**
     * Inicia la extracción del asset ZIP y la carga MMAP del grafo en un
     * hilo de fondo. Solo arranca si el estado es STATE_IDLE; si ya está
     * cargando o listo, notifica inmediatamente al listener.
     *
     * @param context  Contexto de la app para acceder a assets y filesDir.
     * @param listener Callbacks de resultado, invocados en el hilo principal.
     */
    @MainThread
    public void startLoading(@NonNull final Context context,
                             @NonNull final RoutingListener listener) {
        if (state == STATE_READY) {
            listener.onRoutingReady();
            return;
        }
        if (state == STATE_LOADING) {
            // Ya hay una carga en curso; el listener se notificará al terminar.
            return;
        }
        if (state == STATE_ERROR) {
            // Permitimos reintentar después de un error.
            state = STATE_IDLE;
        }

        state = STATE_LOADING;

        // applicationContext para no retener la Activity si el usuario navega.
        final Context appContext = context.getApplicationContext();

        new Thread(new Runnable() {
            @Override
            public void run() {
                loadInBackground(appContext, listener);
            }
        }, "gh-loader").start();
    }

    // ------------------------------------------------------------------
    // Lógica interna de carga (ejecutada en el hilo gh-loader)
    // ------------------------------------------------------------------

    private void loadInBackground(@NonNull Context appContext,
                                  @NonNull final RoutingListener listener) {
        try {
            final File graphDir = new File(appContext.getFilesDir(), GRAPH_DIR_NAME);

            // Extraer el ZIP si el directorio de grafo no existe todavía.
            if (!graphDir.exists() || !graphDir.isDirectory()) {
                notifyProgress(listener, "Extrayendo grafo…");
                extractAsset(appContext, graphDir);
            }

            notifyProgress(listener, "Cargando grafo…");

            GraphHopper gh = new GraphHopper();
            // forMobile() desactiva CH y ajusta índices para baja RAM.
            gh.forMobile();
            // MMAP: acceso al grafo sin cargarlo entero en el heap de la JVM.
            gh.setMemoryMapped();
            // Solo lectura: no se necesita escribir en el dispositivo Android.
            gh.setAllowWrites(false);
            // Perfil de coche, sin turn-costs para mantener el grafo ligero.
            gh.setProfiles(new Profile("car")
                    .setVehicle("car")
                    .setWeighting("fastest")
                    .setTurnCosts(false));
            gh.setEncodingManager(EncodingManager.create("car"));

            // load(path) carga un grafo ya construido; no importa OSM.
            boolean loaded = gh.load(graphDir.getAbsolutePath());
            if (!loaded) {
                throw new IOException("GraphHopper.load() devolvió false para: "
                        + graphDir.getAbsolutePath());
            }

            hopper = gh;
            state = STATE_READY;

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onRoutingReady();
                }
            });

        } catch (final Exception e) {
            lastError = e.getMessage();
            state = STATE_ERROR;

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onRoutingError(lastError != null ? lastError : "Error desconocido");
                }
            });
        }
    }

    /**
     * Extrae el contenido del asset murcia-gh.zip en el directorio destino.
     * Crea los subdirectorios necesarios y descarta entradas de directorio.
     */
    private static void extractAsset(@NonNull Context context,
                                     @NonNull File destDir) throws IOException {
        if (!destDir.mkdirs() && !destDir.isDirectory()) {
            throw new IOException("No se pudo crear el directorio: " + destDir.getAbsolutePath());
        }

        // Buffer reutilizable fuera del bucle para no crear objetos en cada iteración.
        final byte[] buffer = new byte[8192];

        InputStream assetStream = context.getAssets().open(ASSET_ZIP_NAME);
        ZipInputStream zipIn = new ZipInputStream(assetStream);
        try {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String entryName = entry.getName();

                // El ZIP incluye el directorio raíz "murcia-gh/"; lo ignoramos
                // para que los archivos queden directamente dentro de destDir.
                int slashIdx = entryName.indexOf('/');
                if (slashIdx >= 0) {
                    entryName = entryName.substring(slashIdx + 1);
                }
                if (entryName.isEmpty()) {
                    zipIn.closeEntry();
                    continue;
                }

                File outFile = new File(destDir, entryName);
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    writeEntry(zipIn, outFile, buffer);
                }
                zipIn.closeEntry();
            }
        } finally {
            zipIn.close();
        }
    }

    /** Escribe una entrada del ZIP en el fichero destino. */
    private static void writeEntry(@NonNull ZipInputStream zipIn,
                                   @NonNull File outFile,
                                   @NonNull byte[] buffer) throws IOException {
        OutputStream out = new FileOutputStream(outFile);
        try {
            int read;
            while ((read = zipIn.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            out.close();
        }
    }

    private void notifyProgress(@NonNull final RoutingListener listener,
                                @NonNull final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onRoutingProgress(message);
            }
        });
    }
}
