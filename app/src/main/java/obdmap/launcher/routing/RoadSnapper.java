package obdmap.launcher.routing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.graphhopper.GraphHopper;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.shapes.GHPoint3D;

/**
 * Proyección de la posición GPS sobre la polilínea de una ruta activa.
 */
public final class RoadSnapper {

    /** Umbral máximo de snap en metros */
    public static final double MAX_SNAP_METERS = 55.0;

    /** Metros por grado de latitud */
    private static final double METERS_PER_DEG = 111320.0;

    /** Segmentos a cada lado del índice sugerido que se exploran antes del barrido completo. */
    private static final int SEARCH_WINDOW_SEGMENTS = 150;

    /** Si la ventana no encuentra nada más cerca de esto, se hace el barrido completo. */
    private static final double WINDOW_ACCEPT_METERS = 60.0;

    private RoadSnapper() {}

    /**
     * Proyecta el punto (lat, lon) sobre la polilínea de la ruta.
     * Explora primero una ventana alrededor de hintIndex y solo barre la polilínea
     * entera si ahí no encuentra nada cerca.
     *
     * @param route     ruta activa
     * @param lat       latitud GPS cruda
     * @param lon       longitud GPS cruda
     * @param maxMeters distancia máxima en metros para considerar el snap válido
     * @param hintIndex segmento donde se proyectó el fix anterior, o negativo para barrer todo
     * @param out       array de tamaño 2; out[0]=lat, out[1]=lon del punto proyectado
     * @return true si se encontró proyección dentro del umbral y se escribió en el array out
     */
    public static boolean snapToRoute(@Nullable Route route,
                                      double lat, double lon,
                                      double maxMeters,
                                      int hintIndex,
                                      @NonNull double[] out) {
        if (route == null || route.pointCount() < 2) {
            return false;
        }

        // Factor de escala para longitud según la latitud de referencia
        double cosLat = Math.cos(Math.toRadians(lat));
        int lastSeg = route.pointCount() - 2;

        if (hintIndex >= 0) {
            int from = Math.max(0, hintIndex - SEARCH_WINDOW_SEGMENTS);
            int to = Math.min(lastSeg, hintIndex + SEARCH_WINDOW_SEGMENTS);
            double windowMeters = scanSegments(route, lat, lon, cosLat, from, to, out);
            if (windowMeters <= WINDOW_ACCEPT_METERS) {
                return windowMeters <= maxMeters;
            }
        }

        return scanSegments(route, lat, lon, cosLat, 0, lastSeg, out) <= maxMeters;
    }

    /**
     * Recorre los segmentos [from, to] y escribe el punto proyectado más cercano en out.
     *
     * @return distancia en metros del mejor punto proyectado al punto original
     */
    private static double scanSegments(@NonNull Route route,
                                       double lat, double lon, double cosLat,
                                       int from, int to,
                                       @NonNull double[] out) {
        // Coordenadas del punto
        double px = lon * cosLat;
        double py = lat;

        double bestDistSq = Double.MAX_VALUE;
        double bestProjLat = lat;
        double bestProjLon = lon;

        double[] lats = route.lats;
        double[] lons = route.lons;

        for (int i = from; i <= to; i++) {
            double ax = lons[i] * cosLat;
            double ay = lats[i];
            double bx = lons[i + 1] * cosLat;
            double by = lats[i + 1];

            double dx = bx - ax;
            double dy = by - ay;

            double lenSq = dx * dx + dy * dy;

            double projLat;
            double projLon;

            if (lenSq < 1e-18) {
                // Segmento degenerado (puntos duplicados): proyectar al vértice A
                projLat = lats[i];
                projLon = lons[i];
            } else {
                double t = ((px - ax) * dx + (py - ay) * dy) / lenSq;
                if (t < 0.0) {
                    t = 0.0;
                } else if (t > 1.0) {
                    t = 1.0;
                }
                // Punto proyectado en coordenadas locales
                double qx = ax + t * dx;
                double qy = ay + t * dy;

                // Convertir de vuelta a lat/lon
                projLat = qy;
                projLon = (cosLat > 1e-9) ? (qx / cosLat) : lon;
            }

            double distLat = projLat - lat;
            double distLon = (projLon - lon) * cosLat;
            double distSq = distLat * distLat + distLon * distLon;

            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestProjLat = projLat;
                bestProjLon = projLon;
            }
        }

        out[0] = bestProjLat;
        out[1] = bestProjLon;

        // Convertir distancia en grados a metros
        return Math.sqrt(bestDistSq) * METERS_PER_DEG;
    }

    public static boolean snapDiagnostic(@Nullable GraphHopper hopper,
                                         double lat, double lon,
                                         @NonNull double[] out) {
        if (hopper == null) {
            return false;
        }
        LocationIndex idx = hopper.getLocationIndex();
        if (idx == null) {
            return false;
        }
        QueryResult qr = idx.findClosest(lat, lon, EdgeFilter.ALL_EDGES);
        if (!qr.isValid()) {
            return false;
        }
        GHPoint3D sp = qr.getSnappedPoint();
        out[0] = sp.lat;
        out[1] = sp.lon;
        out[2] = qr.getQueryDistance();
        return true;
    }
}
