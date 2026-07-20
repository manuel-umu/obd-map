package obdmap.launcher.routing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.graphhopper.GraphHopper;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint3D;

/**
 * Proyecta la posición GPS sobre la carretera más cercana.
 */
public final class RoadSnapper {

    /**
     * Umbral máximo de snap en metros. Subido de 35 a 55 para que el snap
     * se aplique con más frecuencia cuando el GPS tiene deriva habitual en coche.
     * Bajar si hay falsos snaps en zonas con vías muy próximas.
     */
    public static final double MAX_SNAP_METERS = 55.0;

    /**
     * Diferencia angular máxima entre el rumbo del GPS y el azimut de la arista
     * para considerar que el vehículo circula por ella. Se evalúa en ambos
     * sentidos de circulación (directa e inversa), por eso el umbral puede ser
     * de 45° y aún así cubre el 100% de las orientaciones válidas con margen.
     * Valor de 45° es un equilibrio entre tolerancia a GPS impreciso y rechazo
     * de vías paralelas/perpendiculares.
     */
    private static final float MAX_HEADING_DIFF_DEG = 45.0f;

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
     * Pega el punto (lat, lon) a la carretera más cercana, sin filtro de rumbo.
     *
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
        return snapToNetwork(hopper, lat, lon, maxMeters, 0f, false, out);
    }

    public static boolean snapToNetwork(@Nullable GraphHopper hopper,
                                        double lat, double lon,
                                        double maxMeters,
                                        float bearingDeg,
                                        boolean hasBearing,
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

        // Filtro de rumbo: solo se aplica cuando el GPS entrega un bearing fiable.
        if (hasBearing) {
            EdgeIteratorState edge = qr.getClosestEdge();
            if (edge != null) {
                PointList geom = edge.fetchWayGeometry(FetchMode.ALL);
                if (geom.size() < 2) {
                    GHPoint3D pt = qr.getSnappedPoint();
                    out[0] = pt.lat;
                    out[1] = pt.lon;
                    return true;
                }
                int segStart = qr.getWayIndex();
                // En snaps de torre/pilar wayIndex apunta al propio vértice; el
                // segmento hacia delante arranca ahí. Acotar al último segmento.
                if (segStart > geom.size() - 2) {
                    segStart = geom.size() - 2;
                }
                if (segStart < 0) {
                    segStart = 0;
                }
                double baseLat = geom.getLat(segStart);
                double baseLon = geom.getLon(segStart);
                double adjLat  = geom.getLat(segStart + 1);
                double adjLon  = geom.getLon(segStart + 1);

                // atan2 con (dLon * cos(lat), dLat) da el azimut en coordenadas esféricas.
                double cosLat   = Math.cos(Math.toRadians(lat));
                double dLat     = adjLat - baseLat;
                double dLon     = (adjLon - baseLon) * cosLat;
                float  edgeAz   = (float) (Math.toDegrees(Math.atan2(dLon, dLat)));
                if (edgeAz < 0f) {
                    edgeAz += 360f;
                }

                // Diferencia angular normalizada al rango [0, 180].
                // Una carretera tiene dos sentidos, así que comparamos también con el
                // sentido inverso (edgeAz + 180) eligiendo la diferencia más pequeña.
                float diff = Math.abs(bearingDeg - edgeAz);
                if (diff > 180f) {
                    diff = 360f - diff;
                }
                // diff ahora está en [0, 180]; si la arista va en sentido contrario
                // la diferencia superará 90°. Normalizamos al rango [0, 90] para
                // evaluar alineación en cualquier sentido:
                if (diff > 90f) {
                    diff = 180f - diff;
                }

                if (diff > MAX_HEADING_DIFF_DEG) {
                    // La arista más cercana no está alineada con el rumbo: descartamos
                    return false;
                }
            }
        }

        GHPoint3D snapped = qr.getSnappedPoint();
        out[0] = snapped.lat;
        out[1] = snapped.lon;
        return true;
    }

    /**
     * Predice la posición de la flecha adelantándola por la carretera
     */
    public static boolean predictAlongRoad(@Nullable GraphHopper hopper,
                                           double lat, double lon,
                                           float bearingDeg,
                                           double leadMeters,
                                           @NonNull double[] out) {
        if (hopper == null || leadMeters <= 0.0) {
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
        EdgeIteratorState edge = qr.getClosestEdge();
        if (edge == null) {
            return false;
        }

        // Geometría real de la arista
        PointList geom = edge.fetchWayGeometry(FetchMode.ALL);
        int n = geom.size();
        if (n < 2) {
            return false;
        }

        // Segmento de la geometría donde cayó el snap acotado a un segmento valido
        int seg = qr.getWayIndex();
        if (seg > n - 2) {
            seg = n - 2;
        }
        if (seg < 0) {
            seg = 0;
        }

        // Una vía tiene dos sentidos: nos quedamos con la desviación al
        // más alineado y decidimos por qué extremo caminar.
        float segAz = azimuth(geom.getLat(seg), geom.getLon(seg),
                              geom.getLat(seg + 1), geom.getLon(seg + 1));
        float diff = Math.abs(bearingDeg - segAz);
        if (diff > 180f) {
            diff = 360f - diff;
        }
        boolean forward = diff <= 90f; // rumbo en el sentido de índices crecientes
        float align = forward ? diff : 180f - diff;
        if (align > MAX_HEADING_DIFF_DEG) {
            // Vía cruzada/no alineada con la marcha: no adelantamos por ella.
            return false;
        }

        // Punto de partida: el punto exacto pegado a la via
        GHPoint3D sp = qr.getSnappedPoint();
        double curLat = sp.lat;
        double curLon = sp.lon;

        double remaining = leadMeters;
        int step = forward ? 1 : -1;
        int i = forward ? seg + 1 : seg;
        while (i >= 0 && i < n) {
            double vLat = geom.getLat(i);
            double vLon = geom.getLon(i);
            double d = distanceMeters(curLat, curLon, vLat, vLon);
            if (d >= remaining) {
                double t = (d > 1e-9) ? remaining / d : 0.0;
                out[0] = curLat + (vLat - curLat) * t;
                out[1] = curLon + (vLon - curLon) * t;
                return true;
            }
            remaining -= d;
            curLat = vLat;
            curLon = vLon;
            i += step;
        }
        out[0] = curLat;
        out[1] = curLon;
        return true;
    }

    /** Azimut geográfico A->B en grados [0, 360). */
    private static float azimuth(double aLat, double aLon, double bLat, double bLon) {
        double cosLat = Math.cos(Math.toRadians(aLat));
        double dLat = bLat - aLat;
        double dLon = (bLon - aLon) * cosLat;
        float az = (float) Math.toDegrees(Math.atan2(dLon, dLat));
        if (az < 0f) {
            az += 360f;
        }
        return az;
    }

    /** Distancia aproximada en metros */
    private static double distanceMeters(double aLat, double aLon, double bLat, double bLon) {
        double cosLat = Math.cos(Math.toRadians(aLat));
        double dLat = bLat - aLat;
        double dLon = (bLon - aLon) * cosLat;
        return Math.sqrt(dLat * dLat + dLon * dLon) * METERS_PER_DEG;
    }
}
