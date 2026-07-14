package obdmap.launcher.routing;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.IntDef;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.Instruction;
import com.graphhopper.util.InstructionList;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.PointList;

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
 */
public final class RoutingManager {

    // ------------------------------------------------------------------
    // Constantes de estado
    // ------------------------------------------------------------------
    public static final int STATE_IDLE = 0;
    public static final int STATE_LOADING = 1;
    public static final int STATE_READY = 2;
    public static final int STATE_ERROR = 3;

    @IntDef({STATE_IDLE, STATE_LOADING, STATE_READY, STATE_ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    private static final String ASSET_ZIP_NAME = "murcia-gh.zip";
    private static final String GRAPH_DIR_NAME = "murcia-gh";

    @Nullable
    private static volatile RoutingManager instance;

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

    @State
    private volatile int state = STATE_IDLE;
    @Nullable
    private GraphHopper hopper;
    @Nullable
    private String lastError;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RoutingManager() {}

    // ------------------------------------------------------------------
    // Interfaz de callback para el resultado de calculateRoute
    // ------------------------------------------------------------------
    public interface RouteCallback {
        void onRouteReady(@NonNull Route route);
        void onRouteError(@NonNull String message);
    }

    // ------------------------------------------------------------------
    // Interfaz de escucha
    // ------------------------------------------------------------------
    public interface RoutingListener {
        void onRoutingReady();
        void onRoutingError(@NonNull String message);
        void onRoutingProgress(@NonNull String status);
    }

    // ------------------------------------------------------------------
    // API pública
    // ------------------------------------------------------------------
    @State
    public int getState() {
        return state;
    }
    @Nullable
    public GraphHopper getHopper() {
        return hopper;
    }
    @Nullable
    public String getLastError() {
        return lastError;
    }

    /**
     * Calcula una ruta entre dos coordenadas
     */
    @MainThread
    public void calculateRoute(final double fromLat, final double fromLon,
                               final double toLat, final double toLon,
                               @NonNull final RouteCallback cb) {
        if (state != STATE_READY || hopper == null) {
            mainHandler.post(() -> cb.onRouteError("grafo no cargado"));
            return;
        }
        final GraphHopper gh = hopper;
        new Thread(() -> routeInBackground(gh, fromLat, fromLon, toLat, toLon, cb),
                "gh-route").start();
    }

    /**
     *  Ejecuta el cálculo de ruta en el hilo gh-route.
     */
    private void routeInBackground(GraphHopper gh,
                                   double fromLat, double fromLon,
                                   double toLat, double toLon,
                                   final RouteCallback cb) {
        try {
            GHRequest req = new GHRequest(fromLat, fromLon, toLat, toLon);
            req.setProfile("car");
            // El grafo empaquetado no trae preparación CH ni Landmarks, así que se
            // desactivan ambos hints para forzar el algoritmo flexible (Dijkstra/A*).
            req.putHint(Parameters.CH.DISABLE, true);
            req.putHint(Parameters.Landmark.DISABLE, true);

            GHResponse rsp = gh.route(req);

            if (rsp.hasErrors()) {
                final StringBuilder sb = new StringBuilder();
                for (Throwable t : rsp.getErrors()) {
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(t.getMessage());
                }
                final String errorMsg = sb.toString();
                mainHandler.post(() -> cb.onRouteError(errorMsg));
                return;
            }

            ResponsePath path = rsp.getBest();
            PointList pts = path.getPoints();
            int count = pts.size();

            if (count == 0) {
                mainHandler.post(() -> cb.onRouteError("no se encontró ruta"));
                return;
            }

            // Copiar la polilínea a arrays primitivos
            double[] lats = new double[count];
            double[] lons = new double[count];
            for (int i = 0; i < count; i++) {
                lats[i] = pts.getLat(i);
                lons[i] = pts.getLon(i);
            }

            // Extraer instrucciones de maniobra de GraphHopper
            InstructionList instrList = path.getInstructions();
            int instrCount = instrList.size();
            int[] instrSigns = new int[instrCount];
            String[] instrNames = new String[instrCount];
            double[] instrDistances = new double[instrCount];
            long[] instrTimes = new long[instrCount];
            double[] instrLats = new double[instrCount];
            double[] instrLons = new double[instrCount];
            for (int i = 0; i < instrCount; i++) {
                Instruction instr = instrList.get(i);
                instrSigns[i] = instr.getSign();
                instrNames[i] = instr.getName();
                instrDistances[i] = instr.getDistance();
                instrTimes[i] = instr.getTime();
                PointList ipts = instr.getPoints();
                if (ipts != null && ipts.size() > 0) {
                    instrLats[i] = ipts.getLat(0);
                    instrLons[i] = ipts.getLon(0);
                } else {
                    // Sin puntos propios: heredar coordenadas de la instrucción anterior
                    instrLats[i] = (i > 0) ? instrLats[i - 1] : 0.0;
                    instrLons[i] = (i > 0) ? instrLons[i - 1] : 0.0;
                }
            }

            final Route route = new Route(lats, lons, path.getDistance(), path.getTime(),
                    instrSigns, instrNames, instrDistances, instrTimes, instrLats, instrLons);

            mainHandler.post(() -> cb.onRouteReady(route));

        } catch (final Exception e) {
            final String msg = e.getMessage() != null ? e.getMessage() : "Error desconocido en routing";
            mainHandler.post(() -> cb.onRouteError(msg));
        }
    }

    @MainThread
    public void startLoading(@NonNull final Context context,
                             @NonNull final RoutingListener listener) {
        if (state == STATE_READY) {
            listener.onRoutingReady();
            return;
        }
        if (state == STATE_LOADING) {
            return;
        }
        if (state == STATE_ERROR) {
            state = STATE_IDLE;
        }

        state = STATE_LOADING;

        final Context appContext = context.getApplicationContext();

        new Thread(() -> loadInBackground(appContext, listener), "gh-loader").start();
    }

    // ------------------------------------------------------------------
    // Lógica interna de carga
    // ------------------------------------------------------------------
    private void loadInBackground(@NonNull Context appContext,
                                  @NonNull final RoutingListener listener) {
        try {
            final File graphDir = new File(appContext.getFilesDir(), GRAPH_DIR_NAME);

            if (!graphDir.exists() || !graphDir.isDirectory()) {
                notifyProgress(listener, "Extrayendo grafo…");
                extractAsset(appContext, graphDir);
            }

            notifyProgress(listener, "Cargando grafo…");

            GraphHopper gh = new GraphHopper();
            gh.forMobile();
            gh.setMemoryMapped();
            gh.setAllowWrites(false);
            gh.setProfiles(new Profile("car")
                    .setVehicle("car")
                    .setWeighting("fastest")
                    .setTurnCosts(false));
            gh.setEncodingManager(EncodingManager.create("car"));

            boolean loaded = gh.load(graphDir.getAbsolutePath());
            if (!loaded) {
                throw new IOException("GraphHopper.load() devolvió false para: "
                        + graphDir.getAbsolutePath());
            }

            hopper = gh;
            state = STATE_READY;

            mainHandler.post(listener::onRoutingReady);

        } catch (final Exception e) {
            lastError = e.getMessage();
            state = STATE_ERROR;

            mainHandler.post(() ->
                    listener.onRoutingError(lastError != null ? lastError : "Error desconocido"));
        }
    }

    private static void extractAsset(@NonNull Context context,
                                     @NonNull File destDir) throws IOException {
        if (!destDir.mkdirs() && !destDir.isDirectory()) {
            throw new IOException("No se pudo crear el directorio: " + destDir.getAbsolutePath());
        }

        final byte[] buffer = new byte[8192];

        InputStream assetStream = context.getAssets().open(ASSET_ZIP_NAME);
        ZipInputStream zipIn = new ZipInputStream(assetStream);
        try {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String entryName = entry.getName();
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
        mainHandler.post(() -> listener.onRoutingProgress(message));
    }
}
