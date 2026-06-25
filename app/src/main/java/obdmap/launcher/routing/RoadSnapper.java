package obdmap.launcher.routing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.graphhopper.GraphHopper;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.shapes.GHPoint3D;

/**
 * Proyecta la posición GPS sobre la carretera más cercana.
 */
public final class RoadSnapper {

    /** Umbral máximo de snap en metros. */
    public static final double MAX_SNAP_METERS = 35.0;

    /**
     * Metros por grado de latitud
     */
    private static final double METERS_PER_DEG = 111320.0;

    private RoadSnapper() {}

    /**
     * Proyecta el punto (lat, lon) sobre la polilínea de la ruta.
     * @param route     ruta activa
     * @param lat       latitud GPS cruda
     * @param lon       longitud GPS cruda
     * @param maxMeters distancia máxima en metros para considerar el snap válido
     * @param out       array de tamaño 2; out[0]=lat, out[1]=lon del punto proyectado
     * @return true si se encontró proyección dentro del umbral y se escribió en el array out
     */
    public static boolean snapToRoute(@Nullable Route route,
                                      double lat, double lon,
                                      double maxMeters,
                                      @NonNull double[] out) {
        if (route == null || route.pointCount() < 2) {
            return false;
        }

        // Factor de escala para longitud según la latitud de referencia
        double cosLat = Math.cos(Math.toRadians(lat));

        // Coordenadas del punto
        double px = lon * cosLat;
        double py = lat;

        double bestDistSq = Double.MAX_VALUE;
        double bestProjLat = lat;
        double bestProjLon = lon;

        double[] lats = route.lats;
        double[] lons = route.lons;
        int count = route.pointCount();

        for (int i = 0; i < count - 1; i++) {
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

        // Convertir distancia en grados a metros
        double bestDistMeters = Math.sqrt(bestDistSq) * METERS_PER_DEG;

        if (bestDistMeters <= maxMeters) {
            out[0] = bestProjLat;
            out[1] = bestProjLon;
            return true;
        }
        return false;
    }

    /**
     * Pega el punto (lat, lon) a la carretera más cercana
     * @param hopper    instancia de GraphHopper ya cargada
     * @param lat       latitud GPS cruda
     * @param lon       longitud GPS cruda
     * @param maxMeters distancia máxima en metros para considerar el snap válido
     * @param out       array de tamaño 2; out[0]=lat, out[1]=lon del punto pegado
     * @return true si hay un punto de red dentro del umbral y se escribió en el array out
     */
    public static boolean snapToNetwork(@Nullable GraphHopper hopper,
                                        double lat, double lon,
                                        double maxMeters,
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

        double distM = qr.getQueryDistance();
        if (distM > maxMeters) {
            return false;
        }

        GHPoint3D snapped = qr.getSnappedPoint();
        out[0] = snapped.lat;
        out[1] = snapped.lon;
        return true;
    }
}
